package ninja.samryecroft.returnhome.tracker.security.trusteddevice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One trusted-device token (T365 stage 1, design shared-handoff/T365-DESIGN-trusted-device.md §8).
 *
 * <p>A trusted device waives <em>the {@code SIGN_IN} second factor and nothing else</em> for a fixed
 * window. The cookie carries only the raw token; this row holds its SHA-256 hash (via
 * {@code TokenHashing}, the same scheme as the reset token - a second hashing scheme would be a
 * second thing to get wrong and its divergence would be silent) plus the state the flow needs.
 *
 * <p><b>Rotation, and why a device is a short chain of rows rather than one mutated row.</b> Every
 * use mints a fresh token and retires the one presented ({@link #revoke(Instant)}), keeping the
 * absolute {@link #expiresAt}. That is the only mechanism here that can <em>detect</em> theft rather
 * than merely bound it: a copied cookie is usable at most until the real device next signs in, and a
 * presented-but-already-revoked token is positive evidence of reuse. Detecting that requires the
 * retired token to still be FINDABLE by its hash, so a retired row is kept (its {@code revokedAt}
 * set), not deleted - a missing row could not be told apart from ordinary garbage. The successor
 * inherits {@link #createdAt} and {@link #expiresAt} unchanged, so at any time one active row per
 * device carries the device's true first-trust date and its unmoving expiry.
 *
 * <p>This class carries no endpoints and no policy (stage 1 is entity + repository + service only).
 * The break-glass refusal, the {@code SIGN_IN}-only bound, and the absolute expiry live in
 * {@code TrustedDeviceService}; this entity just holds the state that flow moves through.
 */
@Entity
@Table(name = "trusted_devices")
public class TrustedDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 100)
    private String tokenHash;

    /** First-trust instant, carried forward unchanged across rotations (see class javadoc). */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Absolute expiry, 30 days from first trust. NEVER slides; rotation carries it forward as-is. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "user_agent_summary", length = 200)
    private String userAgentSummary;

    /** Set when the token value is retired - by rotation (superseded) or by outright revocation. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected TrustedDevice() {
    }

    /**
     * Mint: a device is trusted for the first time. {@code createdAt} and {@code expiresAt} are the
     * device's birth and its absolute death; both are frozen from here and only copied forward when
     * the token rotates.
     */
    public TrustedDevice(Long userId, String tokenHash, Instant createdAt, Instant expiresAt,
            String userAgentSummary) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.userAgentSummary = userAgentSummary;
    }

    /** Retire this token value: rotation (superseded) and explicit revocation both land here. */
    public void revoke(Instant when) {
        this.revokedAt = when;
    }

    /** Live: neither revoked nor past its absolute expiry. Expiry is by {@code now}, never extended. */
    public boolean isLive(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    void markUsed(Instant when) {
        this.lastUsedAt = when;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public String getUserAgentSummary() {
        return userAgentSummary;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }
}
