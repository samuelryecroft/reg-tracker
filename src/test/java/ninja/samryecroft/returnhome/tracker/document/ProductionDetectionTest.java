package ninja.samryecroft.returnhome.tracker.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.ManagedIdentityCredential;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * T181 follow-up: the production guards had never fired in production.
 *
 * <p>Measured, not deduced. Pam checked the startup line I asked her to check first and the live app
 * said <em>"Authenticating to Azure with the DefaultAzureCredential chain (non-production)"</em>,
 * then paid a 32-second cold start it was supposed to have stopped paying and burned its whole
 * warmup budget on one organisation. The scoped credential was in the jar and switched off.
 *
 * <p>The cause is that {@code azure} is the production profile - {@code deploy.yml} asserts
 * {@code SPRING_PROFILES_ACTIVE} is exactly {@code azure} and fails the deploy otherwise - while
 * {@code isProduction} looked only for {@code prod}/{@code production}. The profile file still
 * describes itself as activated "alongside the environment's own profile, e.g. {@code azure,prod}":
 * two sources for one fact, disagreeing, and only the pipeline binds.
 *
 * <p><b>The credential is the guard that got measured; it is not the only one that was inert.</b>
 * The refusals of {@code keys=local} and {@code storage=local} in production keyed off the same
 * check, so they had never been armed either. They happen not to have mattered because the azure
 * profile sets both backends explicitly - nothing ever reached the check - which is exactly why
 * nobody noticed. Both are pinned here now.
 */
class ProductionDetectionTest {

    private final DocumentStorageConfig config = new DocumentStorageConfig();

    private DocumentStorageProperties properties() {
        DocumentStorageProperties properties = new DocumentStorageProperties();
        properties.getKeyVault().setUri("https://v.vault.azure.net");
        return properties;
    }

    @Test
    void theAzureProfileIsProductionSoTheScopedCredentialIsUsed() {
        TokenCredential credential = config.azureCredential(properties(),
                new MockEnvironment().withProperty("spring.profiles.active", "azure"));

        assertThat(credential).isInstanceOf(ManagedIdentityCredential.class);
    }

    /**
     * The paired negative, so this is a statement about the azure profile rather than about every
     * environment: a developer on no profile still gets the chain, which is the only thing that
     * finds an Azure CLI login.
     */
    @Test
    void anUnprofiledEnvironmentStillGetsTheChain() {
        assertThat(config.azureCredential(properties(), new MockEnvironment()))
                .isInstanceOf(DefaultAzureCredential.class);
    }

    /**
     * The other two guards that keyed off the same check. Neither had ever been armed in the
     * environment it was written for - "not permitted in production" was true of a production the
     * code could not recognise.
     */
    @Test
    void localBackendsAreRefusedUnderTheAzureProfile() {
        MockEnvironment azure = new MockEnvironment().withProperty("spring.profiles.active", "azure");

        DocumentStorageProperties localKeys = properties();
        localKeys.setKeys(DocumentStorageProperties.KeyBackend.LOCAL);
        assertThat(catchIllegalState(() -> config.keyProvider(localKeys, azure, mockCredential())))
                .contains("not permitted in production");

        DocumentStorageProperties localStorage = properties();
        localStorage.setStorage(DocumentStorageProperties.StorageBackend.LOCAL);
        assertThat(catchIllegalState(() -> config.storageProvider(localStorage, azure, mockCredential())))
                .contains("not permitted in production");
    }

    /**
     * The override Pam needs to apply in App Service without a redeploy, pinned in both the forms
     * she could type it in.
     *
     * <p>Written because the answer was going into a production configuration change on my word.
     * <b>Guessing a property name for someone else to apply to production is not a thing to do from
     * memory</b>, and the environment-variable mapping in particular is a relaxed-binding rule
     * rather than a literal one - the hyphen in {@code key-vault} does not survive naively.
     */
    @Test
    void theCredentialSourceCanBeOverriddenFromAnAppServiceSetting() {
        for (String variableName : java.util.List.of(
                "APP_DOCUMENTS_KEYVAULT_CREDENTIAL",
                "APP_DOCUMENTS_KEY_VAULT_CREDENTIAL",
                "APP_DOCUMENTS_KEY-VAULT_CREDENTIAL")) {
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().replace(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Map.of(variableName, "managed-identity")));

            assertThat(Binder.get(environment)
                    .bindOrCreate("app.documents", DocumentStorageProperties.class)
                    .getKeyVault().getCredential())
                    .as("%s must bind - Pam applies this to App Service by hand", variableName)
                    .isEqualTo(DocumentStorageProperties.KeyVault.CredentialSource.MANAGED_IDENTITY);
        }
    }

    /**
     * The control for the test above. Relaxed binding is lenient enough that a probe with a
     * mis-registered property source reported every candidate as "does not bind", including the ones
     * that do - so this asserts the plain dotted property binds, which is the input that must work
     * whatever happens to the environment-variable mapping.
     */
    @Test
    void thePlainPropertyBindsWhichIsWhatMakesTheProbeAboveMeaningful() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("app.documents.key-vault.credential", "managed-identity")));

        assertThat(Binder.get(environment)
                .bindOrCreate("app.documents", DocumentStorageProperties.class)
                .getKeyVault().getCredential())
                .isEqualTo(DocumentStorageProperties.KeyVault.CredentialSource.MANAGED_IDENTITY);
    }

    private TokenCredential mockCredential() {
        return org.mockito.Mockito.mock(TokenCredential.class);
    }

    private String catchIllegalState(Runnable action) {
        try {
            action.run();
            return "";
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }
}
