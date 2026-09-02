package ninja.samryecroft.returnhome.tracker.export;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Holds a generated pack just long enough for the person who asked for it to download it once.
 *
 * <p>Packs are never written to durable storage. A pack store would be an unencrypted second copy of
 * every child's whole file, kept with none of the key protection the reports themselves get - the
 * softest target in the product, and it would undo the encryption work in the same release that
 * shipped it. So the bytes live here, in memory, for fifteen minutes and one download.
 *
 * <p>Consequences worth stating plainly rather than hiding: a pack that expires cannot be recovered,
 * and re-downloading means generating again, which writes a <em>second</em> audit row. That is the
 * feature working, not a limitation - every extraction being separately recorded is the entire point.
 *
 * <p>In-memory suits the single-instance deployment this product runs on, and matches the existing
 * login-throttle store. If the app is ever scaled out, this needs a shared store or sticky sessions -
 * the failure mode being a download that lands on the wrong instance and 404s, never a leak.
 */
@Service
public class ExportLinkService {

    static final Duration LIFETIME = Duration.ofMinutes(15);

    private final Map<String, Held> held = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    private record Held(ExportPack pack, Long ownerUserId, Instant expiresAt) {
    }

    /** @return an unguessable token; the pack itself never appears in a URL */
    public String hold(ExportPack pack, Long ownerUserId) {
        purgeExpired();
        byte[] raw = new byte[32];
        secureRandom.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        held.put(token, new Held(pack, ownerUserId, Instant.now().plus(LIFETIME)));
        return token;
    }

    /**
     * Redeems a token, which consumes it whether or not the caller turns out to be entitled to it.
     *
     * @param requestingUserId must match the account that generated the pack - a link forwarded to a
     *                         colleague must not become a second, unaudited route to the data
     * @return the pack, or empty if the token is unknown, expired, already used, or another user's
     */
    public Optional<ExportPack> redeem(String token, Long requestingUserId) {
        purgeExpired();
        // Removed on the first look, so a leaked token cannot be replayed even in the moment
        // between two requests.
        Held entry = held.remove(token);
        if (entry == null || entry.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        if (!entry.ownerUserId().equals(requestingUserId)) {
            return Optional.empty();
        }
        return Optional.of(entry.pack());
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        held.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    /** Exposed for the screen's countdown, so "expires in" is the real remaining time. */
    public Duration lifetime() {
        return LIFETIME;
    }
}
