package ninja.samryecroft.returnhome.tracker.security.passwordreset;

import ninja.samryecroft.returnhome.tracker.security.secondfactor.SecondFactorService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The reset endpoints B/C/D (T353e), all unauthenticated. The controller only routes; the ordering
 * invariant (no password change before the code verifies) is enforced in {@link PasswordResetService}
 * by the fact that the write path cannot be reached without a grant the verifier alone can mint.
 *
 * <p>An invalid, expired or already-used token renders one page - "this link is no longer valid" -
 * with no distinction between the three, because the difference is only ever useful to a prober.
 * A completed reset lands on the login page: NO auto-sign-in (that would hand a session to whoever
 * holds the mailbox and bypass the sign-in second factor).
 */
@Controller
public class PasswordResetController {

    private static final String FORM = "auth/reset-password";
    private static final String CODE_FORM = "auth/reset-password-code";
    private static final String INVALID = "auth/reset-password-invalid";

    private final PasswordResetService resetService;

    public PasswordResetController(PasswordResetService resetService) {
        this.resetService = resetService;
    }

    /** Step B: the link is followed. Show the new-password form only if the token is still good. */
    @GetMapping("/reset-password")
    public String form(@RequestParam("token") String token, Model model) {
        if (!resetService.tokenIsUsable(token)) {
            return INVALID;
        }
        model.addAttribute("token", token);
        return FORM;
    }

    /** Step C: the new password is submitted. The password is NOT applied - a code is emailed. */
    @PostMapping("/reset-password")
    public String submit(@RequestParam("token") String token,
            @RequestParam("newPassword") String newPassword, Model model) {
        PasswordResetService.SubmitOutcome outcome = resetService.submitNewPassword(token, newPassword);
        return switch (outcome.result()) {
            case OK -> {
                model.addAttribute("token", token);
                yield CODE_FORM;
            }
            case INVALID_TOKEN -> INVALID;
            case POLICY_REJECTED -> {
                model.addAttribute("token", token);
                model.addAttribute("error", outcome.message());
                yield FORM;
            }
            case CODE_NOT_SENT -> {
                model.addAttribute("token", token);
                model.addAttribute("error", "We couldn't send a code just now. Please try again shortly.");
                yield FORM;
            }
        };
    }

    /** Step D: the code is submitted. Only a PASSED code applies the password (via the grant). */
    @PostMapping("/reset-password/verify")
    public String verify(@RequestParam("token") String token,
            @RequestParam("code") String code, Model model) {
        SecondFactorService.Outcome outcome = resetService.verifyAndApply(token, code);
        return switch (outcome) {
            case PASSED -> "redirect:/login?reset";
            case WRONG_CODE -> {
                model.addAttribute("token", token);
                model.addAttribute("error", "That code didn't match. Check the email and try again.");
                yield CODE_FORM;
            }
            case BURNED, EXPIRED, NO_CHALLENGE -> INVALID;
        };
    }
}
