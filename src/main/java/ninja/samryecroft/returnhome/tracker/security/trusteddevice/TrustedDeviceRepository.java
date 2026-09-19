package ninja.samryecroft.returnhome.tracker.security.trusteddevice;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Read/append/revoke for {@link TrustedDevice} (T365 stage 1). A retired row is KEPT (its
 * {@code revokedAt} set) rather than deleted, so a replay of a rotated token meets a revoked row
 * rather than a missing one - that is what turns rotation into theft <em>detection</em> (design §4).
 * Only the expiry sweep removes rows, in bulk, once they can no longer be used or replayed.
 */
public interface TrustedDeviceRepository extends JpaRepository<TrustedDevice, Long> {

    /**
     * The lookup, by SHA-256 hash of the cookie token. Served by the UNIQUE index on
     * {@code token_hash}. Returns the row whatever its state - the caller ({@code TrustedDeviceService})
     * decides live vs revoked vs expired, because a revoked row that is presented is not a miss, it is
     * the theft signal.
     */
    Optional<TrustedDevice> findByTokenHash(String tokenHash);

    /** Every live (not-yet-revoked) trust for a user. Used to revoke the whole set on a theft signal. */
    List<TrustedDevice> findByUserIdAndRevokedAtIsNull(Long userId);

    /**
     * Revoke every currently-live trust for a user in one statement (password change, "sign out
     * everywhere", or a detected replay). A bulk UPDATE, so it works whether or not the user is signed
     * in anywhere - revocation must be a database operation, not built on the in-memory session
     * registry, because the rows then die on every instance after any scale-out (design §7).
     */
    @Modifying
    @Query("update TrustedDevice d set d.revokedAt = :now where d.userId = :userId and d.revokedAt is null")
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * The expiry sweep. Deletes rows that are past their absolute expiry - only then are they beyond
     * both use and replay-detection. Runs on a schedule (wired in a later stage), not on the request
     * path.
     */
    @Modifying
    @Query("delete from TrustedDevice d where d.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
