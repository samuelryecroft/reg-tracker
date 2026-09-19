package ninja.samryecroft.returnhome.tracker.security.trusteddevice;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.security.passwordreset.TokenHashing;
import ninja.samryecroft.returnhome.tracker.security.secondfactor.ChallengePurpose;
import ninja.samryecroft.returnhome.tracker.security.secondfactor.SecondFactorPolicy;
import ninja.samryecroft.returnhome.tracker.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The trusted-device core (T365 stage 1, design §4/§6/§8): mint a device trust, verify a presented
 * token (rotating it and detecting replays), and revoke.
 *
 * <p><b>Stage 1 is inert.</b> Nothing calls this on any request path yet - no success-handler branch,
 * no UI, no per-org gate (those are stages 2 and 3). No user can obtain a token and nothing reads the
 * table in production. This class is the mechanism, with its guarantees built and tested in isolation
 * before anything is wired to it.
 *
 * <p><b>The three guarantees that make it safe, none of them negotiable:</b>
 * <ol>
 *   <li><b>{@code SIGN_IN} only.</b> {@link #verify} takes a {@link ChallengePurpose} and refuses
 *       anything else outright. A bound that names one mechanism ("skip MFA") is not a bound on the
 *       category; the waiver is of the {@code SIGN_IN} challenge and nothing else, so a trusted device
 *       can never shorten a password reset's re-validation (design §3).</li>
 *   <li><b>Rotation on every use.</b> Each successful verify mints a fresh token and retires the one
 *       presented, keeping the absolute expiry. A replay of the retired token is then detectable and
 *       is treated as theft (design §4).</li>
 *   <li><b>Absolute 30-day expiry.</b> Set at first trust and carried forward unchanged by every
 *       rotation; never extended on use. A token used on day 29 still dies on day 30 (design §4).</li>
 * </ol>
 *
 * <p><b>Break-glass is refused at both ends</b> (design §6), and via the <em>existing</em> predicate
 * ({@link SecondFactorPolicy#isEmergencyExempt}) rather than a private copy: never mint a trust for
 * it, never honour a token presented for it. Break-glass is already factor-exempt and exists for when
 * email itself is down; a trusted device would be a second standing way in that skips a check.
 */
@Service
public class TrustedDeviceService {

    /** 30 days, ABSOLUTE, from first trust. One constant - there is no per-org duration (Sam, OQ-1). */
    static final Duration TRUST_DURATION = Duration.ofDays(30);

    /** 256 bits, the same generator discipline as the reset token (design §4). */
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    private final TrustedDeviceRepository trustedDevices;
    private final SecondFactorPolicy secondFactorPolicy;

    public TrustedDeviceService(TrustedDeviceRepository trustedDevices,
            SecondFactorPolicy secondFactorPolicy) {
        this.trustedDevices = trustedDevices;
        this.secondFactorPolicy = secondFactorPolicy;
    }

    /**
     * Trust a device for the first time and return the raw token to place in the cookie, plus its
     * absolute expiry (for the cookie's Max-Age). The raw token is never stored - only its hash.
     *
     * @throws IllegalStateException if the account may never be trusted (break-glass) - refused at
     *     the mint end so the honour end is never the only guard.
     */
    @Transactional
    public MintResult mint(User user, String userAgentSummary, Instant now) {
        if (secondFactorPolicy.isEmergencyExempt(user)) {
            throw new IllegalStateException(
                    "the break-glass account must never be granted a trusted device");
        }
        String token = newToken();
        Instant expiresAt = now.plus(TRUST_DURATION);
        TrustedDevice device = new TrustedDevice(
                user.getId(), TokenHashing.sha256Hex(token), now, expiresAt, userAgentSummary);
        trustedDevices.save(device);
        return new MintResult(token, expiresAt);
    }

    /**
     * Present a token to waive a sign-in code. Rotates on success; detects a replayed rotated token.
     *
     * @param purpose must be {@link ChallengePurpose#SIGN_IN}; anything else is rejected outright.
     * @throws IllegalArgumentException if {@code purpose} is not {@code SIGN_IN} - a caller asking a
     *     trusted device to waive any other challenge is a programming error, not a policy miss, and
     *     must fail loudly rather than silently widen the waiver.
     */
    @Transactional
    public TrustedDeviceVerification verify(String token, ChallengePurpose purpose, User user,
            Instant now) {
        if (purpose != ChallengePurpose.SIGN_IN) {
            throw new IllegalArgumentException(
                    "a trusted device waives only the SIGN_IN challenge, never " + purpose);
        }
        // Never honour a token for the break-glass account (design §6), even one hand-planted in the
        // table: the refusal is on the account, ahead of any lookup.
        if (secondFactorPolicy.isEmergencyExempt(user)) {
            return TrustedDeviceVerification.notTrusted();
        }
        if (token == null || token.isEmpty()) {
            return TrustedDeviceVerification.notTrusted();
        }
        Optional<TrustedDevice> found = trustedDevices.findByTokenHash(TokenHashing.sha256Hex(token));
        if (found.isEmpty()) {
            return TrustedDeviceVerification.notTrusted();
        }
        TrustedDevice device = found.get();
        // A token only ever waives the code for its OWN account. A row that belongs to someone else
        // is not this user's trust and is not honoured.
        if (!device.getUserId().equals(user.getId())) {
            return TrustedDeviceVerification.notTrusted();
        }
        if (device.isRevoked()) {
            // The presented token was already retired - rotated away or revoked - yet here it is
            // again. That is positive evidence of reuse (design §4): revoke every live trust for the
            // account and force the code. (A later stage audits this as the theft signal.)
            trustedDevices.revokeAllForUser(user.getId(), now);
            return TrustedDeviceVerification.replayDetected();
        }
        if (!now.isBefore(device.getExpiresAt())) {
            // Absolute expiry, never extended: a live-looking but past-expiry token is simply not
            // trusted. Fall through to the ordinary code demand.
            return TrustedDeviceVerification.notTrusted();
        }
        String rotatedToken = rotate(device, now);
        return TrustedDeviceVerification.trusted(rotatedToken, device.getExpiresAt());
    }

    /**
     * Rotate a live trust: retire the presented row and mint a successor carrying the SAME first-trust
     * date and the SAME absolute expiry, so the window never moves. Returns the new raw token.
     *
     * <p>Package-private and coupled to {@link #verify}: rotation is "on every use", not a thing a
     * caller does on its own. Retiring the old row (rather than mutating it in place) is what lets a
     * later presentation of the old token be caught as a replay.
     */
    TrustedDevice rotateInternal(TrustedDevice current, Instant now, String newToken) {
        current.revoke(now);
        trustedDevices.save(current);
        TrustedDevice successor = new TrustedDevice(
                current.getUserId(), TokenHashing.sha256Hex(newToken),
                current.getCreatedAt(), current.getExpiresAt(), current.getUserAgentSummary());
        successor.markUsed(now);
        return trustedDevices.save(successor);
    }

    private String rotate(TrustedDevice current, Instant now) {
        String newToken = newToken();
        rotateInternal(current, now, newToken);
        return newToken;
    }

    /** Revoke every live trust for a user (password change, "sign out everywhere", theft). */
    @Transactional
    public int revokeAllFor(Long userId, Instant now) {
        return trustedDevices.revokeAllForUser(userId, now);
    }

    /**
     * Revoke a single trust ("forget this device"), but only if it belongs to the given user - a
     * device id from one account may never revoke another's row.
     */
    @Transactional
    public boolean revokeOne(Long trustedDeviceId, Long userId, Instant now) {
        Optional<TrustedDevice> found = trustedDevices.findById(trustedDeviceId);
        if (found.isEmpty() || !found.get().getUserId().equals(userId) || found.get().isRevoked()) {
            return false;
        }
        TrustedDevice device = found.get();
        device.revoke(now);
        trustedDevices.save(device);
        return true;
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** A freshly minted token and its absolute expiry - the two things the cookie needs. */
    public record MintResult(String token, Instant expiresAt) {
    }
}
