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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T320: <strong>a formatter cannot quietly reinstate the word the product retired.</strong>
 *
 * <p>This is the half of the rename a grep cannot see. {@code AuditHistoryService} ends its switch
 * with {@code default -> titleCase(eventType.name())}, and that default is ruled rather than
 * accidental (T295 §5): it guarantees no unhandled type reaches a reader as a raw SHOUTING constant,
 * and it is deliberately plain, so {@code NAMES_REVEALED} renders "Names Revealed" and reads as
 * something nobody chose.
 *
 * <p><strong>That property is exactly what fails for a retired word.</strong> T170 added
 * {@code CHILD_UPDATED}, {@code CHILD_ARCHIVED} and {@code CHILD_RESTORED} with no case of their
 * own, so this timeline rendered <em>"Child Updated"</em> - and "Child Updated" does not read as
 * undesigned. It reads as ruled copy, on a document a DPO or a court may read, with nothing about it
 * to make a reader doubt it. It is how "Rejected" came back months after the product renamed it to
 * "sent back": not a competing decision, the <em>absence</em> of one.
 *
 * <p><strong>Written over every constant the enum declares, and that is the point.</strong> An
 * assertion naming CHILD_UPDATED would go green the moment those three got cases and stay green
 * while {@code CHILD_DELETED}, added next month by someone who never read T250, walked into the same
 * default and rendered "Child deleted". The vocabulary boundary is a property of the enum, not of
 * three of its members - so the test is the property, and a new constant carrying the retired word
 * is red on arrival.
 *
 * <p><strong>What this does NOT assert, deliberately:</strong> that every constant has an explicit
 * case. The default is ruled and useful; a test demanding ruled copy for all forty-odd constants
 * would be a different decision from the one T250 made, and would fail on constants whose plain
 * rendering is perfectly correct. The line drawn here is narrower and is the one the ruling draws:
 * <em>a safety net is safe for a constant whose plain rendering is merely unchosen; it is not safe
 * for one whose plain rendering is a word the product has retired.</em>
 *
 * <p>Its two siblings, {@link AuditStatusVocabularyTest} and {@link InterviewStatusVocabularyTest},
 * hold the same line for status vocabulary. {@code AuditEventType} had no such guard, which is
 * precisely how three CHILD constants arrived unnoticed.
 */
@ExtendWith(MockitoExtension.class)
class AuditEventVocabularyTest {

    private static final long REQUEST_ID = 1L;
    private static final long REPORT_ID = 9L;

