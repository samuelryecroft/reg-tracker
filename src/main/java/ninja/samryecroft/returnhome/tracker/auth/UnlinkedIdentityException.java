package ninja.samryecroft.returnhome.tracker.auth;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * The token was valid and there is no application user linked to its {@code oid}.
 *
 * <p><b>This is still a refusal.</b> It extends the exception the sign-in already threw and reaches
 * the same failure handler, so the authentication attempt ends with no {@code Authentication} in the
 * context - which is the property the entire fail-closed design rests on and the one the claim-code
 * flow was most likely to break.
 *
 * <p>The design names that trap explicitly: the natural way to build "authenticate, then show a code
 * screen" is to log the user in and redirect, which would put an authenticated principal in the
 * context for an unlinked identity - and it would pass every functional test of the flow. So this
 * carries the {@code oid} <em>as data on a failure</em>, for the redemption exchange to use, rather
 * than as a half-made identity.
 *
 * <p>The {@code oid} is an opaque directory identifier, not a credential: it is the same value an
 * administrator used to paste from the portal by hand. It is safe to hold for the exchange and to
 * name in an audit record, and it is the value that answers "why is this person in this account?".
 */
public class UnlinkedIdentityException extends OAuth2AuthenticationException {

    private final String objectId;

    public UnlinkedIdentityException(String objectId, String message) {
        super(new OAuth2Error("access_denied", message, null), message);
        this.objectId = objectId;
    }

    public String getObjectId() {
        return objectId;
    }
}
