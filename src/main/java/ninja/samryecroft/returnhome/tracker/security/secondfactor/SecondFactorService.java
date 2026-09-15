package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and checks the emailed one-time code (T322).
 *
 * <p><b>What this class is not:</b> it does not decide whether a session is authenticated, and it
 * never touches the {@code SecurityContext}. That separation is deliberate - the gate is enforced by
 * the fact that no authentication exists until {@code SecondFactorAuthenticationController}
 * completes, and a service that could quietly create one would be a second way in.
 */
@Service
public class SecondFactorService {

    private static final Logger log = LoggerFactory.getLogger(SecondFactorService.class);

    /**
     * {@link SecureRandom} rather than {@code Math.random()} or a seeded {@code Random}: this is a
     * credential, and a predictable one is no better than none. Held as a field because seeding is
     * the expensive part and re-seeding per code buys nothing.
     */
    private final SecureRandom random = new SecureRandom();

    private final LoginChallengeRepository challenges;
    private final UserRepository users;
    private final VerificationCodeSender sender;
    private final PasswordEncoder passwordEncoder;
    private final AuditEventPublisher audit;
    private final AppProperties appProperties;

    public SecondFactorService(LoginChallengeRepository challenges,
            UserRepository users,
            VerificationCodeSender sender,
            PasswordEncoder passwordEncoder,
            AuditEventPublisher audit,
            AppProperties appProperties) {
        this.challenges = challenges;
        this.users = users;
        this.sender = sender;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.appProperties = appProperties;
    }

    public boolean isEnabled() {
        return config().isEnabled();
    }

