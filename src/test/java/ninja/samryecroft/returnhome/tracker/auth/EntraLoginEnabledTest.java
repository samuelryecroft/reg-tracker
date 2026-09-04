package ninja.samryecroft.returnhome.tracker.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.web.servlet.MockMvc;

/**
 * P3 with the flag on: the OIDC path is wired, and wired the way the design asked for.
 *
 * <p>The endpoints are set explicitly rather than via {@code issuer-uri} because discovery would
 * make a live call to a tenant that does not exist yet. That is the only thing this test stubs -
 * the filter chain, the resolver and PKCE are the real ones.
 *
 * <p>This does not make form login stop working, and does not test signing in: without P4 there is
 * no link from a token to an application user, which is precisely why the flag ships off.
 */
class EntraLoginEnabledTest extends AbstractEntraEnabledTest {

    @Autowired
    private ClientRegistrationRepository clientRegistrations;
    @Autowired
    private MockMvc mockMvc;

    @Test
    void theRegistrationAsksForTheThreeThinClaimsAndNothingElse() {
        ClientRegistration entra = clientRegistrations.findByRegistrationId("entra");

        assertThat(entra).isNotNull();
        // oid is what we link on (see EntraOidcUserService.objectIdOf), name is display, and email
        // is display only - D4 withdrew the one-time link ceremony this comment used to describe.
        // Roles, organisations, homes and export capability stay in our database, so there is
        // nothing to ask the directory for beyond identity (ENTRA-AUTH-DESIGN.md §3).
        assertThat(entra.getScopes()).containsExactlyInAnyOrder("openid", "profile", "email");
        assertThat(entra.getRedirectUri()).isEqualTo("{baseUrl}/login/oauth2/code/{registrationId}");
    }

    @Test
    void startingSignInRedirectsToTheTenantWithPkce() throws Exception {
        String redirect = mockMvc.perform(get("/oauth2/authorization/entra"))
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirect).startsWith("https://tenant.example/oauth2/v2.0/authorize");
        assertThat(redirect).contains("client_id=test-client-id");
        assertThat(redirect).contains("response_type=code");
        // PKCE has to be asked for on a confidential client - Spring only applies it automatically
        // to public ones - so its absence would be silent. The redirect is where that is visible.
        assertThat(redirect).contains("code_challenge=");
        assertThat(redirect).contains("code_challenge_method=S256");
    }

    @Test
    void formLoginSurvivesAlongsideIt() throws Exception {
        // Both paths exist at once, deliberately: cutover requires proving an ADMIN can sign in
        // through Entra BEFORE the local path is removed, and removing it is P8.
        String content = mockMvc.perform(get("/login"))
                .andReturn().getResponse().getContentAsString();

        assertThat(content).contains("name=\"username\"");
        assertThat(content).contains("name=\"password\"");
    }
}
