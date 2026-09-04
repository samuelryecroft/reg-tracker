package ninja.samryecroft.returnhome.tracker.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LoginController {

    /**
     * Read from the same flag the filter chain reads, so the button appears exactly when the path
     * behind it exists. A sign-in control that 404s or bounces to an unconfigured tenant is worse
     * than no control: on this page the person cannot tell whether they are doing it wrong.
     */
    @Value("${app.auth.entra.enabled:false}")
    private boolean entraEnabled;

    @GetMapping("/login")
    public String login(Model model) {
        model.addAttribute("entraLoginEnabled", entraEnabled);
        return "login";
    }
}