    /**
     * Whether this account can be asked for a second factor at all.
     *
     * <p>An account with no address cannot receive a code, and the honest answer is to say so at
     * configuration time rather than to let the person discover it at a login box. Callers must
     * treat {@code false} as "this account cannot sign in", never as "skip the factor" - a missing
     * address turning MFA OFF for that account is precisely the bypass this method exists to avoid.
     */
    public boolean canChallenge(User user) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return false;
        }
        // An address that has never been proven to receive mail gets a small, bounded allowance.
        // Once it is spent, this account cannot sign in until an administrator corrects the address
        // - which is the intended outcome for a typo, and the only way to stop a stranger receiving
        // sign-in codes for a child's record on every attempt from here to eternity.
        return user.isEmailVerified()
                || user.getUnverifiedChallengeCount() < config().getMaxUnverifiedChallenges();
    }

    /**
     * Issues a code and sends it.
     *
     * @return false if the resend cap has been reached - the caller must not treat that as a reason
     *         to admit the user
     */
    @Transactional
    public boolean issueChallenge(User user) {
        return issueChallenge(user, ChallengePurpose.SIGN_IN);
    }

    /**
     * Issues a code for a specific flow (T353b). The purpose scopes the resend cap and the retiring
     * of the previous code, so issuing a reset code neither counts against a user's sign-in resend
     * allowance nor kills a live sign-in code, and vice versa.
     */
    @Transactional
    public boolean issueChallenge(User user, ChallengePurpose purpose) {
        Instant now = Instant.now();
        long recent = challenges.countIssuedSince(user.getId(), purpose,
                now.minus(config().getResendWindow()));
        if (recent >= config().getMaxResends()) {
            audit.mfaFailure(user, "resend-cap-reached");
            return false;
        }

        // A previously issued code must die when a new one is born; two live codes for one account
        // means the older is a credential nobody is watching for. Scoped to this flow: the other
        // flow's live code is not ours to retire.
        challenges.consumeOutstanding(user.getId(), purpose, now);

        String code = generateCode();
        LoginChallenge challenge = new LoginChallenge(
                user.getId(),
                passwordEncoder.encode(code),
                now.plus(config().getCodeValidity()),
                purpose);
        challenges.save(challenge);

        // Sent BEFORE the audit event, and if the transport throws, the exception propagates and the
        // transaction rolls the challenge back. A stored challenge whose code never left the
        // building is a guaranteed lockout that looks like a mail problem.
        sender.send(user.getEmail(), code);

        // Counted only AFTER the transport accepted it, so a mail outage does not burn a user's
        // allowance for an address that may be perfectly correct.
        if (!user.isEmailVerified()) {
            user.recordUnverifiedChallenge();
            users.save(user);
        }

        audit.mfaChallengeIssued(user);
        return true;
    }

    /**
     * Checks a submitted code.
     *
     * <p>The attempt count is incremented and saved <b>whether or not the code matches</b>. That is
     * the whole of the burn: a challenge that only counts its successes is a challenge an attacker
     * may guess at indefinitely.
     */
    @Transactional
    public Outcome verify(User user, String submittedCode) {
        return verify(user, submittedCode, ChallengePurpose.SIGN_IN);
    }

    /**
     * Checks a submitted code against this user's newest challenge IN THE GIVEN FLOW (T353b). A code
     * issued for one purpose can never satisfy another's verifier, because the lookup itself is
     * purpose-scoped - the two flows are kept apart by the query, not by a check a later caller could
     * forget.
     */
    @Transactional
    public Outcome verify(User user, String submittedCode, ChallengePurpose purpose) {
        Instant now = Instant.now();
        Optional<LoginChallenge> found =
                challenges.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(user.getId(), purpose);
        if (found.isEmpty()) {
            audit.mfaFailure(user, "no-challenge");
            return Outcome.NO_CHALLENGE;
        }

        LoginChallenge challenge = found.get();
        if (!challenge.isUsable(now, config().getMaxAttempts())) {
            audit.mfaFailure(user, "challenge-not-usable");
            return Outcome.EXPIRED;
        }

        challenge.recordAttempt();
        boolean matches = submittedCode != null
                && passwordEncoder.matches(submittedCode, challenge.getCodeHash());

        if (!matches) {
            challenges.save(challenge);
            if (challenge.getAttempts() >= config().getMaxAttempts()) {
                audit.mfaLocked(user);
                log.warn("Second-factor challenge burned for user id {} after {} wrong codes",
                        user.getId(), challenge.getAttempts());
                return Outcome.BURNED;
            }
            audit.mfaFailure(user, "wrong-code");
            return Outcome.WRONG_CODE;
        }

        challenge.consume(now);
        challenges.save(challenge);

        // "Verified on first use": a code that was received and used is the proof, so no separate
        // confirmation step exists and a correct address costs the user nothing.
        //
        // THE LIMIT, stated here because this line is where someone will later read a guarantee into
        // it: this proves the address is DELIVERABLE, not that it belongs to the intended person. A
        // confirmation sent to an address can always be completed by whoever controls that address,
        // so this is not - and cannot be made into - a control against whoever chose the address.
        if (!user.isEmailVerified()) {
            user.markEmailVerified(LocalDateTime.now());
            users.save(user);
        }

        audit.mfaSuccess(user);
        return Outcome.PASSED;
    }

    /**
     * Left-padded to the configured width. Taking {@code random.nextInt(1_000_000)} and printing it
     * without padding would produce a code of fewer digits about one time in ten and quietly shrink
     * the space for those users.
     */
    private String generateCode() {
        int digits = config().getCodeLength();
        int bound = (int) Math.pow(10, digits);
        return String.format("%0" + digits + "d", random.nextInt(bound));
    }

    private AppProperties.SecondFactor config() {
        return appProperties.getSecurity().getSecondFactor();
    }

    /**
     * Why the caller is being refused. {@code WRONG_CODE} and {@code BURNED} are separate because
     * the user needs different things: one more try, versus start again.
     */
    public enum Outcome {
        PASSED,
        WRONG_CODE,
        BURNED,
        EXPIRED,
        NO_CHALLENGE
    }
}
