package ninja.samryecroft.returnhome.tracker.document;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import java.util.Map;

/**
 * Azure Blob Storage, for staging and production - and, unchanged, for the Azurite emulator during
 * development, which is why this can be built and tested before any Azure resource exists (WS-D).
 *
 * <p>It only ever handles ciphertext. The container is private and the storage account's own
 * service-side encryption still applies underneath, but neither of those is what protects the
 * documents here: a leaked account key or SAS reads blobs straight through service-side encryption,
 * and gets nothing but ciphertext from these ones. That is the whole point of encrypting in the
 * application (DOCUMENT-ENCRYPTION-DESIGN.md threat T1).
 */
public class AzureBlobStorageProvider implements StorageProvider {

    private final BlobContainerClient containerClient;

    public AzureBlobStorageProvider(BlobContainerClient containerClient) {
        this.containerClient = containerClient;
    }

    @Override
    public void put(String key, byte[] content, Map<String, String> metadata) {
        BlobClient blob = containerClient.getBlobClient(StorageKeys.validated(key));
        try {
            // One call, so the envelope lands atomically with the bytes. Uploading first and
            // setting metadata after would leave a window in which a blob exists that nothing can
            // ever decrypt.
            blob.uploadWithResponse(new BlobParallelUploadOptions(BinaryData.fromBytes(content))
                    .setMetadata(metadata), null, Context.NONE);
        } catch (HttpResponseException e) {
            throw new DocumentSecurityException("Failed to store the encrypted report document", e);
        }
    }

    @Override
    public StoredObject get(String key) {
        BlobClient blob = containerClient.getBlobClient(StorageKeys.validated(key));
        try {
            Map<String, String> metadata = blob.getProperties().getMetadata();
            return new StoredObject(blob.downloadContent().toBytes(), metadata);
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) {
                throw new DocumentNotFoundException("No stored report document for this report");
            }
            throw new DocumentSecurityException("Failed to read the stored report document", e);
        } catch (HttpResponseException e) {
            throw new DocumentSecurityException("Failed to read the stored report document", e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            return containerClient.getBlobClient(StorageKeys.validated(key)).exists();
        } catch (HttpResponseException e) {
            throw new DocumentSecurityException("Failed to check the stored report document", e);
        }
    }

    @Override
    public String describe() {
        return "azure-blob:" + containerClient.getBlobContainerName();
    }
}
