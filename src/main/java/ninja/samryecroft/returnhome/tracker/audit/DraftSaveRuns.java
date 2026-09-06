package ninja.samryecroft.returnhome.tracker.audit;

/**
 * Whether a caller of {@link AuditHistoryService} wants runs of ordinary draft saves collapsed
 * (T177).
 *
 * <p>This exists because the timeline builders have callers that are not the same kind of thing.
 * A screen may summarise: T174's per-step autosave turns a case history into a wall of "Draft
 * saved". A <em>disclosure</em> may not - the case-file export goes to a DPO, a local authority or
 * a court, and on that surface "tidy" is not a value anybody asked for.
 *
 * <p><strong>Every timeline builder takes this, and none of them defaults.</strong> That is the
 * point, and it was not the first shape: {@code caseHistoryFor} shipped with a one-argument
 * overload defaulting to COLLAPSED and a javadoc asking the next caller to think about it. A
 * comment is a request; a parameter is a question that has to be answered to compile. The failure
 * this guards against is a new caller who never asked whether they were writing a screen or a
 * disclosure - and a caller who never asked is exactly the caller a default answers on behalf of.
 *
 * <p>Defaulting to KEPT_IN_FULL instead would have been better than defaulting to COLLAPSED, since
 * a screen that looks untidy is a smaller failure than an export that looks complete and is not.
 * It was rejected because it still answers for someone who did not ask; it only changes which way
 * the silence falls. There is no safe default because the question is not about this class, it is
 * about the caller.
 *
 * <p>{@code historyForUser} takes it too, even though no REPORT_DRAFT_SAVED can reach a "User"
 * target and the answer there cannot matter today. An exception would need a comment claiming that,
 * and nothing checks a comment - a claim in a comment that everyone relies on is how the last three
 * defects in this area started. One rule with no exceptions is cheaper to trust than one rule and a
 * justified exception.
 *
 * <p>{@code caseActivityFeed} is deliberately NOT in that set: it collapses nothing at all, so it
 * has no question to ask. See its javadoc - it has the same screen-plus-CSV shape and stays
 * complete by construction.
 */
public enum DraftSaveRuns {

    /** A screen: consecutive DRAFT &rarr; DRAFT saves by one actor become one row with a count and a span. */
    COLLAPSED,

    /**
     * A disclosure: every audit row gets its own line, with its own timestamp.
     *
     * <p>The collapsed row does state its count and the span between its ends, so it is not a
     * deletion. But "how many times was this revised, and when" is a question a DPO or a court may
     * legitimately ask (Kevin, T177), and it is asked about a specific row - a span does not answer
     * it. The export pays a longer document for that.
     */
    KEPT_IN_FULL
}