    /**
     * The retired vocabulary, as a reader would meet it on screen. Word-boundary matched so this
     * catches "Child Updated" and "Children flagged" without firing on a headline that legitimately
     * contains, say, "Childcare" - and case-insensitively, because the defect arrives title-cased.
     */
    private static final java.util.regex.Pattern RETIRED =
            java.util.regex.Pattern.compile("\\b(child|children|child's|children's)\\b",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    @Mock
    private AuditEventRepository auditEventRepository;
    @Mock
    private InterviewReportRepository interviewReportRepository;
    @InjectMocks
    private AuditHistoryService service;
    @Mock
    private InterviewRequest request;
    @Mock
    private InterviewReport report;

    @BeforeEach
    void wireTheReportOntoTheRequest() {
        when(request.getId()).thenReturn(REQUEST_ID);
        when(report.getId()).thenReturn(REPORT_ID);
        when(interviewReportRepository.findByInterviewRequestId(REQUEST_ID)).thenReturn(Optional.of(report));
    }

    /**
     * The property, over the whole enum: nothing this timeline renders says the retired word, whether
     * it got there through a ruled case or through the generic formatter.
     */
    @ParameterizedTest
    @EnumSource(value = AuditEventType.class, names = "AUDIT_VIEW_OPENED", mode = EnumSource.Mode.EXCLUDE)
    void noAuditEventRendersTheWordTheProductRetired(AuditEventType type) {
        List<AuditHistoryEntry> rows = rowsFor(event(type));

        // EMPTY IS A FAILURE, NOT A SKIP. A type that renders nothing trivially satisfies "does not
        // say child", so tolerating empty is precisely how this guard would go quietly vacuous -
        // widen the upstream filter, or break rowsFor, and every case would "pass" while asserting
        // nothing at all. That is the defect this test exists to catch, one level up. The single
        // type that legitimately renders nothing is excluded at the source above and asserted on its
        // own below, so here empty can only mean the measurement stopped working.
        assertThat(rows)
                .as("%s rendered no row at all. This guard only means something while events reach "
                        + "it; an empty result is the measurement failing, not the property holding",
                        type)
                .isNotEmpty();

        String headline = rows.get(0).headline();
        assertThat(RETIRED.matcher(headline).find())
                .as("%s renders \"%s\" - the product says young person, and a formatter must not "
                        + "quietly put the old word back on a record a court may read", type, headline)
                .isFalse();
    }

    /** The three that were live: ruled copy, not titleCase's guess at it. */
    @Test
    void theThreeEventsT170AddedSayWhatTheProductCallsThem() {
        assertThat(headlineOf(AuditEventType.CHILD_UPDATED)).isEqualTo("Young person's details updated");
        assertThat(headlineOf(AuditEventType.CHILD_ARCHIVED)).isEqualTo("Young person archived");
        assertThat(headlineOf(AuditEventType.CHILD_RESTORED)).isEqualTo("Young person restored");
    }

    /**
     * The one type excluded from the parameterized run above, asserted rather than assumed. It is
     * dropped by {@code EXCLUDED_FROM_RECORD_HISTORY} before anything but its type is read - so it
     * cannot say the retired word, and including it would have forced the guard to treat "rendered
     * nothing" as a pass. If this ever starts rendering, this test goes red and it rejoins the run.
     */
    @Test
    void theOneEventExcludedFromARecordsHistoryRendersNothingHere() {
        // Stubbed down to the type alone, rather than reusing event(): the filter drops this before
        // it reads anything else, and stubbing what is never read is the thing strict stubs exist to
        // report. Keeping the helper untouched leaves that report working for the 31 types above.
        AuditEvent viewed = mock(AuditEvent.class);
        when(viewed.getEventType()).thenReturn(AuditEventType.AUDIT_VIEW_OPENED);

        assertThat(rowsFor(viewed)).isEmpty();
    }

    /**
     * The default is still doing its job. This is the positive control: without it, deleting the
     * default entirely would leave the guard above green while every unhandled event rendered
     * nothing at all.
     */
    @Test
    void anEventWithNoRuledCopyStillRendersSomethingAReaderCanRead() {
        assertThat(headlineOf(AuditEventType.NAMES_REVEALED)).isEqualTo("Names Revealed");
    }

    private String headlineOf(AuditEventType type) {
        return rowsFor(event(type)).get(0).headline();
    }

    private List<AuditHistoryEntry> rowsFor(AuditEvent auditEvent) {
        when(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("InterviewRequest", REQUEST_ID))
                .thenReturn(List.of());
        when(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("InterviewReport", REPORT_ID))
                .thenReturn(List.of(auditEvent));
        // An event filtered out upstream produces no GROUP at all, not an empty group - so this
        // returns empty rather than indexing into nothing, and the caller decides whether empty is
        // allowed for that type.
        List<AuditHistorySection> groups = service.historyFor(request, DraftSaveRuns.COLLAPSED);
        return groups.isEmpty() ? List.of() : groups.get(0).entries();
    }

    private static AuditEvent event(AuditEventType type) {
        AuditEvent auditEvent = mock(AuditEvent.class);
        when(auditEvent.getId()).thenReturn(1L);
        when(auditEvent.getEventType()).thenReturn(type);
        when(auditEvent.getOccurredAt()).thenReturn(LocalDateTime.of(2026, 3, 4, 9, 14));
        when(auditEvent.getActorRolesAtTime()).thenReturn("REVIEWER");
        when(auditEvent.getMetadata()).thenReturn("fieldsChanged=date of birth");
        return auditEvent;
    }
}
