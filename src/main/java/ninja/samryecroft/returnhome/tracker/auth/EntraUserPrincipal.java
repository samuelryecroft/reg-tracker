package ninja.samryecroft.returnhome.tracker.auth;

import java.util.Map;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.User;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * The principal an Entra sign-in produces: our own {@link AppUserPrincipal}, which additionally
 * satisfies {@link OidcUser}.
 *
 * <p><b>It extends rather than replaces, and that is the whole design.</b> 50 controller parameters
 * across 18 classes are declared {@code @AuthenticationPrincipal AppUserPrincipal}, and two more
 * places test {@code getPrincipal() instanceof AppUserPrincipal}. A subclass is assignable to all of
 * them, so the OIDC path reaches every existing site unchanged - no controller in this codebase
 * knows which front door the user came through, which is what keeps authorisation a single
 * implementation rather than two that must be kept in step.
 *
 * <p><b>What a stock {@code DefaultOidcUser} would have done instead is worth recording, because it
 * would not have looked like a failure.</b> {@code AuthenticationPrincipalArgumentResolver} checks
 * assignability and, with {@code errorOnInvalidType} defaulting to false, injects {@code null} - so
 * those 50 sites get a null principal rather than a cast error, and the two {@code instanceof} sites
 * take their false branch in silence. One of those is
 * {@code AuthenticationAuditListener.onSuccess}: the application would have started, served pages,
 * and quietly stopped writing LOGIN_SUCCESS rows.
 */
public final class EntraUserPrincipal extends AppUserPrincipal implements OidcUser {

    private final OidcIdToken idToken;
    private final OidcUserInfo userInfo;

    EntraUserPrincipal(User user, OidcIdToken idToken, OidcUserInfo userInfo) {
        // locked=false, deliberately. LoginAttemptService throttles failed FORM logins by username;
        // on this path credentials are never presented to us, so there is no local failure count
        // that could mean anything. Passing the form-login lockout through here would let a burst of
        // wrong-password attempts against a username lock that person out of single sign-on, which
        // surfaces weeks later as one user mysteriously unable to get in. Lockout on this path is
        // the identity provider's job.
        super(user, false);
        this.idToken = idToken;
        this.userInfo = userInfo;
    }

    @Override
    public Map<String, Object> getClaims() {
        return idToken.getClaims();
    }

    @Override
    public Map<String, Object> getAttributes() {
        return idToken.getClaims();
    }

    @Override
    public OidcIdToken getIdToken() {
        return idToken;
    }

    @Override
    public OidcUserInfo getUserInfo() {
        return userInfo;
    }

    /**
     * The application username, not the subject claim.
     *
     * <p>{@link OidcUser} declares {@code getName()}, so this cannot be forgotten - it can only be
     * implemented the obvious way, returning the {@code sub}/{@code oid} claim, which is what
     * {@code DefaultOidcUser} does. Two things read it and neither wants an opaque identifier:
     * {@code layout.html}'s {@code sec:authentication="name"} renders it in the sidebar as the
     * signed-in person, and {@code AuthenticationAuditListener.onFailure} records it as the
     * attempted username. Both would have kept working while showing and storing a GUID.
     *
     * <p>A comment here would be arguing against the more natural implementation, so
     * {@code EntraUserPrincipalTest} asserts it instead.
     */
    @Override
    public String getName() {
        return getUsername();
    }
}
