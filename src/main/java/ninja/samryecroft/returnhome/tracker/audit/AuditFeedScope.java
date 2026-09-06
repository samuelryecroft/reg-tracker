package ninja.samryecroft.returnhome.tracker.audit;

/**
 * Which kinds of event an org-wide feed query is asking for (T274).
 *
 * <p>The feed screen and the audit-trail CSV call the SAME {@code caseActivityFeed}, deliberately, so
 * that the CSV cannot silently omit what the screen showed. Access events had to become visible
 * somewhere - <strong>a record surfaced nowhere is not kept, it is hoarded</strong> - and the screen
 * is where an auditor asking <em>who looked at this child's record</em> goes.
 *
 * <p><strong>But adding them to both at once would have changed every disclosure this system makes
 * from here on, and the problem is not volume.</strong> A case-activity export is about a child and
 * the professional actions on their case. An org-wide export over a date range that also contains
 * access rows <strong>acquires a second data subject</strong>: it is additionally a record of what
 * staff looked at, when and how often - an employee-monitoring dataset leaving the building under a
 * purpose and a reference that were about a child. Nobody decided to disclose that, and the exporter
 * would not know they had.
 *
 * <p>So the scope is a parameter rather than a second filter path. Giving the CSV its own filtering
 * would recreate exactly the divergence T177 exists to stop: one source, and
 * <strong>the output declares its scope.</strong> This is T283's R3 one level up - the artefact must
 * say what it is - and the property worth protecting was never "screen and CSV are identical", it is
 * <strong>"the CSV cannot silently omit what the screen showed"</strong>. A declared scope satisfies
 * that; a silent default does not.
 */
public enum AuditFeedScope {

    /**
     * Case activity only: what was DONE to a case. The audit-trail CSV's default, so today's
     * disclosures keep exactly the content they have always had - and say so on their face.
     */
    CASE_ACTIVITY_ONLY,

    /**
     * Case activity plus who ACCESSED the records. What the feed screen shows, because that is where
     * the question "who looked at this child's record" is asked and answered.
     */
    WITH_ACCESS_EVENTS
}
