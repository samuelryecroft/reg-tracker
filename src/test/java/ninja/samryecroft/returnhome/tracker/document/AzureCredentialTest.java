package ninja.samryecroft.returnhome.tracker.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.ManagedIdentityCredential;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * T181: how the application authenticates to Azure, and how many times it does it.
 *
 * <p>Measured in App Insights on the live deployment: a managed-identity token took 6-7 seconds to
 * acquire on a fresh container and was acquired <em>twice</em>, and the first {@code getKey} took
 * 22-33 seconds and then failed. Two separate causes sit behind that, and each has a test here
 * because each is invisible in code review: a duplicated credential looks like two correct call
 * sites, and a credential chain looks like a sensible default.
 */
class AzureCredentialTest {

    private final DocumentStorageConfig config = new DocumentStorageConfig();

    private DocumentStorageProperties properties() {
        DocumentStorageProperties properties = new DocumentStorageProperties();
        properties.getKeyVault().setUri("https://v.vault.azure.net");
        return properties;
    }

    /**
     * In production the managed identity is the only source that can ever succeed, so walking the
     * chain to reach it is pure waiting - each source that cannot answer has to time out first.
     */
    @Test
    void productionAuthenticatesAsTheManagedIdentityWithoutWalkingTheChain() {
        TokenCredential credential = config.azureCredential(properties(),
                new MockEnvironment().withProperty("app.env", "prod"));

        assertThat(credential).isInstanceOf(ManagedIdentityCredential.class);
    }

    /**
     * The paired case, and the reason this is not simply hardcoded: a developer running against a
     * real vault authenticates through the Azure CLI, which only the chain finds. A "fix" that
     * always used the managed identity would be faster in production and completely unusable
     * everywhere else.
     */
    @Test
    void developmentKeepsTheCredentialChain() {
        TokenCredential credential = config.azureCredential(properties(), new MockEnvironment());

        assertThat(credential).isInstanceOf(DefaultAzureCredential.class);
    }

    @Test
    void theChoiceCanBeOverriddenInBothDirections() {
        DocumentStorageProperties forced = properties();
        forced.getKeyVault().setCredential(DocumentStorageProperties.KeyVault.CredentialSource.MANAGED_IDENTITY);
        assertThat(config.azureCredential(forced, new MockEnvironment()))
                .isInstanceOf(ManagedIdentityCredential.class);

        DocumentStorageProperties relaxed = properties();
        relaxed.getKeyVault().setCredential(DocumentStorageProperties.KeyVault.CredentialSource.DEFAULT_CHAIN);
        assertThat(config.azureCredential(relaxed, new MockEnvironment().withProperty("app.env", "prod")))
                .isInstanceOf(DefaultAzureCredential.class);
    }

    /**
     * The duplication guard, and the defect it pins is the one nothing else could see.
     *
     * <p>Key Vault and Blob Storage each built their own {@code DefaultAzureCredential}. Both call
     * sites were individually correct and neither was wrong to read - but a credential owns its
     * token cache, so the two never shared a token and a cold container paid the acquisition twice.
     * Nothing in a review shows that; it took a dependency trace to see two identical token calls.
     *
     * <p>Written as a source scan rather than a context assertion because what must hold is not
     * "the bean is a singleton" - Spring guarantees that - but that <b>nobody constructs a second
     * credential outside it</b>, which is a property of the source and the thing that would quietly
     * reintroduce the cost.
     */
    @Test
    void nothingConstructsACredentialOutsideTheSharedBean() throws IOException {
        List<String> offences = new java.util.ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                int builders = count(source, "new DefaultAzureCredentialBuilder(")
                        + count(source, "new ManagedIdentityCredentialBuilder(");
                // COUNTED, not excluded. The first version of this guard skipped
                // DocumentStorageConfig entirely as "the file that is allowed to build one" - and a
                // mutation restoring the original defect, a second credential for Blob Storage in
                // that same file, PASSED IT. The one file the bug lived in was the one file the
                // guard did not look at. An allowance has to be for the exact thing that is
                // allowed - one of each builder, the two arms of the choice - not for a whole file.
                int allowed = file.getFileName().toString().equals("DocumentStorageConfig.java") ? 2 : 0;
                if (builders > allowed) {
                    offences.add(file.getFileName() + " builds " + builders + " credentials");
                }
            }
        }

        assertThat(offences)
                .as("an Azure credential is built outside DocumentStorageConfig.azureCredential. A "
                        + "credential owns its token cache, so a second one does not share a token "
                        + "with the first and a cold container pays the acquisition again - 6-7 "
                        + "seconds each, measured (T181). Inject the TokenCredential bean instead")
                .isEmpty();
    }

    private static int count(String source, String needle) {
        int total = 0;
        for (int i = source.indexOf(needle); i >= 0; i = source.indexOf(needle, i + 1)) {
            total++;
        }
        return total;
    }
}
