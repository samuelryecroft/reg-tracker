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

    /**
     * Sends the code for a NAMED FLOW (T353h). A sign-in code and a password-reset code are the same
     * six digits from the same generator, but they are not the same message, and getting that wrong
     * is not a tone problem.
     *
     * <p><b>The safety sentence is the reason this overload exists.</b> The sign-in message ends
     * <em>"If you were not signing in, tell your manager"</em> - that line is the product's tripwire
     * for a stolen password, and it works because it reaches someone who did NOT act. Sent
     * unchanged during a password reset it is wrong twice over: the reader knows they were not
     * signing in, so a true alarm reads as a glitch and gets ignored; and the genuinely alarming
     * case - <em>a reset code arriving when you never asked for a reset</em> - is then the one event
     * with no sentence telling anyone what it means. <b>The alarm has to describe the event it is
     * actually attached to, or it trains people to disregard it.</b>
     *
     * <p>Default-delegates to the two-argument form so that a sender which does not care about the
     * flow - the development logger, a test stub - needs no change. A real transport overrides this
     * one and lets the two-argument form delegate to it, never the reverse.
     *
     * @param purpose which flow minted the code; never rendered verbatim into the message
     */
    default void send(String emailAddress, String code, ChallengePurpose purpose) {
        send(emailAddress, code);
    }
}
