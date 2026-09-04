package ninja.samryecroft.returnhome.tracker.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * T113 Inc 1: which of our users a validated Entra token corresponds to, and what happens when the
 * answer is "none".
 *
 * <p>The token itself is a stub, deliberately - {@code OidcUserService} validating signatures and
 * calling a userinfo endpoint is Spring's code and needs a live tenant to exercise. What is ours,
 * and what these tests are about, is the decision taken afterwards.
 */
class EntraOidcUserServiceTest {

    private static final String OBJECT_ID = "6f0a1c9e-3c2b-4c1a-9f77-0c0a1b2c3d4e";

    private final UserRepository userRepository = mock(UserRepository.class);

    @Test
    void aTokenWhoseObjectIdMatchesAnEnabledUserBecomesThatUsersPrincipal() {
        User user = enabledUser("nadia.khan");
        when(userRepository.findByIdpSubject(OBJECT_ID)).thenReturn(Optional.of(user));

        OidcUser loaded = service(tokenWith("oid", OBJECT_ID)).loadUser(mock(OidcUserRequest.class));

        assertThat(loaded).isInstanceOf(EntraUserPrincipal.class);
        // The assignability the whole design rests on: every @AuthenticationPrincipal
        // AppUserPrincipal parameter in the codebase can receive this.
        assertThat(loaded).isInstanceOf(AppUserPrincipal.class);
        assertThat(((AppUserPrincipal) loaded).getUsername()).isEqualTo("nadia.khan");
    }

    @Test
    void aTokenMatchingNoAccountIsRefused() {
        when(userRepository.findByIdpSubject(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(tokenWith("oid", OBJECT_ID)).loadUser(mock(OidcUserRequest.class)))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining(EntraOidcUserService.REFUSED);
    }

    /**
     * The refusal must not distinguish "no account" from "account disabled". Telling them apart
     * answers "does this person have an account on the children's safeguarding system?" for anyone
     * who can authenticate to the tenant. Asserting the message, not just that both throw, is the
     * point - two different messages would still be two exceptions.
     */
    @Test
    void aDisabledAccountIsRefusedWithTheSameMessageAsNoAccountAtAll() {
        User disabled = enabledUser("suspended.leaver");
        disabled.setEnabled(false);
        when(userRepository.findByIdpSubject(OBJECT_ID)).thenReturn(Optional.of(disabled));
        String disabledMessage = messageFrom(tokenWith("oid", OBJECT_ID));

        when(userRepository.findByIdpSubject(OBJECT_ID)).thenReturn(Optional.empty());
        String noAccountMessage = messageFrom(tokenWith("oid", OBJECT_ID));

        assertThat(disabledMessage).isEqualTo(noAccountMessage);
    }

    /**
     * A token carrying no {@code oid} is refused rather than quietly falling back to {@code sub}.
     * A fallback would look up a value nothing ever stores today - harmless right up until someone
     * stores a sub-shaped value, at which point it matches the wrong row.
     */
    @Test
    void aTokenWithoutAnObjectIdIsRefusedRatherThanFallingBackToSub() {
        assertThatThrownBy(() -> service(tokenWith("sub", "pairwise-subject-value"))
                .loadUser(mock(OidcUserRequest.class)))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .hasMessageContaining(EntraOidcUserService.REFUSED);
    }

    private String messageFrom(OidcUser token) {
        try {
            service(token).loadUser(mock(OidcUserRequest.class));
            throw new AssertionError("expected the sign-in to be refused");
        } catch (OAuth2AuthenticationException expected) {
            return expected.getMessage();
        }
    }

    private EntraOidcUserService service(OidcUser token) {
        @SuppressWarnings("unchecked")
        OAuth2UserService<OidcUserRequest, OidcUser> delegate = mock(OAuth2UserService.class);
        when(delegate.loadUser(any())).thenReturn(token);
        return new EntraOidcUserService(userRepository, delegate);
    }

    private OidcUser tokenWith(String claim, String value) {
        OidcIdToken idToken = OidcIdToken.withTokenValue("stub-token")
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(600))
                .subject("pairwise-subject-value")
                .claim(claim, value)
                .build();
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken);
    }

    private User enabledUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setLastName(username);
        user.setRoles(Set.of(Role.HOME_STAFF));
        user.setEnabled(true);
        user.setIdpSubject(OBJECT_ID);
        return user;
    }
}
