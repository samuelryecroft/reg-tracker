package ninja.samryecroft.returnhome.tracker.security.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Makes sure the registry actually knows about the session it is going to be asked to expire.
 *
 * <p><b>Why this exists at all, because the configuration alone looks sufficient and is not.</b>
 * Spring Security populates a {@link SessionRegistry} through
 * {@code RegisterSessionAuthenticationStrategy}, which runs inside
 * {@code UsernamePasswordAuthenticationFilter} when authentication succeeds there. This application
 * has <b>two</b> routes to an authenticated session and only one of them goes through that filter:
 *
 * <ol>
 *   <li><b>No second factor</b> (the break-glass account, or the factor switched off) - the filter
 *       authenticates, the strategy runs, the session is registered.</li>
 *   <li><b>Second factor required</b> - the filter's success handler <em>removes</em> the
 *       authentication and parks a pending id on the session. The real authentication is established
 *       later, by hand, in {@code SecondFactorController.completeSignIn}: it calls
 *       {@code request.changeSessionId()} and saves the context straight to the repository. <b>No
 *       session authentication strategy runs, so nothing registers anything</b> - and the id the
 *       password stage did register is not even the id the session now has.</li>
 * </ol>
 *
 * <p><b>Route 2 is the one that is live in production.</b> Configure the registry and nothing else,
 * and every ordinary sign-in produces a session the registry has never heard of: expiry is then a
 * method that runs, returns, reports success and does nothing. That is the failure family this
 * whole card was written against - so the fix is not a second registration call bolted next to the
 * first, which the next sign-in route would miss in the same way. <b>This observes the finished
 * state instead:</b> if a request is authenticated and its session is not in the registry, it is
 * registered. A third route added later is covered without knowing about it.
 *
 * <p>Cost is one map lookup per authenticated request - the same lookup
 * {@code ConcurrentSessionFilter} is already doing beside it.
 */
public class AuthenticatedSessionRegistrar extends OncePerRequestFilter {

    private final SessionRegistry sessionRegistry;

    public AuthenticatedSessionRegistrar(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        register(request);
        chain.doFilter(request, response);
    }

    /**
     * Remembers which id this session was registered under, so that a session whose id CHANGES can
     * take its registry entry with it.
     */
    static final String REGISTERED_AS = AuthenticatedSessionRegistrar.class.getName() + ".REGISTERED_AS";

    private void register(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            return;
        }

        String currentId = session.getId();
        Object registeredAs = session.getAttribute(REGISTERED_AS);

        // A session whose id has changed leaves its old entry behind, and nothing ever clears it:
        // the session was not DESTROYED, so no SessionDestroyedEvent is published and
        // HttpSessionEventPublisher has nothing to tell the registry. This matters here rather than
        // in the abstract because signing in through the second factor calls
        // request.changeSessionId() every time. Left alone, the register fills with ids that name
        // no session, and "expire this user's sessions" reports work done on sessions that stopped
        // existing at sign-in - a count that looks like evidence and is not.
        if (registeredAs instanceof String previousId && !previousId.equals(currentId)) {
            sessionRegistry.removeSessionInformation(previousId);
        }

        if (sessionRegistry.getSessionInformation(currentId) == null) {
            sessionRegistry.registerNewSession(currentId, principal);
        }
        session.setAttribute(REGISTERED_AS, currentId);
    }
}
