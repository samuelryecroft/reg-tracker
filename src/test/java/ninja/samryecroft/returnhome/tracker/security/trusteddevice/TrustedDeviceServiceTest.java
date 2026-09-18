package ninja.samryecroft.returnhome.tracker.security.trusteddevice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.security.passwordreset.TokenHashing;
import ninja.samryecroft.returnhome.tracker.security.secondfactor.ChallengePurpose;
import ninja.samryecroft.returnhome.tracker.security.trusteddevice.TrustedDeviceService.MintResult;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * T365 stage 1 guards for {@link TrustedDeviceService}, each a positive control - the behaviour is
 * driven and observed, not asserted to exist. These are the three non-negotiables (design §3/§4) plus
 * the break-glass refusal at both ends (§6).
 */
@SpringBootTest
class TrustedDeviceServiceTest extends AbstractIntegrationTest {

    private static final String UA = "Chrome on Windows";

    @Autowired
    private TrustedDeviceService trustedDeviceService;
    @Autowired
    private TrustedDeviceRepository trustedDeviceRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private User seedUser(boolean breakGlass) {
        User user = new User();
        user.setUsername("device-" + System.nanoTime());
        user.setEmail("device-" + System.nanoTime() + "@example.com");
        user.setFirstName("Test");
        user.setLastName("User");
        user.setBreakGlass(breakGlass);
        user.setPassword(passwordEncoder.encode("password-does-not-matter-here"));
        user.setRoles(Set.of(Role.HOME_STAFF));
        user.setEnabled(true);
        return userRepository.save(user);
    }

    // GUARD 1 (design §4): rotation makes a replayed token DETECTABLE, and a detected replay revokes
    // the whole chain. Arm by making verify() treat a revoked row as merely not-trusted: the first
    // assertion (REPLAY_DETECTED) and the chain-revocation assertion both go red.
    @Test
    void aReplayedRotatedTokenIsDetectedAndRevokesTheChain() {
        User user = seedUser(false);
        Instant now = Instant.now();

        MintResult minted = trustedDeviceService.mint(user, UA, now);

        TrustedDeviceVerification firstUse =
                trustedDeviceService.verify(minted.token(), ChallengePurpose.SIGN_IN, user, now);
        assertThat(firstUse.isTrusted())
                .as("the first presentation of a freshly minted token is honoured")
                .isTrue();
        String rotatedToken = firstUse.rotatedToken();

        // Replay the ORIGINAL token - the one rotation just retired.
        TrustedDeviceVerification replay =
                trustedDeviceService.verify(minted.token(), ChallengePurpose.SIGN_IN, user, now);
        assertThat(replay.status())
                .as("a presented-but-already-rotated token is positive evidence of theft, not a miss")
                .isEqualTo(TrustedDeviceVerification.Status.REPLAY_DETECTED);

        // And the whole chain is gone: the legitimately rotated token no longer works either.
        TrustedDeviceVerification afterReplay =
                trustedDeviceService.verify(rotatedToken, ChallengePurpose.SIGN_IN, user, now);
        assertThat(afterReplay.isTrusted())
                .as("detecting theft revokes every live trust for the account, forcing the code")
                .isFalse();
    }

    // GUARD 2 (design §3): the waiver is of the SIGN_IN challenge and nothing else. A bound that names
    // one mechanism is not a bound on the category. Arm by dropping the purpose check: the throw
    // disappears and this goes red.
    @Test
    void verifyRefusesAnyPurposeThatIsNotSignIn() {
        User user = seedUser(false);
        Instant now = Instant.now();
        MintResult minted = trustedDeviceService.mint(user, UA, now);

        assertThatThrownBy(() ->
                trustedDeviceService.verify(minted.token(), ChallengePurpose.PASSWORD_RESET, user, now))
                .as("a trusted device must never waive a password-reset re-validation")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // GUARD 3 (design §4): expiry is ABSOLUTE - set at first trust, never extended by use. A token
    // used on day 29 still dies on day 30. Arm by making rotation slide the expiry off `now`: the
    // unchanged-expiry assertion and the day-30 death both go red.
    @Test
    void expiryIsAbsoluteAndATokenUsedOnDayTwentyNineStillDiesOnDayThirty() {
        User user = seedUser(false);
        Instant firstTrust = Instant.now();

        MintResult minted = trustedDeviceService.mint(user, UA, firstTrust);
        Instant absoluteExpiry = minted.expiresAt();
        assertThat(absoluteExpiry)
                .as("30 days absolute from first trust")
                .isEqualTo(firstTrust.plus(Duration.ofDays(30)));

        // Use it on day 29 - allowed, and it rotates.
        Instant day29 = firstTrust.plus(Duration.ofDays(29));
        TrustedDeviceVerification useOnDay29 =
                trustedDeviceService.verify(minted.token(), ChallengePurpose.SIGN_IN, user, day29);
        assertThat(useOnDay29.isTrusted())
                .as("a live token is honoured on day 29")
                .isTrue();
        assertThat(useOnDay29.expiresAt())
                .as("using it did NOT push the expiry out - the window is absolute, not sliding")
                .isEqualTo(absoluteExpiry);
        String rotatedToken = useOnDay29.rotatedToken();

        // Day 30: the absolute expiry has arrived. The rotated token dies with the original.
        Instant day30 = firstTrust.plus(Duration.ofDays(30));
        TrustedDeviceVerification useOnDay30 =
                trustedDeviceService.verify(rotatedToken, ChallengePurpose.SIGN_IN, user, day30);
        assertThat(useOnDay30.isTrusted())
                .as("the token still dies on day 30 even though it was used on day 29")
                .isFalse();
    }

    // GUARD (design §6): break-glass must never be trustable - refused at BOTH ends. Arm by dropping
    // the isEmergencyExempt guards from mint and verify: the mint no longer throws and a planted row
    // is honoured, so both assertions go red.
    @Test
    void theBreakGlassAccountIsRefusedATrustedDeviceAtBothEnds() {
        User breakGlass = seedUser(true);
        Instant now = Instant.now();

        // End 1 - never mint one.
        assertThatThrownBy(() -> trustedDeviceService.mint(breakGlass, UA, now))
                .as("the break-glass account must never be granted a trusted device")
                .isInstanceOf(IllegalStateException.class);

        // End 2 - never honour one, even hand-planted straight into the table.
        String plantedToken = "planted-break-glass-token";
        trustedDeviceRepository.save(new TrustedDevice(
                breakGlass.getId(), TokenHashing.sha256Hex(plantedToken),
                now, now.plus(Duration.ofDays(30)), UA));

        TrustedDeviceVerification presented =
                trustedDeviceService.verify(plantedToken, ChallengePurpose.SIGN_IN, breakGlass, now);
        assertThat(presented.isTrusted())
                .as("a token presented for the break-glass account is never honoured")
                .isFalse();
    }
}
