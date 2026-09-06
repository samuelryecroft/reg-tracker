package ninja.samryecroft.returnhome.tracker.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventRepository;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventType;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T221: while a lockout holds, <b>neither</b> a real account nor an unknown username may cause a
 * password hash to be computed.
 *
 * <h2>What this guard is NOT asserting, corrected after it was believed</h2>
 *
 * <p>It was written to catch a ~53ms timing oracle: a locked real account supposedly short-circuiting
 * before any compare while an unknown username paid a full BCrypt. <b>That asymmetry does not exist on
 * spring-security-core 7.1.0.</b> {@code AbstractUserDetailsAuthenticationProvider.performPreCheck}
 * catches the {@code LockedException} and runs {@code additionalAuthenticationChecks} anyway when
 * {@code alwaysPerformAdditionalChecksOnUser} is set - and the constructor sets it {@code true}. It is
 * a deliberate timing-equalisation mitigation, on by default. See {@code LockedAccountFilter} for the
 * disassembly.
 *
 * <p><b>So this guard asserts a property of OUR code, not the absence of a framework hole:</b> that no
 * hash is computed on either locked path, because {@code LockedAccountFilter} rejects before the
 * provider is reached. <b>It would fail if that framework default were ever flipped off</b>, which is
 * the thing worth having a test for - the equalisation is one public setter from gone.
 *
 * <h2>The lesson this test taught, which is worth more than the test</h2>
 *
 * <p>It reported symmetric counts twice, and <b>both times it was right</b> - the paths genuinely are
 * symmetric. It was disbelieved twice: once against a pre-committed rule that symmetry meant the filter
 * was not running, and once as an unexplained anomaly. <b>Neither reading reached the simplest
 * explanation, that it was symmetric because there was never an asymmetry to restore.</b>
 *
 * <p><b>A negative control reporting a false premise is the one thing a negative control is least
 * likely to be believed about</b> - because the premise is what everyone is measuring against.
 *
 * <h2>Why this counts hashes instead of measuring time</h2>
 *
 * <p>The defect was a timing difference, so the obvious guard is a stopwatch. <b>A stopwatch assertion
 * would be the wrong test</b>: flaky on shared CI, with a threshold nobody can defend, and the usual
 * response to it going red is to widen it until it stops - ending in a guard that cannot fail.
 *
 * <p>Counting {@link PasswordEncoder} invocations asserts the <b>cause</b> rather than the symptom, and
 * is deterministic: the count is 0 or it is not, on any hardware, with no threshold to argue about.
 *
 * <h2>Armed by reverting the fix, not by a synthetic mutation</h2>
 *
 * <p>Remove {@code .addFilterBefore(lockedAccountFilter, ...)} from {@code SecurityConfig} and both
 * locked cases reach the provider again, each paying one hash:
 * {@code neitherLockedCaseComputesAPasswordHash} fails with {@code [afterReal=1, afterUnknown=1]}.
 * <b>Symmetric is the correct armed result</b>, and the first assertion (the two counts being equal)
 * still passes - it is the second, requiring them to be ZERO, that catches the revert.
 *
 * <p><b>An arming edit that does not compile proves nothing</b>, and fails in a direction that
 * superficially resembles a red test. Comment the line out rather than deleting it, and check the call
 * is genuinely gone before trusting the run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(LockedAccountTimingGuardTest.CountingEncoderConfig.class)
// PINNED, and the first armed run is why. This class hardcoded MAX_ATTEMPTS = 5 and merely ASSUMED
// the application agreed; it stated the dependency nowhere and asserted it nowhere. If the two ever
// diverged, lock() would quietly fail to lock and every measurement below would be taken against an
// UNLOCKED account - which produces exactly the symmetric (1,1) that arming #2 reported, and produces
// it while looking like a result rather than a broken precondition.
// The same reason LoginThrottlingIntegrationTest pins its own: a guard must not depend on ambient
// configuration it does not state.
@TestPropertySource(properties = {
        "app.security.login-throttle.enabled=true",
        "app.security.login-throttle.max-attempts=5",
        "app.security.login-throttle.lockout-duration=15m"
})
class LockedAccountTimingGuardTest extends AbstractIntegrationTest {

    /** Must equal the pinned {@code max-attempts} above - the two are deliberately adjacent. */
    private static final int MAX_ATTEMPTS = 5;

    /**
     * Wraps the real encoder rather than stubbing it, so the application still behaves exactly as
     * it does in production - a stub that returned a constant could make the lock unreachable and
     * the test would pass for the wrong reason.
     */
    @TestConfiguration
    static class CountingEncoderConfig {
        static final AtomicInteger MATCH_CALLS = new AtomicInteger();

