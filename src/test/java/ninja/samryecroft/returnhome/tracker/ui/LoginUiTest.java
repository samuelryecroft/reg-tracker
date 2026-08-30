package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LoginUiTest extends AbstractUiTest {

    @Test
    void wrongPasswordShowsErrorMessage() {
        page.navigate(url("/login"));
        page.fill("#username", "admin");
        page.fill("#password", "definitely-the-wrong-password");
        page.click("button[type=submit]");
        page.waitForLoadState();

        // T25 redesign: "We couldn't sign you in" replaces "Invalid username or password" - same
        // information, without implying the user necessarily did something wrong (mockups.html §01).
        assertThat(page.getByText("We couldn't sign you in").isVisible()).isTrue();
        assertThat(page.url()).contains("/login");
    }
}
