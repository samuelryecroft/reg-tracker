package ninja.samryecroft.returnhome.tracker.document;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
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
    private static final List<String> PRODUCTION_MARKERS = List.of("prod", "production");

    @Bean
    StorageProvider storageProvider(DocumentStorageProperties properties, Environment environment) {
        boolean production = isProduction(environment);
        if (properties.getStorage() == DocumentStorageProperties.StorageBackend.AZURE_BLOB) {
            return new AzureBlobStorageProvider(blobContainerClient(properties));
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
    KeyProvider keyProvider(DocumentStorageProperties properties, Environment environment) {
        boolean production = isProduction(environment);
        if (properties.getKeys() == DocumentStorageProperties.KeyBackend.KEY_VAULT) {
            return keyVaultKeyProvider(properties);
        }
        if (production) {
            throw new IllegalStateException("app.documents.keys=local is not permitted in production: "
                    + "the per-organisation KEKs would be derived in application memory rather than held "
                    + "in Key Vault. Set app.documents.keys=key-vault.");
        }
        log.info("Report document keys are derived locally (development only); production uses Key Vault");
        return new LocalKeyProvider(properties.getLocalKeys().getMasterSecret());
    }

    private KeyProvider keyVaultKeyProvider(DocumentStorageProperties properties) {
        String uri = require(properties.getKeyVault().getUri(), "app.documents.key-vault.uri");
        TokenCredential credential = new DefaultAzureCredentialBuilder().build();
        KeyClient keyClient = new KeyClientBuilder().vaultUrl(uri).credential(credential).buildClient();
        return new KeyVaultKeyProvider(keyClient, credential, uri,
                KeyWrapAlgorithm.fromString(properties.getKeyVault().getWrapAlgorithm()),
                properties.getKeyVault().isAutoCreateKeys());
    }

    private BlobContainerClient blobContainerClient(DocumentStorageProperties properties) {
        DocumentStorageProperties.Blob blob = properties.getBlob();
        BlobServiceClientBuilder builder = new BlobServiceClientBuilder();
        if (blob.getConnectionString() != null && !blob.getConnectionString().isBlank()) {
            // Azurite and local development. Production sets an endpoint instead and authenticates
            // as its managed identity, so no storage credential ever exists to be leaked.
            builder.connectionString(blob.getConnectionString());
        } else {
            builder.endpoint(require(blob.getEndpoint(), "app.documents.blob.endpoint"))
                    .credential(new DefaultAzureCredentialBuilder().build());
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
    private boolean isProduction(Environment environment) {
        if (PRODUCTION_MARKERS.contains(String.valueOf(environment.getProperty("app.env")).toLowerCase())) {
            return true;
        }
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> PRODUCTION_MARKERS.contains(profile.toLowerCase()));
    }

    private String require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be set");
        }
        return value;
    }
}
