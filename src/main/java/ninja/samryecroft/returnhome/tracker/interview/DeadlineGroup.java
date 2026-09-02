package ninja.samryecroft.returnhome.tracker.interview;

import java.util.List;

/** A urgency-tier heading (e.g. "▲ Overdue — statutory 72 hours passed (2)") plus its rows, most urgent group first. */
public record DeadlineGroup(String label, List<DeadlineRow> rows) {
}
