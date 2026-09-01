package ninja.samryecroft.returnhome.tracker.document;

import static org.assertj.core.api.Assertions.assertThat;

import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The production guard. The local storage and key backends are legitimate for development - they
 * are what keeps the Testcontainers and Playwright suites free of Azure - but either of them
 * reaching production would mean approved reports on ephemeral disk, or every organisation's KEK
 * derived in application memory. A deployment configured that way must refuse to start rather than
 * run in a state that looks encrypted and is not.
 */
class DocumentStorageConfigTest {

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of())
                .withUserConfiguration(AppPropertiesConfiguration.class, DocumentStorageConfig.class)
                .withPropertyValues(
                        "app.docx.output-dir=target/test-documents",
                        "app.documents.local-keys.master-secret=a-master-secret-for-tests");
    }

    @Test
    void developmentUsesTheLocalBackends() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(StorageProvider.class)).isInstanceOf(LocalFileStorageProvider.class);
            assertThat(context.getBean(KeyProvider.class)).isInstanceOf(LocalKeyProvider.class);
        });
    }

    @Test
    void productionRefusesLocalFilesystemStorage() {
        runner().withPropertyValues("app.env=prod", "app.documents.keys=key-vault",
                        "app.documents.key-vault.uri=https://example.vault.azure.net")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("app.documents.storage=local is not permitted in production"));
    }

    @Test
    void productionRefusesLocallyDerivedKeys() {
        runner().withPropertyValues("app.env=prod",
                        "app.documents.storage=azure-blob",
                        "app.documents.blob.endpoint=https://example.blob.core.windows.net")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("app.documents.keys=local is not permitted in production"));
    }

    @Test
    void aProductionSpringProfileTripsTheSameGuard() {
        // Both markers are honoured, because a deployment may set one and not the other and the
        // guard is worthless if it can be sidestepped by the one that was forgotten.
        runner().withPropertyValues("spring.profiles.active=azure,prod")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void deploymentWiresTheAzureBackends() {
        runner().withPropertyValues("app.env=prod",
                        "app.documents.storage=azure-blob",
                        "app.documents.blob.endpoint=https://example.blob.core.windows.net",
                        "app.documents.keys=key-vault",
                        "app.documents.key-vault.uri=https://example.vault.azure.net")
                .run(context -> {
                    // Clients are constructed lazily enough that no Azure call is made here - the
                    // point is only that the production wiring resolves.
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(StorageProvider.class)).isInstanceOf(AzureBlobStorageProvider.class);
                    assertThat(context.getBean(KeyProvider.class)).isInstanceOf(KeyVaultKeyProvider.class);
                });
    }

    @Test
    void aMissingKeyVaultUriFailsStartupRatherThanLater() {
        runner().withPropertyValues("app.documents.keys=key-vault")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("app.documents.key-vault.uri must be set"));
    }

    @Configuration
    @EnableConfigurationProperties(AppProperties.class)
    static class AppPropertiesConfiguration {
    }
}
