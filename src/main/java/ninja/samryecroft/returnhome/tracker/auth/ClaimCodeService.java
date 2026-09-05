package ninja.samryecroft.returnhome.tracker.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and redeems the one-time claim code that pins an Entra {@code oid} onto a user an
 * administrator already created (T197, design §4).
 *
 * <p><b>The identity model does not change.</b> {@code idp_subject} remains the only thing sign-in
 * matches on; this is purely how an {@code oid} first gets there. Valid login stays
 * necessary-but-not-sufficient, there is no just-in-time provisioning, and an unknown {@code oid}
 * with no valid code is refused exactly as it was.
 *
 * <p><b>Email is a delivery channel, not an identity assertion.</b> The admin may email the code,
 * hand it over or read it out - a logistics choice with no bearing on who the system believes the
 * person is. That is what makes this work where email-first matching cannot: <b>shared mailboxes are
 * ordinary in this sector</b>, {@code User.email} is deliberately not unique, and a flow that
 * silently does not work for the population where it is most common is not simple.
 *
 * <h2>Entropy: the choice, made explicitly</h2>
 *
 * <p>The design required picking a side. <b>This takes the high-entropy side: 128 bits from
 * {@link SecureRandom}</b>, rendered in a case-insensitive alphabet. A short human-friendly code
 * would have made the rate limiter the only thing between a guesser and a safeguarding account, and
 * would then have needed a low per-code attempt cap and a slow hash to be safe - more moving parts,
 * each of which has to keep working. At 128 bits there is nothing to guess, so a fast hash is
 * correct and a lockout is a courtesy rather than the control.
 *
 * <p>The rejected middle is the dangerous one: <b>a short code with a generous limit looks
 * reasonable in review and is guessable in practice.</b>
 *
 * <h2>The code is a credential</h2>
 *
 * <p>Only its hash is stored, and the plaintext is returned exactly once - from {@link #issue}, to
 * the administrator who will deliver it. It must never reach a log line, an exception message or
 * audit metadata: the T179 rule applies to it as it does to a decrypted date of birth, and for the
 * same reason - App Insights feeds a Log Analytics workspace shared across the platform with no
 * field-level encryption. <b>An administrator can reissue; nobody can reveal.</b>
 */
@Service
public class ClaimCodeService {

    /**
     * Crockford-style base32: no {@code I}, {@code L}, {@code O} or {@code U}, so nothing in a code
     * can be misread as a digit or misheard when it is read down a phone.
     */
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int BITS = 128;
    private static final int LENGTH = 26;
    private static final Duration VALID_FOR = Duration.ofDays(7);

    private final SecureRandom random = new SecureRandom();
    private final UserRepository userRepository;

    public ClaimCodeService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Issues a fresh code, replacing any previous one.
     *
     * <p>Reissuing invalidates what came before, which is the only remedy when a code is lost or was
     * given to the wrong person - the previous hash is overwritten, so the old code stops working
     * the moment this returns.
     *
     * @return the plaintext code, the only time it exists outside the admin's hands
     */
    @Transactional
    public String issue(User user) {
        String code = generate();
        user.setClaimCodeHash(hash(code));
        user.setClaimCodeIssuedAt(LocalDateTime.now());
        user.setClaimCodeExpiresAt(LocalDateTime.now().plus(VALID_FOR));
        user.setClaimCodeConsumedAt(null);
        userRepository.save(user);
        return code;
    }

    /**
     * Finds the user a code belongs to, if it is valid and unspent.
     *
     * <p>Deliberately returns nothing rather than saying which of the reasons applied. The caller
     * shows one message for every refusal - wrong, expired, already used - because distinguishing
     * them tells an attacker whether a code ever existed, and the remedy is the same in all three
     * cases: ask an administrator for a new one.
     */
    @Transactional(readOnly = true)
    public Optional<User> findRedeemable(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByClaimCodeHash(hash(normalise(code)))
                .filter(User::isEnabled)
                .filter(user -> user.getClaimCodeConsumedAt() == null)
                .filter(user -> user.getClaimCodeExpiresAt() != null
                        && user.getClaimCodeExpiresAt().isAfter(LocalDateTime.now()))
                // A user who already has an identity cannot be re-linked by redeeming a code. An
                // account is linked once; re-linking is an admin action with its own audit trail.
                .filter(user -> user.getIdpSubject() == null || user.getIdpSubject().isBlank());
    }

    /**
     * Pins the {@code oid} and spends the code, in one transaction.
     *
     * <p>The uniqueness of {@code idp_subject} is what refuses a second account claiming an identity
     * that is already linked - <b>the constraint is the guarantee, not a pre-check</b>, which is the
     * house pattern. A pre-check here would race; the constraint cannot.
     */
    @Transactional
    public User redeem(User user, String objectId) {
        user.setIdpSubject(objectId.toLowerCase(Locale.ROOT));
        user.setClaimCodeConsumedAt(LocalDateTime.now());
        user.setClaimCodeHash(null);
        return userRepository.save(user);
    }

    /** Groups of four, for a code somebody has to read aloud or retype. Case and dashes ignored on the way back in. */
    private String generate() {
        byte[] entropy = new byte[BITS / 8];
        random.nextBytes(entropy);
        StringBuilder code = new StringBuilder();
        java.math.BigInteger value = new java.math.BigInteger(1, entropy);
        java.math.BigInteger size = java.math.BigInteger.valueOf(ALPHABET.length());
        for (int i = 0; i < LENGTH; i++) {
            code.append(ALPHABET.charAt(value.mod(size).intValue()));
            value = value.divide(size);
        }
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                grouped.append('-');
            }
            grouped.append(code.charAt(i));
        }
        return grouped.toString();
    }

    /** Upper-cased and stripped of anything that is not in the alphabet, so spacing and case never refuse a correct code. */
    static String normalise(String code) {
        StringBuilder cleaned = new StringBuilder();
        for (char c : code.toUpperCase(Locale.ROOT).toCharArray()) {
            if (ALPHABET.indexOf(c) >= 0) {
                cleaned.append(c);
            }
        }
        return cleaned.toString();
    }

    private static String hash(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalise(code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS, so this cannot happen - and if it somehow did, failing
            // is the only safe answer: a fallback would silently weaken a credential's storage.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
