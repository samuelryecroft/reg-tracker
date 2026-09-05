package ninja.samryecroft.returnhome.tracker.document;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.azure.security.keyvault.keys.KeyClient;
import com.azure.security.keyvault.keys.KeyClientBuilder;
import com.azure.security.keyvault.keys.cryptography.models.KeyWrapAlgorithm;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import ninja.samryecroft.returnhome.tracker.config.DeployedEnvironment;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the storage and key backends, and refuses to start a production environment on the
 * development ones.
 *
 * <p>That guard is the point of this class. Both local implementations are legitimate for
 * development and keep the test suites free of Azure, but either of them reaching production would
 * mean statutory records on ephemeral disk, or every organisation's KEK derived from one secret in
 * application memory. A misconfigured deployment must fail to start rather than run in a state that
 * looks encrypted and is not - the same defence-in-depth reasoning as the demo-profile guard.
 */
@Configuration
@EnableConfigurationProperties(DocumentStorageProperties.class)
public class DocumentStorageConfig {

    private static final Logger log = LoggerFactory.getLogger(DocumentStorageConfig.class);


    @Bean
    StorageProvider storageProvider(DocumentStorageProperties properties, Environment environment,
            TokenCredential credential) {
        boolean production = isProduction(environment);
        if (properties.getStorage() == DocumentStorageProperties.StorageBackend.AZURE_BLOB) {
            return new AzureBlobStorageProvider(blobContainerClient(properties, credential));
        }
        if (production) {
            throw new IllegalStateException("app.documents.storage=local is not permitted in production: "
                    + "App Service disk is ephemeral, so approved reports would be lost on restart. "
                    + "Set app.documents.storage=azure-blob.");
        }
        Path directory = Path.of(require(properties.getLocal().getDirectory(),
                "app.documents.local.directory"));
        log.info("Report documents are encrypted and stored on the local filesystem at {} (development only)",
                directory);
        return new LocalFileStorageProvider(directory);
    }

    @Bean
    KeyProvider keyProvider(DocumentStorageProperties properties, Environment environment,
            TokenCredential credential) {
        boolean production = isProduction(environment);
        if (properties.getKeys() == DocumentStorageProperties.KeyBackend.KEY_VAULT) {
            return keyVaultKeyProvider(properties, credential);
        }
        if (production) {
            throw new IllegalStateException("app.documents.keys=local is not permitted in production: "
                    + "the per-organisation KEKs would be derived in application memory rather than held "
                    + "in Key Vault. Set app.documents.keys=key-vault.");
        }
        log.info("Report document keys are derived locally (development only); production uses Key Vault");
        return new LocalKeyProvider(properties.getLocalKeys().getMasterSecret());
    }

    private KeyProvider keyVaultKeyProvider(DocumentStorageProperties properties, TokenCredential credential) {
        String uri = require(properties.getKeyVault().getUri(), "app.documents.key-vault.uri");
        KeyClient keyClient = new KeyClientBuilder().vaultUrl(uri).credential(credential).buildClient();
        return new KeyVaultKeyProvider(keyClient, credential, uri,
                KeyWrapAlgorithm.fromString(properties.getKeyVault().getWrapAlgorithm()),
                properties.getKeyVault().isAutoCreateKeys(),
                properties.getKeyVault().getKeyHandleTtl());
    }

