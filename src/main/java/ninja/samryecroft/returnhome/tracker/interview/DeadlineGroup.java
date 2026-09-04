package ninja.samryecroft.returnhome.tracker.interview;

import java.util.List;

/**
 * A urgency-tier heading (e.g. "Overdue — statutory 72 hours passed (2)") plus its rows, most
 * urgent group first.
 *
 * <p>T165: {@code state} is here so the template can pick the heading's aria-hidden icon without
 * the glyph being baked into {@code label} - a glyph inside announced text is read out as the
 * character's name. Null for the "No active deadline" group, which has no due state and never had
 * a glyph.
 */
public record DeadlineGroup(DueState state, String label, List<DeadlineRow> rows) {
}
