package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Bounds how many codes one account, and one client address, may submit to {@code /login/verify}
 * inside a window (T380, CODE-REVIEW-2026-09-18 P1.4).
 *
 * <p><b>Why the per-challenge cap is not enough.</b> {@code max-attempts} burns a challenge after
 * five wrong codes. But a caller who already holds the password - the only caller who reaches this
 * page - can mint a fresh challenge, and a fresh five, by signing in again; {@code LoginAttemptService}
 * throttles password <em>failures</em> and never sees a password that succeeds. So the effective
 * budget against a six-digit code was five per sign-in, times as many sign-ins as the caller cared
 * to make. This is the account-level bound that was missing.
 *
 * <p><b>Every submission counts, right or wrong.</b> A throttle that only counted refused codes
 * could be held just under its own cap forever, which is the same reasoning as
 * {@code PasswordResetThrottle}, whose shape this copies deliberately - two of these that drifted
 * apart would be two security controls to reason about instead of one.
 *
 * <p><b>In memory, per instance.</b> The same posture as the login lockout and the reset throttle:
 * exact on the single instance this application runs as, silently N-times looser if it is ever
 * scaled out. RELEASE-NOTES.md records that fact alongside the other two.
 */
@Service
public class SecondFactorVerifyThrottle {

    private static final Logger log = LoggerFactory.getLogger(SecondFactorVerifyThrottle.class);

    private static final int MAX_TRACKED_KEYS = 50_000;

    private final Map<String, Deque<Instant>> submissionsByKey = new ConcurrentHashMap<>();
    private final AppProperties appProperties;

    public SecondFactorVerifyThrottle(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /** True if this submission is within both caps; false means refuse it without checking the code. */
    public synchronized boolean allow(Long userId, String ipAddress) {
        AppProperties.SecondFactor config = appProperties.getSecurity().getSecondFactor();
        Instant now = Instant.now();
        pruneIfCrowded(now, config.getVerifyWindow());

        boolean userOk = record("user:" + userId, now, config.getVerifyWindow(),
                config.getMaxVerifiesPerUser());
        boolean ipOk = record("ip:" + (ipAddress == null ? "" : ipAddress), now,
                config.getVerifyWindow(), config.getMaxVerifiesPerIp());

        // Both counters are advanced whether or not the submission is allowed.
        return userOk && ipOk;
    }

    private boolean record(String key, Instant now, Duration window, int max) {
        Deque<Instant> times = submissionsByKey.computeIfAbsent(key, k -> new ArrayDeque<>());
        Instant cutoff = now.minus(window);
        while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
            times.pollFirst();
        }
        times.addLast(now);
        return times.size() <= max;
    }

    private void pruneIfCrowded(Instant now, Duration window) {
        if (submissionsByKey.size() < MAX_TRACKED_KEYS) {
            return;
        }
        Instant cutoff = now.minus(window);
        submissionsByKey.entrySet().removeIf(e -> {
            Deque<Instant> times = e.getValue();
            return times.isEmpty() || times.peekLast().isBefore(cutoff);
        });
        if (submissionsByKey.size() >= MAX_TRACKED_KEYS) {
            log.warn("Second-factor verify throttle is tracking {} keys - possible distributed guessing",
                    submissionsByKey.size());
        }
    }
}
