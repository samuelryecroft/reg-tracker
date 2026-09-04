package ninja.samryecroft.returnhome.tracker.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T113 Inc 3: signing out ends the Entra session too, and does not send form-login users through the
 * tenant on their way out.
 *
 * <p><b>The branch is the point, and it only exists during cutover.</b> A logout success handler is
 * configured once for the whole filter chain, while P7 has both authentication paths live at the
 * same time. Applied unconditionally, RP-initiated logout would route a form-login user - who has no
 * provider session - through the tenant's end-session endpoint. At worst that breaks sign-out for
 * the break-glass admin, the one account that must work when everything else does not.
 *
 * <p>The control itself is about shared devices: a care home, out of hours, one machine. Ending only
 * our session leaves the Entra session intact, so the next person clicks sign in and is signed
 * straight back in as the previous user - with their roles, their home scope, and every action
 * attributed to them in the audit trail.
 */
class EntraLogoutIntegrationTest extends AbstractEntraEnabledTest {

    private static final String OBJECT_ID = "c3d4e5f6-1a2b-4c3d-9e8f-7a6b5c4d3e2f";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private String username;

    @BeforeEach
    void seedAnAccount() {
        username = "logout-user-" + System.nanoTime();
        User user = new User();
        user.setUsername(username);
        user.setLastName("Khan");
        user.setRoles(new HashSet<>(Set.of(Role.COORDINATOR)));
        user.setOrganisation(seededSupplier());
        user.setEnabled(true);
        user.setIdpSubject(OBJECT_ID);
        userRepository.save(user);
    }

    @Test
    void signingOutAnEntraSessionEndsTheProviderSessionToo() throws Exception {
        MockHttpSession session = new MockHttpSession();

        String redirect = mockMvc.perform(post("/logout").session(session).with(csrf())
                        .with(securityContext(new SecurityContextImpl(entraAuthentication()))))
                .andReturn().getResponse().getRedirectedUrl();

        // The whole control: we hand the browser to the tenant's end-session endpoint, rather than
        // just dropping our own cookie and leaving the provider session signed in.
        assertThat(redirect).startsWith("https://tenant.example/oauth2/v2.0/logout");
        // id_token_hint is what tells the tenant WHICH session to end. Without it the endpoint may
        // prompt the user to choose an account instead of ending anything, which on a shared device
        // is the failure this control exists to prevent, wearing the appearance of having worked.
        assertThat(redirect).contains("id_token_hint=stub-token");
        // Absolute, and resolved from {baseUrl} rather than hardcoded - the shape that keeps working
        // when the custom domain lands. The host is the test's own; what matters is that the path we
        // send is the one the tenant will have registered.
        assertThat(redirect).contains("post_logout_redirect_uri=http://localhost/login?logout");
        assertThat(session.isInvalid()).as("our own session is ended as well").isTrue();
    }

    /**
     * The other branch, and the one an unconditional handler would break. A form-login user has no
     * provider session, so sending them to the tenant would be a confusing redirect at best - and
     * for the break-glass admin, a broken sign-out at the moment it is most needed.
     */
    @Test
    void signingOutAFormLoginSessionStaysLocalAndNeverTouchesTheTenant() throws Exception {
        MockHttpSession session = new MockHttpSession();

        String redirect = mockMvc.perform(post("/logout").session(session).with(csrf())
                        .with(securityContext(new SecurityContextImpl(formAuthentication()))))
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirect).isEqualTo("/login?logout");
        assertThat(redirect).doesNotContain("tenant.example");
        assertThat(session.isInvalid()).isTrue();
    }

    /**
     * An already-expired session logs out with a null authentication. That must take the local
     * branch: with nothing identifying a provider session there is nothing to end, and guessing
     * would send an anonymous visitor to the tenant.
     */
    @Test
    void loggingOutWithNoAuthenticationAtAllStaysLocal() throws Exception {
        String redirect = mockMvc.perform(post("/logout").with(csrf()))
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(redirect).isEqualTo("/login?logout");
    }

    @Test
    void theSignInButtonIsOfferedWhileTheFlagIsOn() throws Exception {
        String html = mockMvc.perform(get("/login")).andReturn().getResponse().getContentAsString();

        assertThat(html).contains("/oauth2/authorization/entra");
        // Form login is still offered too: cutover requires proving an ADMIN can get in through
        // Entra BEFORE the local path is removed, which is P8.
        assertThat(html).contains("name=\"username\"");
    }

    private OAuth2AuthenticationToken entraAuthentication() {
        User loaded = userRepository.findByIdpSubject(OBJECT_ID).orElseThrow();
        OidcIdToken idToken = OidcIdToken.withTokenValue("stub-token")
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(600))
                .subject("pairwise-subject-value")
                .claim("oid", OBJECT_ID)
                .build();
        return new OAuth2AuthenticationToken(new EntraUserPrincipal(loaded, idToken, null),
                List.of(new SimpleGrantedAuthority("ROLE_COORDINATOR")), "entra");
    }

    private UsernamePasswordAuthenticationToken formAuthentication() {
        UserDetails details = appUserDetailsService.loadUserByUsername(username);
        return new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
    }
}
