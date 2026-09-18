package ninja.samryecroft.returnhome.tracker.security.trusteddevice;

import java.time.Instant;

/**
 * The outcome of presenting a trusted-device token (T365 stage 1). Three outcomes, and they mean
 * different things to the caller ({@code SecondFactorSuccessHandler}, wired in a later stage):
 *
 * <ul>
 *   <li>{@link Status#TRUSTED} - waive the {@code SIGN_IN} code for this sign-in. The token has been
 *       rotated: {@link #rotatedToken()} is the new value to write back into the cookie, and
 *       {@link #expiresAt()} is its unchanged absolute expiry (for the cookie's Max-Age).</li>
 *   <li>{@link Status#NOT_TRUSTED} - no valid trust (no cookie, unknown token, wrong user, expired,
 *       or a break-glass account, which is never trusted). Fail closed: demand the code as normal.</li>
 *   <li>{@link Status#REPLAY_DETECTED} - a token that had already been rotated (or revoked) was
 *       presented. That is positive evidence of theft (design §4): the whole set of the user's trusts
 *       has been revoked, and the caller must demand the code. A later stage audits this as the theft
 *       signal it is.</li>
 * </ul>
 *
 * <p>Only {@code TRUSTED} skips the factor. Both other outcomes fall through to the ordinary code
 * demand - the design fails towards asking, never away from it.
 */
public record TrustedDeviceVerification(Status status, String rotatedToken, Instant expiresAt) {

    public enum Status {
        TRUSTED,
        NOT_TRUSTED,
        REPLAY_DETECTED
    }

    public static TrustedDeviceVerification trusted(String rotatedToken, Instant expiresAt) {
        return new TrustedDeviceVerification(Status.TRUSTED, rotatedToken, expiresAt);
    }

    public static TrustedDeviceVerification notTrusted() {
        return new TrustedDeviceVerification(Status.NOT_TRUSTED, null, null);
    }

    public static TrustedDeviceVerification replayDetected() {
        return new TrustedDeviceVerification(Status.REPLAY_DETECTED, null, null);
    }

    public boolean isTrusted() {
        return status == Status.TRUSTED;
    }
}
