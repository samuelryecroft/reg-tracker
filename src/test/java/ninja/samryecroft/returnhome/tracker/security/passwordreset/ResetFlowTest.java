package ninja.samryecroft.returnhome.tracker.security.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventRepository;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventType;
import ninja.samryecroft.returnhome.tracker.security.secondfactor.SecondFactorService;
import ninja.samryecroft.returnhome.tracker.security.secondfactor.VerificationCodeSender;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * T353e steps B/C/D. The load-bearing claims: the password does NOT change until the code verifies,
 * the reset code inherits the sign-in attempt cap (so a second code is brute-force protection), a
 * used token cannot be replayed, and completing a reset retires every outstanding token for the user.
 */
@SpringBootTest(properties = {
        "app.security.second-factor.enabled=true",
        "app.security.second-factor.max-attempts=5",
        "app.security.second-factor.transport=capturing"
})
class ResetFlowTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        CapturingSender capturingSender() {
            return new CapturingSender();
        }
    }

    static class CapturingSender implements VerificationCodeSender {
        final List<String> codes = new ArrayList<>();

        @Override
        public void send(String emailAddress, String code) {
            codes.add(code);
        }

        String latest() {
            assertThat(codes).as("a reset code should have been emailed").isNotEmpty();
            return codes.get(codes.size() - 1);
        }
    }

    private static final String OLD_PASSWORD = "old-passphrase-abc-1";
    private static final String NEW_PASSWORD = "brand-new-passphrase-9";

    @Autowired
    private PasswordResetService reset;
    @Autowired
    private PasswordResetTokenRepository tokens;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AuditEventRepository auditEvents;
    @Autowired
    private CapturingSender sender;

    private final String suffix = "-" + System.nanoTime();

    @BeforeEach
    void clear() {
        sender.codes.clear();
    }

    private User user() {
        User u = new User();
        u.setUsername("t353e" + suffix);
        u.setFirstName("Reset");
        u.setLastName("Flow");
        u.setEmail("t353e" + suffix + "@example.test");
        u.setPassword(passwordEncoder.encode(OLD_PASSWORD));
        u.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        u.setEnabled(true);
        u.markEmailVerified(java.time.LocalDateTime.now());
        return userRepository.save(u);
    }

    /** Mints a token for the user as step A would, returning the RAW token. */
    private String mintToken(Long userId) {
        String raw = "raw-token" + suffix + "-" + System.nanoTime();
        tokens.save(new PasswordResetToken(userId, TokenHashing.sha256Hex(raw),
                Instant.now().plus(30, ChronoUnit.MINUTES), "203.0.113.9"));
        return raw;
    }

    private String currentPasswordHash(Long userId) {
        return userRepository.findById(userId).orElseThrow().getPassword();
    }

    @Test
    void theHappyPathAppliesTheNewPasswordOnlyAfterTheCodeVerifies() {
        User u = user();
        String raw = mintToken(u.getId());
        String oldHash = currentPasswordHash(u.getId());

        assertThat(reset.tokenIsUsable(raw)).isTrue();

        // Step C: submit the new password. It must NOT be applied yet.
        assertThat(reset.submitNewPassword(raw, NEW_PASSWORD).result())
                .isEqualTo(PasswordResetService.SubmitOutcome.Result.OK);
        assertThat(currentPasswordHash(u.getId()))
                .as("the password must be unchanged after step C - the code has not verified")
                .isEqualTo(oldHash);

        // Step D: the correct code applies it.
        assertThat(reset.verifyAndApply(raw, sender.latest())).isEqualTo(SecondFactorService.Outcome.PASSED);
        String newHash = currentPasswordHash(u.getId());
        assertThat(newHash).isNotEqualTo(oldHash);
        assertThat(passwordEncoder.matches(NEW_PASSWORD, newHash)).isTrue();
        assertThat(auditEvents.findByEventTypeOrderByOccurredAtDesc(AuditEventType.PASSWORD_RESET_COMPLETED))
                .anyMatch(e -> e.getActorId().equals(u.getId()));
    }

    @Test
    void theResetCodeInheritsTheAttemptCapSoItCannotBeBruteForced() {
        User u = user();
        String raw = mintToken(u.getId());
        String oldHash = currentPasswordHash(u.getId());
        assertThat(reset.submitNewPassword(raw, NEW_PASSWORD).result())
                .isEqualTo(PasswordResetService.SubmitOutcome.Result.OK);
        String correctCode = sender.latest();

        // Five wrong codes (max-attempts=5). The fifth is the one that BURNS the challenge.
        SecondFactorService.Outcome last = null;
        for (int i = 0; i < 5; i++) {
            last = reset.verifyAndApply(raw, "000000");
        }
        assertThat(last).as("the cap fires: the fifth wrong code burns the challenge").isEqualTo(SecondFactorService.Outcome.BURNED);

        // The tripwire: once burned, even the CORRECT code cannot complete the reset (a burned
        // challenge is no longer usable, so it is rejected before the code is even compared), and the
        // password is unchanged. Remove the cap (raise max-attempts) and this reddens, because the
        // fifth wrong code would not burn and the correct code would then PASS and change the password.
        assertThat(reset.verifyAndApply(raw, correctCode))
                .as("a burned reset code must not complete even with the correct code")
                .isNotEqualTo(SecondFactorService.Outcome.PASSED);
        assertThat(currentPasswordHash(u.getId()))
                .as("a brute-forced reset must not change the password")
                .isEqualTo(oldHash);
    }

    @Test
    void aUsedTokenCannotBeReplayed() {
        User u = user();
        String raw = mintToken(u.getId());
        reset.submitNewPassword(raw, NEW_PASSWORD);
        assertThat(reset.verifyAndApply(raw, sender.latest())).isEqualTo(SecondFactorService.Outcome.PASSED);

        // The same token again: consumed, so not usable and not completable.
        assertThat(reset.tokenIsUsable(raw)).isFalse();
        assertThat(reset.verifyAndApply(raw, sender.latest())).isEqualTo(SecondFactorService.Outcome.NO_CHALLENGE);
        assertThat(auditEvents.findByEventTypeOrderByOccurredAtDesc(AuditEventType.PASSWORD_RESET_FAILED))
                .anyMatch(e -> e.getActorId().equals(u.getId()));
    }

    @Test
    void completingAResetConsumesEveryOutstandingTokenForTheUser() {
        User u = user();
        String rawA = mintToken(u.getId());
        String rawB = mintToken(u.getId());  // a second live token for the same user

        reset.submitNewPassword(rawA, NEW_PASSWORD);
        assertThat(reset.verifyAndApply(rawA, sender.latest())).isEqualTo(SecondFactorService.Outcome.PASSED);

        // The sibling token B must have been retired by the completion.
        assertThat(reset.tokenIsUsable(rawB)).as("a completed reset retires the user's other tokens").isFalse();
    }
}
