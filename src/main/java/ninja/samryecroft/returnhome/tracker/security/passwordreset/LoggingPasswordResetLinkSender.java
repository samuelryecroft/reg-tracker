package ninja.samryecroft.returnhome.tracker.security.passwordreset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The reset-link sender for every environment that is not sending real mail (T353d). Chosen by the
 * SAME switch as the code sender - {@code app.security.second-factor.transport} - because reset mail
 * rides the same transport as sign-in codes (T353j): one knob, so the two can never be split so that
 * one sends real mail and the other logs.
 *
 * <p>{@code matchIfMissing = true}: the safe default in dev and test is to log, never to attempt a
 * real send. The wiring mirrors {@code LoggingVerificationCodeSender} exactly, including why it is
 * {@code @ConditionalOnProperty} rather than {@code @ConditionalOnMissingBean} (that condition is
 * evaluated before scanned component beans it would ask about exist).
 */
@Component
@ConditionalOnProperty(prefix = "app.security.second-factor", name = "transport",
        havingValue = "log", matchIfMissing = true)
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
