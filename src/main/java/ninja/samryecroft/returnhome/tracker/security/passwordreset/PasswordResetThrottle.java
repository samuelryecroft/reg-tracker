package ninja.samryecroft.returnhome.tracker.security.passwordreset;

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
 * The request-rate cap on {@code /forgot-password} (T353d), per source IP AND per submitted address.
 *
 * <p><b>IN-MEMORY ON PURPOSE, AND THAT IS A CORRECTNESS-NEUTRAL CHOICE HERE - DO NOT "FIX" IT BY
 * MOVING IT INTO THE DATABASE.</b> This is a throttle, exactly like {@code LoginAttemptService},
 * which T322 already recorded is deliberately in-memory: losing the state on a restart or across a
 * second instance loses only an attacker's accumulated penalty, so it degrades toward "a few more
 * requests", never toward a wrong answer. That is the opposite of {@code login_challenges} and
 * {@code password_reset_tokens}, whose state MUST be correct across instances and therefore lives in
 * Postgres. The reset TOKENS are the security boundary; this counter only decides how fast someone
 * may knock. Persisting it would buy nothing and would put an unauthenticated write path in front of
 * the database on every request.
 *
 * <p><b>The per-IP cap is the one that matters.</b> A per-address cap alone lets an attacker walk an
 * address list at one request each and never trip anything; capping the source IP is what bounds
 * enumeration and mail-cannon abuse. Both are enforced; a request must pass both.
 */
@Service
public class PasswordResetThrottle {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetThrottle.class);

    /** A ceiling so a flood of distinct keys cannot grow the map without bound (cf. LoginAttemptService). */
    private static final int MAX_TRACKED_KEYS = 50_000;

    private final Map<String, Deque<Instant>> requestsByKey = new ConcurrentHashMap<>();
    private final AppProperties appProperties;

    public PasswordResetThrottle(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * Records a request against both the address and the IP and reports whether it is within both
     * caps. Called once per {@code /forgot-password} POST, BEFORE any account lookup, so a throttled
     * request never even touches the database - and never reveals, by taking a different code path,
     * whether the address exists.
     */
    public synchronized boolean allow(String submittedAddress, String ipAddress) {
        AppProperties.PasswordReset config = appProperties.getSecurity().getPasswordReset();
        Instant now = Instant.now();
        pruneIfCrowded(now, config.getWindow());

        boolean addressOk = record("addr:" + normalise(submittedAddress), now, config.getWindow(),
                config.getMaxRequestsPerAddress());
        boolean ipOk = record("ip:" + (ipAddress == null ? "" : ipAddress), now, config.getWindow(),
                config.getMaxRequestsPerIp());

        // Both counters are advanced whether or not the request is allowed - a throttle that only
        // counts the requests it lets through can be held just under its own cap forever.
        return addressOk && ipOk;
    }

    private boolean record(String key, Instant now, Duration window, int max) {
        Deque<Instant> times = requestsByKey.computeIfAbsent(key, k -> new ArrayDeque<>());
        Instant cutoff = now.minus(window);
        while (!times.isEmpty() && times.peekFirst().isBefore(cutoff)) {
            times.pollFirst();
        }
        times.addLast(now);
        return times.size() <= max;
    }

    private static String normalise(String address) {
        return address == null ? "" : address.trim().toLowerCase();
    }

    private void pruneIfCrowded(Instant now, Duration window) {
        if (requestsByKey.size() < MAX_TRACKED_KEYS) {
            return;
        }
        Instant cutoff = now.minus(window);
        requestsByKey.entrySet().removeIf(e -> {
            Deque<Instant> times = e.getValue();
            return times.isEmpty() || times.peekLast().isBefore(cutoff);
        });
        if (requestsByKey.size() >= MAX_TRACKED_KEYS) {
            log.warn("Password-reset throttle is tracking {} keys - possible enumeration attempt",
                    requestsByKey.size());
        }
    }
}
