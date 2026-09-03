package ninja.samryecroft.returnhome.tracker.dashboard;

import java.util.List;
import java.util.OptionalInt;

/**
 * The "held within 72 hours" compliance rate, honestly - Oscar's dashboard-build-brief.md D-4/D-5.
 *
 * <p>{@code excludedNotMeasurable} interviews are completed but have no recorded time the interview
 * was held, so the 72-hour window has no end and the interval cannot be computed. It is <em>not</em>
 * a missing return time: {@code returned_at} is NOT NULL as of V15, so the clock always has a start.
 * These are absent from both {@code within72} and {@code validCompleted}, never silently folded into
 * either side - which is the whole point, because the implementation this replaced counted an
 * unanswered compliance question as a breach while leaving it in the denominator, making an
 * incomplete record indistinguishable from a late interview. Below the minimum reportable base the
 * rate is withheld entirely rather than shown as a misleadingly precise (or bare {@code 0%})
 * percentage.
 */
public record RateStat(int within72, int validCompleted, int excludedNotMeasurable) {

    /** Oscar's D-5: below this many measurable interviews, don't publish a rate at all. */
    public static final int MINIMUM_REPORTABLE_BASE = 5;

    public int totalCompleted() {
        return validCompleted + excludedNotMeasurable;
    }

    public boolean tooFewToReport() {
        return validCompleted < MINIMUM_REPORTABLE_BASE;
    }

    /** Empty when there isn't enough data to publish a rate - never a bare 0%, never a fabricated rate over a tiny base. */
    public OptionalInt percent() {
        if (tooFewToReport()) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) Math.round(within72 * 100.0 / validCompleted));
    }

    public static RateStat combine(List<RateStat> parts) {
        int within72 = 0;
        int validCompleted = 0;
        int excluded = 0;
        for (RateStat part : parts) {
            within72 += part.within72();
            validCompleted += part.validCompleted();
            excluded += part.excludedNotMeasurable();
        }
        return new RateStat(within72, validCompleted, excluded);
    }
}
