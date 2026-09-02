package ninja.samryecroft.returnhome.tracker.audit;

/**
 * One row of the roadmap 2.5 org-wide case-activity feed - an {@link AuditHistoryEntry} plus the
 * context a flat, multi-child feed needs that a single record's timeline doesn't: which home, and
 * a link to the interview it belongs to. Still built from the same allow-list projection.
 */
public record AuditFeedRow(AuditHistoryEntry entry, String homeName, String childLabel, Long requestId) {
}
