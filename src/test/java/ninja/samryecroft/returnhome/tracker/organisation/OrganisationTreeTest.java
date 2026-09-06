package ninja.samryecroft.returnhome.tracker.organisation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.home.Home;
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
                Map.of(1L, 6), Set.of(1L));

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
                List.of(beacon, orphan), List.of(), Map.of(), Set.of());

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
                List.of(second, first), List.of(), Map.of(2L, 1), Set.of());

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
                List.of(beacon, provider), List.of(), Map.of(), Set.of());

        assertThat(tree.suppliers().get(0).careProviders().get(0).homeNames()).isEqualTo("No homes yet");
    }
}
