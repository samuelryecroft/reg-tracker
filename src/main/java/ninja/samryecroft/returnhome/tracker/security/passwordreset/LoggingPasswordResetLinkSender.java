package ninja.samryecroft.returnhome.tracker.security.passwordreset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The reset-link sender for every environment that is not sending real mail (T353d). Selected by
 * {@link PasswordResetLinkSenderConfig} whenever the transport is not {@code acs} - including
 * {@code log} (the default), the {@code capturing} value tests use, and an unset transport. Logging,
 * not sending, is the safe default: dev and test never attempt a real send.
 *
 * <p>The address IS logged here, deliberately and only in this transport, which never runs in
 * production - a developer driving the reset flow needs to see where the link went. The ACS sender
 * logs neither.
 */
public class LoggingPasswordResetLinkSender implements PasswordResetLinkSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPasswordResetLinkSender.class);

    @Override
    public void send(String emailAddress, String resetLink) {
        // The address IS logged here, deliberately and only in the log transport: this bean never
        // runs in production, and a developer driving the reset flow needs to see where the link
        // went and what it was. The ACS sender logs neither.
        log.info("[password-reset] would send reset link to {}: {}", emailAddress, resetLink);
    }
}
