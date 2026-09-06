package ninja.samryecroft.returnhome.tracker.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * T171: key minting is off unless someone turns it on.
 *
 * <p>{@code autoCreateKeys} used to default to {@code true}, so the mint path created
 * key-encryption keys on demand <em>wherever nobody had said otherwise</em>. This estate's
 * convention is the opposite - {@code DatabasePasswordGuard} halts rather than inventing a
 * password, {@code ADMIN_SEED_PASSWORD} skips seeding rather than falling back to a baked-in
 * credential. <b>A default is a decision made on behalf of everyone who did not make one</b>, and
 * for key material the safe side is to do nothing.
 *
 * <p><b>Why a test for a one-word default.</b> Because that is exactly the kind of value a later
 * change flips back for a good local reason - a developer whose Key Vault run fails on a missing
 * key, and for whom {@code true} makes the symptom go away. The failure it removes is not local: it
 * is a deployment that quietly gains the ability to create key material because nobody stated an
 * opinion. Nothing else in the codebase would go red.
 */
class KeyMintingDefaultTest {

    private static final Path AZURE_PROFILE =
            Path.of("src/main/resources/application-azure.properties");

    @Test
    void mintingIsOffUnlessDeliberatelyEnabled() {
        DocumentStorageProperties properties = new DocumentStorageProperties();

        assertThat(properties.getKeyVault().isAutoCreateKeys())
                .as("a crypto property must not fail open. Creating a KEK needs Key Vault Crypto "
                        + "Officer, so the permissive default silently asked for - and used - a "
                        + "privilege a least-privilege deployment deliberately withholds. Turning "
                        + "it on must be an act, not an absence")
                .isFalse();
    }

    @Test
    void theAzureProfileStillStatesItExplicitly() throws IOException {
        String azure = Files.readString(AZURE_PROFILE, StandardCharsets.UTF_8);

        // The code default now agrees with this line, which makes it redundant for SAFETY and still
        // worth keeping: it is the operator's knob for turning minting on, and it states the
        // intended value at the deployment layer rather than relying on a reader knowing the code
        // default. Asserted so a tidy-up that removes it as "redundant" has to argue with this.
        assertThat(azure)
                .as("the azure profile should keep saying what it wants rather than inheriting it "
                        + "silently - a deployment that states its security posture can be audited "
                        + "by reading it, one that inherits it cannot")
                .contains("app.documents.key-vault.auto-create-keys=${KEY_VAULT_AUTO_CREATE:false}");
    }
}
