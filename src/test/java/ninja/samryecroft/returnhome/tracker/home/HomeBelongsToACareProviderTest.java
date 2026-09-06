package ninja.samryecroft.returnhome.tracker.home;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import org.junit.jupiter.api.Test;

/**
 * T237: only a CARE_PROVIDER organisation may hold a home, enforced on the entity rather than at one
 * endpoint.
 *
 * <p><b>Why it moved.</b> The controller check it joins carries its own argument:
 * <em>"a filtered dropdown is not a constraint - it shapes the form, not the POST"</em>. That
 * sentence applies to the guard it justifies. A controller check is not a constraint either: it
 * shapes one endpoint, not the data. There is no {@code HomeService}, the controller writes through
 * the repository directly, and {@code DemoDataSeeder} is a second write path that accepts any
 * organisation - not an exposure today, but the door a future importer or fixture will resemble.
 *
 * <p><b>Other code already relies on this being true.</b> Every encrypted entity resolves its owning
 * organisation through {@code home.getOrganisation()}, which is why
 * {@code OrganisationLifecycleService} requires a KEK only for care providers. A home under a
 * supplier makes that narrowing wrong, and the write then fails closed against a key that does not
 * exist and never should.
 */
class HomeBelongsToACareProviderTest {

    @Test
    void aHomeCannotBeHungOffASupplier() {
        Organisation supplier = organisation("Ryecroft Supplies", OrgType.SUPPLIER);

        assertThatThrownBy(() -> new Home().setOrganisation(supplier))
                .isInstanceOf(IllegalArgumentException.class)
                .as("the message is read by someone debugging a write they believed was legal, so "
                        + "it names the organisation, its actual type, and the rule. The id is "
                        + "asserted in the persisted case rather than here - Organisation has no "
                        + "setId, so a detached fixture cannot have one, and asserting on the null "
                        + "would pin an artefact of the fixture rather than the message")
                .hasMessageContaining("Ryecroft Supplies")
                .hasMessageContaining("SUPPLIER")
                .hasMessageContaining("CARE_PROVIDER");
    }

    @Test
    void aCareProviderIsAcceptedAndSoIsNoOrganisationAtAll() {
        assertThatCode(() -> new Home()
                .setOrganisation(organisation("Bright Futures", OrgType.CARE_PROVIDER)))
                .doesNotThrowAnyException();

        assertThatCode(() -> new Home().setOrganisation(null))
                .as("the controller deliberately nulls this when it rejects a selection so the form "
                        + "can be redisplayed with its error. 'Absent' is the NOT NULL column's job; "
                        + "this setter's job is 'wrong kind'")
                .doesNotThrowAnyException();
    }

    private static Organisation organisation(String name, OrgType type) {
        Organisation organisation = new Organisation();
        organisation.setName(name);
        organisation.setType(type);
        return organisation;
    }
}
