package ninja.samryecroft.returnhome.tracker.security.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.security.secondfactor.ChallengePurpose;
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
 * T353f - THE ORDERING GUARD, written as a second pair of hands.
 *
 * <p>The invariant (design §6): <b>the stored password cannot change until the code for THIS reset
 * token verifies.</b> {@link ResetFlowTest} already walks the happy path and checks the password is
 * unchanged after step C - but a happy-path test written alongside the code it exercises cannot tell
 * an enforced invariant from a coincidence of routing. This test is deliberately adversarial and
 * deliberately separate: it drives the flow to the end of step C and then attacks <em>every write
 * path a caller can reach</em> with every code short of the verified one, asserting after each that
 * the stored hash is byte-for-byte the one it started with.
 *
 * <p><b>Why this can be trusted:</b> the assertion is not vacuous. {@link
 * #theCorrectCodeForThisTokenDoesApplyTheNewPassword()} proves the same flow really does change the
 * password when the code verifies, and the arming evidence recorded on the T353f pull request shows
 * every "unchanged" assertion turning RED the moment the grant check in {@code verifyAndApply} is
 * removed. A guard nobody has watched fail is a guard nobody has tested.
 *
 * <p>{@link #theOnlyPasswordWriteIsUnreachableWithoutAGrantTheVerifierAloneCanMint()} carries the
 * structural half of T353i into a firable form: it fails if a later change makes {@code
 * PasswordResetGrant} constructable from outside {@link PasswordResetService}, or exposes a
 * non-private method that takes one - the two ways the "absence of the capability, not a check"
 * guarantee could be quietly lost.
 */
@SpringBootTest(properties = {
        "app.security.second-factor.enabled=true",
        "app.security.second-factor.max-attempts=5",
        "app.security.second-factor.transport=capturing"
})
class ThePasswordCannotChangeUntilTheCodeVerifiesTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        CapturingSender capturingSender() {
            return new CapturingSender();
        }
    }

    /** Captures every emitted code - both SIGN_IN and PASSWORD_RESET ride the same sender. */
    static class CapturingSender implements VerificationCodeSender {
        final List<String> codes = new ArrayList<>();

        @Override
        public void send(String emailAddress, String code) {
            codes.add(code);
        }

        String latest() {
            assertThat(codes).as("a code should have been emailed").isNotEmpty();
            return codes.get(codes.size() - 1);
        }
    }

    private static final String OLD_PASSWORD = "old-passphrase-abc-1";
    private static final String NEW_PASSWORD = "brand-new-passphrase-9";
    private static final String NOT_THE_CODE = "000000";

    @Autowired
    private PasswordResetService reset;
    @Autowired
    private PasswordResetTokenRepository tokens;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private SecondFactorService secondFactor;
    @Autowired
    private CapturingSender sender;

    private int userSeq;

    @BeforeEach
    void clear() {
        sender.codes.clear();
    }

    /** A reset flow driven to the END OF STEP C: pending hash stashed, password NOT yet changed. */
    private record ArmedReset(User user, String rawToken, String resetCode, String hashBeforeVerify) {
    }

    private User user() {
        String tag = "t353f-" + System.nanoTime() + "-" + (userSeq++);
        User u = new User();
        u.setUsername(tag);
        u.setFirstName("Guard");
        u.setLastName("Case");
        u.setEmail(tag + "@example.test");
        u.setPassword(passwordEncoder.encode(OLD_PASSWORD));
        u.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        u.setEnabled(true);
        u.markEmailVerified(java.time.LocalDateTime.now());
        return userRepository.save(u);
    }

    /** Mints a token as step A would and returns the RAW token. */
    private String mintToken(Long userId) {
        String raw = "raw-" + System.nanoTime() + "-" + (userSeq++);
        tokens.save(new PasswordResetToken(userId, TokenHashing.sha256Hex(raw),
                Instant.now().plus(30, ChronoUnit.MINUTES), "203.0.113.9"));
        return raw;
    }

    private String currentHash(Long userId) {
        return userRepository.findById(userId).orElseThrow().getPassword();
    }

    /** Drives a fresh user through step C so each adversarial case starts from a clean, live token. */
    private ArmedReset armReset() {
        User u = user();
        String raw = mintToken(u.getId());
        String hashBefore = currentHash(u.getId());
        assertThat(reset.submitNewPassword(raw, NEW_PASSWORD).result())
                .as("step C must accept the new password without applying it")
                .isEqualTo(PasswordResetService.SubmitOutcome.Result.OK);
        assertThat(currentHash(u.getId()))
                .as("the password must be unchanged at the end of step C")
                .isEqualTo(hashBefore);
        return new ArmedReset(u, raw, sender.latest(), hashBefore);
    }

    // -----------------------------------------------------------------------------------------------
    // The battery: at the end of step C, no write path changes the password short of the right code.
    // Each case gets its own armed flow so a burn or consume in one cannot mask another.
    // -----------------------------------------------------------------------------------------------

    @Test
    void aWrongCodeLeavesThePasswordUnchanged() {
        ArmedReset r = armReset();
        assertThat(reset.verifyAndApply(r.rawToken(), NOT_THE_CODE))
                .isEqualTo(SecondFactorService.Outcome.WRONG_CODE);
        assertThat(currentHash(r.user().getId())).isEqualTo(r.hashBeforeVerify());
    }

    @Test
    void anEmptyCodeLeavesThePasswordUnchanged() {
        ArmedReset r = armReset();
        reset.verifyAndApply(r.rawToken(), "");
        assertThat(currentHash(r.user().getId())).isEqualTo(r.hashBeforeVerify());
    }

    @Test
    void aNullCodeLeavesThePasswordUnchanged() {
        ArmedReset r = armReset();
        reset.verifyAndApply(r.rawToken(), null);
        assertThat(currentHash(r.user().getId())).isEqualTo(r.hashBeforeVerify());
    }

    @Test
    void aValidSignInCodeForTheSameUserCannotCompleteAReset() {
        ArmedReset r = armReset();
        // A live SIGN_IN code for the very same user. It is purpose-scoped, so it must never satisfy
        // the reset verifier - the sharpest cross-flow case, and one no single-flow test can see.
        assertThat(secondFactor.issueChallenge(r.user(), ChallengePurpose.SIGN_IN)).isTrue();
        String signInCode = sender.latest();

        assertThat(reset.verifyAndApply(r.rawToken(), signInCode))
                .as("a SIGN_IN code must not verify a PASSWORD_RESET")
                .isNotEqualTo(SecondFactorService.Outcome.PASSED);
        assertThat(currentHash(r.user().getId())).isEqualTo(r.hashBeforeVerify());
    }

    @Test
    void anotherUsersResetCodeCannotCompleteThisReset() {
        ArmedReset victim = armReset();
        ArmedReset attacker = armReset(); // a different user's own live reset code

        assertThat(reset.verifyAndApply(victim.rawToken(), attacker.resetCode()))
                .as("a reset code minted for another user must not apply here")
                .isNotEqualTo(SecondFactorService.Outcome.PASSED);
        assertThat(currentHash(victim.user().getId())).isEqualTo(victim.hashBeforeVerify());
    }

    @Test
    void aTokenWhoseChallengeWasSupersededByASecondResetCannotComplete() {
        User u = user();
        String tokenA = mintToken(u.getId());
        String hashBefore = currentHash(u.getId());
        assertThat(reset.submitNewPassword(tokenA, NEW_PASSWORD).result())
                .isEqualTo(PasswordResetService.SubmitOutcome.Result.OK);
        String codeA = sender.latest();

        // A second reset for the same user retires token A's challenge (issueChallenge consumes the
        // prior PASSWORD_RESET challenge). Token A is now stale on both codes.
        String tokenB = mintToken(u.getId());
        assertThat(reset.submitNewPassword(tokenB, "second-new-passphrase-x").result())
                .isEqualTo(PasswordResetService.SubmitOutcome.Result.OK);
        String codeB = sender.latest();

        assertThat(reset.verifyAndApply(tokenA, codeA))
                .as("token A's own code is no longer the live challenge")
                .isNotEqualTo(SecondFactorService.Outcome.PASSED);
        assertThat(reset.verifyAndApply(tokenA, codeB))
                .as("token B's code belongs to token B, not token A (challenge-mismatch)")
                .isNotEqualTo(SecondFactorService.Outcome.PASSED);
        assertThat(currentHash(u.getId())).isEqualTo(hashBefore);
    }

    @Test
    void aBurnedChallengeCannotBeCompletedEvenWithTheCorrectCode() {
        ArmedReset r = armReset();
        // Five wrong codes (max-attempts=5) burn the challenge.
        for (int i = 0; i < 5; i++) {
            reset.verifyAndApply(r.rawToken(), NOT_THE_CODE);
        }
        assertThat(reset.verifyAndApply(r.rawToken(), r.resetCode()))
                .as("once burned, even the correct code must not apply the reset")
                .isNotEqualTo(SecondFactorService.Outcome.PASSED);
        assertThat(currentHash(r.user().getId())).isEqualTo(r.hashBeforeVerify());
    }

    // -----------------------------------------------------------------------------------------------
    // Anti-vacuity: the same flow really does change the password when the code verifies. Without
    // this, every assertion above could pass simply because the flow never changes anything.
    // -----------------------------------------------------------------------------------------------

    @Test
    void theCorrectCodeForThisTokenDoesApplyTheNewPassword() {
        ArmedReset r = armReset();
        assertThat(reset.verifyAndApply(r.rawToken(), r.resetCode()))
                .isEqualTo(SecondFactorService.Outcome.PASSED);
        String after = currentHash(r.user().getId());
        assertThat(after).isNotEqualTo(r.hashBeforeVerify());
        assertThat(passwordEncoder.matches(NEW_PASSWORD, after)).isTrue();
    }

    // -----------------------------------------------------------------------------------------------
    // The structural guard (T353i, made firable): the single password write on the reset path is
    // reachable only through a grant the verifier alone can mint. This reddens if a later change
    // makes the grant constructable from a controller, or exposes a non-private method taking one.
    // -----------------------------------------------------------------------------------------------

    @Test
    void theOnlyPasswordWriteIsUnreachableWithoutAGrantTheVerifierAloneCanMint() {
        Class<?> grant = Arrays.stream(PasswordResetService.class.getDeclaredClasses())
                .filter(c -> c.getSimpleName().equals("PasswordResetGrant"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("PasswordResetGrant is gone - the invariant's construct"
                        + " has been dismantled"));

        assertThat(grant.isRecord()).as("the grant is a record carrying proof of a passed code").isTrue();
        assertThat(Modifier.isPrivate(grant.getModifiers()))
                .as("the grant record must be private to PasswordResetService")
                .isTrue();
        for (Constructor<?> ctor : grant.getDeclaredConstructors()) {
            assertThat(Modifier.isPrivate(ctor.getModifiers()))
                    .as("no grant constructor may be reachable from outside: " + ctor)
                    .isTrue();
        }

        // The only method that accepts a grant must itself be private - so there is no externally
        // callable door into the password write, only verifyAndApply's internal one.
        for (Method m : PasswordResetService.class.getDeclaredMethods()) {
            if (Arrays.asList(m.getParameterTypes()).contains(grant)) {
                assertThat(Modifier.isPrivate(m.getModifiers()))
                        .as("a method taking a grant must be private: " + m.getName())
                        .isTrue();
            }
        }
    }
}
