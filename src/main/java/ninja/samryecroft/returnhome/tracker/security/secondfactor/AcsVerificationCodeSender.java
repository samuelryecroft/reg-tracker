package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import com.azure.communication.email.EmailAsyncClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.communication.email.models.EmailMessage;
import com.azure.core.credential.TokenCredential;
import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Delivers the second-factor code through Azure Communication Services Email.
 *
 * <p><b>UK data location, ruled by the human:</b> <i>"I have checked we can make this UK based - no PII
 * should ever be in this message anyway so another location would be acceptable."</i> The resource is
 * provisioned with a UK data location; US is an accepted fallback if UK proves unavailable, and that
 * fallback needs no second decision. <b>The reason it is acceptable is the design constraint, not the
 * region:</b> this message carries a six-digit code and nothing else, so what would cross a border is
 * a number with a ten-minute life and a staff address.
 *
 * <p><b>What must never be added to this message.</b> No young person's name, no case or interview
 * reference, no report content, nothing naming who the session is about to open. That is what keeps
 * residency a paperwork question rather than an exposure one. An implementation that adds context to
 * be helpful is the change that makes the region matter - and by then the mail has already been sent.
 *
 * <p>Authenticates as the application's managed identity through the <b>shared</b>
 * {@code TokenCredential} bean, the same way Blob and Key Vault do, so there is no connection string
 * or access key to hold anywhere.
 */
@Component
@ConditionalOnProperty(prefix = "app.security.second-factor", name = "transport", havingValue = "acs")
public class AcsVerificationCodeSender implements VerificationCodeSender {

    private static final Logger log = LoggerFactory.getLogger(AcsVerificationCodeSender.class);

    private final EmailAsyncClient client;
    private final String fromAddress;

    public AcsVerificationCodeSender(AppProperties appProperties,
            TokenCredential credential,
            @Value("${app.security.second-factor.acs-endpoint}") String endpoint) {
        this.fromAddress = appProperties.getSecurity().getSecondFactor().getFromAddress();
        // Fails at startup rather than at the first sign-in. A deployment missing its sender address
        // would otherwise look healthy until somebody tried to log in.
        //
        // "Still a placeholder" is checked as well as blank, and it is the case that actually
        // happens: an unset ${SECOND_FACTOR_FROM_ADDRESS} does NOT fail configuration-properties
        // binding - it binds the literal placeholder text, which is not blank. So the blank check
        // alone passed cleanly on exactly the deployment it exists to catch, and the sender was
        // built pointing at a sender address of "${SECOND_FACTOR_FROM_ADDRESS}".
        if (fromAddress == null || fromAddress.isBlank() || fromAddress.contains("${")) {
            throw new IllegalStateException(
                    "app.security.second-factor.from-address is required when transport=acs, and "
                            + "must be a real address - it is currently unset or unresolved. Set "
                            + "SECOND_FACTOR_FROM_ADDRESS alongside SECOND_FACTOR_TRANSPORT=acs.");
        }
        this.client = new EmailClientBuilder()
                .endpoint(endpoint)
                // The SHARED credential bean, injected rather than built. A credential owns its
                // token cache, so a second one does not share a token with the first and a cold
                // container pays the acquisition again - 6-7 seconds each, measured (T181).
                .credential(credential)
                .buildAsyncClient();
    }

    @Override
    public void send(String emailAddress, String code) {
        send(emailAddress, code, ChallengePurpose.SIGN_IN);
    }

    /**
     * T353h. Two flows, two messages - and the difference that matters is the last line, not the
     * subject. See {@link VerificationCodeSender#send(String, String, ChallengePurpose)} for why an
     * alarm attached to the wrong event is worse than no alarm.
     *
     * <p><b>Both messages still carry the code and nothing else.</b> No name, no case or interview
     * reference, no mention of a young person. The reset message names an ACTION the reader just
     * took; it never names a PERSON. That constraint is the residency argument and it is unchanged.
     */
    @Override
    public void send(String emailAddress, String code, ChallengePurpose purpose) {
        boolean reset = purpose == ChallengePurpose.PASSWORD_RESET;
        EmailMessage message = new EmailMessage()
                .setSenderAddress(fromAddress)
                .setToRecipients(emailAddress)
                // Distinct subjects because both messages can sit in one inbox at once, a minute
                // apart, containing different six-digit numbers. "Your code" for both would make the
                // two indistinguishable in a notification preview, which is where they are read.
                .setSubject(reset ? "Your password reset code" : "Your sign-in code")
                // Plain text only. An HTML body invites a template, a template invites a greeting by
                // name, and the whole safety of this message is that it says nothing about anyone.
                .setBodyPlainText(reset
                        ? code + " is your code to finish resetting your password. It expires in "
                                + "10 minutes.\n\n"
                                + "Your password has not changed yet. It only changes once this code "
                                + "is entered.\n\n"
                                + "If you did not ask to reset your password, do not enter this code "
                                + "- someone else has your reset link. Tell your manager."
                        : code + " is your code to sign in. It expires in 10 minutes.\n\n"
                                + "If you were not signing in, tell your manager.");

        try {
            // Blocks on purpose: the caller is inside the sign-in transaction and must not report a
            // code as sent until the service has accepted it. An accepted-but-unsent code is a
            // guaranteed lockout that presents as a mail problem.
            client.beginSend(message).getSyncPoller().waitForCompletion();
        } catch (RuntimeException ex) {
            // The address is NOT logged. This runs on the failure path of a sign-in, which is exactly
            // where somebody would later add "which address?" to help debugging - and that is a staff
            // member's address landing in Log Analytics on every mail wobble.
            log.error("Second-factor code delivery failed", ex);
            throw new VerificationCodeDeliveryException("could not deliver second-factor code", ex);
        }
    }
}
