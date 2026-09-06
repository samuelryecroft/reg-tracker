package ninja.samryecroft.returnhome.tracker.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The org-wide case-activity feed does not collapse anything, and this is here so that stays a
 * decision rather than an accident.
 *
 * <p>{@code caseActivityFeed} has the shape T177 got wrong one method over: ONE BUILDER, TWO
 * CONSUMERS THAT ARE NOT THE SAME KIND OF THING. {@code AuditFeedController} renders the org-wide
 * feed SCREEN from it, and {@code AuditFeedController.exportCsv} builds the audit-trail CSV - a
 * disclosure, taken under a purpose and a reference and recorded as its own audit event. Today the
 * CSV is complete by construction, because nothing here collapses. That is the same standing that
 * {@code caseHistoryFor} had before somebody collapsed it for a screen, which is not a standing
 * worth relying on twice.
 *
 * <p>So: if a future card collapses this feed to tidy the screen, this test goes red, and the
 * person doing it has to split the two callers first - the same way {@link DraftSaveRuns} makes
 * them split for the case history. It is not asserting that collapsing the feed is wrong. It is
 * asserting that collapsing it silently, for both consumers at once, cannot happen by omission.
 */
class AuditFeedNeverCollapsesTest {

    private static final long ORG_ID = 3L;
    private static final long HOME_ID = 4L;
    private static final long REQUEST_ID = 1L;
    private static final long REPORT_ID = 9L;

    private final AuditEventRepository auditEventRepository = mock(AuditEventRepository.class);
    private final InterviewReportRepository interviewReportRepository = mock(InterviewReportRepository.class);
    private final AuditHistoryService service =
            new AuditHistoryService(auditEventRepository, interviewReportRepository);

    // Built in fields: Mockito's when(...) refers to the last call it saw, so stubbing a second mock
    // while evaluating an argument to a first thenReturn(...) leaves the matcher stack half-built.
    private final Organisation organisation = mock(Organisation.class);
    private final Home home = mock(Home.class);
    private final Child child = mock(Child.class);
    private final InterviewRequest request = mock(InterviewRequest.class);
    private final InterviewReport report = mock(InterviewReport.class);

    @BeforeEach
    void wireOneRequestInScope() {
        when(organisation.getId()).thenReturn(ORG_ID);
        when(home.getId()).thenReturn(HOME_ID);
        when(home.getName()).thenReturn("History House");
        when(home.getOrganisation()).thenReturn(organisation);
        when(child.getFullName()).thenReturn("A Child");
        when(request.getId()).thenReturn(REQUEST_ID);
        when(request.getHome()).thenReturn(home);
        when(request.getChild()).thenReturn(child);
        when(report.getId()).thenReturn(REPORT_ID);
        when(report.getInterviewRequest()).thenReturn(request);
        when(interviewReportRepository.findByInterviewRequestIdIn(anyCollection())).thenReturn(List.of(report));
    }

    @Test
    void fourConsecutiveDraftSavesAreFourRowsInTheFeed() {
        List<AuditEvent> saves = List.of(
                draftSave(4L, at(11, 2)), draftSave(3L, at(10, 30)),
                draftSave(2L, at(9, 40)), draftSave(1L, at(9, 14)));
        when(auditEventRepository.findByOrganisationIdIn(anyCollection())).thenReturn(saves);

        assertThat(service.caseActivityFeed(List.of(request), null, null, null))
                .extracting(row -> row.entry().id(), row -> row.entry().headline())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(4L, "Draft saved"),
                        org.assertj.core.groups.Tuple.tuple(3L, "Draft saved"),
                        org.assertj.core.groups.Tuple.tuple(2L, "Draft saved"),
                        org.assertj.core.groups.Tuple.tuple(1L, "Draft saved"));
    }

    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(2026, 3, 4, hour, minute);
    }

    private AuditEvent draftSave(long id, LocalDateTime occurredAt) {
        AuditEvent event = mock(AuditEvent.class);
        when(event.getId()).thenReturn(id);
        when(event.getEventType()).thenReturn(AuditEventType.REPORT_DRAFT_SAVED);
        when(event.getOccurredAt()).thenReturn(occurredAt);
        when(event.getActorRolesAtTime()).thenReturn("VISITOR");
        when(event.getMetadata()).thenReturn("statusBefore=DRAFT");
        when(event.getTargetType()).thenReturn("InterviewReport");
        when(event.getTargetId()).thenReturn(REPORT_ID);
        return event;
    }
}
