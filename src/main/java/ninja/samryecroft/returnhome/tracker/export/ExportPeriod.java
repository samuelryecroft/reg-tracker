package ninja.samryecroft.returnhome.tracker.export;

import java.time.LocalDate;
import java.time.LocalDateTime;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;

/**
 * The period an export covers. Together with the subject, this is the other half of the rule that
 * every export is bounded - there is no "everything" that is not also "everything for this child".
 *
 * @param from inclusive, or null for "all interviews"
 * @param to   inclusive, or null for open-ended
 */
public record ExportPeriod(LocalDate from, LocalDate to, String label) {

    public static ExportPeriod all() {
        return new ExportPeriod(null, null, "All interviews");
    }

    public static ExportPeriod lastMonths(int months) {
        LocalDate from = LocalDate.now().minusMonths(months);
        return new ExportPeriod(from, null, "Last " + months + " months");
    }

    public static ExportPeriod between(LocalDate from, LocalDate to) {
        return new ExportPeriod(from, to, describe(from, to));
    }

    public boolean covers(InterviewRequest request) {
        LocalDateTime createdAt = request.getCreatedAt();
        if (createdAt == null) {
            return true;
        }
        LocalDate date = createdAt.toLocalDate();
        return (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
    }

    private static String describe(LocalDate from, LocalDate to) {
        if (from == null && to == null) {
            return "All interviews";
        }
        if (from == null) {
            return "Up to " + to;
        }
        return to == null ? "From " + from : from + " to " + to;
    }
}
