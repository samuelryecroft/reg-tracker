package ninja.samryecroft.returnhome.tracker.organisation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import ninja.samryecroft.returnhome.tracker.user.Role;
import org.junit.jupiter.api.Test;

/**
 * Oscar's two statements, as a plain unit test (T267).
 *
 * <p><b>A SUPPLIER is not operational until it has a coordinator, a visitor AND a reviewer; a CARE
 * PROVIDER is not until it has home staff.</b> The rule is a pure function so it can be exercised
 * without a database, the same choice {@link OrganisationTree#from} makes and for the same reason:
 * the interesting mistakes here are about WHICH roles count, and a test that needs a container to
 * ask that question gets run less often.
 *
 * <p>The gathering - which people the roles are counted from - is the other half, and it is where
 * the silent failures live. That is tested against a real database in
 * {@code OrganisationReadinessIsComputedFromMembershipTest}, because a mocked repository would
 * agree with whatever query I wrote.
 */
class OrganisationReadinessRuleTest {

    @Test
    void aSupplierNeedsAllThreeAndNamesTheOnesItHasNot() {
        OrganisationReadiness justCoordinators =
                new OrganisationReadiness(OrgType.SUPPLIER, Set.of(Role.COORDINATOR));

        assertThat(justCoordinators.isOperational()).isFalse();
        // Named rather than counted: "two roles missing" is not something an administrator can act
        // on, and the whole point of the notice is that it says what to add.
        assertThat(justCoordinators.missingRoles()).containsExactly(Role.VISITOR, Role.REVIEWER);
    }

    @Test
    void aSupplierWithAllThreeIsOperationalAndSaysNothing() {
        OrganisationReadiness complete = new OrganisationReadiness(OrgType.SUPPLIER,
                Set.of(Role.COORDINATOR, Role.VISITOR, Role.REVIEWER));

        assertThat(complete.isOperational()).isTrue();
        assertThat(complete.missingRoles()).isEmpty();
    }

    @Test
    void aCareProviderNeedsHomeStaff() {
        assertThat(new OrganisationReadiness(OrgType.CARE_PROVIDER, Set.of()).missingRoles())
                .containsExactly(Role.HOME_STAFF);
        assertThat(new OrganisationReadiness(OrgType.CARE_PROVIDER, Set.of(Role.HOME_STAFF)).isOperational())
                .isTrue();
    }

    /**
     * THE ARM THAT CATCHES A RULE COLLAPSED INTO ONE LIST, which is the shape a later simplification
     * would take: "just check the organisation has somebody in it."
     *
     * <p>Each type is given a full house of the OTHER type's roles. Both must still be missing
     * everything, because the two statements are about different work: a supplier stuffed with home
     * staff cannot allocate, visit or review anything, and a care provider full of coordinators has
     * nobody to raise a request in the first place.
     */
    @Test
    void theOtherTypesRolesDoNotCount() {
        assertThat(new OrganisationReadiness(OrgType.SUPPLIER, Set.of(Role.HOME_STAFF)).missingRoles())
                .containsExactly(Role.COORDINATOR, Role.VISITOR, Role.REVIEWER);
        assertThat(new OrganisationReadiness(OrgType.CARE_PROVIDER,
                Set.of(Role.COORDINATOR, Role.VISITOR, Role.REVIEWER)).missingRoles())
                .containsExactly(Role.HOME_STAFF);
    }

    /**
     * ORG_ADMIN and VIEWER are not on either list, and this is the guard on the line rather than on
     * the omission.
     *
     * <p>An organisation containing nothing but administrators and viewers is exactly the shape of
     * one part-way through setup - somebody has been given the keys, nobody has been given the work
     * - and it is the state Oscar's statements are pointed at. Adding either role to the needed
     * lists would make it report as ready on the day it can do nothing at all.
     */
    @Test
    void anOrganisationOfAdministratorsAndViewersIsNotOperational() {
        assertThat(new OrganisationReadiness(OrgType.SUPPLIER,
                Set.of(Role.ORG_ADMIN, Role.VIEWER)).isOperational()).isFalse();
        assertThat(new OrganisationReadiness(OrgType.CARE_PROVIDER,
                Set.of(Role.ORG_ADMIN, Role.VIEWER)).isOperational()).isFalse();
    }

    /**
     * The order is the order of the rule, not of the set - this is rendered copy, and a sentence
     * that reshuffles between page loads reads as a fault.
     */
    @Test
    void missingRolesReadInAStableOrderAndAsLabelsAPersonReads() {
        OrganisationReadiness empty = new OrganisationReadiness(OrgType.SUPPLIER, Set.of());

        assertThat(empty.missingRoles()).containsExactly(Role.COORDINATOR, Role.VISITOR, Role.REVIEWER);
        // Never the constant. T265 found "PENDING" in shouting caps on this very screen.
        assertThat(empty.missingRoleNames()).containsExactly("Coordinator", "Visitor", "Reviewer");
    }
}
