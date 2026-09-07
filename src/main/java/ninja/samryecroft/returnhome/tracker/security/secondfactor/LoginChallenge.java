package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One issued second-factor code: the state between "the password was right" and "this session is
 * authenticated".
 *
 * <p>The code itself is never held here - only {@code codeHash} - so reading this table, a backup of
 * it, or a log line that dumped a row cannot yield a live code.
 *
 * <p>There is no email column on purpose. The destination is read from {@code users.email} at send
 * time, so a challenge can never be delivered to an address the account no longer has.
 */
@Entity
@Table(name = "login_challenges")
public class LoginChallenge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "code_hash", nullable = false, length = 100)
    private String codeHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /**
     * Set when the code is accepted. Single use is enforced by this column rather than by deleting
     * the row, so a replay attempt meets a <em>consumed</em> challenge - a visible fact - instead of
     * a missing one, which is indistinguishable from an expiry.
     */
    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    protected LoginChallenge() {
    }

    public LoginChallenge(Long userId, String codeHash, Instant expiresAt) {
        this.userId = userId;
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getCodeHash() {
        return codeHash;
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

    public int getAttempts() {
        return attempts;
    }

    public void recordAttempt() {
        this.attempts++;
    }

    public void consume(Instant when) {
        this.consumedAt = when;
    }

    /**
     * Burned rather than merely expired: a challenge that has been consumed, has run out of
     * attempts, or has timed out is dead for all three reasons at once as far as a caller is
     * concerned. Keeping the three conditions in one place is what stops a later caller checking two
     * of them.
     */
    public boolean isUsable(Instant now, int maxAttempts) {
        return consumedAt == null
                && attempts < maxAttempts
                && now.isBefore(expiresAt);
    }
}
