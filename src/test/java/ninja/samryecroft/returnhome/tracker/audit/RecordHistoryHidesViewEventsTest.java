package ninja.samryecroft.returnhome.tracker.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T246: a request's own history stops listing the times somebody opened it.
 *
 * <p>Opening the interview request page emits AUDIT_VIEW_OPENED against that very request, so the
 * panel telling the request's story filled with rows about people reading it - rendered, for want of
 * a case in the entry switch, as the literal string {@code "AUDIT_VIEW_OPENED"}.
 *
 * <p><strong>Display only.</strong> Nothing here asserts anything about what is emitted, because
 * nothing about that changed: the events are still written and still queryable. A panel you filtered
 * can be unfiltered; a record you stopped writing cannot be recovered.
 */
@ExtendWith(MockitoExtension.class)
class RecordHistoryHidesViewEventsTest {

    private static final long REQUEST_ID = 1L;
    private static final long REPORT_ID = 9L;

    @Mock
    private AuditEventRepository auditEventRepository;
    @Mock
    private InterviewReportRepository interviewReportRepository;
    @Mock
    private InterviewRequest request;
    @Mock
    private InterviewReport report;

    private AuditHistoryService service;

    @BeforeEach
    void wireTheRequest() {
        service = new AuditHistoryService(auditEventRepository, interviewReportRepository);
        when(request.getId()).thenReturn(REQUEST_ID);
    }

    @Test
    void theTimesSomebodyOpenedTheRequestAreNotPartOfItsStory() {
        List<AuditHistoryEntry> rows = rowsFor(
                event(3L, AuditEventType.AUDIT_VIEW_OPENED, at(11, 0)),
                event(2L, AuditEventType.INTERVIEW_REQUEST_SCHEDULED, at(10, 0)),
                event(1L, AuditEventType.AUDIT_VIEW_OPENED, at(9, 0)));

        assertThat(rows).extracting(AuditHistoryEntry::headline)
                .containsExactly("Interview scheduled")
                .doesNotContain("AUDIT_VIEW_OPENED");
    }

    /**
     * THE TEST THAT DECIDES WHERE THE FILTER GOES, and the reason this card had an ordering
     * requirement at all.
     *
     * <p>Two draft saves with a view event between them are not adjacent in the raw list. Once the
     * view event is gone they ARE, and they must collapse into a SINGLE row - because what the
     * reader sees has to be the collapse of what the reader sees. Filter after the collapse and this
     * goes red with two "Draft saved" rows and nothing between them: T177's wall of noise,
     * reintroduced by the fix for a different kind of noise.
     */
    @Test
    void aViewEventBetweenTwoDraftSavesLeavesThemADJACENTAndTheyCollapseAsOne() {
        List<AuditHistoryEntry> rows = rowsFor(
                draftSave(3L, at(11, 0)),
                event(2L, AuditEventType.AUDIT_VIEW_OPENED, at(10, 0)),
                draftSave(1L, at(9, 0)));

        assertThat(rows).singleElement()
                .extracting(AuditHistoryEntry::headline)
                .isEqualTo("Draft saved (2 times)");
    }

    /** Only the view rows go. Everything the request actually did is still there, in order. */
    @Test
    void nothingElseIsRemovedFromTheStory() {
        List<AuditHistoryEntry> rows = rowsFor(
                event(4L, AuditEventType.REPORT_SUBMITTED, at(12, 0)),
                event(3L, AuditEventType.AUDIT_VIEW_OPENED, at(11, 0)),
                event(2L, AuditEventType.INTERVIEW_REQUEST_ALLOCATED, at(10, 0)),
                event(1L, AuditEventType.INTERVIEW_REQUEST_CREATED, at(9, 0)));

        assertThat(rows).extracting(AuditHistoryEntry::headline)
                .containsExactly("Report submitted for review", "Visitor allocated", "Interview requested");
    }

    /** A history that was ONLY view events becomes empty rather than partially filtered. */
    @Test
    void aRequestNobodyHasActedOnHasNoRowsAtAll() {
        // Only the event TYPE is stubbed here, and the shared helper is not used, because under
        // strict stubs an unused stub is a build failure - and that is the guard doing its job:
        // when every event is filtered, nothing downstream ever asks this one for its timestamp.
        // The stub that would go unread is itself evidence the filter ran before the grouping.
        AuditEvent view = org.mockito.Mockito.mock(AuditEvent.class);
        when(view.getEventType()).thenReturn(AuditEventType.AUDIT_VIEW_OPENED);
        List<AuditEvent> onlyViews = List.of(view);
        when(interviewReportRepository.findByInterviewRequestId(REQUEST_ID)).thenReturn(Optional.empty());
        when(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("InterviewRequest", REQUEST_ID))
                .thenReturn(onlyViews);

        assertThat(service.historyFor(request, DraftSaveRuns.COLLAPSED)).isEmpty();
    }

    // --- T274 A5: the child page's cross-request case history, and the export that shares it ---

    /**
     * The child page asks for the same story without the record-access rows.
     *
     * <p>This is the SECOND half of a move rather than a subtraction: the access events reached the
     * audit feed first (T274 A1), and had they not, removing them from the child page would have
     * amounted to having quietly stopped recording them.
     */
    @Test
    void theChildPageStoryDoesNotListWhoOpenedTheRequest() {
        List<AuditHistoryEntry> rows = caseRowsFor(AuditFeedScope.CASE_ACTIVITY_ONLY,
                event(3L, AuditEventType.REPORT_SUBMITTED, at(12, 0)),
                filteredView(),
                event(1L, AuditEventType.INTERVIEW_REQUEST_CREATED, at(9, 0)));

        assertThat(rows).extracting(AuditHistoryEntry::headline)
                .containsExactly("Report submitted for review", "Interview requested");
    }

