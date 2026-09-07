package ninja.samryecroft.returnhome.tracker.security.secondfactor;

/**
 * The code could not be handed to the transport.
 *
 * <p>This is thrown rather than swallowed because the alternative is the worst outcome available: a
 * user staring at a code entry box waiting for a message that was never sent, with nothing on screen
 * distinguishing that from a slow mail server. A failure to send is an authentication failure and
 * has to look like one.
 */
public class VerificationCodeDeliveryException extends RuntimeException {

    public VerificationCodeDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
