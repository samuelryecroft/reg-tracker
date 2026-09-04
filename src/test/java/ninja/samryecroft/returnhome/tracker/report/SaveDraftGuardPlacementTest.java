package ninja.samryecroft.returnhome.tracker.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestService;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestTestFixtures;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
import ninja.samryecroft.returnhome.tracker.report.docx.DocxReportGenerator;
import ninja.samryecroft.returnhome.tracker.report.dto.SubmitReportForm;
import ninja.samryecroft.returnhome.tracker.theme.ThemeService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;

/**
 * The {@code saveDraft} half of {@link SubmitForReviewGuardPlacementTest}, and it exists for exactly
 * the same reason: {@code saveDraft} is {@code @Transactional}, so a guard placed at the end of the
 * method produces the same observable end state as one placed at the top - the rollback undoes the
 * field writes either way, and every assertion in
 * {@link DraftSaveCannotOverwriteAFinishedReportIntegrationTest} still passes.
 * <b>When the outcome is identical either way, assert the branch rather than the outcome.</b> So
 * this is a plain unit test with mocked collaborators: no transaction, no rollback to be rescued by.
 *
 * <p>The placement is load-bearing for the reason Kevin gave on the submit side and which applies
 * unchanged here: the report is a <em>managed</em> entity in production, and Hibernate flushes
 * in-memory mutations at commit whether or not anything called {@code save()}. "Mutate, then throw"
 * is safe today only because the rollback happens to catch it, which makes the safety of the method
 * a property of where the transaction boundary sits rather than of the guard.
 *
 * <p>This class also pins the {@code statusBefore} argument, which the integration test can only
 * observe through rendered metadata: the value published must be the status the save <em>overwrote</em>,
 * not the {@code DRAFT} the method has just written. Reading it after {@code applyFormValues} would
 * record every save as DRAFT &rarr; DRAFT and lose the one transition worth finding.
 */
class SaveDraftGuardPlacementTest {

