package ninja.samryecroft.returnhome.tracker.export;

/**
 * T218: a download link that cannot be redeemed - <b>and deliberately does not say why.</b>
 *
 * <p>{@link ExportLinkService#redeem} answers {@code Optional.empty()} for four different
 * situations: the token is unknown, it has expired, it has already been spent, or it belongs to
 * another user. <b>Those four collapse into one empty Optional before this class exists</b>, so the
 * controller cannot tell them apart even if a later edit wanted to. This exception carries no cause
 * and no detail for the same reason.
 *
 * <p><b>Distinguishing them would be an enumeration oracle:</b> telling someone holding a guessed
 * token that it is "expired" rather than "unknown" tells them the token was once real, which is
 * most of the work of finding a live one. The same shape as T215's lockout banner and 6c's
 * 404-not-403 rule - and the third place in this codebase where the safe answer is that two states
 * must be indistinguishable rather than that one of them must be worded carefully.
 *
 * <p>Exists only so the download path can render a page instead of a bare 404 body. It says nothing
 * a bare 404 did not.
 */
public class ExportLinkUnavailableException extends RuntimeException {

    public ExportLinkUnavailableException() {
        super("Export link cannot be redeemed");
    }
}
