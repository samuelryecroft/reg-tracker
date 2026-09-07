package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The code-entry step. This is the only place in the application that turns a pending sign-in into
 * an authenticated session.
 */
@Controller
public class SecondFactorController {

    private final SecondFactorService secondFactorService;
    private final SecondFactorPolicy policy;
    private final UserRepository userRepository;
    private final AuditEventPublisher audit;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public SecondFactorController(SecondFactorService secondFactorService,
            SecondFactorPolicy policy,
            UserRepository userRepository,
            AuditEventPublisher audit) {
        this.secondFactorService = secondFactorService;
        this.policy = policy;
        this.userRepository = userRepository;
        this.audit = audit;
    }

    @GetMapping("/login/verify")
    public String form(HttpSession session, Model model,
            @RequestParam(required = false) String resendlimit) {
        if (pendingUser(session).isEmpty()) {
            return "redirect:/login";
        }
        model.addAttribute("resendLimitReached", resendlimit != null);
        return "auth/verify";
    }

    @PostMapping("/login/verify")
    public String verify(@RequestParam(name = "code", required = false) String code,
            HttpServletRequest request, HttpServletResponse response, HttpSession session,
            Model model) {

        Optional<User> pending = pendingUser(session);
        if (pending.isEmpty()) {
            return "redirect:/login";
        }
        User user = pending.get();

        SecondFactorService.Outcome outcome = secondFactorService.verify(user, code);
        if (outcome == SecondFactorService.Outcome.PASSED) {
            completeSignIn(user, request, response, session);
            return "redirect:/";
        }

        // A burned or expired challenge ends this attempt entirely: the pending state goes, so the
        // user starts again from the password. Leaving them on this page with a dead challenge is
        // a box that cannot accept any value, which reads as the code being wrong.
        if (outcome == SecondFactorService.Outcome.BURNED
                || outcome == SecondFactorService.Outcome.EXPIRED
                || outcome == SecondFactorService.Outcome.NO_CHALLENGE) {
            session.removeAttribute(SecondFactorSuccessHandler.PENDING_USER_ID);
            return "redirect:/login?error=" + (outcome == SecondFactorService.Outcome.BURNED
                    ? "codeburned" : "codeexpired");
        }

        model.addAttribute("codeError", true);
        return "auth/verify";
    }

    /**
     * Builds the authentication that the password stage deliberately threw away.
     *
     * <p>The session id is changed first. Spring Security normally does that for us at the moment of
     * authentication, and by taking the authentication out of that moment we took the protection
     * with it - without this line a session id captured before sign-in stays valid after it, which
     * is session fixation reintroduced by our own design.
     */
    private void completeSignIn(User user, HttpServletRequest request, HttpServletResponse response,
            HttpSession session) {
        session.removeAttribute(SecondFactorSuccessHandler.PENDING_USER_ID);
        request.changeSessionId();

        AppUserPrincipal principal = new AppUserPrincipal(user);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities());

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);

        // LOGIN_SUCCESS is written HERE, not at the password stage, and that is the whole reason
        // AuthenticationAuditListener defers to the policy. With a second factor in the way, an
        // event written when the password matched would mean "somebody knew the password" while the
        // feed goes on rendering it as "signed in" - so a person who failed the second factor would
        // appear in the trail as having signed in. One event, one meaning: both factors passed.
        audit.loginSuccess(principal);
    }

    /**
     * Loads the pending account, re-reading it from the database rather than trusting anything in
     * the session beyond the id. An account disabled between the two steps must not be admitted by
     * a code that was already in flight.
     */
    private Optional<User> pendingUser(HttpSession session) {
        Object pendingId = session.getAttribute(SecondFactorSuccessHandler.PENDING_USER_ID);
        if (!(pendingId instanceof Long id)) {
            return Optional.empty();
        }
        return userRepository.findById(id)
                .filter(User::isEnabled)
                .filter(policy::isRequiredFor);
    }
}
