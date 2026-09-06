package ninja.samryecroft.returnhome.tracker.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureLockedEvent;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * T221: refuses a locked sign-in <b>before the authentication provider runs</b>, so that a locked
 * account and a locked-but-non-existent username cost the same to reject.
 *
 * <h2>The defect this closes, and why it is backwards from intuition</h2>
 *
 * <p>{@code DaoAuthenticationProvider} calls {@code mitigateAgainstTimingAttack} when
 * {@code loadUserByUsername} finds nothing - one full BCrypt, measured at ~53ms - so an
 * <b>unknown</b> username pays for a hash that was never going to match. A <b>real</b> account that
 * is locked never gets that far: {@code DefaultPreAuthenticationChecks} tests
 * {@code isAccountNonLocked} and throws {@code LockedException} <em>before</em>
 * {@code additionalAuthenticationChecks} compares anything, so it costs nothing.
 *
 * <p><b>So during a lockout the slower answer was the username that does NOT exist</b>, and the
 * difference was large enough to read over a network. Worse, while a lockout holds an attacker gets
 * unlimited free samples: a rejected attempt does not extend the window (see
 * {@link LoginAttemptService#recordFailure}), so the measurement can be averaged until it is
 * certain.
 *
 * <p><b>Spring's mitigation is not broken and was never claiming to cover this.</b> It equalises
 * <em>unknown</em> against <em>known-with-the-wrong-password</em> - both pay one hash. It cannot
 * equalise <em>unknown</em> against <em>known-and-locked</em>, because that second path
 * short-circuits before the password is consulted. <b>A framework mitigation covers the pair it was
 * designed for, not the property you happen to want.</b>
 *
 * <h2>Why the rejection was moved rather than the cost balanced</h2>
 *
 * <p>Two other shapes were considered and rejected, and they are recorded because both are more
 * obvious than this one.
 *
 * <p><b>Hashing a dummy password on the locked path</b> would equalise the timing with work whose
 * only purpose is to consume time. That balance is invisible in the source and confirmable only by
 * measurement, so a future upgrade that reorders the provider's checks would unbalance it silently
 * and everything would still look correct.
 *
 * <p><b>Moving the locked test into {@code postAuthenticationChecks}</b>, which runs after the
 * password compare, is a two-line change and equalises the timing correctly - and it opens a much
 * worse hole. A locked account with the <em>wrong</em> password fails with
 * {@code BadCredentialsException}; with the <em>right</em> one it reaches the post-check and fails
 * with {@code LockedException}. Those are distinguishable, so <b>the lockout window would become a
 * password oracle with unlimited free attempts</b> - an attacker would lock an account deliberately
 * in order to crack it, and the lock would stop preventing the one thing it exists to prevent.
 * <b>Where two designs are both defensible, prefer the one whose mistakes are survivable:</b> a
 * mistake here leaves a quieter audit log, a mistake there leaks credentials.
 *
 * <h2>Why this cannot live in {@link LoginFailureHandler}, which is where it looks like it belongs</h2>
 *
 * <p>By the time a failure handler runs the hash has already been computed or already been skipped.
 * <b>The handler chooses a page; it cannot unspend time.</b> The handler is still the one place that
 * decides the wording - this filter delegates to it rather than redirecting itself, so there remains
 * exactly one definition of what a locked user sees.
 *
 * <h2>What the audit trail loses, stated rather than discovered</h2>
 *
 * <p>{@code AuthenticationAuditListener} records the exception's class name as the {@code reason} on
 * a {@code LOGIN_FAILURE} row. Because this filter answers both cases the same way, that reason is
 * now uniformly {@code LockedException} where a locked unknown username would previously have
 * recorded {@code BadCredentialsException}. <b>No information leaves the system:</b> whether the
 * username exists is a fact about the {@code users} table, which an analyst can join, and the rows
 * that lost the distinction are the attacker's free repeats after the lock engaged - the least
 * informative rows in the sequence.
 *
 * <p><b>What must NOT be lost is the row itself.</b> Neither case reaches the provider any more, so
 * no authentication event would be published and {@code LOGIN_FAILURE} would silently stop being
 * written for attempts against locked accounts - which is precisely the signal worth having during
 * an attack, and it would fail with no error and no test going red. That is why this filter
 * publishes the event itself, and why {@code LockedAccountFilterTest} asserts the audit row still
 * appears.
 *
 * <h2>Deliberately NOT a {@code @Component}</h2>
 *
 * <p>It is constructed by {@code SecurityConfig} instead. Spring Boot auto-registers any {@code
 * Filter} <em>bean</em> into the servlet container's own chain, so a {@code @Component} here would be
 * registered <b>twice</b>: once by Boot, ahead of Spring Security's filter chain entirely, and once
 * by {@code addFilterBefore} where it is meant to sit. {@code OncePerRequestFilter} would suppress
 * the second run, which means <b>the outer registration would win and the filter's real position
 * would not be the one this class documents</b> - it would run before CSRF rather than beside the
 * authentication filter.
 *
 * <p>Nothing observable might change, and that is the point: <b>the position would be wrong in a way
 * no test would notice</b>, which is the exact failure this card already produced once when the
 * matcher read {@code getServletPath()}. Not being a bean removes the second registration rather
 * than compensating for it.
 */
public class LockedAccountFilter extends OncePerRequestFilter {

    /**
     * {@code formLogin}'s default processing URL, which {@code SecurityConfig} keeps.
     *
     * <p><b>Matched with Spring Security's own matcher rather than by reading a field off the
     * request, and that is not a style preference - it is the fix for a real defect.</b> This filter
     * originally tested {@code request.getServletPath().equals("/login")}. That value is <b>not a
     * property of the request</b>; it is a property of how the container mapped the servlet, so it
     * is {@code "/login"} under Boot's {@code DispatcherServlet} at {@code "/"} and <b>empty string
     * under MockMvc</b>, whose builder populates {@code requestURI} and leaves {@code servletPath}
     * unset. The filter therefore never matched in the test harness, silently did nothing, and the
     * integration guard measured an application with no filter in it.
     *
     * <p>A path matcher removes the question rather than answering it: it resolves the same way for
     * a harness request, for {@code DispatcherServlet} at {@code "/"}, and for a deployment under a
     * context path - the last being a case a hand-rolled {@code getRequestURI()} comparison would
     * get wrong, since the URI there carries the context prefix and the pattern does not.
     * {@code LockedAccountFilterTest} pins all four, plus two negative controls.
     *
     * <p>It is also the <b>same matcher machinery Spring Security uses to decide which requests
     * {@code UsernamePasswordAuthenticationFilter} handles</b>, so this filter and the filter it
     * stands in front of now agree about what a login submission is by construction rather than by
     * two strings happening to be equal.
     */
    static final String LOGIN_PROCESSING_URL = "/login";

    private static final RequestMatcher LOGIN_SUBMISSION =
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, LOGIN_PROCESSING_URL);

    private final LoginAttemptService loginAttemptService;
    private final AuthenticationFailureHandler failureHandler;
    private final ApplicationEventPublisher eventPublisher;

    public LockedAccountFilter(LoginAttemptService loginAttemptService,
            AuthenticationFailureHandler failureHandler, ApplicationEventPublisher eventPublisher) {
        this.loginAttemptService = loginAttemptService;
        this.failureHandler = failureHandler;
        this.eventPublisher = eventPublisher;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String username = request.getParameter(LoginFailureHandler.USERNAME_PARAMETER);
        if (!isLoginSubmission(request) || !loginAttemptService.isLocked(username)) {
            chain.doFilter(request, response);
            return;
        }
        // The username is NOT looked up. Asking the database whether it exists would reintroduce a
        // smaller version of the difference this filter removes, and nothing here needs the answer.
        LockedException locked = new LockedException("Sign-in is paused for this account");
        eventPublisher.publishEvent(new AuthenticationFailureLockedEvent(
                UsernamePasswordAuthenticationToken.unauthenticated(username, null), locked));
        failureHandler.onAuthenticationFailure(request, response, locked);
    }

    /**
     * Deliberately narrow: only the form-login POST. A GET of the login page must still render, and
     * the lock is about submitting credentials rather than about reaching the page.
     */
    private boolean isLoginSubmission(HttpServletRequest request) {
        return LOGIN_SUBMISSION.matches(request);
    }
}
