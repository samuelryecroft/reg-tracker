package ninja.samryecroft.returnhome.tracker.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T113 Inc 3: the sign-in button's OFF state, asserted from the configuration that actually ships
 * today - no flag set, no properties overridden.
 *
 * <p>Its own class rather than a case in the flag-on test, because the flag is read at context
 * startup: proving the button is absent requires a context where it was never on, and the two states
 * cannot share one. Without this the button would be tested only in the deployment that does not yet
 * exist, and a build with the flag off would offer a control whose endpoint is not registered - a
 * dead link on the one page where the person has no way to tell whether the fault is theirs.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EntraSignInButtonDefaultOffTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void theSignInButtonIsAbsentWhileTheFlagIsOff() throws Exception {
        String html = mockMvc.perform(get("/login")).andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("/oauth2/authorization/entra");
        // Form login is still there, so this is the button being absent rather than the page failing.
        assertThat(html).contains("name=\"username\"");
    }
}
