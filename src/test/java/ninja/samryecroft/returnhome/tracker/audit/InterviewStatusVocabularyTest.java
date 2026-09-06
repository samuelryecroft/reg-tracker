package ninja.samryecroft.returnhome.tracker.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A status transition in the audit timeline says both of its ends in the words the system uses for
 * those states (T262).
 *
 * <p>{@code formatted()} titleCased both operands, so re-allocating a sent-back interview rendered
 * <em>"Report Rejected → Allocated"</em> - the exact pre-rename string, rebuilt by the formatter
 * from the constant, in front of the visitor whose work it describes.
 *
 * <p><strong>The tests are parameterised over the enum, not over the three constants that differ
 * today.</strong> Three of seven differ, and that is a fact about today's names rather than a set to
 * assert: converting three constants is the one-string patch wearing a bigger number. Reading the
 * expectation from {@code getDisplayName()} at run time is what makes these guards outlive the
 * names - see the note on {@link #everyStatusIsSaidInTheSystemsWordsOnTheBeforeSide} for exactly how
 * far that protection reaches, and where it does not.
 *
 * <p>Two requirements Creed put on this guard, both taken deliberately rather than by default:
 * the assertions are <strong>case-sensitive</strong> (REPORT_APPROVED differs from its formatted
 * form by one character's case and nothing else), and the guard <strong>covers constants no current
 * transition can produce</strong> - CANCELLED has no in-edges at all. His argument for the second is
 * his own error: he trimmed a claim to what he believed reachable, and the trim is exactly where it
 * was wrong. An {@code @EnumSource} guard does not depend on anyone's reachability analysis.
 */
@ExtendWith(MockitoExtension.class)
class InterviewStatusVocabularyTest {

    private static final long REQUEST_ID = 1L;

    @Mock
    private AuditEventRepository auditEventRepository;
    @Mock
    private InterviewReportRepository interviewReportRepository;
    @Mock
    private InterviewRequest request;
    @Mock
    private AuditEvent allocation;

    private AuditHistoryService service;

    // --- the mechanism ---

    /**
     * Every state, on the side the transition table controls.
     *
     * <p><strong>What this catches and what it does not, said plainly rather than implied.</strong>
     * It is red for reverting to the formatter, and red for converting one operand and not the
     * other. A per-constant list that happens to name all seven correctly is GREEN today - no
     * assertion about output can distinguish it, because its output is the same. What makes it red
     * is the thing this test does <em>not</em> hard-code: the expectation is read from
     * {@code getDisplayName()} at run time, so the moment somebody renames a display name and the
     * hand-written list does not follow, this fails. The guard does not catch the patch on the day
     * it is written; it catches it on the day it becomes wrong, which is the day Creed's objection
     * describes.
     */
    @ParameterizedTest
    @EnumSource(InterviewStatus.class)
    void everyStatusIsSaidInTheSystemsWordsOnTheBeforeSide(InterviewStatus before) {
        assertThat(detailOf(before, InterviewStatus.ALLOCATED))
                .isEqualTo(before.getDisplayName() + " → " + InterviewStatus.ALLOCATED.getDisplayName());
    }

    /** And on the other side, because {@code formatted()} renders both and a bound naming one is not a bound. */
    @ParameterizedTest
    @EnumSource(InterviewStatus.class)
    void everyStatusIsSaidInTheSystemsWordsOnTheAfterSide(InterviewStatus after) {
        assertThat(detailOf(InterviewStatus.REQUESTED, after))
                .isEqualTo(InterviewStatus.REQUESTED.getDisplayName() + " → " + after.getDisplayName());
    }

    // --- the two constants that are easy to get wrong ---

    /**
     * The live row. {@code InterviewStatusTransitions} makes REPORT_REJECTED → ALLOCATED legal by
     * design ("the original visitor may well have moved on"), so this is what a visitor sees on the
     * record of their own work after a reviewer sends it back and it is re-allocated.
     */
    @Test
    void aReAllocatedSentBackInterviewNoLongerSaysTheWordTheRenameRemoved() {
        assertThat(detailOf(InterviewStatus.REPORT_REJECTED, InterviewStatus.ALLOCATED))
                .isEqualTo("Sent back → Allocated")
                .doesNotContain("Report Rejected");
    }

    /**
     * REPORT_APPROVED differs from its formatted form ONLY in capitalisation - "Report approved" vs
     * "Report Approved". A reviewer comparing screenshots will not see it and a case-insensitive
     * assertion goes green on it, so the difference is asserted explicitly here rather than left to
     * {@code isEqualTo} being case-sensitive as a matter of luck.
     */
    @Test
    void theOneThatDiffersByASingleCharactersCaseIsAssertedCaseSensitively() {
        assertThat(detailOf(InterviewStatus.REPORT_SUBMITTED, InterviewStatus.REPORT_APPROVED))
                .isEqualTo("Pending review → Report approved")
                .doesNotContain("Report Approved");
    }

    /**
     * CANCELLED has no in-edges in {@code InterviewStatusTransitions} at all, so no transition can
     * currently produce it on either side. It is covered anyway - it is inside the {@code @EnumSource}
     * above, and this names why that is deliberate rather than incidental.
     */
    @Test
    void aStatusNoTransitionCanProduceIsCoveredAnyway() {
        assertThat(detailOf(InterviewStatus.CANCELLED, InterviewStatus.CANCELLED))
                .isEqualTo("Cancelled → Cancelled");
    }

    /**
     * An audit row is permanent and may name a constant a later InterviewStatus no longer has. That
     * row still has to render: this is the branch nothing in normal operation reaches, so nothing
     * else would exercise it.
     */
    @Test
    void aStatusThisVersionNoLongerKnowsStillRenders() {
        assertThat(detail("statusBefore=WITHDRAWN_IN_2031; statusAfter=ALLOCATED"))
                .isEqualTo("Withdrawn In 2031 → Allocated");
    }

    // --- fixtures ---

    private String detailOf(InterviewStatus before, InterviewStatus after) {
        return detail("statusBefore=" + before.name() + "; statusAfter=" + after.name());
    }

    /** Through {@code historyFor}, the only path by which an INTERVIEW_REQUEST_ALLOCATED row reaches a screen. */
    private String detail(String metadata) {
        service = new AuditHistoryService(auditEventRepository, interviewReportRepository);
        when(request.getId()).thenReturn(REQUEST_ID);
        when(interviewReportRepository.findByInterviewRequestId(REQUEST_ID)).thenReturn(Optional.empty());
        when(allocation.getEventType()).thenReturn(AuditEventType.INTERVIEW_REQUEST_ALLOCATED);
        when(allocation.getOccurredAt()).thenReturn(LocalDateTime.of(2026, 3, 4, 9, 14));
        when(allocation.getMetadata()).thenReturn(metadata);
        when(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("InterviewRequest", REQUEST_ID))
                .thenReturn(List.of(allocation));
        return service.historyFor(request, DraftSaveRuns.COLLAPSED).get(0).entries().get(0).detail();
    }
}
