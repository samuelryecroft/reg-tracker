package ninja.samryecroft.returnhome.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The reset in {@link AbstractIntegrationTest} really does hand the next test a freshly-migrated
 * database, organisations included.
 *
 * <p>Nine test classes needed "the organisation V5 seeded", and three others create organisations
 * of their own. While {@code organisations} was preserved wholesale, those creations outlived their
 * class, and the positional {@code findByTypeOrderByName(type).get(0)} those nine used started
 * returning a leftover - detaching a coordinator's supplier from the care provider that owns the
 * request, which the access check then correctly refused with a 403.
 *
 * <p>Two halves, and this covers both. The reset stops the strays crossing a class boundary; the
 * nine classes now resolve the pair through {@link AbstractIntegrationTest#seededOrganisations()},
 * which does not consult sort order at all (T123). The last test here is the one the reset cannot
 * cover: strays and the seeded pair coexisting <em>within</em> a single test.
 *
 * <p>It only bit when a polluting class happened to run first, so it depended on filesystem class
 * order: green on macOS, red on the Linux CI runner (T120). A test that runs in one order cannot
 * catch that, so this asserts the invariant itself rather than any symptom of it.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseResetIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OrganisationRepository organisationRepository;

    @Test
    @Order(1)
    void anOrganisationCreatedByATest() {
        // Sorts before both seeded names ("Default Care Provider", "STEPS with Children"), which is
        // what made the leak change the answer rather than merely add to it.
        Organisation supplier = new Organisation();
        supplier.setName("AAA Leaked Supplier");
        supplier.setType(OrgType.SUPPLIER);
        organisationRepository.save(supplier);

        assertThat(organisationRepository.findByTypeOrderByName(OrgType.SUPPLIER).get(0).getName())
                .isEqualTo("AAA Leaked Supplier");
    }

    @Test
    @Order(2)
    void doesNotSurviveIntoTheNextTest() {
        assertThat(organisationRepository.findByTypeOrderByName(OrgType.SUPPLIER))
                .extracting(Organisation::getName)
                .containsExactly("STEPS with Children");
        assertThat(organisationRepository.findByTypeOrderByName(OrgType.CARE_PROVIDER))
                .extracting(Organisation::getName)
                .containsExactly("Default Care Provider");
        // The pair the tests depend on is the linked one, not merely the surviving one.
        assertThat(organisationRepository.findByTypeOrderByName(OrgType.CARE_PROVIDER).get(0)
                .getSupplierOrganisation().getId())
                .isEqualTo(organisationRepository.findByTypeOrderByName(OrgType.SUPPLIER).get(0).getId());
    }

    @Test
    @Order(3)
    void theSeededPairResolvesEvenWhileStrayOrganisationsExist() {
        // The reset above removes strays BETWEEN tests. This is the case it cannot help with: a test
        // that creates organisations and then, in the same test, needs the seeded pair - which is
        // exactly what the dashboard, audit-feed and case-file-export classes do. Both decoys sort
        // ahead of both seeded names, so this is the T120 failure reproduced deliberately.
        Organisation decoySupplier = new Organisation();
        decoySupplier.setName("AAA Decoy Supplier");
        decoySupplier.setType(OrgType.SUPPLIER);
        organisationRepository.save(decoySupplier);

        Organisation decoyCareProvider = new Organisation();
        decoyCareProvider.setName("AAA Decoy Care Provider");
        decoyCareProvider.setType(OrgType.CARE_PROVIDER);
        decoyCareProvider.setSupplierOrganisation(decoySupplier);
        organisationRepository.save(decoyCareProvider);

        // What the old idiom would now answer, and why it was a bug rather than a nuisance: it hands
        // back a supplier and a care provider that are a pair, but not the pair the fixtures built
        // their homes and users against.
        assertThat(organisationRepository.findByTypeOrderByName(OrgType.SUPPLIER).get(0).getName())
                .isEqualTo("AAA Decoy Supplier");

        // The helper resolves by the ids the migrations seeded, so sort order cannot reach it.
        SeededOrganisations seeded = seededOrganisations();
        assertThat(seeded.supplier().getName()).isEqualTo("STEPS with Children");
        assertThat(seeded.careProvider().getName()).isEqualTo("Default Care Provider");
        assertThat(seeded.careProvider().getSupplierOrganisation().getId())
                .isEqualTo(seeded.supplier().getId());
        assertThat(seededSupplier().getId()).isEqualTo(seeded.supplier().getId());
        assertThat(seededCareProvider().getId()).isEqualTo(seeded.careProvider().getId());
    }
}
