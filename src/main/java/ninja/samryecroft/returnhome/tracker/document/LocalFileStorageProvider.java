package ninja.samryecroft.returnhome.tracker.document;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Filesystem-backed storage for local development and tests, so the whole encrypted path can be
 * exercised - including by the Testcontainers and Playwright suites - without an Azure account or
 * even Docker.
 *
 * <p>What it stores is still ciphertext: this is not the old plaintext-on-disk behaviour with a new
 * name. The envelope goes in a sibling {@code .meta} properties file, which is this provider's
 * stand-in for Azure blob metadata.
 *
 * <p>Not for production - {@code DocumentStorageConfig} refuses to start a production environment
 * on it, because App Service disk is ephemeral and single-instance.
 */
public class LocalFileStorageProvider implements StorageProvider {

    private static final String METADATA_SUFFIX = ".meta";

    private final Path root;

    public LocalFileStorageProvider(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] content, Map<String, String> metadata) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);

            Properties properties = new Properties();
            properties.putAll(metadata);
            try (OutputStream out = Files.newOutputStream(sidecar(target))) {
                properties.store(out, "Envelope for " + key + " - not secret; the KEK is not here.");
            }
        } catch (IOException e) {
            throw new DocumentSecurityException("Failed to write encrypted report document", e);
        }
    }

    @Override
    public StoredObject get(String key) {
        Path target = resolve(key);
        if (!Files.exists(target)) {
            throw new DocumentNotFoundException("No stored report document for this report");
        }
        Path sidecar = sidecar(target);
        if (!Files.exists(sidecar)) {
            // A document with no envelope cannot be decrypted, and must not be served as-is on the
            // assumption it might be plaintext. That assumption is precisely the failure to avoid.
            throw new DocumentIntegrityException("Stored report document has no envelope");
        }
        try {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(sidecar)) {
                properties.load(in);
            }
            Map<String, String> metadata = new LinkedHashMap<>();
            properties.stringPropertyNames().forEach(name -> metadata.put(name, properties.getProperty(name)));
            return new StoredObject(Files.readAllBytes(target), metadata);
        } catch (IOException e) {
            throw new DocumentSecurityException("Failed to read stored report document", e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolve(key));
    }

    @Override
    public String describe() {
        return "local:" + root;
    }

    private Path resolve(String key) {
        Path resolved = root.resolve(StorageKeys.validated(key)).normalize();
        if (!resolved.startsWith(root)) {
            // Belt and braces behind StorageKeys: the regex already excludes traversal, and this
            // catches anything a future key format change might let through.
            throw new DocumentIntegrityException("Rejected a document storage key outside the store root");
        }
        return resolved;
    }

    private Path sidecar(Path target) {
        return target.resolveSibling(target.getFileName() + METADATA_SUFFIX);
    }
}
