package ninja.samryecroft.returnhome.tracker.security.passwordreset;

import ninja.samryecroft.returnhome.tracker.security.session.SessionTerminationService;
import java.time.Instant;
import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.security.secondfactor.ChallengePurpose;
import ninja.samryecroft.returnhome.tracker.security.secondfactor.LoginChallenge;
import ninja.samryecroft.returnhome.tracker.security.secondfactor.LoginChallengeRepository;
import ninja.samryecroft.returnhome.tracker.security.secondfactor.SecondFactorService;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import ninja.samryecroft.returnhome.tracker.user.password.PasswordContext;
import ninja.samryecroft.returnhome.tracker.user.password.PasswordPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Steps B, C and D of the reset flow (T353e). The password cannot be applied until the code for THIS
 * token verifies, and that is enforced by construct rather than by an {@code if}:
 *
 * <ul>
 *   <li><b>There is exactly one method that writes the password on this path,
 *       {@link #applyVerifiedReset}, and it is {@code private} and takes a {@link PasswordResetGrant}
 *       - a {@code private} nested record only this class can construct.</b> It is minted in exactly
 *       one place, {@link #verifyAndApply}, after {@link SecondFactorService#verify} returns
 *       {@code PASSED} AND the verified challenge is the one recorded on the token. A controller
 *       cannot produce the argument, so it cannot reach the write - the same "absence of the
 *       capability, not a check" shape as the sign-in gate. (Oscar's T353f proves this adversarially;
 *       it is deliberately not written here.)</li>
 *   <li><b>The new password is never in a place it could be applied from</b> until D: between C and D
 *       it lives BCrypt-encoded in {@code pending_password_hash} on the token, never on {@code users}
 *       and never plaintext.</li>
 * </ul>
 *
 * <p><b>The attempt cap carries over unchanged</b> because the reset code IS a {@code login_challenges}
 * row verified by the same {@link SecondFactorService#verify}: five wrong codes BURN it, exactly as
 * at sign-in. That is what makes the second code brute-force protection and not decoration.
 */
@Service
public class PasswordResetService {

    private final PasswordResetTokenRepository tokens;
    private final UserRepository users;
    private final SecondFactorService secondFactor;
    private final LoginChallengeRepository challenges;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditEventPublisher audit;
    private final SessionTerminationService sessionTermination;

    public PasswordResetService(PasswordResetTokenRepository tokens, UserRepository users,
            SecondFactorService secondFactor, LoginChallengeRepository challenges,
            PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy, AuditEventPublisher audit,
            SessionTerminationService sessionTermination) {
        this.tokens = tokens;
        this.users = users;
        this.secondFactor = secondFactor;
        this.challenges = challenges;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.audit = audit;
        this.sessionTermination = sessionTermination;
    }

    /** Step B: is this token good enough to show the new-password form? Invalid/expired/consumed all look the same. */
    @Transactional(readOnly = true)
    public boolean tokenIsUsable(String rawToken) {
        return usable(rawToken).isPresent();
    }

    /**
     * Step C: validate the chosen password, stash it (BCrypt) on the token, and issue + email the
     * reset code. The PASSWORD IS NOT APPLIED here. Returns why it was rejected, or OK.
     */
    @Transactional
    public SubmitOutcome submitNewPassword(String rawToken, String newPassword) {
        Optional<PasswordResetToken> found = usable(rawToken);
        if (found.isEmpty()) {
            return SubmitOutcome.invalidToken();
        }
        PasswordResetToken token = found.get();
        User user = users.findDetailedById(token.getUserId()).orElse(null);
        if (user == null) {
            return SubmitOutcome.invalidToken();
        }

        String organisationName = user.getOrganisation() == null ? null : user.getOrganisation().getName();
        Optional<String> rejection = passwordPolicy.rejectionFor(newPassword,
                new PasswordContext(user.getUsername(), user.getEmail(), organisationName));
        if (rejection.isPresent()) {
            return SubmitOutcome.policyRejected(rejection.get());
        }

        // Issue the reset-scoped code (purpose PASSWORD_RESET, T353b): this consumes any earlier
        // reset challenge for the user and emails the new one via the same second-factor sender.
        if (!secondFactor.issueChallenge(user, ChallengePurpose.PASSWORD_RESET)) {
            return SubmitOutcome.codeNotSent();
        }
        Long challengeId = challenges
                .findFirstByUserIdAndPurposeOrderByCreatedAtDesc(user.getId(), ChallengePurpose.PASSWORD_RESET)
                .map(LoginChallenge::getId)
                .orElse(null);

        token.attachPendingReset(passwordEncoder.encode(newPassword), challengeId);
        tokens.save(token);
        return SubmitOutcome.ok();
    }

    /**
     * Step D: verify the code against THIS token's challenge and, only if it passes, apply the reset.
     * Every failure that has a resolvable user is audited; a token that never existed writes nothing.
     */
    @Transactional
    public SecondFactorService.Outcome verifyAndApply(String rawToken, String code) {
        Optional<PasswordResetToken> found = usable(rawToken);
        if (found.isEmpty()) {
            // Invalid/expired/replayed token. If it exists (expired/consumed) we can attribute the
            // failure; a wholly unknown token writes no row (append-only / enumeration).
            hashToken(rawToken).flatMap(tokens::findByTokenHash)
                    .flatMap(t -> users.findDetailedById(t.getUserId()))
                    .ifPresent(u -> audit.passwordResetFailed(u, "invalid-or-expired-token"));
            return SecondFactorService.Outcome.NO_CHALLENGE;
        }
        PasswordResetToken token = found.get();
        User user = users.findDetailedById(token.getUserId()).orElse(null);
        if (user == null) {
            return SecondFactorService.Outcome.NO_CHALLENGE;
        }

        // The code must be the one issued for THIS token, not merely a live reset code for the user.
        // issueChallenge consumed prior reset challenges, so the newest reset challenge IS this
        // token's - unless a second reset was started, in which case this token's is stale.
        Long liveChallengeId = challenges
                .findFirstByUserIdAndPurposeOrderByCreatedAtDesc(user.getId(), ChallengePurpose.PASSWORD_RESET)
                .map(LoginChallenge::getId)
                .orElse(null);
        if (token.getChallengeId() == null || !token.getChallengeId().equals(liveChallengeId)) {
            audit.passwordResetFailed(user, "challenge-mismatch");
            return SecondFactorService.Outcome.NO_CHALLENGE;
        }

        SecondFactorService.Outcome outcome = secondFactor.verify(user, code, ChallengePurpose.PASSWORD_RESET);
        if (outcome != SecondFactorService.Outcome.PASSED) {
            audit.passwordResetFailed(user, "code-" + outcome.name().toLowerCase());
            return outcome;
        }

        applyVerifiedReset(new PasswordResetGrant(token, user));
        return SecondFactorService.Outcome.PASSED;
    }

    /**
     * THE ONLY password-write on the reset path, and it cannot be called without a grant the verifier
     * alone can mint. Applies the pending hash, consumes this token AND every other outstanding token
     * for the user, and audits the completion in the same transaction as the write.
     *
     * <p>T357 closes the gap this comment used to record. Existing authenticated SESSIONS for this
     * user are now expired here: without it the reset changed the credential and left whoever was
     * already inside exactly where they were, which is the half of the remedy nobody can see is
     * missing. There is still NO auto-sign-in - a completed reset lands on the login page.
     */
    private void applyVerifiedReset(PasswordResetGrant grant) {
        Instant now = Instant.now();
        User user = grant.user();
        user.setPassword(grant.token().getPendingPasswordHash());
        users.save(user);
        grant.token().consume(now);
        tokens.save(grant.token());
        tokens.consumeAllOutstandingForUser(user.getId(), now);
        audit.passwordResetCompleted(user);
        // T357: and anyone already signed in as this account stops being signed in. The person
        // resetting is doing it BECAUSE they think somebody else is in there; leaving that session
        // alive makes the reset a gesture.
        sessionTermination.terminateAllSessionsFor(user.getId());
    }

    private Optional<PasswordResetToken> usable(String rawToken) {
        Instant now = Instant.now();
        return hashToken(rawToken)
                .flatMap(tokens::findByTokenHash)
                .filter(t -> t.isUsable(now));
    }

    private Optional<String> hashToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(TokenHashing.sha256Hex(rawToken));
    }

    /**
     * Proof that {@link SecondFactorService#verify} returned {@code PASSED} for the code issued for
     * this exact token. A {@code private} record with the default (also private, because the record
     * is private) canonical constructor: only {@link PasswordResetService} can name or build it, so
     * {@link #applyVerifiedReset} is unreachable without going through {@link #verifyAndApply}.
     */
    private record PasswordResetGrant(PasswordResetToken token, User user) {
    }

    /** Why step C was refused, carrying the policy message when that is the reason. */
    public record SubmitOutcome(Result result, String message) {
        public enum Result { OK, INVALID_TOKEN, POLICY_REJECTED, CODE_NOT_SENT }

        static SubmitOutcome ok() {
            return new SubmitOutcome(Result.OK, null);
        }

        static SubmitOutcome invalidToken() {
            return new SubmitOutcome(Result.INVALID_TOKEN, null);
        }

        static SubmitOutcome policyRejected(String message) {
            return new SubmitOutcome(Result.POLICY_REJECTED, message);
        }

        static SubmitOutcome codeNotSent() {
            return new SubmitOutcome(Result.CODE_NOT_SENT, null);
        }
    }
}
