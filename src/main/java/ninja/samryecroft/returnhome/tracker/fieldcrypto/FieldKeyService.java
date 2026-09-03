package ninja.samryecroft.returnhome.tracker.fieldcrypto;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import ninja.samryecroft.returnhome.tracker.document.DocumentSecurityException;
import ninja.samryecroft.returnhome.tracker.document.KeyProvider;
import ninja.samryecroft.returnhome.tracker.document.WrappedKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Hands out an organisation's field data key, unwrapping it through the same {@link KeyProvider}
 * the document path uses and caching the result for a bounded time.
 *
 * <p>The cache is the whole reason this class exists. Without it every decrypted column is a Key
 * Vault round trip, which turns a list of fifty children into fifty network calls and makes a
 * momentary vault outage look like total data loss. With it, the cost is one call per organisation
 * per TTL and AES-GCM in memory thereafter.
 *
 * <p>What the TTL actually buys, since it is easy to mistake it for a performance knob: it bounds
 * how long a revoked Key Vault permission keeps working. Unwrapping is the only point at which the
 * vault is consulted, so a cached key is a key the vault can no longer refuse. Fifteen minutes is
 * short enough that revocation takes effect within a maintenance window and long enough that normal
 * traffic almost never pays for an unwrap.
 */
@Service
public class FieldKeyService {

    /** Small: one entry per organisation, and organisations number in the tens. */
    private static final int MAX_CACHED_KEYS = 256;

    private final OrgFieldKeyStore keyStore;
    private final KeyProvider keyProvider;
    private final Map<Long, CachedKey> cache = new ConcurrentHashMap<>();
    private final Duration ttl;

    public FieldKeyService(OrgFieldKeyStore keyStore, KeyProvider keyProvider,
            @Value("${app.fieldcrypto.key-cache-ttl:PT15M}") Duration ttl) {
        this.keyStore = keyStore;
        this.keyProvider = keyProvider;
        this.ttl = ttl;
    }

    /**
     * The organisation's data key, from cache if it is still fresh.
     *
     * @throws FieldCryptoException if the key cannot be obtained - never a substitute key
     */
    public SecretKey dataKeyFor(long organisationId) {
        CachedKey cached = cache.get(organisationId);
        if (cached != null && cached.isFreshAt(Instant.now())) {
            return cached.key();
        }
        SecretKey key = unwrap(organisationId, keyStore.loadOrCreate(organisationId));
        evictIfFull();
        cache.put(organisationId, new CachedKey(key, Instant.now().plus(ttl)));
        return key;
    }

    /**
     * Forgets every cached key. Call after rotating a KEK if the new version must take effect
     * immediately rather than within the TTL; otherwise rotation needs nothing at all, because the
     * wrapped row records the version it was wrapped with.
     */
    public void clearCache() {
        cache.clear();
    }

    private SecretKey unwrap(long organisationId, OrgFieldKey stored) {
        byte[] dataKey = null;
        try {
            dataKey = keyProvider.unwrap(organisationId, new WrappedKey(stored.getKeyName(),
                    stored.getKeyVersion(), stored.getWrapAlgorithm(), stored.getWrappedKey()));
            return new SecretKeySpec(dataKey, "AES");
        } catch (DocumentSecurityException e) {
            // Fail closed. A vault that is unreachable, an RBAC grant that was revoked, or a row
            // pointing at another organisation's key all mean the same thing here: we do not have
            // the key, and there is no acceptable substitute.
            throw new FieldCryptoException(
                    "Could not unwrap the field key for organisation " + organisationId, e);
        } finally {
            if (dataKey != null) {
                // The SecretKeySpec copied the bytes; wipe ours rather than leave a second copy.
                Arrays.fill(dataKey, (byte) 0);
            }
        }
    }

    /** Drops expired entries first, and only then the arbitrary one, so a full cache stays correct. */
    private void evictIfFull() {
        if (cache.size() < MAX_CACHED_KEYS) {
            return;
        }
        Instant now = Instant.now();
        cache.values().removeIf(entry -> !entry.isFreshAt(now));
        if (cache.size() >= MAX_CACHED_KEYS) {
            Iterator<Long> keys = cache.keySet().iterator();
            if (keys.hasNext()) {
                cache.remove(keys.next());
            }
        }
    }

    private record CachedKey(SecretKey key, Instant expiresAt) {
        boolean isFreshAt(Instant now) {
            return now.isBefore(expiresAt);
        }
    }
}
