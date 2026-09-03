package ninja.samryecroft.returnhome.tracker.fieldcrypto;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * One organisation's field data-encryption key, held <strong>wrapped</strong> by that
 * organisation's Key Vault KEK. The unwrapped key exists only in memory, in
 * {@link FieldKeyService}'s cache, and is never written anywhere.
 *
 * <p>Why a stored per-organisation DEK at all, when the document path wraps a fresh key per file:
 * a document affords one Key Vault round trip per download, a column does not. Listing fifty
 * children would be fifty unwrap calls - slow, and a fail-closed cliff the moment Key Vault
 * hiccups. One wrapped key per organisation turns that into one call per organisation per cache
 * TTL, with per-row cost reduced to AES-GCM in memory.
 *
 * <p>Rotation stays cheap for the same reason it does for documents: rotating an organisation's KEK
 * re-wraps this single row. Not one encrypted column is rewritten.
 */
@Entity
@Table(name = "org_field_key")
public class OrgFieldKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** One key per organisation; the column carries a unique constraint. */
    @Column(name = "organisation_id", nullable = false, unique = true)
    private Long organisationId;

    /**
     * The KEK that wrapped it. Cross-checked against the independently resolved organisation before
     * any unwrap, exactly as the document envelope does - so a row edited to point at another
     * organisation's key fails rather than decrypting.
     */
    @Column(name = "key_name", nullable = false)
    private String keyName;

    /** The KEK version, so rotating the KEK never strands an already-wrapped data key. */
    @Column(name = "key_version", nullable = false)
    private String keyVersion;

    @Column(name = "wrap_algorithm", nullable = false)
    private String wrapAlgorithm;

    /** The wrapped data key. Never the data key itself. */
    @Column(name = "wrapped_key", nullable = false)
    private byte[] wrappedKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected OrgFieldKey() {
    }

    public OrgFieldKey(Long organisationId, String keyName, String keyVersion, String wrapAlgorithm,
            byte[] wrappedKey) {
        this.organisationId = organisationId;
        this.keyName = keyName;
        this.keyVersion = keyVersion;
        this.wrapAlgorithm = wrapAlgorithm;
        this.wrappedKey = wrappedKey;
    }

    public Long getId() {
        return id;
    }

    public Long getOrganisationId() {
        return organisationId;
    }

    public String getKeyName() {
        return keyName;
    }

    public String getKeyVersion() {
        return keyVersion;
    }

    public String getWrapAlgorithm() {
        return wrapAlgorithm;
    }

    public byte[] getWrappedKey() {
        return wrappedKey;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
