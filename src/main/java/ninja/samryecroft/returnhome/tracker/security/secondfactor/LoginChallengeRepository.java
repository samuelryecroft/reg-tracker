package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginChallengeRepository extends JpaRepository<LoginChallenge, Long> {

    /** The newest challenge for this user, live or not - the caller decides what to do about it. */
    Optional<LoginChallenge> findFirstByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * How many challenges this user has been sent recently. This is the resend cap, and it exists so
     * the login form cannot be pointed at a colleague as a mail amplifier.
     */
    @Query("select count(c) from LoginChallenge c where c.userId = :userId and c.createdAt > :since")
    long countIssuedSince(@Param("userId") Long userId, @Param("since") Instant since);

    /**
     * Issuing a new challenge must retire the old one; otherwise two live codes exist for one
     * account and the older one is a credential nobody is watching.
     */
    @Modifying
    @Query("update LoginChallenge c set c.consumedAt = :now "
            + "where c.userId = :userId and c.consumedAt is null")
    int consumeOutstanding(@Param("userId") Long userId, @Param("now") Instant now);

    /** Expired rows have no value at all once they cannot be used - they are swept, not kept. */
    @Modifying
    @Query("delete from LoginChallenge c where c.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
