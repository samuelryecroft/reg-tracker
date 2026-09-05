package ninja.samryecroft.returnhome.tracker.audit;

import java.util.List;

/**
 * One day of the org-wide case-activity feed (screen 2g) - the same day separator the single
 * record's history already uses, over rows that carry a home and a child as well as an event.
 *
 * @param label the day heading, from {@link AuditHistoryService#dayLabel} - the SAME method the
 *     record timeline's sections use, so "Today" cannot come to mean two different things on two
 *     screens showing the same events
 * @param rows that day's events, most recent first
 */
public record AuditFeedDay(String label, List<AuditFeedRow> rows) {
}
