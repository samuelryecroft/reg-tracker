package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * Runs when the PASSWORD was right, and decides whether that is the end of the sign-in or the middle
 * of it.
 *
 * <p><b>The design decision that matters is what this class does NOT do: it does not leave a
 * half-authenticated session behind.</b> The obvious implementation gives the pending session a
 * reduced authority and relies on every protected URL requiring a real role. That was measured
 * against the actual rules and is not safe here - {@code SecurityConfig} ends with
 * {@code /interview-requests/**}, {@code /reports/**} and {@code anyRequest()} all mapped to plain
 * {@code authenticated()}, which is the core child data. A partial authentication would satisfy all
 * three.
 *
 * <p>So the authentication is <b>removed entirely</b> - from the holder and from the session - and
 * the pending sign-in is carried as an ordinary session attribute that Spring Security attaches no
 * meaning to. {@code authenticated()} is then literally false until the code is accepted, which
 * makes every one of those rules safe <em>by construction</em> rather than by remembering. A route
 * added years from now inherits the gate without anyone knowing this class exists.
 */
public class SecondFactorSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(SecondFactorSuccessHandler.class);

    /** Session key for the account that has passed the password stage and nothing further. */
    public static final String PENDING_USER_ID = "SECOND_FACTOR_PENDING_USER_ID";

    private final SecondFactorService secondFactorService;
    private final SecondFactorPolicy policy;
    private final AuthenticationSuccessHandler completedLoginHandler;

    public SecondFactorSuccessHandler(SecondFactorService secondFactorService,
            SecondFactorPolicy policy) {
        this.secondFactorService = secondFactorService;
        this.policy = policy;
        SavedRequestAwareAuthenticationSuccessHandler delegate =
                new SavedRequestAwareAuthenticationSuccessHandler();
        // Matches the previous .defaultSuccessUrl("/", false) exactly: land on "/" unless the user
        // was originally heading somewhere else.
        delegate.setDefaultTargetUrl("/");
        this.completedLoginHandler = delegate;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {

        if (!(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            completedLoginHandler.onAuthenticationSuccess(request, response, authentication);
            return;
        }

        User user = principal.getUser();
        if (!policy.isRequiredFor(user)) {
            completedLoginHandler.onAuthenticationSuccess(request, response, authentication);
            return;
        }

        // An account with no address cannot be sent a code. It must NOT fall through to a completed
        // login - a missing address silently turning the factor off for that one account is exactly
        // the bypass this feature exists to prevent.
        if (!secondFactorService.canChallenge(user)) {
            log.warn("Sign-in refused for user id {}: second factor is required and the account has "
                    + "no email address", user.getId());
            abandon(request);
            response.sendRedirect(request.getContextPath() + "/login?error=nofactor");
            return;
        }

        boolean issued;
        try {
            issued = secondFactorService.issueChallenge(user);
        } catch (RuntimeException ex) {
            // Delivery failed. Refuse the sign-in visibly rather than parking the user at a code box
            // waiting for something that is not coming.
            log.error("Second-factor delivery failed for user id {}", user.getId(), ex);
            abandon(request);
            response.sendRedirect(request.getContextPath() + "/login?error=nodelivery");
            return;
        }

        // The pending state is written only AFTER a code is actually on its way, so there is no
        // window in which a session is waiting to be completed by a code that was never sent.
        HttpSession session = request.getSession(true);
        clearAuthentication(session);
        session.setAttribute(PENDING_USER_ID, user.getId());

        response.sendRedirect(request.getContextPath()
                + (issued ? "/login/verify" : "/login/verify?resendlimit"));
    }

    private void abandon(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            clearAuthentication(session);
            session.removeAttribute(PENDING_USER_ID);
        }
    }

    /**
     * Both halves are required. Clearing the holder alone leaves the context in the session, where
     * the next request would load it straight back and the user would be fully signed in without
     * ever entering a code.
     */
    private void clearAuthentication(HttpSession session) {
        SecurityContextHolder.clearContext();
        session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    }
}
