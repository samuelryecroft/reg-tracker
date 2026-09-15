package ninja.samryecroft.returnhome.tracker.security.passwordreset;

import com.azure.communication.email.EmailAsyncClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.communication.email.models.EmailMessage;
import com.azure.core.credential.TokenCredential;
import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers the password-reset link through Azure Communication Services Email (T353d), on the SAME
 * transport, from-address and managed-identity credential as the sign-in code (T353j) - so an ACS
 * outage takes out reset and sign-in together, which is the honest coupling rather than a hidden one.
 *
 * <p><b>What must never be added to this message:</b> anything beyond the link. No name, no case or
 * interview reference. The residency argument is the code sender's, verbatim: what crosses a border
 * is a URL with a 30-minute life and a staff address, and an implementation that adds context to be
 * helpful is the change that makes the region matter.
 */
public class AcsPasswordResetLinkSender implements PasswordResetLinkSender {

    private static final Logger log = LoggerFactory.getLogger(AcsPasswordResetLinkSender.class);

    private final EmailAsyncClient client;
    private final String fromAddress;

    public AcsPasswordResetLinkSender(AppProperties appProperties, TokenCredential credential,
            String endpoint) {
        this.fromAddress = appProperties.getSecurity().getSecondFactor().getFromAddress();
        // Same fail-at-startup guard as the code sender: an unresolved ${SECOND_FACTOR_FROM_ADDRESS}
        // binds the literal placeholder, which is not blank, so the placeholder is checked too.
        if (fromAddress == null || fromAddress.isBlank() || fromAddress.contains("${")) {
            throw new IllegalStateException(
                    "app.security.second-factor.from-address is required when transport=acs, and "
                            + "must be a real address - it is currently unset or unresolved.");
        }
        this.client = new EmailClientBuilder()
                .endpoint(endpoint)
                .credential(credential)
                .buildAsyncClient();
    }

    @Override
    public void send(String emailAddress, String resetLink) {
        EmailMessage message = new EmailMessage()
                .setSenderAddress(fromAddress)
                .setToRecipients(emailAddress)
                .setSubject("Reset your password")
                // Plain text only, for the same reason the code email is: an HTML body invites a
                // template, a template invites a greeting by name, and the safety of this message is
                // that it names no one.
                .setBodyPlainText("Use this link to reset your password. It expires in 30 minutes.\n\n"
                        + resetLink + "\n\n"
                        + "If you did not ask to reset your password, you can ignore this email.");
        try {
            client.beginSend(message).getSyncPoller().waitForCompletion();
        } catch (RuntimeException ex) {
            // The address is NOT logged - this is an unauthenticated flow and a staff address must
            // not land in Log Analytics on every mail wobble.
            log.error("Password-reset link delivery failed", ex);
            throw new PasswordResetLinkDeliveryException("could not deliver password-reset link", ex);
        }
    }
}
