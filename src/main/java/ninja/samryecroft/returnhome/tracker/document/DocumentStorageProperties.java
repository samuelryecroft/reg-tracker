package ninja.samryecroft.returnhome.tracker.document;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for where report documents are stored and who holds the keys.
 *
 * <p>Note what is <em>not</em> here: there is no switch to turn encryption off. The two knobs
 * choose custody (local filesystem/derived keys for development, Azure Blob/Key Vault for
 * deployment); both paths encrypt, so the code under test is the code that runs in production.
 */
@ConfigurationProperties(prefix = "app.documents")
public class DocumentStorageProperties {

    public enum StorageBackend { LOCAL, AZURE_BLOB }

    public enum KeyBackend { LOCAL, KEY_VAULT }

    private StorageBackend storage = StorageBackend.LOCAL;
    private KeyBackend keys = KeyBackend.LOCAL;

    private final Local local = new Local();
    private final LocalKeys localKeys = new LocalKeys();
    private final Blob blob = new Blob();
    private final KeyVault keyVault = new KeyVault();

    public StorageBackend getStorage() {
        return storage;
    }

    public void setStorage(StorageBackend storage) {
        this.storage = storage;
    }

    public KeyBackend getKeys() {
        return keys;
    }

    public void setKeys(KeyBackend keys) {
        this.keys = keys;
    }

    public Local getLocal() {
        return local;
    }

    public LocalKeys getLocalKeys() {
        return localKeys;
    }

    public Blob getBlob() {
        return blob;
    }

    public KeyVault getKeyVault() {
        return keyVault;
    }

    public static class Local {
        private String directory;

        public String getDirectory() {
            return directory;
        }

        public void setDirectory(String directory) {
            this.directory = directory;
        }
    }

    public static class LocalKeys {
        /**
         * Deliberately no default. An unset secret fails startup rather than deriving every
         * organisation's KEK from a well-known value - the same posture the bootstrap admin
         * password takes.
         */
        private String masterSecret;

        public String getMasterSecret() {
            return masterSecret;
        }

        public void setMasterSecret(String masterSecret) {
            this.masterSecret = masterSecret;
        }
    }

    public static class Blob {
        /** Set for Azurite and local development; production authenticates as a managed identity instead. */
        private String connectionString;
        private String endpoint;
        private String container = "report-documents";
        /** Azurite starts empty, so development needs this; production containers come from IaC. */
        private boolean createContainerIfMissing = false;

        public String getConnectionString() {
            return connectionString;
        }

        public void setConnectionString(String connectionString) {
            this.connectionString = connectionString;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getContainer() {
            return container;
        }

        public void setContainer(String container) {
            this.container = container;
        }

        public boolean isCreateContainerIfMissing() {
            return createContainerIfMissing;
        }

        public void setCreateContainerIfMissing(boolean createContainerIfMissing) {
            this.createContainerIfMissing = createContainerIfMissing;
        }
    }

    public static class KeyVault {
        private String uri;
        /**
         * RSA-OAEP-256 by default: symmetric ({@code oct}) keys are Managed HSM only, and Managed
         * HSM is out of proportion to this deployment. See {@link KeyVaultKeyProvider}.
         */
        private String wrapAlgorithm = "RSA-OAEP-256";
        /**
         * Lazily create {@code org-{id}-kek} on an organisation's first report. Needs Key Vault
         * Crypto Officer; turn it off to run as Crypto User with keys provisioned from IaC.
         */
        private boolean autoCreateKeys = true;

        public String getUri() {
            return uri;
        }

        public void setUri(String uri) {
            this.uri = uri;
        }

        public String getWrapAlgorithm() {
            return wrapAlgorithm;
        }

        public void setWrapAlgorithm(String wrapAlgorithm) {
            this.wrapAlgorithm = wrapAlgorithm;
        }

        public boolean isAutoCreateKeys() {
            return autoCreateKeys;
        }

        public void setAutoCreateKeys(boolean autoCreateKeys) {
            this.autoCreateKeys = autoCreateKeys;
        }
    }
}
