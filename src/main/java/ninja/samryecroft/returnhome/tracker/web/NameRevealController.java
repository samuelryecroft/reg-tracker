package ninja.samryecroft.returnhome.tracker.web;

import jakarta.servlet.http.HttpServletRequest;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.child.NameRevealService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * T138 1c: the shell header's "Reveal" button posts here. Reveal is a server round trip rather
 * than a client-side class swap for exactly one reason (Kevin's review): a client-side toggle
 * cannot be audited. There is no server event, so nothing would record that someone unmasked a
 * list of children - and revealing a list is at least as much professional access to safeguarding
 * data as opening one child's own record already is (see {@code AuditEventPublisher#auditViewOpened}).
 *
 * <p>One endpoint, no id of any kind in the request - it arms {@code principal}'s own session for
 * exactly the next page rendered (see {@link NameRevealService}'s javadoc for why that page-level,
 * self-consuming scope is deliberate) and records one audit event naming the page being revealed,
 * then redirects back to wherever the button was clicked from.
 */
@Controller
public class NameRevealController {

    private final NameRevealService nameRevealService;
    private final AuditEventPublisher auditEventPublisher;

    public NameRevealController(NameRevealService nameRevealService, AuditEventPublisher auditEventPublisher) {
        this.nameRevealService = nameRevealService;
        this.auditEventPublisher = auditEventPublisher;
    }

    @PostMapping("/account/reveal-names")
    public String reveal(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) String returnTo, HttpServletRequest request) {
        String target = SafeReturnTo.of(returnTo);
        nameRevealService.arm(request);
        auditEventPublisher.namesRevealed(target, principal);
        return "redirect:" + target;
    }
}
