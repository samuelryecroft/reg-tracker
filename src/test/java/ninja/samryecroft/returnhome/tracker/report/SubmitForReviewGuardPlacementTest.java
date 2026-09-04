package ninja.samryecroft.returnhome.tracker.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
 * T145 follow-up: the guard in {@code submitForReview} runs <em>before</em> anything is mutated, not
 * merely before the status is written.
 *
 * <p>The integration tests can't see this. {@code submitForReview} is {@code @Transactional}, so a
 * guard at the end of the method produces exactly the same observable end state as a guard at the
 * top - the rollback undoes the field writes either way, and every assertion still passes. That is
 * the T130 shape: <b>when the outcome is identical either way, assert the BRANCH rather than the
 * outcome.</b> So this test removes the thing doing the hiding - it is a plain unit test with mocked
 * collaborators, where there is no transaction and therefore no rollback to be rescued by.
 *
 * <p><b>Why the placement is genuinely load-bearing rather than tidiness</b> (Kevin's point, and it
 * isn't visible from reading the method): the report here is a <em>managed</em> entity in production,
 * and Hibernate's dirty checking flushes in-memory mutations at commit whether or not anything called
 * {@code save()}. So "mutate, then throw" is safe today only because the rollback happens to catch
 * it - the explicit {@code save()} is not what would have persisted the damage. Move the guard down
 * and the safety of this method depends entirely on a transaction boundary staying where it is.
 */
class SubmitForReviewGuardPlacementTest {

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
        // An ADMIN principal, because that is the account that can submit a report it was not
        // allocated - the same path T143 used to make an admin a report's author.
        AppUserPrincipal admin = admin();
        User approver = plainUser("the-approver");
        LocalDateTime approvedAt = LocalDateTime.of(2026, 7, 30, 9, 0);

        InterviewRequest request = InterviewRequestTestFixtures.requestAt(InterviewStatus.REPORT_SUBMITTED);
        InterviewReport approved = new InterviewReport();
        approved.setInterviewRequest(request);
        approved.setVisitor(plainUser("the-author"));
        approved.setStatus(ReportStatus.APPROVED);
        approved.setReviewedBy(approver);
        approved.setReviewedAt(approvedAt);
        approved.setReviewComments("Approved - thorough and timely");
        approved.setInterviewLocation("The home's quiet room");

        when(interviewRequestService.getAuthorized(1L, admin)).thenReturn(request);
        when(interviewReportRepository.findByInterviewRequestId(1L)).thenReturn(Optional.of(approved));

        SubmitReportForm form = new SubmitReportForm();
        form.setInterviewLocation("Somewhere else entirely");
        form.setInterviewerComments("Rewritten after approval");

        assertThatThrownBy(() -> reportService.submitForReview(1L, form, admin))
                .isInstanceOf(IllegalStateException.class);

        // The branch assertions. These are what distinguish a guard at the top from a guard at the
        // bottom: with the guard at the bottom, both of these have already happened by the time the
        // exception is thrown, and only the rollback saves us.
        verify(interviewReportRepository, never()).save(any());
        verify(interviewRequestService, never()).markStatus(any(), any());
        verify(auditEventPublisher, never()).reportSubmitted(any(), any(), any());

        // And the in-memory entity is untouched - the part Hibernate would flush at commit even with
        // no save() call at all.
        assertThat(approved.getStatus()).isEqualTo(ReportStatus.APPROVED);
        assertThat(approved.getReviewedBy()).isEqualTo(approver);
        assertThat(approved.getReviewedAt()).isEqualTo(approvedAt);
        assertThat(approved.getReviewComments()).isEqualTo("Approved - thorough and timely");
        assertThat(approved.getInterviewLocation()).isEqualTo("The home's quiet room");
        assertThat(approved.getSubmittedAt()).isNull();
    }

    /**
     * The paired positive: the guard refuses an approved report and nothing else. Without this, a
     * "guard" that rejected every submission would pass the test above.
     */
    @Test
    void aRejectedReportIsStillAcceptedAndSaved() {
        AppUserPrincipal admin = admin();
        InterviewRequest request = InterviewRequestTestFixtures.requestAt(InterviewStatus.REPORT_REJECTED);

        InterviewReport rejected = new InterviewReport();
        rejected.setInterviewRequest(request);
        rejected.setVisitor(plainUser("the-author"));
        rejected.setStatus(ReportStatus.REJECTED);
        rejected.setReviewComments("Please expand the risk section");

        when(interviewRequestService.getAuthorized(1L, admin)).thenReturn(request);
        when(interviewReportRepository.findByInterviewRequestId(1L)).thenReturn(Optional.of(rejected));
        when(interviewReportRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        reportService.submitForReview(1L, new SubmitReportForm(), admin);

        verify(interviewReportRepository).save(any());
        verify(interviewRequestService).markStatus(request, InterviewStatus.REPORT_SUBMITTED);
        assertThat(rejected.getStatus()).isEqualTo(ReportStatus.SUBMITTED);
        assertThat(rejected.getReviewComments()).isNull();
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
