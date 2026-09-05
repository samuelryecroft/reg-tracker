package ninja.samryecroft.returnhome.tracker.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * T197 §6a: <b>no {@code Authentication} carrying any authority may exist before the code is
 * redeemed.</b>
 *
 * <p>This is the control the design asked for, and it is deliberately not a test that the happy path
 * works. <b>Watching redemption succeed cannot distinguish a correct implementation from one that
 * authorised too early</b> - the natural build of "authenticate, then show a code screen" is to log
 * the user in and redirect, which would put an authenticated principal in the context for an
 * unlinked identity and would pass every functional test of the flow.
 *
 * <p>So what is asserted here is the absence: the user service <em>throws</em> rather than returning
 * a partial principal, the security context is still empty afterwards, and what survives the refusal
 * is one opaque directory identifier in the session and nothing else.
 */
class NoPrincipalBeforeRedemptionTest {

    private static final String OID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    private final UserRepository userRepository = mock(UserRepository.class);

    private OidcUser tokenWithObjectId() {
        OidcIdToken idToken = new OidcIdToken("t", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("sub", "pairwise-value", "oid", OID));
        return new DefaultOidcUser(java.util.List.of(), idToken);
    }

    @SuppressWarnings("unchecked")
    private EntraOidcUserService serviceReturning(OidcUser token) {
        OAuth2UserService<OidcUserRequest, OidcUser> delegate = mock(OAuth2UserService.class);
        when(delegate.loadUser(any())).thenReturn(token);
        return new EntraOidcUserService(userRepository, delegate);
    }

    /**
     * The refusal still refuses. It carries the {@code oid} so the exchange can pin it, but it is a
     * thrown {@code OAuth2AuthenticationException} - so no principal is created and the attempt ends
     * in the failure path exactly as it did before T197.
     */
    @Test
    void anUnlinkedIdentityProducesNoPrincipalAtAll() {
        when(userRepository.findByIdpSubject(OID)).thenReturn(Optional.empty());
        EntraOidcUserService service = serviceReturning(tokenWithObjectId());

        assertThatThrownBy(() -> service.loadUser(mock(OidcUserRequest.class)))
                .isInstanceOf(UnlinkedIdentityException.class)
                .extracting(e -> ((UnlinkedIdentityException) e).getObjectId())
                .isEqualTo(OID);

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("no Authentication may exist for an identity with no application user")
                .isNull();
    }

    /**
     * The failure handler carries the {@code oid} across and <b>nothing else</b> - in particular it
     * does not authenticate. The exchange begins from an empty context because of where this sits in
     * the filter chain, not because of anything it remembers to avoid doing.
     */
    @Test
    void theFailureHandlerCarriesOnlyTheObjectIdAndAuthenticatesNobody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ClaimCodeFailureHandler("/login?error").onAuthenticationFailure(
                request, response, new UnlinkedIdentityException(OID, "refused"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/onboarding/claim");
        assertThat(request.getSession().getAttribute(ClaimCodeController.OBJECT_ID_ATTRIBUTE))
                .isEqualTo(OID);
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("the claim-code exchange must begin with an empty security context")
                .isNull();
    }

    /**
     * The paired negative: an ordinary refusal is not diverted to the claim-code screen. Without
     * this, a handler that sent every failure there would pass the test above and hand a code screen
     * to anyone whose sign-in failed for any reason.
     */
    @Test
    void anOrdinaryAuthenticationFailureStillGoesToTheLoginError() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new ClaimCodeFailureHandler("/login?error").onAuthenticationFailure(request, response,
                new org.springframework.security.authentication.BadCredentialsException("nope"));

        assertThat(response.getRedirectedUrl()).isEqualTo("/login?error");
        assertThat(request.getSession(false) == null
                || request.getSession().getAttribute(ClaimCodeController.OBJECT_ID_ATTRIBUTE) == null)
                .as("a failure that is not an unlinked identity must leave no oid behind")
                .isTrue();
    }
}
