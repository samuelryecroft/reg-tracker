package ninja.samryecroft.returnhome.tracker.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import org.junit.jupiter.api.Test;

/**
 * T187 (spec §7a): the 72-hour verdict is stated so a reader can check it, not just take it.
 *
 * <p>The sentence this replaces was composed from {@code getInterviewDate()} - {@code heldAt}
 * truncated to a date - beside a verdict derived from the full {@code heldAt}, so the document
 * truncated the very value its claim rested on. Two reports could show the same date and opposite
 * verdicts with nothing on the page explaining it.
 */
class SeventyTwoHourReadingTest {

    private static final LocalDateTime RETURNED = LocalDateTime.of(2026, 9, 2, 14, 20);

    private InterviewReport report(LocalDateTime returnedAt, LocalDateTime heldAt, String reason) {
        InterviewRequest request = new InterviewRequest();
        request.setReturnedAt(returnedAt);
        InterviewReport report = new InterviewReport();
        report.setInterviewRequest(request);
        report.setHeldAt(heldAt);
        report.setIfNotWhyLate(reason);
        return report;
    }

    @Test
    void bothEndsOfTheClockTheElapsedTimeAndTheVerdictAreAllStated() {
        SeventyTwoHourReading reading = SeventyTwoHourReading.of(
                report(RETURNED, LocalDateTime.of(2026, 9, 5, 11, 5), null));

        assertThat(reading.returnedLine()).isEqualTo("02 Sept 2026 14:20");
        assertThat(reading.heldLine()).isEqualTo("05 Sept 2026 11:05");
        assertThat(reading.elapsedLine()).isEqualTo("68 hours 45 minutes");
        assertThat(reading.verdictLine()).isEqualTo("Within 72 hours of return");
        assertThat(reading.reasonLine()).isEqualTo("Not applicable");
    }

    /**
     * <b>The test Creed most wanted, and the reason the elapsed figure is never rounded.</b>
     *
     * <p>Rounded to whole hours, 71h50m and 72h10m both print "72 hours" - the same displayed number
     * beside opposite verdicts, which is the defect this reading exists to fix, reintroduced one
     * layer up. So the assertion is that the two displayed strings <em>differ</em>, not merely that
     * each is individually right: a formatter that rounded would pass a per-case assertion and fail
     * this one.
     *
     * <p><b>Display precision must never be able to contradict the verdict beside it.</b>
     */
    @Test
    void twoCasesEitherSideOfTheBoundaryNeverShowTheSameElapsedFigure() {
        SeventyTwoHourReading just = SeventyTwoHourReading.of(
                report(RETURNED, RETURNED.plusHours(71).plusMinutes(50), null));
        SeventyTwoHourReading missed = SeventyTwoHourReading.of(
                report(RETURNED, RETURNED.plusHours(72).plusMinutes(10), "Child was in hospital"));

        assertThat(just.elapsedLine()).isNotEqualTo(missed.elapsedLine());
        assertThat(just.elapsedLine()).isEqualTo("71 hours 50 minutes");
        assertThat(missed.elapsedLine()).isEqualTo("72 hours 10 minutes");
        assertThat(just.verdictLine()).isEqualTo("Within 72 hours of return");
        assertThat(missed.verdictLine()).isEqualTo("NOT within 72 hours of return");
    }

    /** The boundary itself counts as met, and the elapsed figure says exactly why. */
    @Test
    void theBoundaryItselfIsWithinAndReadsAsExactlySeventyTwoHours() {
        SeventyTwoHourReading reading = SeventyTwoHourReading.of(
                report(RETURNED, RETURNED.plusHours(72), null));

        assertThat(reading.elapsedLine()).isEqualTo("72 hours 0 minutes");
        assertThat(reading.verdictLine()).isEqualTo("Within 72 hours of return");
    }

    /**
     * Units never change, however large the gap. "16 days" reads better than "400 hours 12 minutes"
     * and is worse here: a threshold expressed in hours must be comparable without the reader
     * converting anything first. Comparability beats elegance.
     */
    @Test
    void aVeryLateInterviewIsStillExpressedInHoursSoItComparesToTheThreshold() {
        SeventyTwoHourReading reading = SeventyTwoHourReading.of(
                report(RETURNED, RETURNED.plusHours(400).plusMinutes(12), null));

        assertThat(reading.elapsedLine()).isEqualTo("400 hours 12 minutes");
        assertThat(reading.elapsedLine()).doesNotContain("day");
    }

