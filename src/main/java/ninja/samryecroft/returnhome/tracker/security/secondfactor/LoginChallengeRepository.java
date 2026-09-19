package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import java.time.Instant;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginChallengeRepository extends JpaRepository<LoginChallenge, Long> {

    /**
     * The newest challenge for this user IN THIS FLOW, live or not - the caller decides what to do
     * about it. Purpose-scoped (T353b): the verifier must never be handed a code minted for a
     * different flow, or a sign-in code would satisfy a reset and vice versa.
     */
    Optional<LoginChallenge> findFirstByUserIdAndPurposeOrderByCreatedAtDesc(Long userId,
            ChallengePurpose purpose);

    /**
     * The same row as the finder above, but locked for update for the rest of the transaction
     * (T380). {@code verify} reads the attempt count, increments it and writes it back; two
     * verifications running at once both read the same count and both write count-plus-one, so
     * under enough concurrency the cap that burns a challenge after five wrong codes was never
     * reached. The lock serialises them on the row: the second waits, then reads the first's
     * write. It MUST be the first read of the challenge in the transaction - locking an entity
     * already in the persistence context does not refresh it, so a lock taken after an unlocked
     * read would guard stale state.
     *
     * <p>Only for a read-write transaction: the password-reset flow's own reads stay on the
     * unlocked finder because a {@code FOR UPDATE} inside a read-only transaction is refused by
     * PostgreSQL.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from LoginChallenge c where c.userId = :userId and c.purpose = :purpose "
            + "order by c.createdAt desc limit 1")
    Optional<LoginChallenge> lockNewest(@Param("userId") Long userId, @Param("purpose") ChallengePurpose purpose);

    /**
     * How many challenges this user has been sent recently IN THIS FLOW. This is the resend cap, and
     * it exists so the form cannot be pointed at a colleague as a mail amplifier. Purpose-scoped
     * (T353b) for the same reason the lookup is: a reset flood must not exhaust a user's sign-in
     * resend allowance, which would read to them as sign-in being broken.
     */
    @Query("select count(c) from LoginChallenge c "
            + "where c.userId = :userId and c.purpose = :purpose and c.createdAt > :since")
    long countIssuedSince(@Param("userId") Long userId, @Param("purpose") ChallengePurpose purpose,
            @Param("since") Instant since);

    /**
     * Issuing a new challenge must retire the old one IN THE SAME FLOW; otherwise two live codes
     * exist for one account and the older one is a credential nobody is watching. Purpose-scoped
     * (T353b): issuing a reset code must NOT retire a live sign-in code, and vice versa - the two
     * flows keep separate live codes.
     */
    @Modifying
    @Query("update LoginChallenge c set c.consumedAt = :now "
            + "where c.userId = :userId and c.purpose = :purpose and c.consumedAt is null")
    int consumeOutstanding(@Param("userId") Long userId, @Param("purpose") ChallengePurpose purpose,
            @Param("now") Instant now);

    /** Expired rows have no value at all once they cannot be used - they are swept, not kept. */
    @Modifying
    @Query("delete from LoginChallenge c where c.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
