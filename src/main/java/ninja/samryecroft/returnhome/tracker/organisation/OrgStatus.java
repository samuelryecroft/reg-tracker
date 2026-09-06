package ninja.samryecroft.returnhome.tracker.organisation;

/**
 * Where an organisation is in its life (T168(b)). One model, designed once, so the KEK activation
 * guard, {@code T170}'s archive/soft-delete and {@code T166 §5}'s auto-provision all read the same
 * field rather than each growing its own flag.
 *
 * <p><b>There is deliberately no DELETED.</b> It was proposed and dropped on a falsifiable test
 * Kevin set: name one rule that differs between "archived" and "deleted" today. There isn't one -
 * restore permission is undesigned for both, an organisation may do nothing in either, and a hard
 * delete is refused either way because its children's records are encrypted under its KEK, so
 * destroying it would orphan them. Two enum values that behave identically are two values every
 * future query has to remember to check together, and every {@code == ARCHIVED} becomes a chance to
 * forget {@code || == DELETED}.
 *
 * <p>The distinction people actually want is INTENT, and intent is a property of the EVENT, not of
 * the state: the state answers "what may happen now", the audit trail answers "what did a human
 * mean". So archived-versus-removed lives on the audit event.
 *
 * <p>The one thing that would justify splitting this later is a designed difference in list
 * VISIBILITY - removed organisations hidden from the admin list while archived ones stay. If someone
 * designs that rule, revisit this decision on that evidence, and not on the general feeling that a
 * DELETED state ought to exist.
 */
public enum OrgStatus {

    /**
     * Created, not yet usable. No encrypted record may be created for this organisation, because
     * its per-organisation KEK is not yet confirmed to exist.
     */
    PENDING("Awaiting activation"),

    /**
     * Usable. Reached only by a transition that VERIFIED the KEK exists - never by assertion. A
     * status that says everything is fine when it isn't is worse than no status at all: it is the
     * original incident with a reassurance attached.
     */
    ACTIVE("Active"),

    /**
     * Offboarded. Out of the active surfaces, fully retained, restorable. Never a physical delete -
     * this is a safeguarding system, and the records are retained for audit and retention duties.
     */
    ARCHIVED("Archived");

    /**
     * What a screen calls this state (T265). {@code ReportStatus} and {@code InterviewStatus} each
     * carry one already; this enum had none, so the admin organisation tree rendered the CONSTANT -
     * "PENDING" in shouting caps. That is the same finding as those two, <strong>one step
     * earlier</strong>: nothing was bypassing the authoritative name, because there was no
     * authoritative name to bypass. A display name is only authoritative on the paths that ask for
     * it, and a path can fail to ask because the authority does not exist yet.
     *
     * <p><strong>"Awaiting activation" is settled by the row the chip sits on</strong>, not chosen:
     * a PENDING row already carries an <em>Activate</em> button, so the state now names the action
     * that resolves it and the label a person reads agrees with the control they press.
     *
     * <p><strong>"Archived" deliberately does not say "removed" or "deleted."</strong> The javadoc
     * above is explicit that there is no DELETED, that archived is fully retained and restorable,
     * and that removed-versus-archived is a property of the audit EVENT rather than the state. A
     * chip reading "Removed" would re-introduce as copy the distinction this enum was designed to
     * refuse.
     *
     * <p>Two of the three are the constant in sentence case, and that is not under-delivery: the
     * value of having these is not that the words change, it is that <strong>the screen stops
     * asking the constant.</strong> Two of three coinciding is a fact about today's names.
     */
    private final String displayName;

    OrgStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
