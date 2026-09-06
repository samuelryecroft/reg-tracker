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
 * T221: refuses a locked sign-in <b>before the authentication provider runs</b>, so that rejecting a
 * locked account costs nothing rather than costing a wasted password hash.
 *
 * <h2>Read this first: the oracle this class was written to close DOES NOT EXIST</h2>
 *
 * <p>The card said a locked account short-circuited in {@code preAuthenticationChecks} and paid
 * nothing, while an unknown username paid a full BCrypt through
 * {@code mitigateAgainstTimingAttack} - a ~53ms difference answering <em>"does this account
 * exist?"</em> to anyone able to time a response. <b>That is not what spring-security-core 7.1.0
 * does.</b> The correction lives here rather than only in the card, because <b>a wrong reason left in
 * the code is the next thing somebody reasons from.</b>
 *
 * <p><b>What 7.1.0 actually does</b>, from the bytecode of
 * {@code AbstractUserDetailsAuthenticationProvider.performPreCheck}. The method is named here
 * deliberately: its two {@code additionalAuthenticationChecks} call sites are visible in a class-wide
 * listing <b>in an order that suggests the opposite conclusion</b>, and reading that listing without
 * opening this method is exactly how the original claim was reached.
 *
 * <pre>
 * try { preAuthenticationChecks.check(user); }              // throws LockedException
 * catch (AuthenticationException ex) {
 *     if (!alwaysPerformAdditionalChecksOnUser) throw ex;
 *     try { additionalAuthenticationChecks(user, token); }  // THE HASH RUNS ANYWAY
 *     catch (AuthenticationException ignored) { }
 *     throw ex;
 * }
 * additionalAuthenticationChecks(user, token);
 * </pre>
 *
 * <p><b>An exception handler inverts the ordering everyone expects.</b> The pre-7 shape - pre-checks
 * throw, password never compared - is what the method names imply and is <em>not</em> what this method
 * does. The constructor sets {@code alwaysPerformAdditionalChecksOnUser = true}, so <b>the framework
 * already equalises a locked account against every other failure, by default.</b> Measured on this
 * application: a locked real account and a locked unknown username each cause exactly one
 * {@code PasswordEncoder.matches} call, at 76ms and 87ms.
 *
 * <h2>So what is this filter for?</h2>
 *
 * <p><b>Defence in depth - it turns an assumed framework default into an asserted property of our own
 * code.</b> Three things, none of which is closing a hole:
 *
 * <ul>
 *   <li><b>{@code setAlwaysPerformAdditionalChecksOnUser} is public and one call from off</b>, and
 *       nothing in this application sets it - so the equalisation we rely on rests on a default we
 *       inherit rather than on anything we state. This filter makes it ours.</li>
 *   <li>Rejecting here costs <b>zero</b> hashes rather than one wasted one, and skips a
 *       {@code loadUserByUsername} per attempt <b>during a lockout, the window carrying the most
 *       attacker traffic.</b></li>
 *   <li>{@code LockedAccountTimingGuardTest}, green at {@code (0,0)}, is a real assertion about our own
 *       code and <b>would catch that default being flipped.</b></li>
 * </ul>
 *
 * <p><b>This is a materially smaller claim than the card was written on, and it is stated that way on
 * purpose.</b> Keeping the stronger framing under weaker facts is the defect this comment exists to
 * avoid.
 *
 * <h2>Why the rejection was moved rather than the cost balanced</h2>
 *
 * <p><b>Hashing a dummy password on the locked path</b> would equalise timing with work whose only
 * purpose is to consume it - invisible in the source, confirmable only by measurement, so a future
 * upgrade reordering the provider's checks would unbalance it silently.
 *
 * <p><b>Moving the locked test into {@code postAuthenticationChecks}</b>, which runs after the password
 * compare, is a two-line change and <b>opens a far worse hole</b>: a locked account with the
 * <em>wrong</em> password fails with {@code BadCredentialsException}; with the <em>right</em> one it
 * reaches the post-check and fails with {@code LockedException}. Distinguishable - so <b>the lockout
 * window would become a password oracle with unlimited free attempts</b>, and an attacker would lock an
 * account deliberately in order to crack it. <b>Where two designs are both defensible, prefer the one
 * whose mistakes are survivable:</b> a mistake here leaves a quieter audit log; a mistake there leaks
 * credentials.
 *
 * <h2>Why this cannot live in {@link LoginFailureHandler}</h2>
 *
 * <p>By the time a failure handler runs the hash has already happened or already been skipped. <b>The
 * handler chooses a page; it cannot unspend time.</b> It remains the one place that decides the
 * wording - this filter delegates to it rather than redirecting itself, so there is exactly one
 * definition of what a locked user sees.
 *
 * <h2>What the audit trail loses, stated rather than discovered</h2>
 *
 * <p>{@code AuthenticationAuditListener} records the exception's class name as the {@code reason} on a
 * {@code LOGIN_FAILURE} row. Because this filter answers both cases the same way, that reason is now
 * uniformly {@code LockedException} where a locked unknown username would previously have recorded
 * {@code BadCredentialsException}. <b>No information leaves the system:</b> whether a username exists is
 * a fact about the {@code users} table, which an analyst can join, and the rows losing the distinction
 * are the attacker's free repeats after the lock engaged.
 *
 * <p><b>What must NOT be lost is the row itself.</b> Neither case reaches the provider any more, so no
 * authentication event would be published and {@code LOGIN_FAILURE} would silently stop being written
 * for attempts against locked accounts - the signal most worth having during an attack, lost with no
 * error and nothing going red. That is why this filter publishes the event itself, and why
 * {@code LockedAccountFilterTest} asserts the audit row still appears.
 *
 * <h2>Deliberately NOT a {@code @Component}</h2>
 *
 * <p>It is constructed by {@code SecurityConfig}. Spring Boot auto-registers any {@code Filter}
 * <em>bean</em> into the servlet container's chain, so a {@code @Component} here would be registered
 * <b>twice</b>: once by Boot, ahead of Spring Security's chain entirely, and once by
 * {@code addFilterBefore} where it is meant to sit. {@code OncePerRequestFilter} would suppress the
 * second run, so <b>the outer registration would win and the filter's real position would not be the
 * one this class documents.</b> Nothing observable would change, which is the point.
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
