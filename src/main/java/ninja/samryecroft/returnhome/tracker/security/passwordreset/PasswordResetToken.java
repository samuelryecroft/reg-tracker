package ninja.samryecroft.returnhome.tracker.security.passwordreset;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One self-service password-reset request (T353c, design §3).
 *
 * <p>The token that goes in the emailed link is never held here - only {@code tokenHash}, the
 * SHA-256 of it - so a database read, a backup, or a stray log line cannot yield a usable reset
 * link. Lookup is by that hash, which is why it is SHA-256 and not BCrypt: a 256-bit random token
 * cannot be brute-forced regardless, and a per-row BCrypt salt would make the hash unindexable.
 *
 * <p>The chosen new password lives in {@code pendingPasswordHash} (already BCrypt-encoded) between
 * step C (submit) and step D (apply), and NOWHERE else - never plaintext, never on {@code users}
 * until the reset code verifies. Because it is a password that may never be adopted, it must be
 * nulled the moment the token is consumed and swept with the row on expiry; {@link #consume(Instant)}
 * does the former, the repository's expiry delete the latter.
 *
 * <p>This class carries no endpoints and no policy (T353c is entity + repository only). The ordering
 * invariant - that the password cannot be applied until the reset code verifies - is enforced in the
 * reset service (T353e) by the fact that only its verifier can mint the grant that reaches step D;
 * this entity just holds the state that flow moves through.
 */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 100)
    private String tokenHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    /**
     * The chosen new password, BCrypt-encoded, held only between step C and step D. Null until step
     * C, and nulled again on consume - a password that may never be adopted must not sit at rest any
     * longer than the flow needs it.
     */
    @Column(name = "pending_password_hash", length = 100)
    private String pendingPasswordHash;

    /** The reset-scoped {@code login_challenges} row (purpose PASSWORD_RESET) minted in step C. */
    @Column(name = "challenge_id")
    private Long challengeId;

    @Column(name = "requested_ip", length = 45)
    private String requestedIp;

    protected PasswordResetToken() {
    }

    /** Step A: a reset is requested. The pending password and challenge are attached later, in step C. */
    public PasswordResetToken(Long userId, String tokenHash, Instant expiresAt, String requestedIp) {
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.requestedIp = requestedIp;
    }

    /**
     * Step C: the chosen new password (BCrypt-encoded) and the reset-scoped challenge are recorded on
     * the token. The password is not applied here - it waits in {@code pendingPasswordHash} until the
     * code for {@code challengeId} verifies (step D).
     */
    public void attachPendingReset(String pendingPasswordHash, Long challengeId) {
        this.pendingPasswordHash = pendingPasswordHash;
        this.challengeId = challengeId;
    }

    /**
     * Single use, and it drops the pending password in the same step. Keeping the row (rather than
     * deleting it) makes a replay meet a CONSUMED token; nulling the hash makes sure the kept row
     * never holds a password that has already been adopted or abandoned.
     */
    public void consume(Instant when) {
        this.consumedAt = when;
        this.pendingPasswordHash = null;
    }

    /** Neither consumed nor expired. Attempt caps live on the challenge, not here. */
    public boolean isUsable(Instant now) {
        return consumedAt == null && now.isBefore(expiresAt);
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

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public String getPendingPasswordHash() {
        return pendingPasswordHash;
    }

    public Long getChallengeId() {
        return challengeId;
    }

    public String getRequestedIp() {
        return requestedIp;
    }
}
