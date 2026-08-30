package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminUserFormUiTest extends AbstractUiTest {

    @Test
    void homeAndOrganisationFieldsTrackSelectedRoles() {
        login("admin", "ChangeMe123!");
        page.navigate(url("/admin/users/new"));

        // No checkbox is checked yet, so both conditional fields start hidden.
        assertThat(page.locator("#homeField").isVisible()).isFalse();
        assertThat(page.locator("#organisationField").isVisible()).isFalse();

        page.check("input[name=roles][value=HOME_STAFF]");
        assertThat(page.locator("#homeField").isVisible()).isTrue();
        assertThat(page.locator("#organisationField").isVisible()).isFalse();
        // HOME_STAFF is a solo role - every org-scoped checkbox is aria-disabled while it's checked.
        // T25 redesign (FE-07): a real disabled attribute pulls the option out of the tab order and
        // announces nothing, so the conflict is now signalled via aria-disabled - the checkbox stays
        // reachable, and a click on it is vetoed in JS rather than blocked by the browser.
        assertThat(ariaDisabled("COORDINATOR")).isEqualTo("true");

        page.uncheck("input[name=roles][value=HOME_STAFF]");
        assertThat(page.locator("#homeField").isVisible()).isFalse();
        assertThat(ariaDisabled("COORDINATOR")).isEqualTo("false");

        // Org-scoped roles (ORG_ADMIN/COORDINATOR/VISITOR) can be combined with each other, and
        // doing so locks out HOME_STAFF and ADMIN rather than the other way round.
        page.check("input[name=roles][value=COORDINATOR]");
        page.check("input[name=roles][value=VISITOR]");
        assertThat(page.locator("#organisationField").isVisible()).isTrue();
        assertThat(ariaDisabled("HOME_STAFF")).isEqualTo("true");
    }

    private String ariaDisabled(String roleValue) {
        return page.locator("input[name=roles][value=" + roleValue + "]").getAttribute("aria-disabled");
    }
}
