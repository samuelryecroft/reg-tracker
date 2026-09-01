package ninja.samryecroft.returnhome.tracker.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The development key provider. It stands in for Key Vault's custody, so what matters is that it
 * reproduces the properties the real one has - per-organisation isolation and a refusal to unwrap
 * across organisations - and that it cannot be started without a configured secret.
 */
class LocalKeyProviderTest {

    private static final byte[] DATA_KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private final LocalKeyProvider provider = new LocalKeyProvider("a-master-secret-for-tests");

    @Test
    void wrapsAndUnwrapsADataKey() {
        WrappedKey wrapped = provider.wrap(provider.currentKeyFor(3L), DATA_KEY);

        assertThat(provider.unwrap(3L, wrapped)).isEqualTo(DATA_KEY);
        assertThat(wrapped.material()).isNotEqualTo(DATA_KEY);
    }

    @Test
    void namesKeysPerOrganisation() {
        assertThat(provider.currentKeyFor(3L).keyName()).isEqualTo("org-3-kek");
        assertThat(KeyProvider.organisationIdIn("org-3-kek")).isEqualTo(3L);
        // A name that does not follow the convention resolves to no organisation, so it can never
        // accidentally match a real one.
        assertThat(KeyProvider.organisationIdIn("org-three-kek")).isEqualTo(-1L);
        assertThat(KeyProvider.organisationIdIn(null)).isEqualTo(-1L);
    }

    @Test
    void refusesToUnwrapAnotherOrganisationsKey() {
        WrappedKey orgThrees = provider.wrap(provider.currentKeyFor(3L), DATA_KEY);

        assertThatThrownBy(() -> provider.unwrap(4L, orgThrees))
                .isInstanceOf(DocumentIntegrityException.class);
    }

    @Test
    void organisationKeysAreIndependentOfEachOther() {
        WrappedKey orgThrees = provider.wrap(provider.currentKeyFor(3L), DATA_KEY);

        // Relabelling the wrapped key as organisation 4's gets past the name check and still fails,
        // because the derived keys genuinely differ. That is the property being asserted: the name
        // check is a clear error message, not the isolation itself.
        WrappedKey relabelled = new WrappedKey("org-4-kek", orgThrees.keyVersion(),
                orgThrees.wrapAlgorithm(), orgThrees.material());

        assertThatThrownBy(() -> provider.unwrap(4L, relabelled))
                .isInstanceOf(DocumentIntegrityException.class);
    }

    @Test
    void refusesAKeyWrappedByADifferentProvider() {
        WrappedKey fromKeyVault = new WrappedKey("org-3-kek", "1", "RSA-OAEP-256", DATA_KEY);

        // Saying so plainly beats a confusing authentication failure when someone points a local
        // instance at documents written by a deployed one.
        assertThatThrownBy(() -> provider.unwrap(3L, fromKeyVault))
                .isInstanceOf(DocumentIntegrityException.class)
                .hasMessageContaining("different key provider");
    }

    @Test
    void refusesToStartWithoutAConfiguredMasterSecret() {
        // The same posture AdminUserSeeder takes with the bootstrap password: an unset value fails
        // loudly rather than deriving every organisation's key from a well-known default.
        assertThatThrownBy(() -> new LocalKeyProvider(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LocalKeyProvider("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LocalKeyProvider("short")).isInstanceOf(IllegalStateException.class);
    }
}
