package ninja.samryecroft.returnhome.tracker.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.event.AuthenticationFailureLockedEvent;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/**
 * T221, at the unit level.
 *
 * <h2>What this proves, and what it deliberately does not</h2>
 *
 * <p>It proves the filter's <b>behaviour</b>: a locked submission is answered without the chain
 * continuing, and the failure is still published so the audit row survives. <b>It does NOT prove the
 * filter's POSITION, and the position is the fix</b> - a filter registered <em>after</em>
 * {@code UsernamePasswordAuthenticationFilter} would pass every assertion below and close nothing at
 * all, because the password hash would already have been computed by then.
 *
 * <p>Position is asserted by {@code LockedAccountTimingGuardTest}, which drives the real chain and
 * counts hashes. This class exists because it needs no database, so it keeps working where that
 * guard cannot run. <b>It is a companion to that guard and never a substitute for it</b> - written
 * down because a green unit test sitting beside an unrunnable integration test is exactly how a lane
 * comes to look covered when it is not.
 *
 * <p>Hand-written fakes rather than a mocking framework: there are three collaborators and two of
 * them only need to record that they were called, so the fakes are shorter than the stubbing would
 * be and carry no bytecode-agent dependency.
 *
 * <h2>Why the requests are BUILT BY MockMvc rather than assembled by hand</h2>
 *
 * <p><b>An earlier version of this class called {@code request.setServletPath("/login")} and was
 * green while the integration guard could not reach the filter at all.</b> The filter matched on
 * {@code getServletPath()}; MockMvc leaves that empty; this test supplied it. So the test was not
 * merely failing to prove position - <b>it was manufacturing the one condition that made the
 * production code match, and then reporting that the production code matched.</b> Dwight's sentence
 * on finding it is the one to keep: <i>the unit test and the guard disagree, and the unit test is
 * the one being told what it wants to hear.</i>
 *
 * <p>So every request here now comes from {@code MockMvcRequestBuilders}, the same builder the
 * integration guard drives, and <b>nothing about the path is set by hand.</b> The general rule, which
 * is why this comment is longer than the fix: <b>a test may choose its INPUTS, but it must not
 * supply a value the code under test is supposed to derive.</b> The moment it does, it stops
 * measuring the code and starts measuring the fixture.
 *
 * <p>{@link #theMatcherResolvesTheSameWayInEveryDeploymentShape} exists for the same reason and
 * covers what a MockMvc request cannot: the production servlet mapping, a context-path deployment,
 * and two negative controls.
 */
class LockedAccountFilterTest {

    /** Locks exactly the names it is given, with none of the real service's timing behaviour. */
    private static final class StubAttempts extends LoginAttemptService {
        private final Set<String> locked;

        StubAttempts(Set<String> locked) {
            super(new AppProperties());
            this.locked = locked;
        }

        @Override
        public boolean isLocked(String username) {
            return username != null && locked.contains(username);
        }
    }