    /**
     * A marginal case never reaches a reader without its explanation, so the reason is a row in this
     * same block - and its absence is stated rather than left blank, because an empty row reads as
     * "nothing to say" where "No reason recorded" reads as the omission it is.
     */
    @Test
    void aMissedWindowCarriesItsReasonOrSaysThereIsNone() {
        assertThat(SeventyTwoHourReading.of(
                report(RETURNED, RETURNED.plusHours(80), "Child was in hospital")).reasonLine())
                .isEqualTo("Child was in hospital");
        assertThat(SeventyTwoHourReading.of(
                report(RETURNED, RETURNED.plusHours(80), "  ")).reasonLine())
                .isEqualTo("No reason recorded");
        assertThat(SeventyTwoHourReading.of(
                report(RETURNED, RETURNED.plusHours(80), null)).reasonLine())
                .isEqualTo("No reason recorded");
    }

    /**
     * An unrecorded interview time is not measurable, which is an exclusion from the rate rather
     * than a breach - and the row still prints the return time and names what is missing. A reader
     * who can see one end of the clock and the words "not recorded" beside the other knows which
     * half is absent; a collapsed "not recorded" tells them only that something is.
     */
    @Test
    void anUnrecordedInterviewTimeStillPrintsTheReturnAndNamesWhatIsMissing() {
        SeventyTwoHourReading reading = SeventyTwoHourReading.of(report(RETURNED, null, null));

        assertThat(reading.returnedLine()).isEqualTo("02 Sept 2026 14:20");
        assertThat(reading.heldLine()).isEqualTo("Interview time not recorded");
        assertThat(reading.elapsedLine()).isEqualTo("Cannot be calculated without both times");
        assertThat(reading.verdictLine()).contains("Not measurable").contains("not counted as a breach");
    }

    /**
     * The statutory form's own question list takes its answers from this same reading, so the head
     * block and the question list are one source stated twice and cannot disagree.
     *
     * <p>{@code verdict()} is derived from {@code verdictLine()} rather than re-deciding from the
     * report - a second ladder over the same state is exactly how a document ends up contradicting
     * itself, which is what T187 exists to remove.
     */
    @Test
    void theOneWordAnswerAndTheSentenceComeFromTheSameDecision() {
        assertThat(SeventyTwoHourReading.of(report(RETURNED, RETURNED.plusHours(10), null)).verdictAnswer())
                .isEqualTo("Yes");
        assertThat(SeventyTwoHourReading.of(report(RETURNED, RETURNED.plusHours(80), null)).verdictAnswer())
                .isEqualTo("No");
        assertThat(SeventyTwoHourReading.of(report(RETURNED, null, null)).verdictAnswer())
                .isEqualTo("Not measurable");
    }

    /**
     * <b>The sentence is not load-bearing state.</b> Both renderings come from the {@code Verdict}
     * held in the record, so rewording one cannot change the other.
     *
     * <p>The first version recovered the short answer by reading {@code verdictLine()}'s text, which
     * avoided a second ladder by introducing a second <em>representation</em> - and its fallback was
     * {@code "Yes"}, so any wording drift defaulted to <b>asserting compliance</b>. Creed's mutation
     * proved it: changing "NOT within" to "Not within" silently turned a missed window into a "Yes"
     * in a statutory document. Run against this version, that same mutation fails only a wording
     * assertion and leaves every answer untouched.
     *
     * <p>Exhaustiveness is the compiler's: both switches cover the enum with no {@code default}, so
     * a fourth state cannot be added without both renderings being confronted.
     */
    @Test
    void everyVerdictStateRendersBothWaysWithoutEitherReadingTheOther() {
        assertThat(SeventyTwoHourReading.of(report(RETURNED, RETURNED.plusHours(10), null)))
                .extracting(SeventyTwoHourReading::verdict, SeventyTwoHourReading::verdictAnswer)
                .containsExactly(SeventyTwoHourReading.Verdict.MET, "Yes");
        assertThat(SeventyTwoHourReading.of(report(RETURNED, RETURNED.plusHours(80), null)))
                .extracting(SeventyTwoHourReading::verdict, SeventyTwoHourReading::verdictAnswer)
                .containsExactly(SeventyTwoHourReading.Verdict.MISSED, "No");
        assertThat(SeventyTwoHourReading.of(report(RETURNED, RETURNED.minusHours(3), null)))
                .extracting(SeventyTwoHourReading::verdict, SeventyTwoHourReading::verdictAnswer)
                .containsExactly(SeventyTwoHourReading.Verdict.NOT_MEASURABLE, "Not measurable");
    }

