package ninja.samryecroft.returnhome.tracker.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import ninja.samryecroft.returnhome.tracker.audit.AuditFeedScope;
import ninja.samryecroft.returnhome.tracker.audit.DraftSaveRuns;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The rules that make this feature safe to ship, expressed as tests: fail closed, never omit
 * silently, and never confirm the existence of a child the account cannot see.
 */
@ExtendWith(MockitoExtension.class)
class CaseFileExportServiceTest {

    private static final byte[] DOCUMENT = "PK an issued report".getBytes(StandardCharsets.UTF_8);

    @Mock
    private ChildRepository childRepository;
    @Mock
    private InterviewRequestRepository requestRepository;
    @Mock
    private InterviewReportRepository reportRepository;
    @Mock
    private OrganisationAccessService accessService;
    @Mock
    private AuditHistoryService historyService;
    @Mock
    private ReportDocumentService documentService;
    @Mock
    private ExportPackWriter packWriter;

    private CaseFileExportService service;

    @Mock
    private AppUserPrincipal principal;

    private InterviewRequest requestOne;
    private InterviewRequest requestTwo;

    @BeforeEach
    void setUp() {
        service = new CaseFileExportService(childRepository, requestRepository,
                reportRepository, accessService, historyService, documentService, packWriter);

        Child child = new Child();
        ReflectionTestUtils.setField(child, "id", 5L);
        ReflectionTestUtils.setField(child, "localCaseReference", "CASE-001");
        when(childRepository.findDetailedById(5L)).thenReturn(Optional.of(child));

        requestOne = interviewRequest(1182L);
        requestTwo = interviewRequest(1191L);
        when(requestRepository.findByChildIdOrderByCreatedAtDesc(5L))
                .thenReturn(List.of(requestOne, requestTwo));
        when(accessService.homeScopeFor(any())).thenReturn(home -> true);
    }

    /**
     * What the EXPORT path needs and the manifest path does not (T297).
     *
     * <p><strong>These stubs used to live in {@code setUp}, where they were unused by four of the
     * seven tests.</strong> Dwight found that when the T263 bound armed strict stubbing, and
     * reported it rather than silencing it - which is the point, because five {@code lenient()}
     * calls would have made the structure invisible again. <em>The stubs were always unused; the
     * guard did not create them, it made them visible.</em>
     *
     * <p>So the fixture is narrowed to what every test needs and each test asks for the rest by
     * name. The method name is the documentation: a reader can now see which tests exercise the
     * export path without reading the service.
     */
    private void bothInterviewsResolve() {
        when(requestRepository.findDetailedById(1182L)).thenReturn(Optional.of(requestOne));
        when(requestRepository.findDetailedById(1191L)).thenReturn(Optional.of(requestTwo));
    }

    /**
     * What is needed only once a pack is actually BUILT - and the split is a finding rather than
     * tidiness.
     *
     * <p>Strict stubbing showed that {@code aReportThatCannotBeRetrievedBlocksTheWholeExport} never
     * touches either of these: it fails closed before any pack is assembled, so it never asks for
     * the history or the operator's name. <strong>That is exactly the property that test asserts</strong>,
     * and having to leave these out of it is the fixture agreeing with the assertion instead of
     * quietly contradicting it.
     */
    private void theWriterHasWhatItNeeds() {
        when(historyService.caseHistoryFor(any(), any(), any())).thenReturn(List.of());
        when(principal.getUsername()).thenReturn("orgadmin");
    }

    /** Both interviews have an approved report with a stored document. */
    private void bothInterviewsHaveApprovedReports() {
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

    /**
     * The pack asks for the audit trail UNCOLLAPSED. T177 folds runs of draft saves on the child
     * page, and this service reaches the timeline through the same builder - so the screen's
     * tidying would have followed the disclosure out of the door for free.
     *
     * <p>Written as a verify on the argument rather than an assertion about the pack, because the
     * signature is where the mistake would be made: this test's own stub was
     * {@code caseHistoryFor(any())} until the overload existed, and when the production call grew a
     * second argument the stub simply stopped matching, the call returned null, and all six tests
     * here stayed green. A stub that no longer matches looks exactly like one that does.
     *
     * <p><strong>It happened again, as predicted, when T274 A5 added the scope</strong> - and the
     * verify is now the reason the pack's SCOPE is a decision rather than whatever the child page
     * last wanted. The child page stopped listing record-access events; this pack did not, and
     * {@code WITH_ACCESS_EVENTS} here means "unchanged" rather than "extended". Both arguments are
     * pinned because both are choices this caller makes and neither has a safe default.
     */
    @Test
    void theExportPackAsksForEverySaveOnItsOwnRow() throws Exception {
        bothInterviewsResolve();
        theWriterHasWhatItNeeds();
        bothInterviewsHaveApprovedReports();
        when(documentService.retrieve(any(), any(), any())).thenReturn(DOCUMENT);

        service.export(5L, ExportPeriod.all(), ExportPurpose.REGULATORY_INSPECTION,
                "OFSTED-1", Set.of(), "", principal);

        verify(historyService).caseHistoryFor(any(), eq(DraftSaveRuns.KEPT_IN_FULL),
                eq(AuditFeedScope.WITH_ACCESS_EVENTS));
    }

    @Test
    void aReportThatCannotBeRetrievedBlocksTheWholeExport() {
        bothInterviewsResolve();
        bothInterviewsHaveApprovedReports();
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
        bothInterviewsResolve();
        theWriterHasWhatItNeeds();
        bothInterviewsHaveApprovedReports();
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
        approvedReportFor(requestOne, 900L);
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
        when(accessService.homeScopeFor(any())).thenReturn(home -> false);

        // Confirming that a child exists to an account that cannot see them is itself a disclosure,
        // so this must not be a different error from "no such child".
        assertThatThrownBy(() -> service.manifestFor(5L, ExportPeriod.all(), principal))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void scopeComesFromTheAccessServiceNotFromTheExportsOwnFilters() {
        service.manifestFor(5L, ExportPeriod.all(), principal);

        // If an export ever re-derives scope from its filters it grows a second, weaker access rule
        // that drifts from every other route. It must ask the same question they do - now via the
        // scope the access service hands out, resolved once for the list rather than per row.
        verify(accessService, org.mockito.Mockito.atLeastOnce()).homeScopeFor(any());
    }

    @Test
    void theExportedDocumentsAreTheStoredOnesNotRegenerated() {
        bothInterviewsResolve();
        theWriterHasWhatItNeeds();
        bothInterviewsHaveApprovedReports();
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
