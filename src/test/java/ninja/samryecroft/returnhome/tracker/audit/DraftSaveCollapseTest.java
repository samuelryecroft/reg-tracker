package ninja.samryecroft.returnhome.tracker.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * T177 - runs of draft saves collapse in the rendered timeline, and the one draft save that means
 * something never does.
 *
 * <p>Driven through {@link AuditHistoryService#historyFor} rather than the collapse helper, which
 * is private on purpose: a REPORT_DRAFT_SAVED targets an InterviewReport, so this is the only path
 * a draft save can actually reach the screen by. A test that reached the rule through
 * {@code historyForUser} would be greener and would be measuring a route no draft save takes.
 *
 * <p>Timestamps are fixed dates in the past rather than offsets from now: {@code dayLabel} says
 * "Today"/"Yesterday" for the two most recent days, so a test anchored on now would assert
 * different headings depending on the hour it ran, and one anchored on midnight would be flaky
 * twice a day besides.
 */
class DraftSaveCollapseTest {


    private static final long REQUEST_ID = 1L;
    private static final long REPORT_ID = 9L;
    private static final LocalDate DAY = LocalDate.of(2026, 3, 4);
    private static final String VISITOR = "VISITOR";

    private final AuditEventRepository auditEventRepository = mock(AuditEventRepository.class);
    private final InterviewReportRepository interviewReportRepository = mock(InterviewReportRepository.class);
    private final AuditHistoryService service =
            new AuditHistoryService(auditEventRepository, interviewReportRepository);

    // Built once, in fields. Mockito's when(...) is a statement about the LAST call it saw, so
    // stubbing a second mock while evaluating the argument to a first thenReturn(...) leaves the
    // matcher stack half-built and every test in the class errors on a stack trace that names the
    // wrong mock.
    private final InterviewRequest request = mock(InterviewRequest.class);
    private final InterviewReport report = mock(InterviewReport.class);

    @BeforeEach
    void wireTheReportOntoTheRequest() {
        when(request.getId()).thenReturn(REQUEST_ID);
        when(report.getId()).thenReturn(REPORT_ID);
        when(interviewReportRepository.findByInterviewRequestId(REQUEST_ID)).thenReturn(Optional.of(report));
    }

    // --- the card ---

    /**
     * THE TEST THIS CARD EXISTS FOR. Replace the rule with "collapse consecutive
     * REPORT_DRAFT_SAVED" and this goes red: the REJECTED -&gt; DRAFT save at 09:00 is folded into
     * the run of three above it and the timeline loses the moment the rework began.
     */
    @Test
    void aDraftSaveOverARejectedReportIsNeverFoldedIntoTheRunAboveIt() {
        List<AuditHistoryEntry> rows = rowsFor(
                draftSave(4L, at(11, 2), "DRAFT"),
                draftSave(3L, at(10, 30), "DRAFT"),
                draftSave(2L, at(9, 40), "DRAFT"),
                draftSave(1L, at(9, 0), "REJECTED"));

        assertThat(rows).extracting(AuditHistoryEntry::headline, AuditHistoryEntry::when)
                .containsExactly(
                        tuple("Draft saved (3 times)", "11:02"),
                        tuple("Draft saved", "09:00"));
        // and the row that survived is the rework, by id - not merely "some row survived".
        assertThat(rows.get(1).id()).isEqualTo(1L);
    }

    /** The save that CREATED the report is a beginning too: the builder writes "none" for a null. */
    @Test
    void theSaveThatCreatedTheReportIsNotFoldedIntoTheRunAboveIt() {
        List<AuditHistoryEntry> rows = rowsFor(
                draftSave(3L, at(10, 30), "DRAFT"),
                draftSave(2L, at(9, 40), "DRAFT"),
                draftSave(1L, at(9, 0), "none"));

        assertThat(rows).extracting(AuditHistoryEntry::headline)
                .containsExactly("Draft saved (2 times)", "Draft saved");
        assertThat(rows.get(1).id()).isEqualTo(1L);
    }

    // --- what collapsing does, and does not, cost ---

    @Test
    void aCollapsedRowCarriesTheCountAndTheSpanRatherThanHidingThem() {
        AuditHistoryEntry row = rowsFor(
                draftSave(3L, at(11, 2), "DRAFT"),
                draftSave(2L, at(10, 30), "DRAFT"),
                draftSave(1L, at(9, 14), "DRAFT")).get(0);

        assertThat(row.headline()).isEqualTo("Draft saved (3 times)");
        assertThat(row.detail()).isEqualTo("09:14 – 11:02");
        assertThat(row.when()).isEqualTo("11:02");
        assertThat(row.id()).isEqualTo(3L);
        assertThat(row.actorRole()).isEqualTo("Visitor");
    }

    @Test
    void aLoneDraftSaveIsRenderedExactlyAsItWasBefore() {
        assertThat(rowsFor(draftSave(1L, at(9, 14), "DRAFT")))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.headline()).isEqualTo("Draft saved");
                    assertThat(row.detail()).isNull();
                    assertThat(row.when()).isEqualTo("09:14");
                });
    }

    /** Two saves in the same minute: a span reading "09:14 – 09:14" would be noise, so there is none. */
    @Test
    void aRunWhoseEndsRenderIdenticallyGetsNoSpan() {
        AuditHistoryEntry row = rowsFor(
                draftSave(2L, at(9, 14), "DRAFT"),
                draftSave(1L, at(9, 14), "DRAFT")).get(0);

        assertThat(row.headline()).isEqualTo("Draft saved (2 times)");
        assertThat(row.detail()).isNull();
    }

    // --- what breaks a run ---

    @Test
    void anEventOfAnotherKindBreaksTheRun() {
        assertThat(rowsFor(
                draftSave(4L, at(12, 0), "DRAFT"),
                draftSave(3L, at(11, 0), "DRAFT"),
                event(2L, AuditEventType.DOCX_DOWNLOADED, at(10, 0), VISITOR, null),
                draftSave(1L, at(9, 0), "DRAFT")))
                .extracting(AuditHistoryEntry::headline)
                .containsExactly("Draft saved (2 times)", "Report downloaded", "Draft saved");
    }

    /**
     * A run breaks on a change of actor role. Collapsing across one would restate WHO did
     * something, which is the one thing this projection is for.
     */
    @Test
    void aChangeOfActorBreaksTheRun() {
        assertThat(rowsFor(
                draftSave(3L, at(11, 0), "DRAFT"),
                event(2L, AuditEventType.REPORT_DRAFT_SAVED, at(10, 0), "ADMIN", "statusBefore=DRAFT"),
                draftSave(1L, at(9, 0), "DRAFT")))
                .extracting(AuditHistoryEntry::headline, AuditHistoryEntry::actorRole)
                .containsExactly(
                        tuple("Draft saved", "Visitor"),
                        tuple("Draft saved", "Admin"),
                        tuple("Draft saved", "Visitor"));
    }

    /**
     * A run across midnight collapses once per day. Days are cut from the events before anything is
     * collapsed, so a heading never gains rows from a day it does not name.
     */
    @Test
    void aRunAcrossMidnightCollapsesOncePerDay() {
        List<AuditHistorySection> sections = sectionsFor(
                draftSave(4L, LocalDateTime.of(2026, 3, 5, 0, 20), "DRAFT"),
                draftSave(3L, LocalDateTime.of(2026, 3, 5, 0, 5), "DRAFT"),
                draftSave(2L, LocalDateTime.of(2026, 3, 4, 23, 50), "DRAFT"),
                draftSave(1L, LocalDateTime.of(2026, 3, 4, 23, 30), "DRAFT"));

        assertThat(sections).extracting(AuditHistorySection::label)
                .containsExactly("05 March 2026", "04 March 2026");
        assertThat(sections.get(0).entries()).singleElement()
                .extracting(AuditHistoryEntry::detail).isEqualTo("00:05 – 00:20");
        assertThat(sections.get(1).entries()).singleElement()
                .extracting(AuditHistoryEntry::detail).isEqualTo("23:30 – 23:50");
    }

    /**
     * Request rows and report rows arrive as two separately-sorted lists and were concatenated, not
     * merged - so a request event always sorted ahead of a report event whatever the clock said.
     * "Consecutive" is a claim about time order, so the collapse rule is only as correct as this.
     */
    @Test
    void requestAndReportEventsAreInterleavedByTimeNotByWhichListTheyCameFrom() {
        // Built before any when(...) opens - see the note on the mock fields above.
        List<AuditEvent> requestEvents =
                List.of(event(1L, AuditEventType.INTERVIEW_REQUEST_SCHEDULED, at(10, 0), "ADMIN", null));
        List<AuditEvent> reportEvents =
                List.of(draftSave(3L, at(11, 0), "DRAFT"), draftSave(2L, at(9, 0), "none"));
        when(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("InterviewRequest", REQUEST_ID))
                .thenReturn(requestEvents);
        when(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("InterviewReport", REPORT_ID))
                .thenReturn(reportEvents);

        assertThat(service.historyFor(request).get(0).entries())
                .extracting(AuditHistoryEntry::when)
                .containsExactly("11:00", "10:00", "09:00");
    }

    // --- the export is not a screen ---

    /**
     * The case-file export reaches the timeline through the same builder, which is what let this
     * change land without a template edit and is also what would have collapsed a disclosure as a
     * side effect of tidying a screen. KEPT_IN_FULL is the export's answer; this asserts it is a
     * real one and not a parameter nobody reads.
     */
    @Test
    void keptInFullLeavesEverySaveOnItsOwnRow() {
        List<AuditEvent> events = List.of(
                draftSave(3L, at(11, 2), "DRAFT"),
                draftSave(2L, at(10, 30), "DRAFT"),
                draftSave(1L, at(9, 14), "DRAFT"));
        when(request.getCreatedAt()).thenReturn(at(8, 0));
        when(auditEventRepository.findByTargetTypeAndTargetIdInOrderByOccurredAtDesc(
                "InterviewRequest", List.of(REQUEST_ID))).thenReturn(List.of());
        when(auditEventRepository.findByTargetTypeAndTargetIdInOrderByOccurredAtDesc(
                "InterviewReport", List.of(REPORT_ID))).thenReturn(events);
        events.forEach(event -> when(event.getTargetType()).thenReturn("InterviewReport"));
        events.forEach(event -> when(event.getTargetId()).thenReturn(REPORT_ID));

        assertThat(service.caseHistoryFor(List.of(request), DraftSaveRuns.KEPT_IN_FULL))
                .singleElement()
                .extracting(AuditHistorySection::entries, list(AuditHistoryEntry.class))
                .extracting(AuditHistoryEntry::headline, AuditHistoryEntry::id)
                .containsExactly(
                        tuple("Draft saved", 3L),
                        tuple("Draft saved", 2L),
                        tuple("Draft saved", 1L));
    }

    /** The same events through the screen's default, so the two are told apart by the flag alone. */
    @Test
    void theSameEventsCollapseOnTheChildPage() {
        List<AuditEvent> events = List.of(
                draftSave(3L, at(11, 2), "DRAFT"),
                draftSave(2L, at(10, 30), "DRAFT"),
                draftSave(1L, at(9, 14), "DRAFT"));
        when(request.getCreatedAt()).thenReturn(at(8, 0));
        when(auditEventRepository.findByTargetTypeAndTargetIdInOrderByOccurredAtDesc(
                "InterviewRequest", List.of(REQUEST_ID))).thenReturn(List.of());
        when(auditEventRepository.findByTargetTypeAndTargetIdInOrderByOccurredAtDesc(
                "InterviewReport", List.of(REPORT_ID))).thenReturn(events);
        events.forEach(event -> when(event.getTargetType()).thenReturn("InterviewReport"));
        events.forEach(event -> when(event.getTargetId()).thenReturn(REPORT_ID));

        assertThat(service.caseHistoryFor(List.of(request)))
                .singleElement()
                .extracting(AuditHistorySection::entries, list(AuditHistoryEntry.class))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.headline()).isEqualTo("Draft saved (3 times)");
                    // The child page's WHEN column is date-only, so a same-day run gets no span.
                    assertThat(row.when()).isEqualTo("04 Mar 2026");
                    assertThat(row.detail()).isNull();
                });
    }

    // --- fixtures ---

    private List<AuditHistoryEntry> rowsFor(AuditEvent... reportEvents) {
        List<AuditHistorySection> sections = sectionsFor(reportEvents);
        assertThat(sections).hasSize(1);
        return sections.get(0).entries();
    }

    private List<AuditHistorySection> sectionsFor(AuditEvent... reportEvents) {
        when(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("InterviewRequest", REQUEST_ID))
                .thenReturn(List.of());
        when(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("InterviewReport", REPORT_ID))
                .thenReturn(List.of(reportEvents));
        return service.historyFor(request);
    }

    private static LocalDateTime at(int hour, int minute) {
        return LocalDateTime.of(DAY, java.time.LocalTime.of(hour, minute));
    }

    private static AuditEvent draftSave(long id, LocalDateTime occurredAt, String statusBefore) {
        return event(id, AuditEventType.REPORT_DRAFT_SAVED, occurredAt, VISITOR, "statusBefore=" + statusBefore);
    }

    private static AuditEvent event(long id, AuditEventType type, LocalDateTime occurredAt, String roles,
            String metadata) {
        AuditEvent event = mock(AuditEvent.class);
        when(event.getId()).thenReturn(id);
        when(event.getEventType()).thenReturn(type);
        when(event.getOccurredAt()).thenReturn(occurredAt);
        when(event.getActorRolesAtTime()).thenReturn(roles);
        when(event.getMetadata()).thenReturn(metadata);
        return event;
    }
}
