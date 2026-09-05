package ninja.samryecroft.returnhome.tracker.organisation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.home.Home;

/**
 * T119 4e: organisations and homes as <b>one tree in creation order</b> - supplier, the care
 * providers it serves, and each provider's homes.
 *
 * <p>The canvas draws the shape the data actually has: an organisation must exist before a home,
 * and a home before a child. Two flat lists on two screens could never show that, which is why 4e
 * is a tree rather than a restyled table.
 *
 * <h2>Assembled here, from flat lists, on purpose</h2>
 *
 * <p>{@link #from} is a <b>pure function</b> over lists the controller has already fetched. That is
 * a deliberate testability choice, not an aesthetic one: the grouping is where the interesting bugs
 * live (see {@code unassigned} below), and a pure function can be exercised by an ordinary unit
 * test, whereas the same logic inside a controller needs a database container to reach at all. The
 * bugs are then provable on any machine rather than only in CI.
 *
 * <p>It also keeps the query count flat. Four queries build the whole screen - organisations,
 * homes, per-organisation user counts, and which organisations have a theme row - with the joining
 * done in memory. Walking the tree to fetch each provider's homes would have been the obvious
 * shape and an N+1 on the one screen that renders every organisation on the platform.
 */
public record OrganisationTree(List<SupplierNode> suppliers, List<ProviderNode> unassigned) {

    /** A supplier and the care providers it serves. */
    public record SupplierNode(Organisation organisation, int userCount, boolean brandingSet,
            List<ProviderNode> careProviders) {

        /** The canvas's second line: "Supplier · 6 users · branding set". */
        public String meta() {
            String users = userCount == 1 ? "1 user" : userCount + " users";
            return "Supplier · " + users + (brandingSet ? " · branding set" : " · no branding set");
        }

        /** A supplier serving nobody is tagged "Empty" on the canvas rather than hidden. */
        public boolean isEmpty() {
            return careProviders.isEmpty();
        }
    }

    /** A care provider and the homes beneath it. */
    public record ProviderNode(Organisation organisation, List<Home> homes) {

        /**
         * The homes, named, as the canvas renders them - "Oakwood House · Marisco Lodge".
         *
         * <p>Says so in words when there are none. A provider with no homes cannot receive a child
         * yet, which is a real state an admin needs to see and act on, and an empty cell would
         * read as a rendering fault instead.
         */
        public String homeNames() {
            if (homes.isEmpty()) {
                return "No homes yet";
            }
            return String.join(" · ", homes.stream().map(Home::getName).toList());
        }
    }

    /**
     * Groups a flat organisation list into the tree.
     *
     * <p><b>The care that matters here is not losing rows.</b> Grouping care providers under their
     * supplier silently drops any provider whose {@code supplierOrganisation} is null, and the
     * column is nullable - so on a screen whose entire job is to show every organisation on the
     * platform, the natural implementation hides exactly the records that are misconfigured. They
     * are collected into {@code unassigned} and rendered, because an organisation nobody can reach
     * through the tree is precisely the one an admin needs to be told about.
     *
     * <p>Creation order, per the canvas, and {@code id} is the stand-in for it: {@code createdAt}
     * exists but defaults to now() in the entity, so rows created in the same request share a
     * value and sort unstably. The identity sequence cannot tie.
     */
    public static OrganisationTree from(List<Organisation> organisations, List<Home> homes,
            Map<Long, Integer> userCountsByOrgId, Set<Long> orgIdsWithBranding) {

        Map<Long, List<Home>> homesByOrgId = new LinkedHashMap<>();
        for (Home home : homes) {
            if (home.getOrganisation() != null) {
                homesByOrgId.computeIfAbsent(home.getOrganisation().getId(), k -> new ArrayList<>()).add(home);
            }
        }

        // nullsLast, not a bare comparing(): Organisation has no id setter - the identity column
        // is assigned on persist - so an entity that has not been saved has a null id, and a bare
        // natural-order comparator throws on it. Unsaved sorts last, which is also the right
        // answer for a list in creation order: the thing not yet created is the newest.
        List<Organisation> byCreation = new ArrayList<>(organisations);
        byCreation.sort(Comparator.comparing(Organisation::getId,
                Comparator.nullsLast(Comparator.naturalOrder())));

        Map<Long, List<ProviderNode>> providersBySupplierId = new LinkedHashMap<>();
        List<ProviderNode> unassigned = new ArrayList<>();
        for (Organisation organisation : byCreation) {
            if (organisation.getType() != OrgType.CARE_PROVIDER) {
                continue;
            }
            ProviderNode node = new ProviderNode(organisation,
                    homesByOrgId.getOrDefault(organisation.getId(), List.of()));
            Organisation supplier = organisation.getSupplierOrganisation();
            if (supplier == null) {
                unassigned.add(node);
            } else {
                providersBySupplierId.computeIfAbsent(supplier.getId(), k -> new ArrayList<>()).add(node);
            }
        }

        List<SupplierNode> suppliers = new ArrayList<>();
        for (Organisation organisation : byCreation) {
            if (organisation.getType() != OrgType.SUPPLIER) {
                continue;
            }
            suppliers.add(new SupplierNode(organisation,
                    userCountsByOrgId.getOrDefault(organisation.getId(), 0),
                    orgIdsWithBranding.contains(organisation.getId()),
                    providersBySupplierId.getOrDefault(organisation.getId(), List.of())));
        }

        return new OrganisationTree(suppliers, unassigned);
    }
}
