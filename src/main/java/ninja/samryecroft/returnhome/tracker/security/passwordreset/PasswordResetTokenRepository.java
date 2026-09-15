package ninja.samryecroft.returnhome.tracker.security.passwordreset;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Read/append/consume for {@link PasswordResetToken} (T353c). No delete of individual tokens - a
 * consumed token is KEPT (its {@code consumedAt} set) so a replay meets a consumed row rather than a
 * missing one; only the expiry sweep removes rows, in bulk, once they can no longer be used.
 */
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    /**
     * The lookup, by the SHA-256 hash of the token in the emailed link. Served by the UNIQUE index on
     * {@code token_hash}. Returns the row whatever its state - the caller ({@code PasswordResetService},
     * T353e) decides usable vs consumed/expired, and renders all three failures identically so the
     * endpoint never tells a prober which it was.
     */
    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * The expiry sweep. Deleting an expired row is also how its {@code pending_password_hash} is
     * nulled - the row, and the BCrypt hash of a password that was never adopted, go together. Runs
     * on a schedule (wired in T353d/e), not on the request path.
     */
    @Modifying
    @Query("delete from PasswordResetToken t where t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
