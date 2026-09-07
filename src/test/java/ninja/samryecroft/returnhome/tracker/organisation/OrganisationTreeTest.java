package ninja.samryecroft.returnhome.tracker.organisation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.user.Role;
import org.junit.jupiter.api.Test;

/**
 * T119 4e: the grouping that turns two flat lists into the tree.
 *
 * <p><b>A plain unit test, and that is the point of {@link OrganisationTree#from} being a pure
 * function.</b> The same logic inside the controller would need a database container to reach, so
 * on a machine without Docker it could only ever be exercised in CI. Here the interesting case -
 * the one that silently loses records - is provable anywhere.
 */
class OrganisationTreeTest {

    /**
     * {@code Organisation} has no id setter by design: the identity column is assigned on persist,
     * and a setter would invite code that assigns one. The grouping is keyed on id, so a unit test
     * has to supply them, and reflection is the honest way to do that rather than widening the
     * entity's API for a test's convenience.
     */
    private static Organisation org(long id, String name, OrgType type, Organisation supplier) {
        Organisation organisation = new Organisation();
        organisation.setName(name);
        organisation.setType(type);
        organisation.setSupplierOrganisation(supplier);
        try {
            Field idField = Organisation.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(organisation, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Organisation.id moved or was renamed", e);
        }
        return organisation;
    }

    private static Home home(String name, Organisation organisation) {
        Home h = new Home();
        h.setName(name);
        h.setOrganisation(organisation);
        return h;
    }

    @Test
    void careProvidersNestUnderTheirSupplierAndHomesUnderTheirProvider() {
        Organisation beacon = org(1, "Beacon Return Home Services", OrgType.SUPPLIER, null);
        Organisation harbourside = org(2, "Harbourside Children's Care", OrgType.CARE_PROVIDER, beacon);

        OrganisationTree tree = OrganisationTree.from(
                List.of(beacon, harbourside),
                List.of(home("Oakwood House", harbourside), home("Marisco Lodge", harbourside)),
                Map.of(1L, 6), Set.of(1L), Map.of(1L, staffed(OrgType.SUPPLIER)));

        assertThat(tree.suppliers()).hasSize(1);
        OrganisationTree.SupplierNode supplier = tree.suppliers().get(0);
        assertThat(supplier.careProviders()).hasSize(1);
        assertThat(supplier.careProviders().get(0).homeNames())
                .isEqualTo("Oakwood House · Marisco Lodge");
        assertThat(supplier.meta()).isEqualTo("Supplier · 6 users · branding set");
    }

    /**
     * <b>The case this class exists for.</b> {@code supplier_organisation_id} is nullable, so the
     * obvious grouping - build a map of supplier id to providers - drops every provider that has
     * no supplier. On the one screen whose whole job is to show every organisation on the platform,
     * that hides exactly the records that are misconfigured, and it hides them silently: the page
     * renders, looks complete, and is short by however many rows are broken.
     */
    @Test
    void aCareProviderWithNoSupplierIsSurfacedRatherThanSilentlyDropped() {
        Organisation beacon = org(1, "Beacon Return Home Services", OrgType.SUPPLIER, null);
        Organisation orphan = org(9, "Unlinked Care Ltd", OrgType.CARE_PROVIDER, null);

        OrganisationTree tree = OrganisationTree.from(
                List.of(beacon, orphan), List.of(), Map.of(), Set.of(), Map.of());

        assertThat(tree.unassigned())
                .as("a care provider with no supplier must still appear somewhere - it is the row "
                        + "an admin most needs to see, and grouping by supplier is what makes it "
                        + "disappear")
                .hasSize(1);
        assertThat(tree.unassigned().get(0).organisation().getName()).isEqualTo("Unlinked Care Ltd");
        assertThat(tree.suppliers().get(0).careProviders()).isEmpty();

        // Nothing is lost and nothing is duplicated: every care provider given in is rendered once.
        int rendered = tree.unassigned().size()
                + tree.suppliers().stream().mapToInt(s -> s.careProviders().size()).sum();
        assertThat(rendered).isEqualTo(1);
    }

    @Test
    void suppliersAreOrderedByCreationAndAnEmptyOneSaysSo() {
        Organisation second = org(5, "Aardvark Partners", OrgType.SUPPLIER, null);
        Organisation first = org(2, "Zenith Services", OrgType.SUPPLIER, null);

        OrganisationTree tree = OrganisationTree.from(
                List.of(second, first), List.of(), Map.of(2L, 1), Set.of(),
                Map.of(2L, staffed(OrgType.SUPPLIER), 5L, staffed(OrgType.SUPPLIER)));

        // Creation order, NOT alphabetical - the canvas asks for the order the data had to be
        // created in, and findAllWithSupplier() returns them ordered by type then name.
        assertThat(tree.suppliers().stream().map(s -> s.organisation().getName()).toList())
                .containsExactly("Zenith Services", "Aardvark Partners");
        assertThat(tree.suppliers().get(0).isEmpty()).isTrue();
        // Singular, and no branding row.
        assertThat(tree.suppliers().get(0).meta()).isEqualTo("Supplier · 1 user · no branding set");
    }

    @Test
    void aProviderWithNoHomesSaysSoRatherThanRenderingAnEmptyCell() {
        Organisation beacon = org(1, "Beacon", OrgType.SUPPLIER, null);
        Organisation provider = org(2, "New Provider", OrgType.CARE_PROVIDER, beacon);

        OrganisationTree tree = OrganisationTree.from(
                List.of(beacon, provider), List.of(), Map.of(), Set.of(), Map.of());

        assertThat(tree.suppliers().get(0).careProviders().get(0).homeNames()).isEqualTo("No homes yet");
    }

    /**
     * A readiness saying the organisation has its people, so a meta assertion is about the line's
     * OTHER facts (T267).
     *
     * <p>The tests that use this were written before readiness existed and passed an empty map,
     * which {@code from} reads as "holds none of the roles it needs" - deliberately the loud
     * default. They failed when {@code meta()} started replacing the user count with the readiness
     * phrase, which is the right way round: the assertion is exact, so it noticed.
     */
    private static OrganisationReadiness staffed(OrgType type) {
        return new OrganisationReadiness(type,
                java.util.Set.of(Role.COORDINATOR, Role.VISITOR, Role.REVIEWER, Role.HOME_STAFF));
    }

    /**
     * T267: the readiness phrase REPLACES the user count rather than being appended (Creed's
     * ruling) - "6 users" and "Missing: ..." are the same fact at two precisions, and beside a
     * broken organisation the count is the one that invites "it is populated, it is fine". Three
     * segments either way, so nothing has to decide how to truncate.
     */
    @Test
    void aSupplierThatCannotWorkYetSaysWhatIsMissingInsteadOfHowManyUsersItHas() {
        Organisation beacon = org(1, "Beacon", OrgType.SUPPLIER, null);

        OrganisationTree tree = OrganisationTree.from(List.of(beacon), List.of(), Map.of(1L, 6), Set.of(1L),
                Map.of(1L, new OrganisationReadiness(OrgType.SUPPLIER, java.util.Set.of(Role.COORDINATOR))));

        assertThat(tree.suppliers().get(0).meta())
                .isEqualTo("Supplier · Missing: Visitor, Reviewer · branding set");
    }

    /**
     * And on a care provider the phrase goes FIRST, because {@code homeNames()} is unbounded: a
     * finding placed after an unbounded list is the part that gets clipped.
     */
    @Test
    void aProviderThatCannotWorkYetLeadsWithWhatIsMissing() {
        Organisation beacon = org(1, "Beacon", OrgType.SUPPLIER, null);
        Organisation provider = org(2, "Harbourside", OrgType.CARE_PROVIDER, beacon);

        OrganisationTree tree = OrganisationTree.from(List.of(beacon, provider),
                List.of(home("Oakwood House", provider)), Map.of(), Set.of(),
                Map.of(2L, new OrganisationReadiness(OrgType.CARE_PROVIDER, java.util.Set.of())));

        assertThat(tree.suppliers().get(0).careProviders().get(0).meta())
                .isEqualTo("Missing: Home Staff · Oakwood House");
    }

    /** A provider that has its people says only what it has - the notice is absent, not empty. */
    @Test
    void aProviderThatHasItsPeopleJustNamesItsHomes() {
        Organisation beacon = org(1, "Beacon", OrgType.SUPPLIER, null);
        Organisation provider = org(2, "Harbourside", OrgType.CARE_PROVIDER, beacon);

        OrganisationTree tree = OrganisationTree.from(List.of(beacon, provider),
                List.of(home("Oakwood House", provider)), Map.of(), Set.of(),
                Map.of(2L, staffed(OrgType.CARE_PROVIDER)));

        assertThat(tree.suppliers().get(0).careProviders().get(0).meta()).isEqualTo("Oakwood House");
    }
}