    private final InterviewReportRepository interviewReportRepository = mock(InterviewReportRepository.class);
    private final InterviewRequestService interviewRequestService = mock(InterviewRequestService.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final AuditEventPublisher auditEventPublisher = mock(AuditEventPublisher.class);

    private final ReportService reportService = new ReportService(
            interviewReportRepository, interviewRequestService, userRepository,
            mock(DocxReportGenerator.class), mock(AppProperties.class), mock(ThemeService.class),
            auditEventPublisher, mock(ReportDocumentService.class));

    @Test
    void anApprovedReportIsRefusedBeforeAnyOfItIsOverwritten() {
        AppUserPrincipal admin = admin();
        User approver = plainUser("the-approver");
        LocalDateTime approvedAt = LocalDateTime.of(2026, 7, 30, 9, 0);

        InterviewRequest request = InterviewRequestTestFixtures.requestAt(InterviewStatus.REPORT_APPROVED);
        InterviewReport approved = new InterviewReport();
        approved.setInterviewRequest(request);
        approved.setVisitor(plainUser("the-author"));
        approved.setStatus(ReportStatus.APPROVED);
        approved.setReviewedBy(approver);
        approved.setReviewedAt(approvedAt);
        approved.setReviewComments("Approved - thorough and timely");
        approved.setInterviewLocation("The home's quiet room");
        approved.setGeneratedDocumentPath("reports/approved.docx");

        when(interviewRequestService.getAuthorized(1L, admin)).thenReturn(request);
        when(interviewReportRepository.findByInterviewRequestId(1L)).thenReturn(Optional.of(approved));

        SubmitReportForm form = new SubmitReportForm();
        form.setInterviewLocation("Somewhere else entirely");
        form.setInterviewerComments("Rewritten after approval");

        assertThatThrownBy(() -> reportService.saveDraft(1L, form, admin))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approved");

        verify(interviewReportRepository, never()).save(any());
        verify(auditEventPublisher, never()).reportDraftSaved(any(), any(), any());

        // The in-memory entity is untouched - the part Hibernate would flush at commit with no
        // save() call at all, and the two properties CaseFileExportService reads.
        assertThat(approved.getStatus()).isEqualTo(ReportStatus.APPROVED);
        assertThat(approved.getGeneratedDocumentPath()).isEqualTo("reports/approved.docx");
        assertThat(approved.getReviewedBy()).isEqualTo(approver);
        assertThat(approved.getReviewedAt()).isEqualTo(approvedAt);
        assertThat(approved.getInterviewLocation()).isEqualTo("The home's quiet room");
    }

    /**
     * A separate harm from the approved case, so it gets its own branch assertion: a guard written as
     * {@code == APPROVED} alone passes the test above and leaves a reviewer's in-flight report
     * editable underneath them.
     */
    @Test
    void aSubmittedReportIsRefusedBeforeAnyOfItIsOverwritten() {
        AppUserPrincipal admin = admin();
        InterviewRequest request = InterviewRequestTestFixtures.requestAt(InterviewStatus.REPORT_SUBMITTED);

        InterviewReport submitted = new InterviewReport();
        submitted.setInterviewRequest(request);
        submitted.setVisitor(plainUser("the-author"));
        submitted.setStatus(ReportStatus.SUBMITTED);
        submitted.setInterviewerComments("As submitted for review");

        when(interviewRequestService.getAuthorized(1L, admin)).thenReturn(request);
        when(interviewReportRepository.findByInterviewRequestId(1L)).thenReturn(Optional.of(submitted));

        SubmitReportForm form = new SubmitReportForm();
        form.setInterviewerComments("Changed while the reviewer was reading it");

        assertThatThrownBy(() -> reportService.saveDraft(1L, form, admin))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("submitted for review");

        verify(interviewReportRepository, never()).save(any());
        verify(auditEventPublisher, never()).reportDraftSaved(any(), any(), any());
        assertThat(submitted.getStatus()).isEqualTo(ReportStatus.SUBMITTED);
        assertThat(submitted.getInterviewerComments()).isEqualTo("As submitted for review");
    }

    /**
     * The paired positive - a "guard" that refused everything would pass both tests above - and the
     * assertion that the published {@code statusBefore} is the status that was overwritten rather
     * than the DRAFT just written over it.
     */
    @Test
    void aRejectedReportIsSavedAndTheEventRecordsTheStatusItOverwrote() {
        AppUserPrincipal admin = admin();
        InterviewRequest request = InterviewRequestTestFixtures.requestAt(InterviewStatus.REPORT_REJECTED);

        InterviewReport rejected = new InterviewReport();
        rejected.setInterviewRequest(request);
        rejected.setVisitor(plainUser("the-author"));
        rejected.setStatus(ReportStatus.REJECTED);

        when(interviewRequestService.getAuthorized(1L, admin)).thenReturn(request);
        when(interviewReportRepository.findByInterviewRequestId(1L)).thenReturn(Optional.of(rejected));
        when(interviewReportRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SubmitReportForm form = new SubmitReportForm();
        form.setInterviewerComments("Reworking the risk section as asked");
        reportService.saveDraft(1L, form, admin);

        verify(interviewReportRepository).save(any());
        verify(auditEventPublisher).reportDraftSaved(any(), eq(ReportStatus.REJECTED), eq(admin));
        assertThat(rejected.getStatus()).isEqualTo(ReportStatus.DRAFT);
        assertThat(rejected.getInterviewerComments()).isEqualTo("Reworking the risk section as asked");
    }

    private AppUserPrincipal admin() {
        User user = plainUser("platform-admin");
        user.setRoles(Set.of(Role.ADMIN));
        return new AppUserPrincipal(user);
    }

    private User plainUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setLastName(username);
        user.setRoles(Set.of(Role.VISITOR));
        user.setEnabled(true);
        return user;
    }
}