    /**
     * <b>"Not recorded" would be false here, and that is why the derived value needs its own words.</b>
     * The interview time IS recorded for an impossible sequence - it is inconsistent, not absent -
     * so the vocabulary the other answers use (stored questions a person did or did not fill in)
     * asserts an absence that is not there.
     */
    @Test
    void anImpossibleSequenceIsNotMeasurableRatherThanNotRecorded() {
        SeventyTwoHourReading reading =
                SeventyTwoHourReading.of(report(RETURNED, RETURNED.minusHours(3), null));

        assertThat(reading.verdictAnswer()).isEqualTo("Not measurable");
        assertThat(reading.verdictAnswer()).isNotEqualTo("Not recorded");
    }

    /**
     * These are {@code LocalDateTime}. Printing an offset or "UTC" would assert a precision the
     * stored data does not carry, which is its own false claim - so no row may acquire one.
     */
    @Test
    void noRowAssertsATimeZoneTheDataDoesNotCarry() {
        SeventyTwoHourReading reading = SeventyTwoHourReading.of(
                report(RETURNED, RETURNED.plusHours(10), null));

        assertThat(String.join(" ", reading.returnedLine(), reading.heldLine(), reading.elapsedLine(),
                        reading.verdictLine(), reading.reasonLine()))
                .doesNotContain("UTC").doesNotContain("GMT").doesNotContain("+00").doesNotContain("Z ");
    }

    /**
     * An interview recorded before the return is a data-entry error, and it is said in words. A
     * signed figure - "-3 hours 0 minutes" - sitting beside "Within 72 hours of return" would be the
     * display contradicting the verdict, which is the one thing this reading exists to prevent.
     * Flagged to Creed as a case the spec did not cover.
     */
    /**
     * Creed's ruling (D-187-5), and it was not the display question I raised it as. An interview
     * recorded before the return <em>satisfies</em> {@code !heldAt.isAfter(returnedAt + 72h)}, so it
     * was not merely shown as compliant - it was counted in the NUMERATOR of the published
     * compliance rate. The fix is the predicate; this asserts the reading that follows from it.
     *
     * <p>One verdict with more than one cause, and the cause in the elapsed row - which is what that
     * row is for. <b>Both timestamps stay printed</b>, because they are the evidence a reader needs
     * to correct the record.
     */
    @Test
    void anInterviewRecordedBeforeTheReturnIsNotMeasurableAndKeepsBothTimestamps() {
        SeventyTwoHourReading reading = SeventyTwoHourReading.of(
                report(RETURNED, RETURNED.minusHours(3), null));

        assertThat(reading.verdictLine())
                .isEqualTo("Not measurable - excluded from the 72-hour rate, not counted as a breach");
        assertThat(reading.elapsedLine())
                .isEqualTo("Interview recorded before the return - times need checking");
        assertThat(reading.returnedLine()).isEqualTo("02 Sept 2026 14:20");
        assertThat(reading.heldLine()).isEqualTo("02 Sept 2026 11:20");
    }

    /**
     * The two not-measurable causes are never collapsed: "cannot be calculated without both times"
     * is false when both times are present and merely inconsistent.
     */
    @Test
    void theTwoNotMeasurableCausesAreDistinguished() {
        assertThat(SeventyTwoHourReading.of(report(RETURNED, null, null)).elapsedLine())
                .isEqualTo("Cannot be calculated without both times");
        assertThat(SeventyTwoHourReading.of(report(RETURNED, RETURNED.minusMinutes(1), null)).elapsedLine())
                .isEqualTo("Interview recorded before the return - times need checking");
    }

