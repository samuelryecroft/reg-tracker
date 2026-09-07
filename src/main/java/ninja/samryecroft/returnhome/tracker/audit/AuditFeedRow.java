package ninja.samryecroft.returnhome.tracker.audit;

/**
 * One row of the roadmap 2.5 org-wide case-activity feed - an {@link AuditHistoryEntry} plus the
 * context a flat, multi-child feed needs that a single record's timeline doesn't: which home, and a
 * link to the record it belongs to. Still built from the same allow-list projection.
 *
 * <p><strong>A row is about EITHER an interview request OR a child, never both and never neither</strong>
 * (T292). Record access to a child's page is recorded against the child, and before T292 the feed
 * dropped every such event because it could not be resolved to a request. The fix was refused in its
 * easy form - retargeting the event at a request so it would resolve - because that falsifies what
 * happened. So the row carries the target it actually had, and the invariant is enforced in the
 * compact constructor rather than left to callers: two nullable ids with an unwritten "exactly one"
 * rule is precisely the shape that produces a link to {@code /interview-requests/null}.
 */
public record AuditFeedRow(AuditHistoryEntry entry, String homeName, String childLabel,
        Long requestId, Long childId) {

    public AuditFeedRow {
        if ((requestId == null) == (childId == null)) {
            throw new IllegalArgumentException(
                    "An audit feed row is about exactly one of a request or a child, but had requestId="
                            + requestId + " and childId=" + childId);
        }
    }

    /** An event about an interview request or its report. */
    public static AuditFeedRow forRequest(AuditHistoryEntry entry, String homeName, String childLabel,
            Long requestId) {
        return new AuditFeedRow(entry, homeName, childLabel, requestId, null);
    }

    /** An event about the child's own record - today only record access. */
    public static AuditFeedRow forChild(AuditHistoryEntry entry, String homeName, String childLabel,
            Long childId) {
        return new AuditFeedRow(entry, homeName, childLabel, null, childId);
    }
}
