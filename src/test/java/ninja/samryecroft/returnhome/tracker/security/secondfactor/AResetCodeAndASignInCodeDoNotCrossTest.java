package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
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
 * T353b: a {@code login_challenges} row now carries the flow it was issued for, and the lookup and
 * the consume-outstanding query both filter on it. This is the test the design (§5) named as the one
 * that would catch the hole - and the hole is invisible to any test that exercises ONE flow at a
 * time, because within a single flow the code behaves exactly as before. Only crossing the two
 * reveals it.
 *
 * <p><b>What was wrong before the purpose column.</b> {@code verify} looked up
 * {@code findFirstByUserIdOrderByCreatedAtDesc} - the newest challenge for the user, whatever it was
 * for - and {@code issueChallenge} consumed every outstanding challenge for the user. So a SIGN-IN
 * code satisfied a RESET verifier and vice versa, and issuing either code silently killed the other
 * flow's live code. If reset shipped on top of that, its ordering invariant ("the password cannot
 * change until the code for THIS reset verifies") would have been satisfiable with a code that was
 * never a reset code at all.
 *
 * <p>These tests would all PASS against the old, unscoped queries if reset and sign-in never
 * coexisted for one user. They are written so that they DO coexist.
 */
@SpringBootTest(properties = {
        "app.security.second-factor.enabled=true",
        "app.security.second-factor.max-attempts=5",
        "app.security.second-factor.transport=capturing"
})
class AResetCodeAndASignInCodeDoNotCrossTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class CapturingSenderConfig {
        @Bean
        CapturingSender capturingSender() {
            return new CapturingSender();
        }
    }

    /** Records every code sent, newest last, so each flow's code can be picked out by issue order. */
    static class CapturingSender implements VerificationCodeSender {
        final List<String> codes = new ArrayList<>();

        @Override
        public void send(String emailAddress, String code) {
            codes.add(code);
        }

        String latest() {
            assertThat(codes).as("a code should have been sent").isNotEmpty();
            return codes.get(codes.size() - 1);
        }
    }

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private SecondFactorService secondFactorService;
    @Autowired
    private LoginChallengeRepository challenges;
    @Autowired
    private CapturingSender sender;

    @BeforeEach
    void clearSentCodes() {
        sender.codes.clear();
    }

    private User account() {
        User user = new User();
        user.setUsername("t353b-" + System.nanoTime());
        user.setFirstName("Cross");
        user.setLastName("Flow");
        user.setEmail(user.getUsername() + "@example.test");
        user.setPassword(passwordEncoder.encode("correct-horse-battery-staple"));
        user.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        user.setEnabled(true);
        // Verified so issuing does not walk the unverified-allowance path - irrelevant to what these
        // tests are about, and it keeps the assertions to the one thing they measure.
        user.markEmailVerified(LocalDateTime.now());
        return userRepository.save(user);
    }

    @Test
    void aResetCodeCannotSatisfyTheSignInVerifier() {
        User user = account();

        secondFactorService.issueChallenge(user, ChallengePurpose.PASSWORD_RESET);
        String resetCode = sender.latest();

        // The sign-in verifier does not merely reject the reset code - it cannot even SEE the reset
        // challenge, so it reports "no challenge", not "wrong code". A live reset row is not a live
        // sign-in row.
        assertThat(secondFactorService.verify(user, resetCode, ChallengePurpose.SIGN_IN))
                .isEqualTo(SecondFactorService.Outcome.NO_CHALLENGE);

        // And the same code passes its OWN verifier - proving the code itself was valid and the
        // rejection above was about the flow, not a bad code.
        assertThat(secondFactorService.verify(user, resetCode, ChallengePurpose.PASSWORD_RESET))
                .isEqualTo(SecondFactorService.Outcome.PASSED);
    }

    @Test
    void aSignInCodeCannotSatisfyTheResetVerifier() {
        User user = account();

        secondFactorService.issueChallenge(user, ChallengePurpose.SIGN_IN);
        String signInCode = sender.latest();

        assertThat(secondFactorService.verify(user, signInCode, ChallengePurpose.PASSWORD_RESET))
                .isEqualTo(SecondFactorService.Outcome.NO_CHALLENGE);

        assertThat(secondFactorService.verify(user, signInCode, ChallengePurpose.SIGN_IN))
                .isEqualTo(SecondFactorService.Outcome.PASSED);
    }

    @Test
    void issuingAResetCodeDoesNotConsumeALiveSignInCode() {
        User user = account();

        secondFactorService.issueChallenge(user, ChallengePurpose.SIGN_IN);
        String signInCode = sender.latest();

        // Starting a reset must not retire the sign-in code the user may be holding in their hand.
        secondFactorService.issueChallenge(user, ChallengePurpose.PASSWORD_RESET);

        assertThat(secondFactorService.verify(user, signInCode, ChallengePurpose.SIGN_IN))
                .as("the sign-in code must still be live after a reset was issued")
                .isEqualTo(SecondFactorService.Outcome.PASSED);
    }

    @Test
    void issuingASignInCodeDoesNotConsumeALiveResetCode() {
        User user = account();

        secondFactorService.issueChallenge(user, ChallengePurpose.PASSWORD_RESET);
        String resetCode = sender.latest();

        secondFactorService.issueChallenge(user, ChallengePurpose.SIGN_IN);

        assertThat(secondFactorService.verify(user, resetCode, ChallengePurpose.PASSWORD_RESET))
                .as("the reset code must still be live after a sign-in was issued")
                .isEqualTo(SecondFactorService.Outcome.PASSED);
    }

    @Test
    void eachFlowKeepsItsOwnLiveChallengeAtOnce() {
        User user = account();

        secondFactorService.issueChallenge(user, ChallengePurpose.SIGN_IN);
        secondFactorService.issueChallenge(user, ChallengePurpose.PASSWORD_RESET);

        // Two live codes for one user is normally the thing consumeOutstanding prevents; across two
        // DIFFERENT flows it is correct, and this is the row-level proof that both survive.
        long live = challenges.findAll().stream()
                .filter(c -> c.getUserId().equals(user.getId()) && c.getConsumedAt() == null)
                .count();
        assertThat(live).isEqualTo(2);
        Set<ChallengePurpose> purposes = challenges.findAll().stream()
                .filter(c -> c.getUserId().equals(user.getId()) && c.getConsumedAt() == null)
                .map(LoginChallenge::getPurpose)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(purposes).containsExactlyInAnyOrder(ChallengePurpose.SIGN_IN, ChallengePurpose.PASSWORD_RESET);
    }
}
