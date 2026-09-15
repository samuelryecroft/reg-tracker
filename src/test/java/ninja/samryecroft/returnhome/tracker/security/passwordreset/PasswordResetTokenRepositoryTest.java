package ninja.samryecroft.returnhome.tracker.security.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * T353c: the password-reset token's persistence and its two load-bearing invariants - the row is
 * found by its hash, and consuming it drops the pending password. No endpoints are exercised here
 * (there are none yet); this pins the state the reset flow will move through.
 */
@SpringBootTest
class PasswordResetTokenRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private PasswordResetTokenRepository tokens;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long userId() {
        User user = new User();
        user.setUsername("t353c-" + System.nanoTime());
        user.setFirstName("Reset");
        user.setLastName("Token");
        user.setEmail(user.getUsername() + "@example.test");
        user.setPassword(passwordEncoder.encode("correct-horse-battery-staple"));
        user.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        user.setEnabled(true);
        return userRepository.save(user).getId();
    }

    @Test
    void aTokenIsFoundByItsHashAndNotByAnother() {
        Instant now = Instant.now();
        tokens.save(new PasswordResetToken(userId(), "hash-abc", now.plus(30, ChronoUnit.MINUTES), "203.0.113.7"));

        assertThat(tokens.findByTokenHash("hash-abc")).isPresent();
        assertThat(tokens.findByTokenHash("hash-xyz")).isEmpty();
    }

    @Test
    void usableUntilItExpiresOrIsConsumed() {
        Instant now = Instant.now();
        PasswordResetToken live = new PasswordResetToken(userId(), "h-live", now.plus(30, ChronoUnit.MINUTES), null);
        PasswordResetToken expired = new PasswordResetToken(userId(), "h-exp", now.minus(1, ChronoUnit.MINUTES), null);

        assertThat(live.isUsable(now)).isTrue();
        assertThat(expired.isUsable(now)).isFalse();

        live.consume(now);
        assertThat(live.isUsable(now)).as("a consumed token is not usable, even before expiry").isFalse();
    }

    @Test
    void consumingNullsThePendingPasswordHash() {
        Instant now = Instant.now();
        PasswordResetToken token =
                new PasswordResetToken(userId(), "h-pending", now.plus(30, ChronoUnit.MINUTES), null);
        // challengeId left null: this test is about the pending-password-hash invariant, not the
        // challenge link, and a non-null id here would have to reference a real login_challenges row.
        token.attachPendingReset(passwordEncoder.encode("a-new-password-12345"), null);
        PasswordResetToken saved = tokens.save(token);
        assertThat(saved.getPendingPasswordHash()).as("pending hash is set at step C").isNotNull();

        saved.consume(now);
        tokens.save(saved);

        // The whole point: a password that has been adopted or abandoned must not linger at rest on
        // the kept (consumed) row. Read it back to be sure it is null in the database, not just in
        // the in-memory object.
        PasswordResetToken reloaded = tokens.findByTokenHash("h-pending").orElseThrow();
        assertThat(reloaded.getConsumedAt()).isNotNull();
        assertThat(reloaded.getPendingPasswordHash())
                .as("consuming a token must null its pending password hash")
                .isNull();
    }

    @Test
    @Transactional  // the @Modifying sweep needs a tx; its real caller (T353d/e scheduler) is @Transactional
    void theSweepRemovesExpiredRowsAndKeepsLiveOnes() {
        Instant now = Instant.now();
        tokens.save(new PasswordResetToken(userId(), "h-old", now.minus(2, ChronoUnit.HOURS), null));
        tokens.save(new PasswordResetToken(userId(), "h-fresh", now.plus(30, ChronoUnit.MINUTES), null));

        int deleted = tokens.deleteExpiredBefore(now);

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(tokens.findByTokenHash("h-old")).as("expired row is swept - and its pending hash with it").isEmpty();
        assertThat(tokens.findByTokenHash("h-fresh")).as("a live row is left alone").isPresent();
    }
}
