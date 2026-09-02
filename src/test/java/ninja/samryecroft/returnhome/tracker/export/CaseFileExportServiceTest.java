package ninja.samryecroft.returnhome.tracker.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryService;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.document.KeyUnavailableException;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import ninja.samryecroft.returnhome.tracker.report.ReportDocumentService;
import ninja.samryecroft.returnhome.tracker.report.ReportStatus;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The rules that make this feature safe to ship, expressed as tests: fail closed, never omit
 * silently, and never confirm the existence of a child the account cannot see.
 */
class CaseFileExportServiceTest {

    private static final byte[] DOCUMENT = "PK an issued report".getBytes(StandardCharsets.UTF_8);

    private final ChildRepository childRepository = mock(ChildRepository.class);
    private final InterviewRequestRepository requestRepository = mock(InterviewRequestRepository.class);
    private final InterviewReportRepository reportRepository = mock(InterviewReportRepository.class);
    private final OrganisationAccessService accessService = mock(OrganisationAccessService.class);
    private final AuditHistoryService historyService = mock(AuditHistoryService.class);
    private final ReportDocumentService documentService = mock(ReportDocumentService.class);
    private final ExportPackWriter packWriter = mock(ExportPackWriter.class);

    private final CaseFileExportService service = new CaseFileExportService(childRepository, requestRepository,
            reportRepository, accessService, historyService, documentService, packWriter);

    private final AppUserPrincipal principal = mock(AppUserPrincipal.class);

    private InterviewRequest requestOne;
    private InterviewRequest requestTwo;

    @BeforeEach
    void setUp() {
        Child child = new Child();
        ReflectionTestUtils.setField(child, "id", 5L);
        ReflectionTestUtils.setField(child, "localCaseReference", "CASE-001");
        when(childRepository.findById(5L)).thenReturn(Optional.of(child));

        requestOne = interviewRequest(1182L);
        requestTwo = interviewRequest(1191L);
        when(requestRepository.findByChildIdOrderByCreatedAtDesc(5L))
                .thenReturn(List.of(requestOne, requestTwo));
        when(requestRepository.findDetailedById(1182L)).thenReturn(Optional.of(requestOne));
        when(requestRepository.findDetailedById(1191L)).thenReturn(Optional.of(requestTwo));
        when(accessService.canViewHome(any(), any())).thenReturn(true);
        when(historyService.caseHistoryFor(any())).thenReturn(List.of());
        when(principal.getUsername()).thenReturn("orgadmin");

        approvedReportFor(requestOne, 900L);
        approvedReportFor(requestTwo, 901L);
    }

    private InterviewRequest interviewRequest(Long id) {
        InterviewRequest request = new InterviewRequest();
        ReflectionTestUtils.setField(request, "id", id);
        ReflectionTestUtils.setField(request, "createdAt", LocalDateTime.of(2026, 8, 3, 10, 0));
        Home home = new Home();
        ReflectionTestUtils.setField(home, "id", 1L);
        request.setHome(home);
        return request;
    }

    private void approvedReportFor(InterviewRequest request, Long reportId) {
        InterviewReport report = new InterviewReport();
        ReflectionTestUtils.setField(report, "id", reportId);
        report.setStatus(ReportStatus.APPROVED);
        report.setGeneratedDocumentPath("org-1/rhi-report-" + request.getId() + "-abc.docx");
        when(reportRepository.findByInterviewRequestId(request.getId())).thenReturn(Optional.of(report));
    }

    @Test
    void aReportThatCannotBeRetrievedBlocksTheWholeExport() {
        when(documentService.retrieve(any(), any(), any()))
                .thenReturn(DOCUMENT)
                .thenThrow(new KeyUnavailableException("Key Vault is unreachable"));

        assertThatThrownBy(() -> service.export(5L, ExportPeriod.all(), ExportPurpose.REGULATORY_INSPECTION,
                "OFSTED-1", Set.of(), "", principal))
                .isInstanceOf(ExportBlockedException.class)
                .satisfies(thrown -> assertThat(((ExportBlockedException) thrown).getBlocked())
                        .singleElement()
                        .satisfies(blocked -> assertThat(blocked.interviewId()).isEqualTo(1191L)));

        // The assertion that matters. A pack of one-of-two would be the worst failure available
        // here, because it looks complete - so nothing is written at all.
        verify(packWriter, never()).write(any());
    }

