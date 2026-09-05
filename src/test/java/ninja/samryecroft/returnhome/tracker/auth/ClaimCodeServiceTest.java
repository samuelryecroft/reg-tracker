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

    private final Map<String, User> byHash = new HashMap<>();
    private final UserRepository userRepository = mock(UserRepository.class);
    private ClaimCodeService service;

    @BeforeEach
    void setUp() {
        // A tiny fake index rather than a stub per test: redemption looks a code up BY HASH, so the
        // test has to exercise that lookup for the hashing to be under test at all.
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            byHash.values().removeIf(existing -> existing == saved);
            if (saved.getClaimCodeHash() != null) {
                byHash.put(saved.getClaimCodeHash(), saved);
            }
            return saved;
        });
        when(userRepository.findByClaimCodeHash(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(byHash.get(invocation.getArgument(0))));
        service = new ClaimCodeService(userRepository);
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

        assertThat(service.findRedeemable(code)).containsSame(user);
    }

    /**
     * <b>The plaintext exists once.</b> Only a hash is stored, which is what makes "an administrator
     * can reissue, never reveal" a property of the storage rather than a rule someone remembers.
     */
    @Test
    void onlyAHashIsStoredAndItIsNotTheCode() {
        User user = enabledUser();
        String code = service.issue(user);

        assertThat(user.getClaimCodeHash()).isNotNull().isNotEqualTo(code);
        assertThat(user.getClaimCodeHash()).doesNotContain(ClaimCodeService.normalise(code));
        assertThat(user.getClaimCodeHash()).hasSize(64);
    }

    /** Read down a phone and typed back in any casing, with or without the grouping dashes. */
    @Test
    void caseAndDashesDoNotRefuseACorrectCode() {
        User user = enabledUser();
        String code = service.issue(user);

        assertThat(service.findRedeemable(code.toLowerCase())).containsSame(user);
        assertThat(service.findRedeemable(code.replace("-", ""))).containsSame(user);
        assertThat(service.findRedeemable(" " + code + " ")).containsSame(user);
    }

    /** 128 bits, so the rate limiter is a courtesy rather than the control. */
    @Test
    void theCodeCarriesTheEntropyTheDesignChose() {
        String code = ClaimCodeService.normalise(service.issue(enabledUser()));

        // 26 characters of a 32-symbol alphabet is 130 bits.
        assertThat(code).hasSize(26);
        assertThat(code).matches("[0123456789ABCDEFGHJKMNPQRSTVWXYZ]+");
        // No I, L, O or U: nothing in a code can be misread as a digit or misheard aloud.
        assertThat(code).doesNotContain("I").doesNotContain("L").doesNotContain("O").doesNotContain("U");
    }

    @Test
    void twoIssuedCodesAreNotTheSame() {
        assertThat(service.issue(enabledUser())).isNotEqualTo(service.issue(enabledUser()));
    }

    @Test
    void aWrongCodeIsRefused() {
        service.issue(enabledUser());
        assertThat(service.findRedeemable("ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZ")).isEmpty();
        assertThat(service.findRedeemable("")).isEmpty();
        assertThat(service.findRedeemable(null)).isEmpty();
    }

    @Test
    void anExpiredCodeIsRefused() {
        User user = enabledUser();
        String code = service.issue(user);
        user.setClaimCodeExpiresAt(LocalDateTime.now().minusMinutes(1));

        assertThat(service.findRedeemable(code)).isEmpty();
    }

    @Test
    void anAlreadyConsumedCodeIsRefused() {
        User user = enabledUser();
        String code = service.issue(user);
        user.setClaimCodeConsumedAt(LocalDateTime.now());

        assertThat(service.findRedeemable(code)).isEmpty();
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

        assertThat(service.findRedeemable(code)).isEmpty();
    }

    @Test
    void aDisabledUsersCodeIsRefused() {
        User user = enabledUser();
        String code = service.issue(user);
        user.setEnabled(false);

        assertThat(service.findRedeemable(code)).isEmpty();
    }

    /** Reissuing is the remedy for a lost code, and it must stop the previous one working. */
    @Test
    void reissuingInvalidatesThePreviousCode() {
        User user = enabledUser();
        String first = service.issue(user);
        String second = service.issue(user);

        assertThat(service.findRedeemable(first)).isEmpty();
        assertThat(service.findRedeemable(second)).containsSame(user);
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

        assertThat(service.findRedeemable(code1)).containsSame(duty1);
        assertThat(service.findRedeemable(code2)).containsSame(duty2);
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

        assertThat(service.findRedeemable(code))
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
        assertThat(user.getClaimCodeHash()).isNull();
        assertThat(service.findRedeemable(code)).isEmpty();
    }
}