    /**
     * One credential for the whole application, and a scoped one in production.
     *
     * <p><b>One, because there were two.</b> Key Vault and Blob Storage each built their own
     * {@code DefaultAzureCredential}, and a credential owns its token cache - so the two never
     * shared a token and a cold container acquired one twice. App Insights measured each
     * acquisition at 6-7 seconds (T181). Sharing the bean halves that before anything else is
     * changed, and it is the kind of duplication that is invisible until someone measures it,
     * because both call sites are individually correct.
     *
     * <p><b>Scoped, because the chain is the cost.</b> {@code DefaultAzureCredential} tries its
     * sources in order and each one that cannot answer has to time out first; on App Service only
     * the instance metadata endpoint will ever answer, so the whole walk before it is dead time
     * paid on every fresh container. {@code ManagedIdentityCredential} goes straight there.
     *
     * <p>Not applied outside production, deliberately: a developer running against a real vault
     * authenticates through the Azure CLI, which only the chain finds. Overridable either way with
     * {@code app.documents.key-vault.credential}, and the choice is logged, because a credential
     * that silently picked the wrong source is exactly the failure this is meant to remove.
     */
    /**
     * Only registered when there is something to warm: a local key provider derives its keys in
     * memory, so there is no token and no vault round trip to pay for, and a runner that did
     * nothing would still have to be read and explained by everyone who met it.
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.documents.key-vault", name = "warm-keys-on-startup",
            havingValue = "true", matchIfMissing = true)
    ApplicationRunner keyWarmupRunner(DocumentStorageProperties properties, KeyProvider keyProvider,
            OrganisationRepository organisationRepository) {
        if (properties.getKeys() != DocumentStorageProperties.KeyBackend.KEY_VAULT) {
            return args -> { };
        }
        return new KeyWarmupRunner(keyProvider, organisationRepository,
                properties.getKeyVault().getWarmupTimeout());
    }

    @Bean
    TokenCredential azureCredential(DocumentStorageProperties properties, Environment environment) {
        DocumentStorageProperties.KeyVault keyVault = properties.getKeyVault();
        String clientId = keyVault.getManagedIdentityClientId();
        boolean managedIdentity = switch (keyVault.getCredential()) {
            case MANAGED_IDENTITY -> true;
            case DEFAULT_CHAIN -> false;
            case AUTO -> isProduction(environment);
        };
        if (managedIdentity) {
            ManagedIdentityCredentialBuilder builder = new ManagedIdentityCredentialBuilder();
            if (clientId != null && !clientId.isBlank()) {
                builder.clientId(clientId);
            }
            log.info("Authenticating to Azure with the {} managed identity, no credential chain walk",
                    (clientId == null || clientId.isBlank()) ? "system-assigned" : "user-assigned");
            return builder.build();
        }
        log.info("Authenticating to Azure with the DefaultAzureCredential chain (non-production)");
        return new DefaultAzureCredentialBuilder().build();
    }

    private BlobContainerClient blobContainerClient(DocumentStorageProperties properties,
            TokenCredential credential) {
        DocumentStorageProperties.Blob blob = properties.getBlob();
        BlobServiceClientBuilder builder = new BlobServiceClientBuilder();
        if (blob.getConnectionString() != null && !blob.getConnectionString().isBlank()) {
            // Azurite and local development. Production sets an endpoint instead and authenticates
            // as its managed identity, so no storage credential ever exists to be leaked.
            builder.connectionString(blob.getConnectionString());
        } else {
            // The shared credential (see azureCredential): a second one here would keep its own
            // token cache and pay the acquisition again.
            builder.endpoint(require(blob.getEndpoint(), "app.documents.blob.endpoint"))
                    .credential(credential);
        }
        BlobServiceClient serviceClient = builder.buildClient();
        BlobContainerClient container = serviceClient.getBlobContainerClient(blob.getContainer());
        if (blob.isCreateContainerIfMissing()) {
            container.createIfNotExists();
        }
        return container;
    }

    /**
     * Production is asserted by an explicit marker rather than inferred from the absence of others,
     * so a deployment that forgets to set it fails the {@code prod} checks loudly in staging rather
     * than silently passing them in production.
     */
    /**
     * Delegated to {@link DeployedEnvironment}, the single answer to this question.
     *
     * <p>This held its own {@code {prod, production}} and had therefore never fired in production,
     * where the profile is {@code azure} - so all three checks that key off it were inert: the
     * credential source, and the refusals of local keys and local storage.
     */
    private boolean isProduction(Environment environment) {
        return DeployedEnvironment.isDeployed(environment);
    }

    private String require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be set");
        }
        return value;
    }
}
