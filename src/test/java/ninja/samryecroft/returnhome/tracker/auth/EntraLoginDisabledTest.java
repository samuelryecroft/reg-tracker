package ninja.samryecroft.returnhome.tracker.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.MockMvc;

/**
 * P3 with the flag off, which is the default and the shipped state: Entra sign-in exists in the
 * code and does nothing at all.
 *
 * <p>"Off" here is stronger than "disabled". Because the client registration lives in
 * {@code application-entra.properties} rather than the base file, no
 * {@link ClientRegistrationRepository} is created, so the OIDC filters are never built rather than
 * built and bypassed. That is what makes this phase safe to deploy ahead of the tenant existing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EntraLoginDisabledTest extends AbstractIntegrationTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private MockMvc mockMvc;

    @Test
    void noOidcClientIsRegistered() {
        assertThat(context.getBeanNamesForType(ClientRegistrationRepository.class)).isEmpty();
    }

    @Test
    void theAuthorizationEndpointDoesNotExist() throws Exception {
        // The filter that would serve this URL was never added, so the request falls through to the
        // ordinary "you are not signed in" rule. What matters is that it does NOT bounce the user
        // out to an identity provider - there isn't one, and a half-live front door is exactly what
        // deploying this phase early must not create.
        String redirect = mockMvc.perform(get("/oauth2/authorization/entra"))
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirect).endsWith("/login");
    }

    @Test
    void formLoginIsStillTheLivePath() throws Exception {
        mockMvc.perform(get("/login")).andReturn().getResponse().getContentAsString();
        String redirect = mockMvc.perform(get("/dashboard"))
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirect).endsWith("/login");
    }
}
