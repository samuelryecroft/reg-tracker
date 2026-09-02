package ninja.samryecroft.returnhome.tracker.document;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The key material and parameters stored <em>alongside</em> a document's ciphertext, so a stored
 * object is self-describing and there is no key table to keep in sync with the files
 * (DOCUMENT-ENCRYPTION-DESIGN.md §2a).
 *
 * <p>It is modelled on Azure blob metadata and encodes to a flat string map for that reason: names
 * are lowercase and identifier-safe (Azure normalises metadata names and rejects the rest), values
 * are base64 or plain digits so they survive being carried in HTTP headers.
 *
 * <p><strong>The GCM authentication tag is not a field here.</strong> The JCE appends it to the
 * ciphertext, so it travels in the blob body rather than the metadata. That is the same guarantee
 * the design asked for - tamper the body or truncate it and the tag check fails - just not a
 * separate metadata entry. Splitting it out would mean hand-rolling the tag handling for nothing.
 *
 * @param version       envelope format version, so a future format change is detectable rather
 *                      than a silent misparse
 * @param algorithm     the content cipher, e.g. {@code AES_256_GCM}
 * @param iv            the per-file nonce; unique per encryption, never reused under one data key
 * @param organisationId the organisation whose KEK wraps {@code wrappedKey}
 * @param wrappedKey    the data key, encrypted under that organisation's KEK
 */
public record DocumentEnvelope(int version, String algorithm, byte[] iv, long organisationId,
        WrappedKey wrappedKey) {

    public static final int CURRENT_VERSION = 1;
    public static final String AES_256_GCM = "AES_256_GCM";

    private static final String M_VERSION = "encv";
    private static final String M_ALGORITHM = "encalg";
    private static final String M_IV = "enciv";
    private static final String M_ORG = "encorg";
    private static final String M_KEY_NAME = "enckeyname";
    private static final String M_KEY_VERSION = "enckeyversion";
    private static final String M_WRAP_ALGORITHM = "encwrapalg";
    private static final String M_WRAPPED_KEY = "encwrappedkey";

    public Map<String, String> toMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put(M_VERSION, Integer.toString(version));
        metadata.put(M_ALGORITHM, algorithm);
        metadata.put(M_IV, Base64.getEncoder().encodeToString(iv));
        metadata.put(M_ORG, Long.toString(organisationId));
        metadata.put(M_KEY_NAME, wrappedKey.keyName());
        metadata.put(M_KEY_VERSION, wrappedKey.keyVersion());
        metadata.put(M_WRAP_ALGORITHM, wrappedKey.wrapAlgorithm());
        metadata.put(M_WRAPPED_KEY, Base64.getEncoder().encodeToString(wrappedKey.material()));
        return metadata;
    }

    /**
     * @throws DocumentIntegrityException if anything is missing or unparseable. Deliberately not a
     *         lenient parse with defaults: a half-read envelope must fail closed, not decrypt with
     *         a guessed parameter.
     */
    public static DocumentEnvelope fromMetadata(Map<String, String> raw) {
        // Azure lowercases metadata names on the way back out; normalising here means the local
        // provider and Blob behave identically instead of only one of them working.
        Map<String, String> metadata = new LinkedHashMap<>();
        if (raw != null) {
            raw.forEach((k, v) -> metadata.put(k.toLowerCase(Locale.ROOT), v));
        }

        int version = parseInt(required(metadata, M_VERSION), M_VERSION);
        if (version != CURRENT_VERSION) {
            throw new DocumentIntegrityException("Unsupported document envelope version " + version);
        }
        return new DocumentEnvelope(version,
                required(metadata, M_ALGORITHM),
                decode(required(metadata, M_IV), M_IV),
                parseLong(required(metadata, M_ORG), M_ORG),
                new WrappedKey(required(metadata, M_KEY_NAME), required(metadata, M_KEY_VERSION),
                        required(metadata, M_WRAP_ALGORITHM),
                        decode(required(metadata, M_WRAPPED_KEY), M_WRAPPED_KEY)));
    }

    /**
     * The additional authenticated data bound into the AES-GCM tag. It covers the envelope version,
     * the owning organisation and the storage key, none of which are secret - the point is that
     * they cannot be changed. Relabel a blob as another organisation's, or copy the bytes to a
     * different key, and decryption fails on the tag instead of quietly succeeding. That is the
     * cryptographic half of the cross-organisation guard; {@link KeyProvider#unwrap} is the other.
     */
    public static byte[] additionalAuthenticatedData(int version, long organisationId, String storageKey) {
        return ("rht-doc-v" + version + "|org=" + organisationId + "|key=" + storageKey)
                .getBytes(StandardCharsets.UTF_8);
    }

    public byte[] additionalAuthenticatedData(String storageKey) {
        return additionalAuthenticatedData(version, organisationId, storageKey);
    }

    private static String required(Map<String, String> metadata, String name) {
        String value = metadata.get(name);
        if (value == null || value.isBlank()) {
            throw new DocumentIntegrityException("Stored document envelope is missing '" + name + "'");
        }
        return value;
    }

    private static byte[] decode(String value, String name) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new DocumentIntegrityException("Stored document envelope has a malformed '" + name + "'");
        }
    }

    private static int parseInt(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new DocumentIntegrityException("Stored document envelope has a malformed '" + name + "'");
        }
    }

    private static long parseLong(String value, String name) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new DocumentIntegrityException("Stored document envelope has a malformed '" + name + "'");
        }
    }
}
