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
        /**
         * How the app authenticates to Key Vault and Blob Storage.
         *
         * <p>{@code auto} (the default) means a scoped {@link
         * com.azure.identity.ManagedIdentityCredential} in production and the full
         * {@code DefaultAzureCredential} chain everywhere else. That split is the T181 fix, and it
         * is a split rather than a global switch because the two environments genuinely differ: in
         * production the managed identity is the only source that will ever succeed, while a
         * developer running against a real vault authenticates with the Azure CLI, which only the
         * chain finds.
         *
         * <p>Why it matters: {@code DefaultAzureCredential} walks its sources in order and each
         * unreachable one has to time out before the next is tried. On App Service that cost 6-7
         * seconds per token acquisition, and the first {@code getKey} on a fresh container took
         * 22-33 seconds and then FAILED - measured in App Insights, T181. A scoped credential goes
         * straight to the instance metadata endpoint.
         */
        private CredentialSource credential = CredentialSource.AUTO;
        /**
         * The user-assigned managed identity to authenticate as, when there is one. Left null the
         * system-assigned identity is used. {@code AZURE_CLIENT_ID} is the conventional environment
         * variable and binds to this.
         */
        private String managedIdentityClientId;
        /**
         * Fetch a token and the KEK handle for every active organisation during startup, so the
         * first real request does not pay for it.
         *
         * <p>Runs as an {@code ApplicationRunner}, which completes <em>before</em> readiness is
         * published - so the platform does not route traffic here until it is done, and "no user
         * request pays the cold start" is enforced rather than hoped for.
         */
        private boolean warmKeysOnStartup = true;
        /**
         * How long startup may spend warming before giving up and starting anyway.
         *
         * <p>Bounded because warmup is an optimisation and must never be able to keep the
         * application from becoming ready. Exceeding it is logged and startup continues; the only
         * consequence is that somebody pays the cold start after all, which is the behaviour we had
         * before this existed.
         */
        private java.time.Duration warmupTimeout = java.time.Duration.ofSeconds(30);
        /**
         * How long a fetched KEK handle stays usable before it is looked up again.
         *
         * <p>{@code currentKeyFor} previously called Key Vault on every single encrypted write.
         * Caching it costs at most this much delay in picking up a rotated key version, which is
         * already how this class describes rotation: old data keeps unwrapping with the version
         * recorded in its own envelope, so re-wrapping is catch-up work rather than a migration.
         */
        private java.time.Duration keyHandleTtl = java.time.Duration.ofMinutes(10);
        /**
         * The audience the startup warmup fetches a token for, before anything asks the vault for a
         * key. A property rather than a constant because it is cloud-specific - sovereign and
         * government clouds use different vault hostnames - and getting it wrong should be a config
         * change, not a redeploy.
         */
        private String tokenScope = "https://vault.azure.net/.default";

        public String getTokenScope() {
            return tokenScope;
        }

        public void setTokenScope(String tokenScope) {
            this.tokenScope = tokenScope;
        }

        /** @see #credential */
        public enum CredentialSource { AUTO, MANAGED_IDENTITY, DEFAULT_CHAIN }

        public CredentialSource getCredential() {
            return credential;
        }

        public void setCredential(CredentialSource credential) {
            this.credential = credential;
        }

        public String getManagedIdentityClientId() {
            return managedIdentityClientId;
        }

        public void setManagedIdentityClientId(String managedIdentityClientId) {
            this.managedIdentityClientId = managedIdentityClientId;
        }

        public boolean isWarmKeysOnStartup() {
            return warmKeysOnStartup;
        }

        public void setWarmKeysOnStartup(boolean warmKeysOnStartup) {
            this.warmKeysOnStartup = warmKeysOnStartup;
        }

        public java.time.Duration getWarmupTimeout() {
            return warmupTimeout;
        }

        public void setWarmupTimeout(java.time.Duration warmupTimeout) {
            this.warmupTimeout = warmupTimeout;
        }

        public java.time.Duration getKeyHandleTtl() {
            return keyHandleTtl;
        }

        public void setKeyHandleTtl(java.time.Duration keyHandleTtl) {
            this.keyHandleTtl = keyHandleTtl;
        }

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
