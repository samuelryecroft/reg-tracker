package ninja.samryecroft.returnhome.tracker.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * T145(B): the state machine, asserted as a machine rather than one edge at a time.
 *
 * <p>The value of a table over scattered conditionals is that the whole thing can be checked, so
 * these tests sweep every {@code (from, to)} pair rather than the handful the fix happened to be
 * about - the same habit that caught an accent-ramp claim of mine that held for the seven hues I
 * picked and failed for 340 of 360.
 */
class InterviewStatusTransitionsTest {

    @Test
    void aSubmittedRequestCannotBeWalkedBackwards() {
        // The T145 defect, stated directly: re-allocation used to reset a REPORT_SUBMITTED request
        // to SCHEDULED or ALLOCATED, dropping a submitted safeguarding report out of the queue.
        assertThat(InterviewStatusTransitions.isLegal(
                InterviewStatus.REPORT_SUBMITTED, InterviewStatus.SCHEDULED)).isFalse();
        assertThat(InterviewStatusTransitions.isLegal(
                InterviewStatus.REPORT_SUBMITTED, InterviewStatus.ALLOCATED)).isFalse();
    }

    @Test
    void anApprovedRequestIsTerminal() {
        assertThat(InterviewStatusTransitions.legalTargetsFrom(InterviewStatus.REPORT_APPROVED))
                .isEmpty();
    }

    /**
     * The sweep that matters: NOTHING may leave a post-verdict state. Written as "no target at all"
     * rather than as a list of forbidden ones, so an edge added later has to be a deliberate change
     * to this assertion instead of quietly slipping through a gap in an enumeration.
     */
    @ParameterizedTest
    @EnumSource(InterviewStatus.class)
    void noTransitionLeavesApprovedOrCancelled(InterviewStatus to) {
        assertThat(InterviewStatusTransitions.isLegal(InterviewStatus.REPORT_APPROVED, to)).isFalse();
        assertThat(InterviewStatusTransitions.isLegal(InterviewStatus.CANCELLED, to)).isFalse();
    }

    /**
     * CANCELLED has no in-edges, and that is the table telling the truth rather than an oversight:
     * no production path has ever set it.
     *
     * <p><b>T146 is now answered - the state STAYS</b> (see {@link InterviewStatus#CANCELLED} for
     * the evidence), so this assertion has changed job. It was holding a question open; it is now
     * <b>the tripwire on a settled decision</b>. Adding an in-edge is how cancellation gets built,
     * and it must cost a reviewed change rather than a line in a map - so this test going red is the
     * intended cost, not an obstacle to route around.
     *
     * <p><b>If you are here because you added an edge and this failed:</b> that is the design
     * question T146 deliberately did not answer - who may cancel, from which states, whether it
     * reverses, and what it does to a young person's 72-hour clock. Answer it somewhere a person
     * reviews before you change this line.
     *
     * <p>Demo fixtures and tests still construct CANCELLED rows, because {@code markStatus} treats
     * the first status on a never-persisted row as a construction.
     */
    @ParameterizedTest
    @EnumSource(InterviewStatus.class)
    void noTransitionReachesCancelled(InterviewStatus from) {
        assertThat(InterviewStatusTransitions.isLegal(from, InterviewStatus.CANCELLED)).isFalse();
    }

    /**
     * Re-allocating a request that has not been submitted yet is ordinary business, and the table
     * exists to forbid one thing rather than to tighten everything it touches. If closing the hole
     * had also broken reassignment, that would be a worse outcome than the hole.
     */
    @ParameterizedTest
    @EnumSource(value = InterviewStatus.class,
            names = {"REQUESTED", "ALLOCATED", "SCHEDULED", "REPORT_REJECTED"})
    void reallocationStaysLegalWhileNoVerdictStands(InterviewStatus from) {
        assertThat(InterviewStatusTransitions.isLegal(from, InterviewStatus.SCHEDULED)).isTrue();
    }

    @Test
    void everyStateIsInTheTableSoANewOneCannotBeSilentlyUnreachable() {
        // A status added to the enum without a row here would default to "nothing is legal from it",
        // which fails closed but also fails silently. This is the assertion that makes someone
        // adding a state decide what it means.
        for (InterviewStatus from : InterviewStatus.values()) {
            assertThat(InterviewStatusTransitions.legalTargetsFrom(from))
                    .as("no row in the transition table for %s", from)
                    .isNotNull();
        }
        assertThat(InterviewStatusTransitions.legalTargetsFrom(InterviewStatus.REPORT_SUBMITTED))
                .containsExactlyInAnyOrder(InterviewStatus.REPORT_APPROVED, InterviewStatus.REPORT_REJECTED);
    }

    @Test
    void requireNamesBothEndsOfTheRefusedTransition() {
        assertThatThrownBy(() -> InterviewStatusTransitions.require(
                InterviewStatus.REPORT_APPROVED, InterviewStatus.SCHEDULED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REPORT_APPROVED")
                .hasMessageContaining("SCHEDULED");
    }
}