        @Bean
        @Primary
        PasswordEncoder countingPasswordEncoder() {
            PasswordEncoder delegate = new BCryptPasswordEncoder();
            return new PasswordEncoder() {
                @Override
                public String encode(CharSequence rawPassword) {
                    return delegate.encode(rawPassword);
                }

                @Override
                public boolean matches(CharSequence rawPassword, String encodedPassword) {
                    MATCH_CALLS.incrementAndGet();
                    return delegate.matches(rawPassword, encodedPassword);
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AuditEventRepository auditEventRepository;
    @Autowired
    private LoginAttemptService loginAttemptService;

    private final String suffix = "-" + System.nanoTime();

    private String realUser() {
        String username = "t221-real" + suffix;
        User user = new User();
        user.setUsername(username);
        user.setFirstName("Rea");
        user.setLastName("List");
        user.setPassword(passwordEncoder.encode("correct-horse-battery"));
        user.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        user.setEnabled(true);
        userRepository.save(user);
        return username;
    }

    private MockHttpServletResponse attempt(String username) throws Exception {
        return mockMvc.perform(post("/login").with(csrf())
                        .param("username", username)
                        .param("password", "definitely-not-the-password"))
                .andReturn().getResponse();
    }

    /**
     * Drives the account into lockout and <b>asserts that it actually got there</b>.
     *
     * <p>The assertion is the point. Everything this class measures is only meaningful about a LOCKED
     * account, so an unlocked one is not a result - it is a broken precondition, and it fails in a
     * direction that looks exactly like a finding: both cases reach the provider, both pay one hash,
     * and the guard reports a symmetric non-zero count. <b>Without this line that state is
     * indistinguishable from the defect the class exists to detect.</b>
     */
    private void lock(String username) throws Exception {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            attempt(username);
        }
        assertThat(loginAttemptService.isLocked(username))
                .as("PRECONDITION: '%s' must be locked after %d failed attempts before anything below "
                        + "is measured. If this fails the throttle did not engage, and the counts that "
                        + "follow would be measurements of an UNLOCKED account - which looks identical "
                        + "to the timing defect this class detects", username, MAX_ATTEMPTS)
                .isTrue();
    }

    @Test
    void neitherLockedCaseComputesAPasswordHash() throws Exception {
        String real = realUser();
        String unknown = "t221-ghost" + suffix;
        lock(real);
        lock(unknown);

        // Counted from here, so the attempts that BUILT the lock - which legitimately hash - are
        // excluded. Only what happens once the lock holds is under test.
        CountingEncoderConfig.MATCH_CALLS.set(0);
        MockHttpServletResponse realResponse = attempt(real);
        int afterReal = CountingEncoderConfig.MATCH_CALLS.getAndSet(0);
        MockHttpServletResponse unknownResponse = attempt(unknown);
        int afterUnknown = CountingEncoderConfig.MATCH_CALLS.get();

        assertThat(afterUnknown)
                .as("the two locked paths must cost the same. NOTE: they already do without this "
                        + "filter - performPreCheck runs additionalAuthenticationChecks even after "
                        + "LockedException, because alwaysPerformAdditionalChecksOnUser defaults to "
                        + "true - so THIS assertion passing is not the interesting part and never "
                        + "was. The next one is: equal must mean ZERO. See the class comment; an "
                        + "earlier version of this message claimed a ~53ms gap that does not exist")
                .isEqualTo(afterReal);
        // Dwight's suggestion after arming #2: report BOTH counts, not just the sum. "expected 0 but
        // was 2" forced a round-trip through inference to recover (1,1) from (0,1), and the whole
        // value of this guard is in WHICH of the two moved. A message change, not a threshold change.
        assertThat(afterReal + afterUnknown)
                .as("and the equal cost must be ZERO rather than merely equal: balancing the two by "
                        + "hashing on both paths would be work whose only purpose is to consume time, "
                        + "invisible in the source and confirmable only by measurement. "
                        + "[afterReal=%d, afterUnknown=%d] - an ASYMMETRIC failure means the filter is "
                        + "gone and the oracle is back (expected when armed); a SYMMETRIC failure means "
                        + "something else, and the precondition assertions above have already ruled out "
                        + "the account not being locked", afterReal, afterUnknown)
                .isZero();

        assertThat(unknownResponse.getStatus()).isEqualTo(realResponse.getStatus());
        assertThat(unknownResponse.getRedirectedUrl())
                .as("closing the timing channel must not have opened a content one")
                .isEqualTo(realResponse.getRedirectedUrl());
    }

    /**
     * The regression this fix could plausibly cause, asserted rather than assumed.
     *
     * <p>Neither locked case reaches the provider any more, so nothing publishes an authentication
     * event unless the filter does it. Without that, {@code LOGIN_FAILURE} rows for attempts
     * against locked accounts would stop being written - <b>the signal most worth having during an
     * attack, lost with no error and nothing going red.</b>
     */
    @Test
    void aLockedAttemptIsStillAudited() throws Exception {
        String real = realUser();
        lock(real);
        long before = countLoginFailures();
        attempt(real);
        assertThat(countLoginFailures())
                .as("a rejected attempt against a locked account must still produce a LOGIN_FAILURE "
                        + "audit row - the filter short-circuits the provider, so it has to publish "
                        + "the failure event itself")
                .isGreaterThan(before);
    }

    private long countLoginFailures() {
        return auditEventRepository.findByEventTypeOrderByOccurredAtDesc(
                AuditEventType.LOGIN_FAILURE).size();
    }
}
