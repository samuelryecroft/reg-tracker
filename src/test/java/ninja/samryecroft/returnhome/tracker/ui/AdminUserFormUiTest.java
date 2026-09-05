package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminUserFormUiTest extends AbstractUiTest {

    @Test
    void homeAndOrganisationFieldsTrackSelectedRoles() {
        login(ADMIN_USERNAME, ADMIN_PASSWORD);
        page.navigate(url("/admin/users/new"));

        // One Homes field now serves HOME_STAFF and VIEWER alike (T116) - they used to be two
        // controls backed by two tables.
        // No checkbox is checked yet, so both conditional fields start hidden.
        assertThat(page.locator("#homesField").isVisible()).isFalse();
        assertThat(page.locator("#organisationField").isVisible()).isFalse();

        page.check("input[name=roles][value=HOME_STAFF]");
        assertThat(page.locator("#homesField").isVisible()).isTrue();
        assertThat(page.locator("#organisationField").isVisible()).isFalse();
        // HOME_STAFF is a solo role - every org-scoped checkbox is aria-disabled while it's checked.
        // T25 redesign (FE-07): a real disabled attribute pulls the option out of the tab order and
        // announces nothing, so the conflict is now signalled via aria-disabled - the checkbox stays
        // reachable, and a click on it is vetoed in JS rather than blocked by the browser.
        assertThat(ariaDisabled("COORDINATOR")).isEqualTo("true");

        page.uncheck("input[name=roles][value=HOME_STAFF]");
        assertThat(page.locator("#homesField").isVisible()).isFalse();
        assertThat(ariaDisabled("COORDINATOR")).isEqualTo("false");

        // Org-scoped roles (ORG_ADMIN/COORDINATOR/VISITOR) can be combined with each other, and
        // doing so locks out HOME_STAFF and ADMIN rather than the other way round.
        page.check("input[name=roles][value=COORDINATOR]");
        page.check("input[name=roles][value=VISITOR]");
        assertThat(page.locator("#organisationField").isVisible()).isTrue();
        assertThat(ariaDisabled("HOME_STAFF")).isEqualTo("true");
    }

    /**
     * T165. The shared {@code fieldError} fragment had never rendered - it was defined inside
     * {@code <head>}, where the parser's auto-close pushed its {@code <p>} out of the fragment
     * block - so this is the first time any inline validation message is painted, and the first
     * time {@code .field-error}'s own CSS has anything to lay out.
     *
     * <p>A server-side test can prove the message is in the HTML; only a browser can prove the
     * marker beside it is a real glyph rather than a silently blank {@code <use>} (a typo'd symbol
     * id renders zero-width and nothing anywhere complains - the same failure mode
     * {@code NocturneFoundationUiTest} guards on the nav), and that the icon sits BESIDE the
     * message rather than on top of it.
     */
    @Test
    void anInlineValidationErrorPaintsItsMarkerBesideTheMessage() {
        login(ADMIN_USERNAME, ADMIN_PASSWORD);
        page.navigate(url("/admin/users/new"));

        // Everything valid except the email, so the browser's own required-field handling lets the
        // form reach the server and the server sends back one field error.
        page.fill("#username", "t165ui");
        page.fill("#password", "correct-horse-battery");
        page.fill("#firstName", "Val");
        page.fill("#lastName", "Idation");
        page.fill("#email", "not-an-email-address");
        // Scoped to <main>: the shell sidebar's sign-out control is also a submit button, and it
        // comes first in the DOM - an unscoped selector signs the admin out instead.
        page.click("main button[type=submit]");

        page.waitForSelector(".field-error");
        assertThat(page.locator(".field-error").first().textContent().trim()).isNotEmpty();

        // Measured on the <use>, NOT on the <svg>: .icon carries width:1em in CSS, so the <svg>
        // box is the same size whether or not the sprite reference resolved. Only the <use>'s own
        // box collapses to zero when the symbol id does not exist.
        double glyphWidth = ((Number) page.locator(".field-error .icon use").first()
                .evaluate("el => el.getBoundingClientRect().width")).doubleValue();
        assertThat(glyphWidth).isGreaterThan(0);

        // Beside, not overlapping: the icon's right edge is left of the message's left edge.
        double iconRight = ((Number) page.locator(".field-error .icon").first()
                .evaluate("el => el.getBoundingClientRect().right")).doubleValue();
        double messageLeft = ((Number) page.locator(".field-error span").first()
                .evaluate("el => el.getBoundingClientRect().left")).doubleValue();
        assertThat(iconRight).isLessThanOrEqualTo(messageLeft);
    }

    private String ariaDisabled(String roleValue) {
        return page.locator("input[name=roles][value=" + roleValue + "]").getAttribute("aria-disabled");
    }
}
