package ninja.samryecroft.returnhome.tracker.security.passwordreset;

/**
 * Delivers a password-reset LINK (T353d). Deliberately separate from
 * {@code VerificationCodeSender}: that carries a code, this carries a URL, and conflating them would
 * make one message type able to become the other by a signature change.
 *
 * <p><b>What may be in the message is a security constraint, not a formatting preference</b> - the
 * same rule as the code email. The reset URL and nothing else: no young person's name, no case or
 * interview reference, nothing that says whose record this is. Mail leaves the estate and may be
 * processed outside the geography the rest of the system is pinned to, so a leaked or misdelivered
 * reset link must be worth nothing beyond a 30-minute chance to set a password on an account whose
 * address the holder already controls.
 */
public interface PasswordResetLinkSender {

    /**
     * @param emailAddress the account's own address, resolved at send time
     * @param resetLink    the absolute {@code /reset-password?token=...} URL - the plaintext token is
     *                     in it and is held only for the length of this call
     */
    void send(String emailAddress, String resetLink);
}
