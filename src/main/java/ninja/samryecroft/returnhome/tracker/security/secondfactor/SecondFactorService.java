package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import ninja.samryecroft.returnhome.tracker.user.User;
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
    private final VerificationCodeSender sender;
    private final PasswordEncoder passwordEncoder;
    private final AuditEventPublisher audit;
    private final AppProperties appProperties;

    public SecondFactorService(LoginChallengeRepository challenges,
            VerificationCodeSender sender,
            PasswordEncoder passwordEncoder,
            AuditEventPublisher audit,
            AppProperties appProperties) {
        this.challenges = challenges;
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
        return user.getEmail() != null && !user.getEmail().isBlank();
    }

    /**
     * Issues a code and sends it.
     *
     * @return false if the resend cap has been reached - the caller must not treat that as a reason
     *         to admit the user
     */
    @Transactional
    public boolean issueChallenge(User user) {
        Instant now = Instant.now();
        long recent = challenges.countIssuedSince(user.getId(), now.minus(config().getResendWindow()));
        if (recent >= config().getMaxResends()) {
            audit.mfaFailure(user, "resend-cap-reached");
            return false;
        }

        // A previously issued code must die when a new one is born; two live codes for one account
        // means the older is a credential nobody is watching for.
        challenges.consumeOutstanding(user.getId(), now);

        String code = generateCode();
        LoginChallenge challenge = new LoginChallenge(
                user.getId(),
                passwordEncoder.encode(code),
                now.plus(config().getCodeValidity()));
        challenges.save(challenge);

        // Sent BEFORE the audit event, and if the transport throws, the exception propagates and the
        // transaction rolls the challenge back. A stored challenge whose code never left the
        // building is a guaranteed lockout that looks like a mail problem.
        sender.send(user.getEmail(), code);
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
        Instant now = Instant.now();
        Optional<LoginChallenge> found = challenges.findFirstByUserIdOrderByCreatedAtDesc(user.getId());
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
