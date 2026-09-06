package ninja.samryecroft.returnhome.tracker.audit;

/**
 * Whether a caller of {@link AuditHistoryService} wants runs of ordinary draft saves collapsed
 * (T177).
 *
 * <p>This exists because {@code caseHistoryFor} has two callers that are not the same kind of
 * thing. The child page is a screen, and T174's per-step autosave turns its case history into a
 * wall of "Draft saved". The case-file export is a <em>disclosure</em> - it goes to a DPO, a local
 * authority or a court - and on that surface "tidy" is not a value anybody asked for.
 *
 * <p>The distinction is a named constant rather than a boolean flag so both call sites say which
 * they are, and so that adding a third caller is a decision rather than a default.
 */
public enum DraftSaveRuns {

    /** A screen: consecutive DRAFT &rarr; DRAFT saves by one actor become one row with a count and a span. */
    COLLAPSED,

    /**
     * A disclosure: every audit row gets its own line, with its own timestamp.
     *
     * <p>The collapsed row does state its count and the span between its ends, so it is not a
     * deletion. But "when exactly was the third revision saved" is a question a court may ask about
     * a specific row, and a span does not answer it. The export pays a longer document for that.
     */
    KEPT_IN_FULL
}
