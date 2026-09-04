package ninja.samryecroft.returnhome.tracker.auth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;

/**
 * Ends the Entra session as well as ours when the person signed in through Entra, and ends only ours
 * when they did not.
 *
 * <p><b>The branch exists because a logout success handler is configured once for the whole filter
 * chain, while during cutover both authentication paths are live at the same time.</b> An
 * RP-initiated logout applied unconditionally would also catch form-login users - who have no Entra
 * session to end - and route them through the tenant's end-session endpoint on the way out. At best
 * a confusing redirect; at worst a broken sign-out for the break-glass admin, which is the one
 * account that has to work when everything else does not.
 *
 * <p><b>Why RP-initiated logout matters at all, since it reads as plumbing.</b> This is the
 * shared-device control. A care home, out of hours, one machine, several staff: without it, ending
 * our session leaves the Entra session intact, so the next person clicks sign in and is signed
 * straight back in as the previous user - with that person's roles and home scope, and with every
 * action attributed to them in the audit trail.
 *
 * <p><b>A degradation worth knowing about, found by testing this rather than by reading it.</b>
 * {@code OidcClientInitiatedLogoutSuccessHandler} builds its redirect from the registration's
 * {@code end_session_endpoint}, which is populated <em>only</em> by OIDC discovery - Spring Boot has
 * no property for it. If a tenant's metadata ever lacks it, the handler does not fail: it quietly
 * falls back to the local redirect, and sign-out silently stops ending the provider session while
 * still looking like it worked. That is why the live config uses {@code issuer-uri} rather than
 * hand-written endpoints, and why the test supplies the metadata explicitly instead of assembling a
 * registration that would have passed while proving the opposite.
 *
 * <p>{@code authentication} is null when the session has already gone (a double submit, or a
 * timeout), and that takes the local branch: with nothing to identify the provider session there is
 * nothing to end, and guessing would send an anonymous visitor to the tenant.
 */
public class EntraAwareLogoutSuccessHandler implements LogoutSuccessHandler {

    private final LogoutSuccessHandler oidcLogout;
    private final LogoutSuccessHandler localLogout;

    public EntraAwareLogoutSuccessHandler(LogoutSuccessHandler oidcLogout, String localRedirectUrl) {
        this.oidcLogout = oidcLogout;
        SimpleUrlLogoutSuccessHandler local = new SimpleUrlLogoutSuccessHandler();
        local.setDefaultTargetUrl(localRedirectUrl);
        this.localLogout = local;
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        boolean signedInThroughEntra = authentication instanceof OAuth2AuthenticationToken;
        (signedInThroughEntra ? oidcLogout : localLogout).onLogoutSuccess(request, response, authentication);
    }
}
