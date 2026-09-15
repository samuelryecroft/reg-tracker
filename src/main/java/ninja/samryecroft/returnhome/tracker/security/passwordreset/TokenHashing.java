package ninja.samryecroft.returnhome.tracker.security.passwordreset;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * One place that turns a reset token into its stored hash (T353c/d), so the mint side and the lookup
 * side (T353e) cannot drift into hashing it two different ways - a drift that would silently make
 * every emitted link un-findable.
 *
 * <p>SHA-256, not a password encoder: the token is 256 bits of {@code SecureRandom}, so it cannot be
 * brute-forced and a slow per-row salt would only make the by-hash lookup unindexable. See
 * {@code password_reset_tokens.token_hash} (V27) for the same reasoning stated at the schema.
 */
public final class TokenHashing {

    private TokenHashing() {
    }

    /** Lower-case hex SHA-256 of the token. Deterministic, so it can be the indexed lookup key. */
    public static String sha256Hex(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; if it is absent the platform is broken, not the input.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
