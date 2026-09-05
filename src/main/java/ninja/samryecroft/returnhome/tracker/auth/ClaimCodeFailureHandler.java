package ninja.samryecroft.returnhome.tracker.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

/**
 * Sends a valid-but-unlinked Entra sign-in to the claim-code screen, and everything else to the
 * ordinary login error.
 *
 * <p><b>It handles a FAILURE. That is not incidental.</b> By the time this runs the authentication
 * attempt has already been rejected and the security context is empty - so the claim-code exchange
 * begins from a state with no principal and no authorities, rather than from a logged-in session
 * that the exchange then has to be careful not to leak. The design's invariant is a consequence of
 * where this sits, not of anything this class remembers to do.
 *
 * <p>All it carries across is the directory {@code oid}, in the session, for the exchange to pin.
 * That is an opaque identifier - the same value an administrator used to paste by hand - and not a
 * credential.
 */
public class ClaimCodeFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    public ClaimCodeFailureHandler(String failureUrl) {
        super(failureUrl);
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException, jakarta.servlet.ServletException {
        if (exception instanceof UnlinkedIdentityException unlinked) {
            request.getSession(true)
                    .setAttribute(ClaimCodeController.OBJECT_ID_ATTRIBUTE, unlinked.getObjectId());
            getRedirectStrategy().sendRedirect(request, response, "/onboarding/claim");
            return;
        }
        super.onAuthenticationFailure(request, response, exception);
    }
}
