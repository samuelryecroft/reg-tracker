package ninja.samryecroft.returnhome.tracker.report.question;

/**
 * Whose account a question records.
 *
 * <p><b>This exists because the condition cuts across the sections</b> (Creed, T244). When an
 * interview is not accepted, the questions that stop being asked are the ones put <em>to the
 * child</em> - and they sit in the same section as questions about the visit that are still live.
 * A section is a layout grouping; selecting by it would be selecting by the wrong property, and the
 * field it would silently take with it is the one that matters most.
 *
 * <p><b>The trap this is here to close.</b> The nine child's-answer questions are contiguous in the
 * literal, so "everything after the declined-reason question" looks like the rule and is not:
 * {@code additionalInfoFromParentCarer} follows them, and <b>on a declined interview the parent or
 * carer's account may be the only account of the episode anyone obtains.</b> Removing it would
 * quietly delete the field that matters most in exactly the case being handled. Naming the
 * respondent makes that structural rather than a remembered exception.
 */
public enum Respondent {

    /** The visitor's own record of the visit: when it was held, what they observed, what they advise. */
    VISITOR,

    /**
     * Asked of the young person, and therefore only asked when the interview happened at all.
     * A blank on a declined interview means nobody was in a position to ask, not that an answer
     * went unrecorded.
     */
    CHILD,

    /**
     * The parent or carer's account. <b>Live whether or not the interview took place</b> - it is
     * obtained separately, and on a declined interview it may be the only account there is.
     */
    PARENT_OR_CARER
}
