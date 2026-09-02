package ninja.samryecroft.returnhome.tracker.dashboard;

import java.util.List;
import java.util.OptionalInt;

/**
 * The "held within 72 hours" compliance rate, honestly - Oscar's dashboard-build-brief.md D-4/D-5.
 *
 * <p>{@code excludedNoReturnTime} interviews are completed but have no recorded return time, so the
 * 72-hour clock never had a start - they are absent from both {@code within72} and
 * {@code validCompleted}, never silently folded into either side. Below the minimum reportable base
 * the rate is withheld entirely rather than shown as a misleadingly precise (or bare {@code 0%})
 * percentage.
 */
public record RateStat(int within72, int validCompleted, int excludedNoReturnTime) {

    /** Oscar's D-5: below this many interviews with a usable clock, don't publish a rate at all. */
    public static final int MINIMUM_REPORTABLE_BASE = 5;

    public int totalCompleted() {
        return validCompleted + excludedNoReturnTime;
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
            excluded += part.excludedNoReturnTime();
        }
        return new RateStat(within72, validCompleted, excluded);
    }
}
