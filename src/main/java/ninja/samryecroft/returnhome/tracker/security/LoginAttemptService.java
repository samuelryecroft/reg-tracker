package ninja.samryecroft.returnhome.tracker.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Failed-login throttling: after {@code app.security.login-throttle.max-attempts} bad passwords a
 * username is locked out for {@code app.security.login-throttle.lockout-duration}.
 *
 * <p>State is held in memory. That is a deliberate fit for the deployment in ARCHITECTURE.md - a
 * single App Service instance with no auto-scale, ~20 users - and keeps this free of infrastructure
 * that the planned Entra migration would only throw away. The tradeoffs are real and worth stating:
 * counters reset on restart, and a scaled-out deployment would need a shared store (Redis) or,
 * better, would let the IdP own throttling entirely.
 *
 * <p>Lockout is keyed on username rather than IP. That protects an account against distributed
 * guessing, which username-keyed counting handles and IP-keyed counting does not. The cost is that
 * someone who knows a username can deliberately lock that person out; the short default window
 * bounds the damage, and IP-level blocking belongs in front of the app (WAF/App Service) rather
 * than in bespoke code here.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    /** Guards against unbounded growth from attempts against random, non-existent usernames. */
    private static final int MAX_TRACKED_USERNAMES = 10_000;

    private final Map<String, Attempts> attemptsByUsername = new ConcurrentHashMap<>();
    private final AppProperties appProperties;

    public LoginAttemptService(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /** Whether this username is currently locked out and must be refused regardless of password. */
    public boolean isLocked(String username) {
        if (!config().isEnabled() || username == null) {
            return false;
        }
        Attempts attempts = attemptsByUsername.get(key(username));
        if (attempts == null) {
            return false;
        }
        if (attempts.lockedUntil != null && Instant.now().isBefore(attempts.lockedUntil)) {
            return true;
        }
        // The lockout has elapsed - clear it so the next attempt starts from a clean slate.
        if (attempts.lockedUntil != null) {
            attemptsByUsername.remove(key(username));
        }
        return false;
    }

    /**
     * Records a failed password attempt, locking the account once the threshold is reached.
     *
     * <p>{@code ipAddress} is logged but not used as a lockout key - see the class note.
     */
    public void recordFailure(String username, String ipAddress) {
        if (!config().isEnabled() || username == null) {
            return;
        }
        Duration window = config().getLockoutDuration();
        Instant now = Instant.now();
        pruneIfCrowded(now);

        String key = key(username);
        Attempts updated = attemptsByUsername.compute(key, (ignored, existing) -> {
            // Failures older than the window shouldn't accumulate towards a lockout.
            if (existing == null || existing.lastFailureAt.plus(window).isBefore(now)) {
                return new Attempts(1, now, null);
            }
            return new Attempts(existing.count + 1, now, existing.lockedUntil);
        });

        if (updated.lockedUntil == null && updated.count >= config().getMaxAttempts()) {
            attemptsByUsername.put(key, new Attempts(updated.count, now, now.plus(window)));
            log.warn("Locking username '{}' for {} after {} failed login attempts (last from IP {})",
                    username, window, updated.count, ipAddress == null ? "unknown" : ipAddress);
        }
    }

    /** A successful sign-in clears any accumulated failures for that username. */
    public void recordSuccess(String username) {
        if (username != null) {
            attemptsByUsername.remove(key(username));
        }
    }

    /** Lower-cased so an attacker cannot sidestep the counter by varying capitalisation. */
    private String key(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private AppProperties.LoginThrottle config() {
        return appProperties.getSecurity().getLoginThrottle();
    }

    private void pruneIfCrowded(Instant now) {
        if (attemptsByUsername.size() < MAX_TRACKED_USERNAMES) {
            return;
        }
        Duration window = config().getLockoutDuration();
        attemptsByUsername.values().removeIf(attempts ->
                (attempts.lockedUntil == null || now.isAfter(attempts.lockedUntil))
                        && attempts.lastFailureAt.plus(window).isBefore(now));
    }

    private record Attempts(int count, Instant lastFailureAt, Instant lockedUntil) {
    }
}
