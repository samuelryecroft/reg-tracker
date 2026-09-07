package ninja.samryecroft.returnhome.tracker.organisation;

import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.user.Role;

/**
 * Whether an organisation has the people it needs to do its job (T267).
 *
 * <p>Oscar's two checkable statements, off his T266 ruling: <b>a SUPPLIER is not operational until
 * it has a coordinator, a visitor and a reviewer; a CARE PROVIDER is not until it has home
 * staff.</b> Until then nothing tells an administrator that the organisation they are setting up
 * cannot yet do anything - the first sign is a coordinator reaching T266's dead end on the day a
 * child's return home interview needs allocating.
 *
 * <h2>This is deliberately not a status, and deliberately blind to {@link OrgStatus}</h2>
 *
 * <p>An organisation has <b>two independent ways of not working</b>, and the card that raised this
 * is explicit that conflating them produces a single misleading status. {@code PENDING} means its
 * per-organisation KEK is not confirmed - <i>cannot decrypt</i>. This record means <i>has nobody to
 * do the work</i>. They are not degrees of the same thing: an ACTIVE organisation can have nobody in
 * it, and a fully staffed organisation can still be PENDING.
 *
 * <p><b>The reason they must not be merged is the remedy, not the taxonomy.</b> A missing key needs
 * an operator to provision it; a missing role needs an administrator to add a person. One label
 * cannot carry two remedies, and a screen that says only "not ready" sends the reader to the wrong
 * one. That is {@code OrganisationAdminController}'s own ABSENT-versus-UNREACHABLE lesson from
 * T168(b) one level up - it already refuses to give those two the same words for the same reason.
 *
 * <p>So this record cannot see the status: it has no {@link OrgStatus} field and no
 * {@link Organisation} reference. A future {@code isOperational()} that quietly ANDed in the status
 * is not a change someone can make here by accident - they would have to go and fetch the thing
 * first, which is the point at which they would have to think about it.
 *
 * <h2>Computed from membership, never from a flag</h2>
 *
 * <p>The card's own done-condition. A flag is a claim someone has to remember to keep true, and
 * this one would go stale in exactly the case that matters: T266's ruling establishes that an
 * organisation LOSES its last visitor in normal running - somebody leaves or is disabled - and no
 * flag-setting path exists on the day a user is disabled. Derived from the membership, the statement
 * cannot drift, because there is nothing to drift from.
 *
 * <p><b>Enabled accounts only.</b> Not a refinement: it is Oscar's second cause verbatim. His
 * T266 ruling names "a visitor leaves or IS DISABLED" as the recurrence that makes the dead-end
 * screen permanent, so an organisation whose only visitor is disabled has exactly the problem this
 * is meant to surface. Counting the row would report an organisation as operational at the precise
 * moment it stopped being.
 */
public record OrganisationReadiness(OrgType type, Set<Role> rolesPresent) {

    /**
     * What each type of organisation needs, and this list is the whole rule.
     *
     * <p><b>ORG_ADMIN is not on either list, and that is Oscar's line rather than an omission.</b>
     * The statements are about the organisation doing its JOB - allocating, visiting, reviewing;
     * raising requests - not about administering itself. An organisation stranded without an
     * administrator is a real problem and it is a different one, already held by T278's
     * last-enabled-admin invariant, which refuses the act rather than reporting on it afterwards.
     * Adding roles here that Oscar did not name would make this screen disagree with that guard
     * about what "needs fixing" means.
     */
    private static final List<Role> SUPPLIER_NEEDS = List.of(Role.COORDINATOR, Role.VISITOR, Role.REVIEWER);

    /**
     * A care provider needs home staff, who are the people who raise a return home interview
     * request in the first place. Nothing downstream can happen without them.
     *
     * <p>Note what this covers for free: HOME_STAFF belong to an organisation THROUGH THEIR HOMES,
     * so a care provider with no homes at all cannot have any, and is reported as not operational
     * without that being a second rule.
     */
    private static final List<Role> CARE_PROVIDER_NEEDS = List.of(Role.HOME_STAFF);

    /** The roles this type of organisation needs before it can do anything, in a stable order. */
    public static List<Role> rolesNeededBy(OrgType type) {
        return type == OrgType.SUPPLIER ? SUPPLIER_NEEDS : CARE_PROVIDER_NEEDS;
    }

    /**
     * The needed roles nobody enabled currently holds - the half an administrator can act on.
     *
     * <p>Ordered by {@link #rolesNeededBy} rather than by the set, so the sentence a person reads is
     * stable between page loads. A {@link Set} has no order to promise and this is rendered copy.
     */
    public List<Role> missingRoles() {
        return rolesNeededBy(type).stream().filter(role -> !rolesPresent.contains(role)).toList();
    }

    /**
     * The missing roles as the labels a person reads ("Home Staff", not "HOME_STAFF").
     *
     * <p>A rendering convenience rather than copy: the sentence around it lives in the template,
     * where the rest of this product's words live, and this only supplies the names so that a
     * template never reaches for {@code name()} and prints a constant in shouting caps - the exact
     * finding T265 made about {@link OrgStatus}, on this very screen.
     */
    public List<String> missingRoleNames() {
        return missingRoles().stream().map(Role::getDisplayName).toList();
    }

    /**
     * The phrase the screens carry: {@code "Missing: Coordinator, Reviewer"} (Creed's ruling).
     *
     * <p><b>The roles are NAMED rather than the state being labelled, and that is the anti-conflation
     * half of this card arriving through the WORDS.</b> "Not yet operational" and the status chip's
     * "Awaiting activation" are two phrasings of "cannot work yet" with nothing in either to tell a
     * reader them apart - so the copy would merge the key gate and the staffing gap even where the
     * layout kept them separate. Nobody reads "Missing: Coordinator" and thinks encryption key.
     *
     * <p><b>Zero branches</b>, after T251, where a copy table broke on the one branch nobody
     * exercised. One join over a list that is empty when there is nothing to say, and callers ask
     * {@link #isOperational()} rather than this method deciding whether to speak.
     *
     * <p>The order is {@link #rolesNeededBy}'s, which is also enum-ordinal order - so rows on the
     * tree compare straight down the column. That the two coincide is a fact about today's list
     * rather than a thing to rely on; the rule's order is the one that is promised.
     */
    public String missingSummary() {
        return "Missing: " + String.join(", ", missingRoleNames());
    }

    /** Whether it has the people. Says nothing about whether it has a key - see the class note. */
    public boolean isOperational() {
        return missingRoles().isEmpty();
    }
}
