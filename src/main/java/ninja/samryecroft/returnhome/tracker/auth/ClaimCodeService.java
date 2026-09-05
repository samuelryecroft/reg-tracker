package ninja.samryecroft.returnhome.tracker.auth;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and redeems the one-time claim code that pins an Entra {@code oid} onto a user an
 * administrator already created (T197, design §4 and §6b/§6e).
 *
 * <p><b>The identity model does not change.</b> {@code idp_subject} remains the only thing sign-in
 * matches on; this is purely how an {@code oid} first gets there. Valid login stays
 * necessary-but-not-sufficient, there is no just-in-time provisioning, and an unknown {@code oid}
 * with no valid code is refused exactly as it was.
 *
 * <p><b>Email is a delivery channel, not an identity assertion.</b> That is what makes this work
 * where email-first matching cannot: shared mailboxes are ordinary in this sector, {@code User.email}
 * is deliberately not unique, and a flow that silently does not work for the population where it is
 * most common is not simple.
 *
 * <h2>A short code, and why the lockout is the control</h2>
 *
 * <p>Ten Crockford Base32 characters - about fifty bits - rendered {@code XXXXX-XXXXX}. That is
 * defensible <b>because of the precondition, not the code</b>: this screen is reachable only after a
 * successful Entra sign-in in a tenant with self-service sign-up disabled, so the attacker population
 * is people an administrator deliberately created an account for. Insider escalation, not
 * enumeration.
 *
 * <p>Which makes the protections load-bearing rather than courtesies: <b>five attempts per code and
 * then it is dead</b>, and a slow hash, because fifty bits is below the line where a fast hash is
 * safe.
 *
 * <h2>Selector and verifier — the part the design did not resolve</h2>
 *
 * <p>A slow hash is salted, so <b>it cannot be looked up</b>. Something has to identify which code is
 * being presented before it can be verified or its attempts counted. So the code is split at the
 * hyphen it is already rendered with: the first group is a public <b>selector</b> that indexes the
 * row, the second is the secret <b>verifier</b>, and only the verifier is hashed.
 *
 * <p>This is the standard shape for the problem rather than an invention, and it is what makes
 * "five attempts per code" countable at all - without a selector there is no identified code to
 * count attempts against until after you have found it, which is the thing you needed the count for.
 * <b>Flagged to Kevin as a gap in the note</b>: the secret half is five characters, so the effective
 * secret is about twenty-five bits, and it is the lockout that makes that safe. If he wants the full
 * fifty bits secret, the code grows and the shape stays.
 *
 * <h2>The code is a credential</h2>
 *
 * <p>Only the verifier's hash is stored, and the plaintext is returned exactly once - from
 * {@link #issue}, to the administrator who will deliver it. It must never reach a log line, an
 * exception message or audit metadata: the T179 rule applies to it as it does to a decrypted date of
 * birth. <b>An administrator can reissue; nobody can reveal.</b>
 */
@Service
public class ClaimCodeService {

    /**
     * Crockford Base32's own alphabet - a published standard, not a bespoke one.
     *
     * <p>It excludes {@code I}, {@code L}, {@code O} and {@code U}, and defines the aliases used in
     * {@link #normalise}. Hand-rolling an alphabet would cost the standard's decoder and gain
     * nothing; the phonetic confusions it does not fix - {@code 5}/{@code S}, {@code B}/{@code 8}
     * over a telephone - are a spelling problem, answered by spelling it out rather than by shrinking
     * the alphabet further.
     */
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int SELECTOR_LENGTH = 5;
    private static final int VERIFIER_LENGTH = 5;
    private static final Duration VALID_FOR = Duration.ofDays(7);

    /** Five, then the code is dead. The design calls this load-bearing and it is. */
    static final int MAX_ATTEMPTS = 5;

    private final SecureRandom random = new SecureRandom();
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public ClaimCodeService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Issues a fresh code, replacing any previous one.
     *
     * <p>Reissuing is the whole of revocation: there is no separate cancel verb, because issuing a
     * new code invalidates the old one and disabling the user invalidates any outstanding one, and
     * those two cover both real cases - wrong person, and person never joined - using admin concepts
     * that already exist.
     *
     * @return the plaintext code, the only time it exists outside the administrator's hands
     */
    @Transactional
    public String issue(User user) {
        String selector = randomChars(SELECTOR_LENGTH);
        String verifier = randomChars(VERIFIER_LENGTH);
        user.setClaimCodeSelector(selector);
        user.setClaimCodeVerifierHash(passwordEncoder.encode(verifier));
        user.setClaimCodeIssuedAt(LocalDateTime.now());
        user.setClaimCodeExpiresAt(LocalDateTime.now().plus(VALID_FOR));
        user.setClaimCodeConsumedAt(null);
        user.setClaimCodeAttempts(0);
        userRepository.save(user);
        return selector + "-" + verifier;
    }

    /**
     * Verifies a presented code, counting the attempt against it.
     *
     * <p>Returns nothing for every refusal - wrong, expired, spent, locked out, or for an account
     * that is already linked. The caller shows one message for all of them, because distinguishing
     * them tells whoever is guessing whether a code ever existed, and the remedy is identical:
     * ask an administrator for a new one.
     *
     * <p><b>A wrong verifier against a real selector burns an attempt.</b> That is the point of the
     * counter, and it is why this method writes even when it refuses.
     */
    @Transactional
    public Optional<User> redeemable(String presented) {
        String normalised = normalise(presented);
        if (normalised.length() != SELECTOR_LENGTH + VERIFIER_LENGTH) {
            return Optional.empty();
        }
        String selector = normalised.substring(0, SELECTOR_LENGTH);
        String verifier = normalised.substring(SELECTOR_LENGTH);

        Optional<User> candidate = userRepository.findByClaimCodeSelector(selector)
                .filter(User::isEnabled)
                .filter(user -> user.getClaimCodeConsumedAt() == null)
                .filter(user -> user.getClaimCodeAttempts() < MAX_ATTEMPTS)
                .filter(user -> user.getClaimCodeExpiresAt() != null
                        && user.getClaimCodeExpiresAt().isAfter(LocalDateTime.now()))
                // An account is linked once. Re-linking is an administrator action with its own
                // audit trail, not something an unspent code can do to a live account.
                .filter(user -> user.getIdpSubject() == null || user.getIdpSubject().isBlank());
        if (candidate.isEmpty()) {
            return Optional.empty();
        }

        User user = candidate.get();
        if (passwordEncoder.matches(verifier, user.getClaimCodeVerifierHash())) {
            return Optional.of(user);
        }
        user.setClaimCodeAttempts(user.getClaimCodeAttempts() + 1);
        userRepository.save(user);
        return Optional.empty();
    }

    /**
     * Pins the {@code oid} and spends the code, in one transaction.
     *
     * <p>The uniqueness of {@code idp_subject} is what refuses a second account claiming an identity
     * already linked - <b>the constraint is the guarantee, not a pre-check</b>, which is the house
     * pattern. A pre-check here would race; the constraint cannot.
     */
    @Transactional
    public User redeem(User user, String objectId) {
        user.setIdpSubject(objectId.toLowerCase(Locale.ROOT));
        user.setClaimCodeConsumedAt(LocalDateTime.now());
        user.setClaimCodeSelector(null);
        user.setClaimCodeVerifierHash(null);
        return userRepository.save(user);
    }

    /**
     * Crockford's defined normalisation: upper-cased, hyphens and spacing dropped, and
     * {@code O}&rarr;{@code 0}, {@code I}/{@code L}&rarr;{@code 1}.
     *
     * <p>Those aliases are the standard's, not ours - which is the reason for using a published
     * alphabet rather than inventing one. They mean a code read down a phone and typed back with an
     * O for a zero still works, instead of failing in a way neither party can diagnose.
     */
    static String normalise(String code) {
        if (code == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder();
        for (char raw : code.toUpperCase(Locale.ROOT).toCharArray()) {
            char c = switch (raw) {
                case 'O' -> '0';
                case 'I', 'L' -> '1';
                default -> raw;
            };
            if (ALPHABET.indexOf(c) >= 0) {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }

    private String randomChars(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }
}
