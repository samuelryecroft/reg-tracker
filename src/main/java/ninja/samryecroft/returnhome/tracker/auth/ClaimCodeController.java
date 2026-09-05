package ninja.samryecroft.returnhome.tracker.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.user.User;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The one screen a person sees when their Entra sign-in succeeded and no application user is linked
 * to them yet (T197).
 *
 * <h2>Nobody is signed in on this screen</h2>
 *
 * <p><b>There is no {@code Authentication} in the security context while this controller runs, and
 * that is the whole point.</b> The OIDC attempt ended in a refusal; what survives it is one value in
 * the HTTP session - the directory {@code oid} - placed there by {@link ClaimCodeFailureHandler}.
 * The session at this moment carries no authorities, no principal and no access to any application
 * route; every one of them still requires an authenticated user, unchanged.
 *
 * <p>The design named the trap: the natural build is to log the user in and then ask for the code,
 * which authorises an unlinked identity for the duration of the exchange and would pass every
 * functional test of the flow. <b>So redemption does not sign anyone in either.</b> It pins the
 * {@code oid} and sends the person back through the front door, where the ordinary
 * {@code findByIdpSubject} path now finds them - one extra redirect, silent because their Entra
 * session is live, and no code anywhere that turns a half-made identity into a principal.
 *
 * <h2>One message for every refusal</h2>
 *
 * <p>Wrong, expired, already used, or issued to an account that is already linked all say the same
 * thing. Distinguishing them would tell someone holding a guess whether a code ever existed, and the
 * remedy is identical in every case: ask an administrator to issue a new one.
 */
@Controller
public class ClaimCodeController {

    /** Where the failure handler leaves the oid, and the only thing the exchange carries. */
    static final String OBJECT_ID_ATTRIBUTE = "t197.claimCode.objectId";

    private static final String REFUSED =
            "That code is not valid. Codes expire and can only be used once - ask an administrator to issue a new one.";

    private final ClaimCodeService claimCodeService;
    private final AuditEventPublisher auditEventPublisher;

    public ClaimCodeController(ClaimCodeService claimCodeService, AuditEventPublisher auditEventPublisher) {
        this.claimCodeService = claimCodeService;
        this.auditEventPublisher = auditEventPublisher;
    }

    @GetMapping("/onboarding/claim")
    public String form(HttpSession session, Model model) {
        if (objectIdIn(session) == null) {
            // Reached without a completed sign-in, so there is no identity to pin anything to.
            // Nothing to explain and nothing to attempt.
            return "redirect:/login";
        }
        model.addAttribute("refused", false);
        return "auth/claim-code";
    }

    @PostMapping("/onboarding/claim")
    public String redeem(@RequestParam("code") String code, HttpSession session,
            HttpServletRequest request, Model model) {
        String objectId = objectIdIn(session);
        if (objectId == null) {
            return "redirect:/login";
        }

        User user = claimCodeService.findRedeemable(code).orElse(null);
        if (user == null) {
            // The code is NEVER echoed, logged or audited - it is a credential, and this is the
            // path where a wrong one is most tempting to include "for diagnosis".
            auditEventPublisher.identityLinkRefused(objectId, request.getRequestURI());
            model.addAttribute("refused", true);
            return "auth/claim-code";
        }

        claimCodeService.redeem(user, objectId);
        auditEventPublisher.identityLinked(user, objectId);
        // The oid is spent; nothing may reuse it.
        session.removeAttribute(OBJECT_ID_ATTRIBUTE);

        // Back through the front door rather than signing them in here. Their Entra session is live,
        // so this is a silent redirect - and the principal that results is built by the ordinary
        // path, from a user who is now genuinely linked.
        return "redirect:/oauth2/authorization/entra";
    }

    private static String objectIdIn(HttpSession session) {
        Object value = session.getAttribute(OBJECT_ID_ATTRIBUTE);
        return value instanceof String objectId && !objectId.isBlank() ? objectId : null;
    }
}
