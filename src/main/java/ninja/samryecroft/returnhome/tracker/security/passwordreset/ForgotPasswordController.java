package ninja.samryecroft.returnhome.tracker.security.passwordreset;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Step A of the reset flow (T353d): the unauthenticated {@code /forgot-password} request page.
 *
 * <p>The POST ALWAYS renders the same neutral confirmation, whatever the service did - a match, a
 * no-match, a disabled account, the break-glass account, or a throttled request all land here. The
 * controller cannot leak which, because it is not told: {@link PasswordResetRequestService#requestReset}
 * returns nothing, and everything that distinguishes the cases (the email, the audit row) happens
 * inside it and after commit. See that service for why the neutral response is honest rather than a
 * comforting lie.
 */
@Controller
public class ForgotPasswordController {

    private final PasswordResetRequestService requestService;

    public ForgotPasswordController(PasswordResetRequestService requestService) {
        this.requestService = requestService;
    }

    @GetMapping("/forgot-password")
    public String form() {
        return "auth/forgot-password";
    }

    @PostMapping("/forgot-password")
    public String request(@RequestParam("email") String email, HttpServletRequest httpRequest) {
        // getRemoteAddr() is the real client IP in production: the azure profile sets
        // server.forward-headers-strategy=FRAMEWORK, so Spring's ForwardedHeaderFilter has already
        // resolved X-Forwarded-For by the time this runs. In dev it is the direct peer, which is fine.
        String ip = httpRequest.getRemoteAddr();
        // The origin the link is built against, taken from THIS request rather than a configured base
        // URL, so it is correct whatever host the app is reached on and there is nothing to misconfigure.
        String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        requestService.requestReset(email, ip, baseUrl);
        return "auth/forgot-password-sent";
    }
}
