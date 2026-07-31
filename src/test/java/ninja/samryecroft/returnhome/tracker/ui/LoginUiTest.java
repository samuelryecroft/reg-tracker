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

        assertThat(page.getByText("Invalid username or password.").isVisible()).isTrue();
        assertThat(page.url()).contains("/login");
    }
}