    /**
     * Creed's fourth point, live in the null branch I had already shipped: the reason row fell
     * through to the missed-window text, so a not-measurable report printed "No reason recorded" -
     * reading as an accusation of a missing explanation for a breach that did not happen.
     *
     * <p><b>A reason is only owed when the window was measured and missed.</b> And a reason that was
     * actually recorded is printed whatever the verdict, because nothing a visitor took the trouble
     * to write should be hidden.
     */
    @Test
    void aReasonIsOnlyOwedWhenTheWindowWasMeasuredAndMissed() {
        assertThat(SeventyTwoHourReading.of(report(RETURNED, null, null)).reasonLine())
                .isEqualTo("Not applicable");
        assertThat(SeventyTwoHourReading.of(report(RETURNED, RETURNED.minusHours(3), null)).reasonLine())
                .isEqualTo("Not applicable");
        assertThat(SeventyTwoHourReading.of(report(RETURNED, null, "Written before anyone noticed"))
                .reasonLine()).isEqualTo("Written before anyone noticed");
    }

    /**
     * T233. <b>A blank reason means two opposite things, and until now both printed as a gap.</b>
     * Either the interview was on time and nothing is owed, or it was late and nobody explained why.
     * The field cannot tell them apart on its own, so it is scored against the derived verdict - and
     * the result reaches the second renderer as STATE rather than as text it has to interpret.
     *
     * <p>Why the state and not just the sentence: the record screen styles this row as a gap, and the
     * only other way to decide that is to test {@link SeventyTwoHourReading#reasonLine()} against the
     * literal "No reason recorded". That would make a display sentence load-bearing - the exact
     * mistake already recorded on {@code Verdict}, where deriving the short answer from
     * {@code verdictLine()}'s text meant a reword would silently change what a statutory document
     * asserted. Here it would silently restyle one.
     *
     * <p>The direction of the old failure is what makes it worth a test rather than a fix: it landed
     * on the <b>honest, on-time visitor</b> exactly as readily as on the confused one. Leaving an
     * inapplicable field empty is the correct thing to do, and doing it correctly was recorded as
     * declining to justify a breach that never happened.
     */
    @Test
    void onlyAMeasuredMissedWindowWithNothingWrittenCountsAsAMissingExplanation() {
        assertThat(SeventyTwoHourReading.of(report(RETURNED, RETURNED.plusHours(80), null))
                .explanationMissing())
                .as("late, nothing written - a real statutory failure with no explanation, and the "
                        + "one case that should be prominent")
                .isTrue();

        assertThat(SeventyTwoHourReading.of(report(RETURNED, RETURNED.plusHours(10), null))
                .explanationMissing())
                .as("on time, so no explanation was ever owed. This is the honest answer, and it "
                        + "must not be styled as a gap in the record")
                .isFalse();

        assertThat(SeventyTwoHourReading.of(report(RETURNED, null, null)).explanationMissing())
                .as("not measurable: the system already refuses to assert a breach it cannot "
                        + "evidence, so it must not assert a refusal to explain one either")
                .isFalse();
        assertThat(SeventyTwoHourReading.of(report(RETURNED, RETURNED.minusHours(3), null))
                .explanationMissing())
                .as("not measurable for the other cause - an interview recorded before the return")
                .isFalse();

        assertThat(SeventyTwoHourReading.of(report(RETURNED, RETURNED.plusHours(80), "In hospital"))
                .explanationMissing())
                .as("late, but the visitor wrote something - nothing is missing")
                .isFalse();
        assertThat(SeventyTwoHourReading.of(report(RETURNED, RETURNED.plusHours(80), "   "))
                .explanationMissing())
                .as("whitespace is not an explanation, and the gap flag must agree with the "
                        + "sentence beside it rather than being decided separately")
                .isTrue();
    }

    /**
     * The rate itself, which is what this ruling was actually about. Equality stays measurable - a
     * held time equal to the return is odd, not impossible - so the exclusion is exactly one case
     * wide rather than swallowing a legitimate reading.
     */
    @Test
    void anImpossibleSequenceIsExcludedFromTheRateAndZeroElapsedIsNot() {
        assertThat(report(RETURNED, RETURNED.minusMinutes(1), null).getWithin72Hours()).isNull();
        assertThat(report(RETURNED, RETURNED, null).getWithin72Hours()).isTrue();
    }
}