    @Test
    void anAcknowledgedFailureIsCarriedIntoThePackAsAStatedExclusion() {
        when(documentService.retrieve(any(), any(), any()))
                .thenReturn(DOCUMENT)
                .thenThrow(new KeyUnavailableException("Key Vault is unreachable"));

        service.export(5L, ExportPeriod.all(), ExportPurpose.REGULATORY_INSPECTION, "OFSTED-1",
                Set.of(1191L), "", principal);

        // Fail-closed does not mean "no way forward" - it means the omission is on the record. The
        // operator accepted it explicitly, so it goes in the pack's exclusions and onto the cover
        // sheet rather than vanishing.
        verify(packWriter).write(org.mockito.ArgumentMatchers.argThat(request -> {
            assertThat(request.manifest().included()).extracting(ExportManifest.ManifestEntry::interviewId)
                    .containsExactly(1182L);
            assertThat(request.manifest().excluded()).singleElement().satisfies(entry -> {
                assertThat(entry.interviewId()).isEqualTo(1191L);
                assertThat(entry.reason()).contains("could not be retrieved");
            });
            assertThat(request.attachments()).hasSize(1);
            return true;
        }));
    }

    @Test
    void anInterviewWithNoApprovedReportIsExcludedWithItsReason() {
        InterviewReport draft = new InterviewReport();
        ReflectionTestUtils.setField(draft, "id", 901L);
        draft.setStatus(ReportStatus.DRAFT);
        when(reportRepository.findByInterviewRequestId(1191L)).thenReturn(Optional.of(draft));

        ExportManifest manifest = service.manifestFor(5L, ExportPeriod.all(), principal);

        assertThat(manifest.included()).extracting(ExportManifest.ManifestEntry::interviewId).containsExactly(1182L);
        assertThat(manifest.excluded()).singleElement().satisfies(entry -> {
            assertThat(entry.interviewId()).isEqualTo(1191L);
            // The reason is the whole value of the exclusion - a bare count reads as concealment.
            assertThat(entry.reason()).contains("still a draft");
        });
        assertThat(manifest.documentCount()).isEqualTo(1);
    }

    @Test
    void aChildTheAccountCannotSeeIsIndistinguishableFromNoChild() {
        when(accessService.canViewHome(any(), any())).thenReturn(false);

        // Confirming that a child exists to an account that cannot see them is itself a disclosure,
        // so this must not be a different error from "no such child".
        assertThatThrownBy(() -> service.manifestFor(5L, ExportPeriod.all(), principal))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void scopeComesFromTheAccessServiceNotFromTheExportsOwnFilters() {
        service.manifestFor(5L, ExportPeriod.all(), principal);

        // If an export ever re-derives scope from its filters it grows a second, weaker access rule
        // that drifts from every other route. It must ask the same question they do.
        verify(accessService, org.mockito.Mockito.atLeastOnce()).canViewHome(any(), any());
    }

    @Test
    void theExportedDocumentsAreTheStoredOnesNotRegenerated() {
        when(documentService.retrieve(any(), any(), any())).thenReturn(DOCUMENT);

        service.export(5L, ExportPeriod.all(), ExportPurpose.LEGAL_PROCEEDINGS, null, Set.of(), "", principal);

        // Read back through the document store, so what is attached is byte-identical to what was
        // issued. Each retrieval also raises its own DOCUMENT_KEY_UNWRAPPED, which is correct: a
        // pack of two reports genuinely is two document accesses.
        verify(documentService, org.mockito.Mockito.times(2)).retrieve(any(), any(), any());
        verify(packWriter).write(org.mockito.ArgumentMatchers.argThat(request ->
                request.attachments().stream().allMatch(attachment -> attachment.content() == DOCUMENT)));
    }
}
