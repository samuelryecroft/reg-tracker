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
 * <p>Nine test classes read {@code findByTypeOrderByName(type).get(0)} as "the organisation V5
 * seeded", and three others create organisations of their own. While {@code organisations} was
 * preserved wholesale, those creations outlived their class and that {@code get(0)} started
 * returning a leftover - detaching a coordinator's supplier from the care provider that owns the
 * request, which the access check then correctly refused with a 403.
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
}
