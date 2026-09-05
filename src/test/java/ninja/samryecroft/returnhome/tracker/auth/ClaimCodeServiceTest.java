package ninja.samryecroft.returnhome.tracker.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * T197: the claim code as a credential, and the edge-case table from the design.
 *
 * <p>Every refusal below returns the same nothing, deliberately - the caller shows one message for
 * wrong, expired, spent and already-linked, because distinguishing them tells a guesser whether a
 * code ever existed and the remedy is identical in all four cases.
 */
class ClaimCodeServiceTest {

    private final Map<String, User> bySelector = new HashMap<>();
    private final UserRepository userRepository = mock(UserRepository.class);
    private ClaimCodeService service;

    @BeforeEach
    void setUp() {
        // A tiny fake index rather than a stub per test: redemption looks a code up BY HASH, so the
        // test has to exercise that lookup for the hashing to be under test at all.
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            bySelector.values().removeIf(existing -> existing == saved);
            if (saved.getClaimCodeSelector() != null) {
                bySelector.put(saved.getClaimCodeSelector(), saved);
            }
            return saved;
        });
        when(userRepository.findByClaimCodeSelector(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(bySelector.get(invocation.getArgument(0))));
        // The real encoder, not a stub: the slow hash is part of what is under test, and a fake one
        // would let a bug in how the verifier is encoded or matched pass unnoticed.
        service = new ClaimCodeService(userRepository,
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4));
    }

    private User enabledUser() {
        User user = new User();
        user.setUsername("kim");
        user.setLastName("Kim");
        user.setEnabled(true);
        return user;
    }

    @Test
    void aFreshlyIssuedCodeRedeemsToItsOwner() {
        User user = enabledUser();
        String code = service.issue(user);

        assertThat(service.redeemable(code)).containsSame(user);
    }

    /**
     * <b>Only the SECRET half is hashed, and the plaintext exists once.</b> The selector is stored in
     * clear on purpose - it is the lookup, not the secret - and a slow salted hash cannot be looked
     * up, which is why the split exists at all.
     */
    @Test
    void onlyTheVerifierIsHashedAndTheCodeItselfIsNotStored() {
        User user = enabledUser();
        String code = service.issue(user);

        assertThat(user.getClaimCodeVerifierHash()).isNotNull().isNotEqualTo(code);
        assertThat(user.getClaimCodeVerifierHash()).doesNotContain(ClaimCodeService.normalise(code));
        assertThat(user.getClaimCodeVerifierHash()).startsWith("$2");
        assertThat(code).startsWith(user.getClaimCodeSelector() + "-");
    }

    /** Read down a phone and typed back in any casing, with or without the grouping dashes. */
    @Test
    void caseAndDashesDoNotRefuseACorrectCode() {
        User user = enabledUser();
        String code = service.issue(user);

        assertThat(service.redeemable(code.toLowerCase())).containsSame(user);
        assertThat(service.redeemable(code.replace("-", ""))).containsSame(user);
        assertThat(service.redeemable(" " + code + " ")).containsSame(user);
    }

    @Test
    void aCodeOfTheWrongShapeIsRefusedWithoutTouchingAnything() {
        User user = enabledUser();
        service.issue(user);

        assertThat(service.redeemable("SHORT")).isEmpty();
        assertThat(service.redeemable(user.getClaimCodeSelector())).isEmpty();
        assertThat(user.getClaimCodeAttempts()).isZero();
    }

    /** Ten Crockford characters, rendered XXXXX-XXXXX, as the design fixes. */
    @Test
    void theCodeHasTheShapeTheDesignFixed() {
        String code = service.issue(enabledUser());

        assertThat(code).matches("[0-9A-HJKMNP-TV-Z]{5}-[0-9A-HJKMNP-TV-Z]{5}");
        // No I, L, O or U - Crockford's own exclusions, so nothing is misread as a digit.
        assertThat(code).doesNotContain("I").doesNotContain("L").doesNotContain("O").doesNotContain("U");
    }

    /**
     * Crockford's <em>defined</em> aliases, which is the reason for using a published alphabet rather
     * than inventing one: a code read down a phone and typed back with an O for a zero still works,
     * instead of failing in a way neither party can diagnose.
     */
    @Test
    void crockfordsAliasesAreHonouredOnEntry() {
        User user = enabledUser();
        String code = service.issue(user);
        String typedBadly = code.replace('0', 'O').replace('1', 'I').toLowerCase().replace("-", " ");

        assertThat(service.redeemable(typedBadly)).containsSame(user);
    }

    /**
     * <b>The lockout is the control, not the entropy</b> - about fifty bits is defensible only
     * because this screen sits behind a successful sign-in in a tenant with self-service sign-up
     * off, and because the code dies once its attempts are spent. The loop below counts to
     * {@link ClaimCodeService#MAX_ATTEMPTS} rather than to a literal, so moving the cap moves the
     * test with it - the number was five when this was written and is ten now.
     */
    @Test
    void spendingEveryAttemptKillsTheCodeEvenIfTheRightOneArrivesAfterwards() {
        User user = enabledUser();
        String code = service.issue(user);
        String wrong = user.getClaimCodeSelector() + "-ZZZZZ";

        for (int attempt = 0; attempt < ClaimCodeService.MAX_ATTEMPTS; attempt++) {
            assertThat(service.redeemable(wrong)).isEmpty();
        }

        assertThat(user.getClaimCodeAttempts()).isEqualTo(ClaimCodeService.MAX_ATTEMPTS);
        assertThat(service.redeemable(code))
                .as("the correct code must not work once the attempts are spent")
                .isEmpty();
    }

    /** A correct code must not be burning attempts - otherwise a slow typist locks themselves out. */
    @Test
    void aCorrectCodeDoesNotConsumeAnAttempt() {
        User user = enabledUser();
        String code = service.issue(user);

        assertThat(service.redeemable(code)).containsSame(user);
        assertThat(user.getClaimCodeAttempts()).isZero();
    }

    /** A guess at a selector nobody holds cannot burn anyone else's attempts. */
    @Test
    void anUnknownSelectorDoesNotTouchAnyExistingCode() {
        User user = enabledUser();
        service.issue(user);

        assertThat(service.redeemable("ZZZZZ-ZZZZZ")).isEmpty();
        assertThat(user.getClaimCodeAttempts()).isZero();
    }

    @Test
    void twoIssuedCodesAreNotTheSame() {
        assertThat(service.issue(enabledUser())).isNotEqualTo(service.issue(enabledUser()));
    }

    @Test
    void aWrongCodeIsRefused() {
        service.issue(enabledUser());
        assertThat(service.redeemable("ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZ")).isEmpty();
        assertThat(service.redeemable("")).isEmpty();
        assertThat(service.redeemable(null)).isEmpty();
    }

    @Test
    void anExpiredCodeIsRefused() {
        User user = enabledUser();
        String code = service.issue(user);
        user.setClaimCodeExpiresAt(LocalDateTime.now().minusMinutes(1));

        assertThat(service.redeemable(code)).isEmpty();
    }

    @Test
    void anAlreadyConsumedCodeIsRefused() {
        User user = enabledUser();
        String code = service.issue(user);
        user.setClaimCodeConsumedAt(LocalDateTime.now());

        assertThat(service.redeemable(code)).isEmpty();
    }

    /**
     * An account is linked once. Re-linking is an administrator action with its own audit trail, not
     * something a code can do - otherwise anyone holding an old unspent code could re-point a live
     * account at a different directory identity.
     */
    @Test
    void aCodeCannotRelinkAnAccountThatAlreadyHasAnIdentity() {
        User user = enabledUser();
        String code = service.issue(user);
        user.setIdpSubject("00000000-1111-2222-3333-444444444444");

        assertThat(service.redeemable(code)).isEmpty();
    }

    @Test
    void aDisabledUsersCodeIsRefused() {
        User user = enabledUser();
        String code = service.issue(user);
        user.setEnabled(false);

        assertThat(service.redeemable(code)).isEmpty();
    }

    /** Reissuing is the remedy for a lost code, and it must stop the previous one working. */
    @Test
    void reissuingInvalidatesThePreviousCode() {
        User user = enabledUser();
        String first = service.issue(user);
        String second = service.issue(user);

        assertThat(service.redeemable(first)).isEmpty();
        assertThat(service.redeemable(second)).containsSame(user);
    }

    /**
     * <b>The shared-mailbox case, which is the reason this design exists at all.</b> Two app users
     * on one address is ordinary in this sector and {@code User.email} is deliberately not unique -
     * email-first matching would have to refuse both forever. The code is per person, so it simply
     * does not arise: neither user's code can redeem to the other.
     */
    @Test
    void twoUsersSharingOneMailboxEachRedeemTheirOwnCode() {
        User duty1 = enabledUser();
        duty1.setEmail("duty@oakfield.gov.uk");
        User duty2 = enabledUser();
        duty2.setEmail("duty@oakfield.gov.uk");

        String code1 = service.issue(duty1);
        String code2 = service.issue(duty2);

        assertThat(service.redeemable(code1)).containsSame(duty1);
        assertThat(service.redeemable(code2)).containsSame(duty2);
    }

    /**
     * The recycled-address window, which email-first could not close. Here it cannot arise: nothing
     * about redemption consults the address, so a leaver's address on a new starter's directory
     * account grants nothing.
     */
    @Test
    void redemptionNeverConsultsTheEmailAddress() {
        User leaver = enabledUser();
        leaver.setEmail("recycled@oakfield.gov.uk");
        String code = service.issue(leaver);
        leaver.setEmail("someone.else@oakfield.gov.uk");

        assertThat(service.redeemable(code))
                .as("the code identifies the person; the address is a delivery channel")
                .containsSame(leaver);
    }

    @Test
    void redeemingPinsTheObjectIdLowercasedAndSpendsTheCode() {
        User user = enabledUser();
        String code = service.issue(user);

        service.redeem(user, "AABBCCDD-1111-2222-3333-444444444444");

        assertThat(user.getIdpSubject()).isEqualTo("aabbccdd-1111-2222-3333-444444444444");
        assertThat(user.getClaimCodeConsumedAt()).isNotNull();
        assertThat(user.getClaimCodeSelector()).isNull();
        assertThat(user.getClaimCodeVerifierHash()).isNull();
        assertThat(service.redeemable(code)).isEmpty();
    }
}
