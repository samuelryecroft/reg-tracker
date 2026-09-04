package ninja.samryecroft.returnhome.tracker.child;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves and arms the page-level name-reveal state (spec §2.5, Kevin's masking design
 * conversation, T138 1c).
 *
 * <p>What persists per user is only the <em>posture</em> - start masked, offer a reveal control.
 * The reveal state itself is deliberately <strong>not</strong> persisted: it is a one-shot flag,
 * armed by a POST to the reveal endpoint and consumed by exactly the one page that POST redirects
 * to. Navigate anywhere else and the flag is already gone - nobody stays unmasked site-wide from a
 * single click. That is the point of the control: a persisted "revealed" preference would leave
 * children's names on screen all day in a shared office, which is the exact exposure masking
 * exists to prevent, and it would empty the reveal audit event of meaning (one click, indefinite
 * exposure, no further record of who was looking at what).
 *
 * <p>The session attribute is consumed (removed) the first time {@link #isRevealed} runs in a
 * request and the result is cached on the {@code HttpServletRequest} for the rest of that same
 * request - so it does not matter whether {@code GlobalControllerAdvice}'s model attribute or a
 * controller's own handler method asks first; both see the same answer, and the flag is still
 * gone by the next request either way.
 *
 * <p><strong>What actually bounds the flag's lifetime is consumption on EVERY request, not
 * consumption on read</strong> (Kevin's review). {@code GlobalControllerAdvice} calls
 * {@link #isRevealed(HttpServletRequest)} as a {@code @ModelAttribute}, which Spring runs for
 * every request a {@code @Controller} handles - including one whose page has no {@link
 * ChildIdentity} on it at all. That is what guarantees the flag cannot survive into a later,
 * unrelated page: if consumption were left to only the templates that actually print a masked
 * name, a redirect to a page with none would leave the flag armed and unmask something the user
 * never asked to reveal, the next time they did land somewhere with a child on it.
 *
 * <p>This also means {@link #arm} must always be followed by a redirect, never a same-request
 * render. The ordering that makes the whole scheme safe is: the advice's {@code @ModelAttribute}
 * runs BEFORE the handler method, so on the reveal POST itself {@link #isRevealed} already
 * consumed any stale flag and cached {@code false} for this request - only after that does {@link
 * #arm} set a fresh one, for the request the redirect triggers. A handler that armed and rendered
 * in the same request (no redirect) would silently fail to reveal that render (the cache already
 * says false) and would leak the reveal into whatever page came next instead.
 */
@Service
public class NameRevealService {

    static final String SESSION_KEY = "childNames.armedForNextPage";
    private static final String REQUEST_CACHE_KEY = "childNames.revealedThisRequest";

    /** True only for the single page rendered immediately after {@link #arm}. */
    public boolean isRevealed(HttpServletRequest request) {
        Object cached = request.getAttribute(REQUEST_CACHE_KEY);
        if (cached != null) {
            return (Boolean) cached;
        }
        HttpSession session = request.getSession(false);
        boolean revealed = session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_KEY));
        if (session != null) {
            session.removeAttribute(SESSION_KEY);
        }
        request.setAttribute(REQUEST_CACHE_KEY, revealed);
        return revealed;
    }

    /** Convenience for callers already inside a request thread - every controller and the advice. */
    public boolean isRevealed() {
        return isRevealed(currentRequest());
    }

    /** Arms the flag so the very next page rendered - the reveal POST's own redirect target - comes back revealed. */
    public void arm(HttpServletRequest request) {
        request.getSession(true).setAttribute(SESSION_KEY, Boolean.TRUE);
    }

    private static HttpServletRequest currentRequest() {
        return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    }
}
