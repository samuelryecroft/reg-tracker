package ninja.samryecroft.returnhome.tracker.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;

/**
 * T113 Inc 1: {@code getName()} returns the application username, not the subject claim.
 *
 * <p>Worth a test rather than a comment, and the reason is precise. {@link
 * org.springframework.security.oauth2.core.oidc.user.OidcUser} <em>declares</em> {@code getName()},
 * so it cannot be forgotten - it can only be implemented the obvious way, returning the identifier
 * claim, which is what {@code DefaultOidcUser} does. A comment would be arguing against the more
 * natural implementation; this fails instead.
 */
class EntraUserPrincipalTest {

    private static final String OBJECT_ID = "0d4f8a11-9b3c-4e21-8a77-1c2d3e4f5a6b";

    @Test
    void getNameIsTheUsernameThatBothReadersExpectAndNotTheIdentifierClaim() {
        EntraUserPrincipal principal = principalFor("nadia.khan");

        // layout.html:113 renders sec:authentication="name" as the signed-in person in the sidebar,
        // and AuthenticationAuditListener.onFailure records it as the attempted username. Neither
        // would have failed on a GUID - one would have displayed it, the other stored it.
        assertThat(principal.getName()).isEqualTo("nadia.khan");
        assertThat(principal.getName()).isEqualTo(principal.getUsername());
        assertThat(principal.getName()).isNotEqualTo(OBJECT_ID);
        assertThat(principal.getName()).isNotEqualTo("pairwise-subject-value");
    }

    @Test
    void theTokenIsStillReachableForAnythingThatNeedsTheClaims() {
        EntraUserPrincipal principal = principalFor("nadia.khan");

        assertThat(principal.getIdToken()).isNotNull();
        assertThat(principal.getClaims()).containsEntry("oid", OBJECT_ID);
        assertThat(principal.getAttributes()).containsEntry("oid", OBJECT_ID);
    }

    private EntraUserPrincipal principalFor(String username) {
        User user = new User();
        user.setUsername(username);
        user.setLastName("Khan");
        user.setRoles(Set.of(Role.HOME_STAFF));
        user.setEnabled(true);
        user.setIdpSubject(OBJECT_ID);
        OidcIdToken idToken = OidcIdToken.withTokenValue("stub-token")
                .issuedAt(Instant.now().minusSeconds(60))
                .expiresAt(Instant.now().plusSeconds(600))
                .subject("pairwise-subject-value")
                .claim("oid", OBJECT_ID)
                .build();
        return new EntraUserPrincipal(user, idToken, null);
    }
}
