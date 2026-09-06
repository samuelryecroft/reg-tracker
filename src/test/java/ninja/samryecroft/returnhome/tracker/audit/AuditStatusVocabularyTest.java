package ninja.samryecroft.returnhome.tracker.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import ninja.samryecroft.returnhome.tracker.report.ReportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The audit timeline says a report's state in the word the system uses for that state.
 *
 * <p>It did not. {@code statusDetail} rendered the enum constant through {@code titleCase} - the
 * generic formatter shared with role names - so a sent-back report's row read <em>"Report sent back
 * for revision · Status: Rejected"</em>: one row, one event, two vocabularies, three words apart.
 * Creed's #45 follow-up had renamed that word because <em>"Rejected" reads as a verdict where the
 * reality is a request for more detail</em>.
 *
 * <p><strong>The tests are written over every ReportStatus constant, not over REJECTED, and that is
 * the whole point.</strong> Special-casing REJECTED inside {@code titleCase} would fix the reported
 * string and leave the word right by coincidence of the formatter rather than because the system was
 * asked what it calls the state - so an assertion naming only "Sent back" would go green on the fix
 * Creed rejected. Asserting every constant against {@code getDisplayName()} is red for that patch,
 * because SUBMITTED renders "Pending review" and no amount of special-casing REJECTED produces it.
 */
class AuditStatusVocabularyTest {

    private static final long REQUEST_ID = 1L;
    private static final long REPORT_ID = 9L;

    private final AuditEventRepository auditEventRepository = mock(AuditEventRepository.class);
    private final InterviewReportRepository interviewReportRepository = mock(InterviewReportRepository.class);
    private final AuditHistoryService service =
            new AuditHistoryService(auditEventRepository, interviewReportRepository);
    private final InterviewRequest request = mock(InterviewRequest.class);
    private final InterviewReport report = mock(InterviewReport.class);

    @BeforeEach
    void wireTheReportOntoTheRequest() {
        when(request.getId()).thenReturn(REQUEST_ID);
        when(report.getId()).thenReturn(REPORT_ID);
        when(interviewReportRepository.findByInterviewRequestId(REQUEST_ID)).thenReturn(Optional.of(report));
    }

    /**
     * The mechanism, asserted directly: the rendered word is the one the enum gives, for every state
     * the enum has. Red on a one-string patch, and red on the next status added with a display name
     * the formatter cannot guess.
     */
    @ParameterizedTest
    @EnumSource(ReportStatus.class)
    void everyReportStateIsSaidInTheWordTheSystemUsesForIt(ReportStatus status) {
        assertThat(detailOf(AuditEventType.REPORT_SUBMITTED, "reportStatus=" + status.name()))
                .isEqualTo("Status: " + status.getDisplayName());
    }

    /** The reported row, end to end: the headline and the detail are now one vocabulary. */
    @Test
    void aSentBackRowNoLongerSaysTheEventTwiceInTwoVocabularies() {
        List<AuditHistoryEntry> rows = rowsFor(event(AuditEventType.REPORT_REJECTED,
                "reportStatus=REJECTED; commentsProvided=true"));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.headline()).isEqualTo("Report sent back for revision");
            assertThat(row.detail()).isEqualTo("Status: Sent back · Comments provided");
            assertThat(row.headline() + " " + row.detail()).doesNotContain("Rejected");
        });
    }

    /**
     * An audit row is permanent and may name a constant a later ReportStatus no longer has. That row
     * still has to render: this is the branch that would otherwise be taken on trust, since nothing
     * in normal operation reaches it.
     */
    @Test
    void aStatusThisVersionNoLongerKnowsStillRenders() {
        assertThat(detailOf(AuditEventType.REPORT_SUBMITTED, "reportStatus=WITHDRAWN_IN_2031"))
                .isEqualTo("Status: Withdrawn In 2031");
    }

    /** "none" is the builder's rendering of a null, not a state - an absence must not render as one. */
    @Test
    void anAbsentStatusRendersNoDetailLineRatherThanTheWordNone() {
        assertThat(detailOf(AuditEventType.REPORT_APPROVED, "reportStatus=none")).isNull();
    }

    private String detailOf(AuditEventType type, String metadata) {
        return rowsFor(event(type, metadata)).get(0).detail();
    }

    private List<AuditHistoryEntry> rowsFor(AuditEvent auditEvent) {
        when(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("InterviewRequest", REQUEST_ID))
                .thenReturn(List.of());
        when(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("InterviewReport", REPORT_ID))
                .thenReturn(List.of(auditEvent));
        return service.historyFor(request, DraftSaveRuns.COLLAPSED).get(0).entries();
    }

    private static AuditEvent event(AuditEventType type, String metadata) {
        AuditEvent auditEvent = mock(AuditEvent.class);
        when(auditEvent.getId()).thenReturn(1L);
        when(auditEvent.getEventType()).thenReturn(type);
        when(auditEvent.getOccurredAt()).thenReturn(LocalDateTime.of(2026, 3, 4, 9, 14));
        when(auditEvent.getActorRolesAtTime()).thenReturn("REVIEWER");
        when(auditEvent.getMetadata()).thenReturn(metadata);
        return auditEvent;
    }
}