    /**
     * <strong>The other direction, and the reason the scope is a parameter at all.</strong> The
     * case-file pack for a DPO, a local authority or a court asks the same method for the same
     * events and still gets the access rows. Without this, filtering for the child page would have
     * tidied a screen and silently rewritten every disclosure produced afterwards - the trap
     * {@code caseActivityFeed} documents one method over.
     */
    @Test
    void theCaseFilePackStillGetsTheAccessRowsFromTheSameMethod() {
        List<AuditHistoryEntry> rows = caseRowsFor(AuditFeedScope.WITH_ACCESS_EVENTS,
                event(3L, AuditEventType.REPORT_SUBMITTED, at(12, 0)),
                event(2L, AuditEventType.AUDIT_VIEW_OPENED, at(11, 0)),
                event(1L, AuditEventType.INTERVIEW_REQUEST_CREATED, at(9, 0)));

        assertThat(rows).hasSize(3);
    }

    /**
     * The ordering requirement, restated for this method because it has its own copy of the pipeline.
     *
     * <p>Two draft saves with a view event between them are not adjacent in the raw list; once the
     * view event is gone they ARE, and must collapse into ONE row - what the reader sees has to be
     * the collapse of what the reader sees. Filter after the collapse and this goes red with two
     * "Draft saved" rows and nothing between them.
     */
    @Test
    void onTheChildPageTooAViewEventBetweenTwoDraftSavesLeavesThemAdjacent() {
        List<AuditHistoryEntry> rows = caseRowsFor(AuditFeedScope.CASE_ACTIVITY_ONLY,
                draftSave(3L, at(11, 0)),
                filteredView(),
                draftSave(1L, at(9, 0)));

        assertThat(rows).singleElement()
                .extracting(AuditHistoryEntry::headline)
                .isEqualTo("Draft saved (2 times)");
    }

    // --- fixtures ---

    private List<AuditHistoryEntry> rowsFor(AuditEvent... requestEvents) {
        when(interviewReportRepository.findByInterviewRequestId(REQUEST_ID)).thenReturn(Optional.of(report));
        when(report.getId()).thenReturn(REPORT_ID);
        when(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("InterviewRequest", REQUEST_ID))
                .thenReturn(List.of(requestEvents));
        when(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("InterviewReport", REPORT_ID))
                .thenReturn(List.of());
        List<AuditHistorySection> sections = service.historyFor(request, DraftSaveRuns.COLLAPSED);
        assertThat(sections).hasSize(1);
        return sections.get(0).entries();
    }

    /**
     * A view event with ONLY its type stubbed, and the missing timestamp is the point.
     *
     * <p>Under strict stubs an unread stub fails the build, and a timestamp here would go unread -
     * because {@code caseHistoryFor} filters BEFORE it sorts, so a removed event is never asked when
     * it happened. That is a real difference from {@code historyFor}, which sorts first and would
     * read it. <strong>The stub that cannot be written is the evidence the filter runs early</strong>,
     * the same way the empty-history test above uses it.
     */
    private static AuditEvent filteredView() {
        AuditEvent view = org.mockito.Mockito.mock(AuditEvent.class);
        when(view.getEventType()).thenReturn(AuditEventType.AUDIT_VIEW_OPENED);
        return view;
    }

    private List<AuditHistoryEntry> caseRowsFor(AuditFeedScope scope, AuditEvent... requestEvents) {
        when(request.getCreatedAt()).thenReturn(at(8, 0));
        when(interviewReportRepository.findByInterviewRequestId(REQUEST_ID)).thenReturn(Optional.empty());
        when(auditEventRepository.findByTargetTypeAndTargetIdInOrderByOccurredAtDesc(
                "InterviewRequest", List.of(REQUEST_ID))).thenReturn(List.of(requestEvents));
        for (AuditEvent event : requestEvents) {
            // Only read for events that survive the filter, so these are lenient rather than strict:
            // pinning which ones get asked would pin the filter's position into the fixture, and the
            // position is the thing under test.
            org.mockito.Mockito.lenient().when(event.getTargetType()).thenReturn("InterviewRequest");
            org.mockito.Mockito.lenient().when(event.getTargetId()).thenReturn(REQUEST_ID);
        }
        List<AuditHistorySection> sections = service.caseHistoryFor(List.of(request), DraftSaveRuns.COLLAPSED, scope);
        assertThat(sections).hasSize(1);
        return sections.get(0).entries();
    }

    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(2026, 3, 4, hour, minute);
    }

    private static AuditEvent draftSave(long id, LocalDateTime occurredAt) {
        AuditEvent auditEvent = event(id, AuditEventType.REPORT_DRAFT_SAVED, occurredAt);
        when(auditEvent.getMetadata()).thenReturn("statusBefore=DRAFT");
        return auditEvent;
    }

    private static AuditEvent event(long id, AuditEventType type, LocalDateTime occurredAt) {
        AuditEvent auditEvent = org.mockito.Mockito.mock(AuditEvent.class);
        when(auditEvent.getEventType()).thenReturn(type);
        when(auditEvent.getOccurredAt()).thenReturn(occurredAt);
        return auditEvent;
    }
}
