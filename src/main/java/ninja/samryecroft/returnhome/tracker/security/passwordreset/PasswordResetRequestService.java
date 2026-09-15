package ninja.samryecroft.returnhome.tracker.security.passwordreset;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Step A of the reset flow (T353d): {@code /forgot-password} was submitted. This is the whole of the
 * request side - mint a token and email a link IF AND ONLY IF a real, enabled, non-break-glass
 * account matched, and do nothing observably different when one did not.
 *
 * <p><b>The neutral response is defended here, not in the controller.</b> The controller renders the
 * same page whatever this returns; the properties that make that page honest live in this method:
 * <ul>
 *   <li><b>No branch a stranger can time.</b> The throttle check and the account lookup run for
 *       every request; only a MATCH additionally schedules an email, and that email is sent AFTER
 *       COMMIT on another thread, so the SMTP round-trip is never on the response path in either
 *       branch. The empty branch does the same lookup and returns - it does not send, and it does
 *       not sleep to pretend it did.</li>
 *   <li><b>No audit row on a no-match.</b> {@code audit_events} is append-only and this endpoint is
 *       unauthenticated, so a row keyed on an unmatched address would be the enumeration oracle the
 *       response refuses to be AND an unbounded write into a table nothing can prune. The
 *       failed-request signal lives in the throttle counters and the application log, which are
 *       prunable. See {@code AuditEventType.PASSWORD_RESET_REQUESTED}.</li>
 *   <li><b>Never mail the non-account.</b> A "someone tried to reset your password" notice would
 *       confirm non-existence to whoever owns the address and make the endpoint a mail cannon aimed
 *       at third parties.</li>
 * </ul>
 */
@Service
public class PasswordResetRequestService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetRequestService.class);

    /** 32 bytes = 256 bits. High enough entropy that the token cannot be brute-forced, which is why
     *  only its SHA-256 hash is stored and the lookup can be by that hash (V27). */
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();

    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordResetLinkSender linkSender;
    private final PasswordResetThrottle throttle;
    private final AuditEventPublisher audit;
    private final AppProperties appProperties;
    private final TaskExecutor taskExecutor;

    public PasswordResetRequestService(UserRepository users, PasswordResetTokenRepository tokens,
            PasswordResetLinkSender linkSender, PasswordResetThrottle throttle,
            AuditEventPublisher audit, AppProperties appProperties, TaskExecutor taskExecutor) {
        this.users = users;
        this.tokens = tokens;
        this.linkSender = linkSender;
        this.throttle = throttle;
        this.audit = audit;
        this.appProperties = appProperties;
        this.taskExecutor = taskExecutor;
    }

    /**
     * @param submittedEmail the address as typed
     * @param ipAddress      the source IP, for the throttle and (on a match) the token's forensic column
     * @param baseUrl        the absolute origin the reset link is built against, e.g. {@code https://host}
     */
    @Transactional
    public void requestReset(String submittedEmail, String ipAddress, String baseUrl) {
        if (submittedEmail == null || submittedEmail.isBlank()) {
            return;
        }
        String email = submittedEmail.trim();

        // Throttle first, before any DB work, so a throttled request never touches the database and
        // never takes a different code path that would reveal whether the address exists.
        if (!throttle.allow(email, ipAddress)) {
            return;
        }

        Optional<User> match = users.findResettableByEmail(email);
        if (match.isEmpty()) {
            // No account, or disabled, or break-glass: nothing written, nothing sent, nothing logged
            // to the audit trail. This silence IS the feature.
            return;
        }
        User user = match.get();

        String rawToken = newToken();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(appProperties.getSecurity().getPasswordReset().getLinkValidity());
        tokens.save(new PasswordResetToken(user.getId(), TokenHashing.sha256Hex(rawToken), expiresAt, ipAddress));

        // Actor is the target user (unauthenticated flow); only ever on a match. Read while the
        // persistence context is open, so the publisher's read of roles does not detach-fault.
        audit.passwordResetRequested(user);

        // The link carries the RAW token; only its hash is stored. Sent after commit, off the
        // response thread - the address and link are captured as strings so nothing lazy is touched
        // on the pool thread.
        String recipient = user.getEmail();
        String resetLink = baseUrl + "/reset-password?token=" + rawToken;
        sendAfterCommit(recipient, resetLink);
    }

    private void sendAfterCommit(String recipient, String resetLink) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(recipient, resetLink);
                }
            });
        } else {
            dispatch(recipient, resetLink);
        }
    }

    private void dispatch(String recipient, String resetLink) {
        taskExecutor.execute(() -> {
            try {
                linkSender.send(recipient, resetLink);
            } catch (RuntimeException ex) {
                // Off the request thread and after commit: the user has already been shown the
                // neutral page, and a mail failure must not surface to them (it would leak that the
                // address matched). Loud in the log, silent to the caller. They can request again.
                log.error("Password-reset link delivery failed after commit", ex);
            }
        });
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
