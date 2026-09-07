package ninja.samryecroft.returnhome.tracker.security.secondfactor;

/**
 * Delivers a one-time code. One method, because the transport decision is deliberately late-bound:
 * at the time of writing, whether Azure Communication Services Email offers a <b>United Kingdom</b>
 * data location is unresolved - the general ACS data-residency page lists the UK among its
 * geographies, while the Email resource's own provisioning documentation shows United States in
 * every example. That question is with the human, and it decides the implementation, not the logic
 * above it.
 *
 * <p><b>What may be put in the message is a security constraint, not a formatting preference.</b>
 * The code, and nothing else. No child's name, no case or interview reference, no report content,
 * nothing that says which young person this session is about to open. Mail leaves our estate and may
 * be processed outside the geography the rest of this system is pinned to, so the standing rule is
 * that a leaked or misdelivered code is worth nothing on its own. An implementation that adds
 * context to be helpful is the way that stops being true.
 */
public interface VerificationCodeSender {

    /**
     * @param emailAddress the account's own verified address, read at send time
     * @param code         the plaintext code - held only for the length of this call
     * @throws VerificationCodeDeliveryException if the code could not be handed to the transport;
     *                                           the caller must fail the sign-in loudly rather than
     *                                           leave the user waiting for a code that is not coming
     */
    void send(String emailAddress, String code);
}
