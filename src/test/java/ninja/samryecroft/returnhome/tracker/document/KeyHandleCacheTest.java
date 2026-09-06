package ninja.samryecroft.returnhome.tracker.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.credential.TokenCredential;
import com.azure.core.exception.ResourceNotFoundException;
import com.azure.security.keyvault.keys.KeyClient;
import com.azure.security.keyvault.keys.cryptography.models.KeyWrapAlgorithm;
import com.azure.security.keyvault.keys.models.KeyProperties;
import com.azure.security.keyvault.keys.models.KeyVaultKey;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * T181: {@code currentKeyFor} asked Key Vault on every single encrypted write.
 *
 * <p>A handle is a key name and a version, and it changes only when the key is rotated, so the
 * per-write round trip bought nothing - 168-694ms against a warm vault and far worse on a cold
 * container. What the cache must never do is turn a vault problem into a silent success, so the
 * fail-closed cases are tested here alongside the hit.
 */
@ExtendWith(MockitoExtension.class)
class KeyHandleCacheTest {

    private final KeyClient keyClient = mock(KeyClient.class);

    private KeyVaultKeyProvider provider(Duration ttl) {
        return new KeyVaultKeyProvider(keyClient, mock(TokenCredential.class), "https://v.vault.azure.net",
                KeyWrapAlgorithm.RSA_OAEP_256, false, ttl);
    }

    /**
     * Built before any stubbing begins. Creating a mock inside a {@code when(...)} argument nests
     * one stubbing inside another, which Mockito rejects - so every key these tests need is made
     * first and only then handed to a stub.
     */
    private KeyVaultKey aKey(String version) {
        KeyVaultKey key = mock(KeyVaultKey.class);
        KeyProperties properties = mock(KeyProperties.class);
        when(properties.getVersion()).thenReturn(version);
        when(key.getProperties()).thenReturn(properties);
        return key;
    }

    @Test
    void theSecondWriteInsideTheTtlDoesNotAskKeyVaultAgain() {
        KeyVaultKey v1 = aKey("v1");
        when(keyClient.getKey(anyString())).thenReturn(v1);
        KeyVaultKeyProvider provider = provider(Duration.ofMinutes(10));

        KeyHandle first = provider.currentKeyFor(3L);
        KeyHandle second = provider.currentKeyFor(3L);

        verify(keyClient, times(1)).getKey(anyString());
        assertThat(second).isEqualTo(first);
    }

    /**
     * The paired negative. A cache with no expiry passes the test above and never picks up a
     * rotation, so the TTL has to be shown to be doing something rather than merely configured.
     */
    @Test
    void anExpiredHandleIsLookedUpAgainSoRotationIsPickedUp() {
        KeyVaultKey v1 = aKey("v1");
        KeyVaultKey v2 = aKey("v2");
        when(keyClient.getKey(anyString())).thenReturn(v1, v2);
        KeyVaultKeyProvider provider = provider(Duration.ZERO);

        assertThat(provider.currentKeyFor(4L).keyVersion()).isEqualTo("v1");
        assertThat(provider.currentKeyFor(4L).keyVersion()).isEqualTo("v2");
        verify(keyClient, times(2)).getKey(anyString());
    }

    /**
     * The one thing this cache must never do. An unreachable vault has to fail closed on every
     * attempt - serving a remembered handle would let writes continue against a vault we cannot
     * reach, turning an outage into a silent success, and no later request would ever discover it.
     */
    @Test
    void anUnreachableVaultIsNeverServedFromTheCache() {
        KeyVaultKey v1 = aKey("v1");
        when(keyClient.getKey(anyString()))
                .thenReturn(v1)
                .thenThrow(new RuntimeException("vault unreachable"));
        KeyVaultKeyProvider provider = provider(Duration.ZERO);

        assertThat(provider.currentKeyFor(5L).keyVersion()).isEqualTo("v1");

        assertThatThrownBy(() -> provider.currentKeyFor(5L))
                .isInstanceOf(KeyUnavailableException.class);
    }

    /**
     * And an absent key stays absent. Nothing negative is cached either, so a key provisioned a
     * minute later is found rather than remembered as missing for a TTL.
     */
    @Test
    void anAbsentKeyIsNotCachedAsAbsent() {
        KeyVaultKey v1 = aKey("v1");
        when(keyClient.getKey(anyString()))
                .thenThrow(new ResourceNotFoundException("no such key", null))
                .thenReturn(v1);
        KeyVaultKeyProvider provider = provider(Duration.ofMinutes(10));

        assertThatThrownBy(() -> provider.currentKeyFor(6L)).isInstanceOf(KeyUnavailableException.class);
        assertThat(provider.currentKeyFor(6L).keyVersion()).isEqualTo("v1");
    }
}
