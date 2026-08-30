package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminUserFormUiTest extends AbstractUiTest {

    @Test
    void homeAndOrganisationFieldsTrackSelectedRoles() {
        login(ADMIN_USERNAME, ADMIN_PASSWORD);
        page.navigate(url("/admin/users/new"));

        // No checkbox is checked yet, so both conditional fields start hidden.
        assertThat(page.locator("#homeField").isVisible()).isFalse();
        assertThat(page.locator("#organisationField").isVisible()).isFalse();

        page.check("input[name=roles][value=HOME_STAFF]");
        assertThat(page.locator("#homeField").isVisible()).isTrue();
        assertThat(page.locator("#organisationField").isVisible()).isFalse();
        // HOME_STAFF is a solo role - every org-scoped checkbox is greyed out while it's checked.
        assertThat(page.locator("input[name=roles][value=COORDINATOR]").isDisabled()).isTrue();

        page.uncheck("input[name=roles][value=HOME_STAFF]");
        assertThat(page.locator("#homeField").isVisible()).isFalse();
        assertThat(page.locator("input[name=roles][value=COORDINATOR]").isDisabled()).isFalse();

        // Org-scoped roles (ORG_ADMIN/COORDINATOR/VISITOR) can be combined with each other, and
        // doing so locks out HOME_STAFF and ADMIN rather than the other way round.
        page.check("input[name=roles][value=COORDINATOR]");
        page.check("input[name=roles][value=VISITOR]");
        assertThat(page.locator("#organisationField").isVisible()).isTrue();
        assertThat(page.locator("input[name=roles][value=HOME_STAFF]").isDisabled()).isTrue();
    }
}
