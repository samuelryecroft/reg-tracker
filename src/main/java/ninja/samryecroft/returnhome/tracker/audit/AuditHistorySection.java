package ninja.samryecroft.returnhome.tracker.audit;

import java.util.List;

/**
 * A labelled group of {@link AuditHistoryEntry} rows in the V1 timeline (audit-mockups.html §01's
 * {@code .daysep} + {@code .tl}). {@code label} is either a day heading ("Today", "Yesterday", or a
 * date, on the request/report and user pages) or a request heading ("Request #1182 — Aug 2026", on
 * the child page's cross-request case history) - the grouping strategy differs by placement, but
 * both render through the same {@code fragments/audit-history :: timeline(sections)} component.
 */
public record AuditHistorySection(String label, List<AuditHistoryEntry> entries) {
}
