package ninja.samryecroft.returnhome.tracker.security.passwordreset;

/** The reset link could not be handed to the mail transport. Thrown on the async send path only. */
public class PasswordResetLinkDeliveryException extends RuntimeException {
    public PasswordResetLinkDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
