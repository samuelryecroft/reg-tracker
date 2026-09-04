package ninja.samryecroft.returnhome.tracker.document;

import com.azure.core.credential.TokenCredential;
import com.azure.core.exception.ResourceNotFoundException;
import com.azure.security.keyvault.keys.KeyClient;
import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.CryptographyClientBuilder;
import com.azure.security.keyvault.keys.cryptography.models.KeyWrapAlgorithm;
import com.azure.security.keyvault.keys.models.CreateRsaKeyOptions;
import com.azure.security.keyvault.keys.models.KeyOperation;
import com.azure.security.keyvault.keys.models.KeyVaultKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-organisation KEKs held in Azure Key Vault (UK South). The key material never leaves the
 * vault: wrap and unwrap are performed <em>by</em> Key Vault, and the app - authenticated as its
 * managed identity via Entra - only ever sees wrapped data keys. Key Vault also logs every
 * operation somewhere the application cannot edit, which is the independent record the audit trail
 * is checked against.
 *
 * <p><strong>Key type.</strong> The design named a symmetric KEK, but symmetric ({@code oct}) keys
 * are a Managed HSM feature, and Managed HSM was ruled out on cost. The default here is therefore a
 * per-organisation RSA key wrapped with RSA-OAEP-256, which a standard software-protected vault
 * does support and which preserves the property that actually matters - the KEK is never in
 * application memory. It is a property, not a rewrite: set {@code app.documents.key-vault.wrap-algorithm}
 * to {@code A256KW} if the deployment ever does get a Managed HSM with {@code oct} keys, because
 * the algorithm is recorded per document in the envelope and old documents keep unwrapping with
 * whatever wrapped them.
 *
 * <p><strong>Rotation</strong> needs no file rewrite: a new key version becomes the one
 * {@link #currentKeyFor} returns, while {@link #unwrap} uses the version recorded in each stored
 * envelope. Re-wrapping old data keys is therefore optional catch-up work, not a migration.
 */
public class KeyVaultKeyProvider implements KeyProvider {

    private static final Logger log = LoggerFactory.getLogger(KeyVaultKeyProvider.class);
    private static final int RSA_KEY_SIZE = 2048;

    private final KeyClient keyClient;
    private final TokenCredential credential;
    private final String vaultUrl;
    private final KeyWrapAlgorithm wrapAlgorithm;
    private final boolean autoCreateKeys;
    private final Duration keyHandleTtl;

    /**
     * The current KEK handle per organisation, with an expiry.
     *
     * <p>{@code currentKeyFor} called {@code getKey} on <em>every</em> encrypted write - 168-694ms
     * against a warm vault and a great deal worse on a cold container (T181). The handle is a key
     * name and version, which changes only when the key is rotated, so re-asking per write bought
     * nothing.
     *
     * <p>The TTL is what makes the cache honest rather than a correctness change. A rotation is
     * picked up within it; until then new data is wrapped with the previous version, which is
     * already exactly how this class describes rotation - old data keeps unwrapping with the
     * version recorded in its own envelope, so re-wrapping is catch-up work, not a migration. What
     * the cache must never do is outlive a key's existence, so nothing negative is ever cached: an
     * absent or unreachable key is re-asked every time, and continues to fail closed.
     */
    private final Map<Long, CachedHandle> keyHandles = new ConcurrentHashMap<>();

    private record CachedHandle(KeyHandle handle, Instant expiresAt) {
        boolean isFresh(Instant now) {
            return now.isBefore(expiresAt);
        }
    }

    /**
     * Cryptography clients are per key <em>version</em> and are safe to reuse, so they are cached:
     * every download would otherwise pay a fresh client construction and token acquisition. Bounded
     * in practice by (organisations x key versions), which is tens of entries.
     */
    private final Map<String, CryptographyClient> cryptographyClients = new ConcurrentHashMap<>();

    public KeyVaultKeyProvider(KeyClient keyClient, TokenCredential credential, String vaultUrl,
            KeyWrapAlgorithm wrapAlgorithm, boolean autoCreateKeys, Duration keyHandleTtl) {
        this.keyHandleTtl = keyHandleTtl;
        this.keyClient = keyClient;
        this.credential = credential;
        this.vaultUrl = vaultUrl.endsWith("/") ? vaultUrl.substring(0, vaultUrl.length() - 1) : vaultUrl;
        this.wrapAlgorithm = wrapAlgorithm;
        this.autoCreateKeys = autoCreateKeys;
    }

    @Override
    public KeyHandle currentKeyFor(long organisationId) {
        CachedHandle cached = keyHandles.get(organisationId);
        if (cached != null && cached.isFresh(Instant.now())) {
            return cached.handle();
        }
        String keyName = KeyProvider.keyNameFor(organisationId);
        KeyVaultKey key;
        try {
            key = keyClient.getKey(keyName);
        } catch (ResourceNotFoundException e) {
            key = createKey(keyName, organisationId, e);
        } catch (RuntimeException e) {
            // Deliberately not cached, and deliberately not falling back to a stale entry. A vault
            // we cannot reach must fail closed every time; serving a remembered handle here would
            // turn an outage into silent success and is the one thing this cache must never do.
            throw new KeyUnavailableException("Key Vault is unreachable, so this report cannot be stored", e);
        }
        KeyHandle handle = new KeyHandle(organisationId, keyName,
                key.getProperties().getVersion(), wrapAlgorithm.toString());
        keyHandles.put(organisationId, new CachedHandle(handle, Instant.now().plus(keyHandleTtl)));
        return handle;
    }

    /**
     * A bare {@code getKey}, with NO {@link #createKey} fallback - that omission is the whole point
     * (T168(b)). {@link #currentKeyFor} creates on a miss when auto-creation is enabled, so it can
     * never be used to ask whether a key exists; this can, in either configuration.
     *
     * <p>Only {@link ResourceNotFoundException} - a definite "no such key" - answers false. Anything
     * else means we could not determine existence, and saying "absent" then would refuse activation
     * for an organisation whose key is fine and the vault merely unreachable.
     */
    @Override
    public boolean keyExists(long organisationId) {
        String keyName = KeyProvider.keyNameFor(organisationId);
        try {
            keyClient.getKey(keyName);
            return true;
        } catch (ResourceNotFoundException absent) {
            return false;
        } catch (RuntimeException e) {
            throw new KeyUnavailableException(
                    "Could not determine whether " + keyName + " exists", e);
        }
    }

    private KeyVaultKey createKey(String keyName, long organisationId, RuntimeException notFound) {
        if (!autoCreateKeys) {
            // Least-privilege deployments provision keys from IaC and grant the app Crypto User
            // only, which cannot create. Say exactly that, because the fix is an onboarding step,
            // not a code change.
            throw new KeyUnavailableException("No key exists for organisation " + organisationId
                    + " and key creation is disabled; provision " + keyName
                    + " before its first encrypted record", notFound);
        }
        try {
            log.info("Creating the Key Vault KEK for organisation {} on its first encrypted record",
                    organisationId);
            return keyClient.createRsaKey(new CreateRsaKeyOptions(keyName)
                    .setKeySize(RSA_KEY_SIZE)
                    .setKeyOperations(KeyOperation.WRAP_KEY, KeyOperation.UNWRAP_KEY));
        } catch (RuntimeException e) {
            throw new KeyUnavailableException("Could not create the key for organisation " + organisationId, e);
        }
    }

    @Override
    public WrappedKey wrap(KeyHandle handle, byte[] dataKey) {
        try {
            byte[] wrapped = cryptographyClient(handle.keyName(), handle.keyVersion())
                    .wrapKey(KeyWrapAlgorithm.fromString(handle.wrapAlgorithm()), dataKey)
                    .getEncryptedKey();
            return new WrappedKey(handle.keyName(), handle.keyVersion(), handle.wrapAlgorithm(), wrapped);
        } catch (RuntimeException e) {
            throw new KeyUnavailableException("Key Vault could not wrap this document's key", e);
        }
    }

    @Override
    public byte[] unwrap(long organisationId, WrappedKey wrappedKey) {
        // The cross-organisation guard, and the reason it is here rather than only in the caller:
        // the key layer is the last point at which the two can still be compared, so a scoping bug
        // upstream cannot turn into another organisation's plaintext.
        String expectedKeyName = KeyProvider.keyNameFor(organisationId);
        if (!expectedKeyName.equals(wrappedKey.keyName())) {
            throw new DocumentIntegrityException("Wrapped key does not belong to the organisation that owns "
                    + "this document");
        }
        try {
            return cryptographyClient(wrappedKey.keyName(), wrappedKey.keyVersion())
                    .unwrapKey(KeyWrapAlgorithm.fromString(wrappedKey.wrapAlgorithm()), wrappedKey.material())
                    .getKey();
        } catch (RuntimeException e) {
            throw new KeyUnavailableException("Key Vault could not unwrap this document's key", e);
        }
    }

    private CryptographyClient cryptographyClient(String keyName, String keyVersion) {
        String identifier = vaultUrl + "/keys/" + keyName + "/" + keyVersion;
        return cryptographyClients.computeIfAbsent(identifier, id -> new CryptographyClientBuilder()
                .credential(credential)
                .keyIdentifier(id)
                .buildClient());
    }
}
