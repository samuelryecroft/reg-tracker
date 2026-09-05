package ninja.samryecroft.returnhome.tracker.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * T215 / D-4c-1: chooses which sign-in failure message the user gets.
 *
 * <p>Without this, Spring's default sends every {@code AuthenticationException} to
 * {@code /login?error}, and the page has one banner telling the reader to check their username and
 * password. <b>A locked-out user is therefore told to do the one thing that cannot work, on every
 * attempt, for the whole window</b> - the advice does not merely fail to help, it instructs exactly
 * the behaviour the lock exists to stop.
 *
 * <h2>THE MESSAGE IS SELECTED BY ASKING THE SERVICE, NEVER BY THE EXCEPTION TYPE</h2>
 *
 * <p>This is the whole of the card and it is the one thing that is easy to get wrong and impossible
 * to see once wrong. A <b>real</b> locked account fails with {@code LockedException}. An
 * <b>unknown</b> username that is equally locked still fails with {@code BadCredentialsException},
 * because {@code loadUserByUsername} throws before the lock is ever consulted. <b>So branching on
 * the exception type would show the locked banner only for accounts that exist, and the fix would
 * introduce the username enumeration oracle it was written to prevent.</b>
 *
 * <p>{@link LoginAttemptService#isLocked} is username-blind - it knows only its own counter, which
 * tracks unknown usernames on identical terms to real ones - so both cases reach the same branch
 * and render the same page. <b>It looks correct in every manual test where you type a username you
 * know</b>, which is why it is stated here rather than left to the reader.
 *
 * <h2>Why the exception is deliberately NOT saved to the session</h2>
 *
 * <p>Spring's {@code SimpleUrlAuthenticationFailureHandler}, which this replaces rather than
 * extends, stores the failure in the session as {@code SPRING_SECURITY_LAST_EXCEPTION}. For the two
 * cases above that stored value <em>differs</em> - {@code LockedException} against a real account,
 * {@code BadCredentialsException} against an unknown one. Nothing reads it today, and storing it
 * would leave the distinction sitting in the session for the first page that ever does.
 * <b>The oracle is not in the wording; it is in which states can be told apart</b>, and a value
 * nobody currently renders is still a difference between two states that must be indistinguishable.
 *
 * <h2>What identical means</h2>
 *
 * <p>Both locked cases produce the same status and the same {@code Location}, because they take the
 * same line. That is asserted in {@code LoginLockoutIntegrationTest} rather than argued here -
 * without that assertion the oracle can be reintroduced silently by a later edit that looks
 * reasonable.
 */
@Component
public class LoginFailureHandler implements AuthenticationFailureHandler {

    /** Matches the form field in {@code login.html} and Spring's own default. */
    static final String USERNAME_PARAMETER = "username";

    static final String GENERIC_FAILURE_URL = "/login?error";
    static final String LOCKED_FAILURE_URL = "/login?error=locked";

    private final LoginAttemptService loginAttemptService;
    private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

    public LoginFailureHandler(LoginAttemptService loginAttemptService) {
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        // The exception is IGNORED, on purpose. See the class comment: it is the one input that
        // distinguishes a real locked account from an unknown one, which is precisely the
        // distinction this page must not expose.
        String submittedUsername = request.getParameter(USERNAME_PARAMETER);
        String target = loginAttemptService.isLocked(submittedUsername)
                ? LOCKED_FAILURE_URL
                : GENERIC_FAILURE_URL;
        redirectStrategy.sendRedirect(request, response, target);
    }
}