    private static final class RecordingChain implements FilterChain {
        int calls;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            calls++;
        }
    }

    private static final class RecordingHandler implements AuthenticationFailureHandler {
        final List<AuthenticationException> failures = new ArrayList<>();

        @Override
        public void onAuthenticationFailure(jakarta.servlet.http.HttpServletRequest request,
                jakarta.servlet.http.HttpServletResponse response, AuthenticationException exception) {
            failures.add(exception);
        }
    }

    private static final class RecordingPublisher implements ApplicationEventPublisher {
        final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }

        @Override
        public void publishEvent(ApplicationEvent event) {
            events.add(event);
        }
    }

    private final RecordingChain chain = new RecordingChain();
    private final RecordingHandler handler = new RecordingHandler();
    private final RecordingPublisher publisher = new RecordingPublisher();

    private LockedAccountFilter filterLocking(String... lockedNames) {
        return new LockedAccountFilter(new StubAttempts(Set.of(lockedNames)), handler, publisher);
    }

    /**
     * Built by MockMvc's own builder, exactly as the integration guard's requests are - so this test
     * consumes the same shape the guard does and cannot pass by supplying something the guard will
     * not. <b>Nothing about the path is set here.</b>
     */
    private jakarta.servlet.http.HttpServletRequest loginRequest(String method, String username) {
        var builder = "GET".equals(method)
                ? MockMvcRequestBuilders.get("/login")
                : MockMvcRequestBuilders.post("/login");
        if (username != null) {
            builder = builder.param("username", username);
        }
        return builder.param("password", "whatever").buildRequest(new MockServletContext());
    }

    @Test
    void aLockedSubmissionNeverReachesTheRestOfTheChain() throws Exception {
        filterLocking("locked-user").doFilter(loginRequest("POST", "locked-user"),
                new MockHttpServletResponse(), chain);

        assertThat(chain.calls)
                .as("the chain must not continue - continuing is what lets the provider compute a "
                        + "hash, which is the whole of the timing difference T221 closes")
                .isZero();
        assertThat(handler.failures).singleElement().isInstanceOf(LockedException.class);
    }

    @Test
    void aLockedSubmissionStillPublishesTheFailureSoTheAuditRowSurvives() throws Exception {
        filterLocking("locked-user").doFilter(loginRequest("POST", "locked-user"),
                new MockHttpServletResponse(), chain);

        assertThat(publisher.events)
                .as("nothing else publishes an authentication event once the provider is skipped, so "
                        + "without this the LOGIN_FAILURE row for an attempt against a locked "
                        + "account would silently stop being written")
                .singleElement().isInstanceOf(AuthenticationFailureLockedEvent.class);
    }

    @Test
    void anUnlockedSubmissionIsPassedStraightThrough() throws Exception {
        filterLocking("someone-else").doFilter(loginRequest("POST", "ordinary-user"),
                new MockHttpServletResponse(), chain);

        assertThat(chain.calls).isOne();
        assertThat(handler.failures).isEmpty();
        assertThat(publisher.events).isEmpty();
    }

    /**
     * A GET of the login page must render even while that name is locked out - the lock is about
     * submitting credentials, not about reaching the page that explains you are locked out.
     */
    @Test
    void aGetOfTheLoginPageIsNotIntercepted() throws Exception {
        filterLocking("locked-user").doFilter(loginRequest("GET", "locked-user"),
                new MockHttpServletResponse(), chain);

        assertThat(chain.calls).isOne();
        assertThat(handler.failures).isEmpty();
    }

    /** A submission with no username at all must not be treated as a locked one. */
    @Test
    void aSubmissionWithoutAUsernameIsPassedThrough() throws Exception {
        filterLocking("locked-user").doFilter(loginRequest("POST", null),
                new MockHttpServletResponse(), chain);

        assertThat(chain.calls).isOne();
    }

    /**
     * The matcher must resolve identically in every shape the request can arrive in, because the
     * value the filter used to read - {@code getServletPath()} - differs between them.
     *
     * <p>The context-path case is the one that rules out the obvious hand-rolled alternative: under
     * {@code /app} the request URI is {@code /app/login} while the pattern is {@code /login}, so a
     * bare {@code getRequestURI().equals(...)} would silently stop matching for a deployment that
     * moved behind a prefix - trading one environment-sensitive matcher for another.
     */
    @Test
    void theMatcherResolvesTheSameWayInEveryDeploymentShape() throws Exception {
        // Production: Boot's DispatcherServlet mapped to "/" populates servletPath and no pathInfo.
        MockHttpServletRequest production = new MockHttpServletRequest("POST", "/login");
        production.setServletPath("/login");
        production.setParameter("username", "locked-user");
        assertThat(matchedAsLocked(production))
                .as("the production servlet mapping must be intercepted")
                .isTrue();

        // A deployment behind a context path.
        MockHttpServletRequest behindContextPath = new MockHttpServletRequest("POST", "/app/login");
        behindContextPath.setContextPath("/app");
        behindContextPath.setServletPath("/login");
        behindContextPath.setParameter("username", "locked-user");
        assertThat(matchedAsLocked(behindContextPath))
                .as("a context-path deployment must be intercepted too - this is the case a bare "
                        + "getRequestURI() comparison would get wrong")
                .isTrue();

        // Negative control: right method, different path.
        assertThat(matchedAsLocked(MockMvcRequestBuilders.post("/logout")
                .param("username", "locked-user").buildRequest(new MockServletContext())))
                .as("a POST to another path must NOT be intercepted - without this the matcher could "
                        + "be passing by matching everything")
                .isFalse();
    }

    /** Runs the filter and reports whether it short-circuited, which is what "matched" means here. */
    private boolean matchedAsLocked(jakarta.servlet.http.HttpServletRequest request) throws Exception {
        RecordingChain localChain = new RecordingChain();
        new LockedAccountFilter(new StubAttempts(Set.of("locked-user")), new RecordingHandler(),
                new RecordingPublisher()).doFilter(request, new MockHttpServletResponse(), localChain);
        return localChain.calls == 0;
    }
}
