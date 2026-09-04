package ninja.samryecroft.returnhome.tracker.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Docx docx = new Docx();
    private final Admin admin = new Admin();
    private final Security security = new Security();
    private final Auth auth = new Auth();

    public Docx getDocx() {
        return docx;
    }

    public Admin getAdmin() {
        return admin;
    }

    public Security getSecurity() {
        return security;
    }

    public Auth getAuth() {
        return auth;
    }

    public static class Auth {
        private final BreakGlass breakGlass = new BreakGlass();

        public BreakGlass getBreakGlass() {
            return breakGlass;
        }
    }

    /**
     * The emergency local sign-in that survives P8's removal of general form login (D2/D5).
     *
     * <p><b>Read per request rather than bound into a startup field, and that is deliberate.</b> A
     * flag latched at startup can only be turned off by a restart, and "turn this off now" is
     * precisely what you want to be able to do to an emergency credential path - if break-glass is
     * being abused, waiting for a deployment is the wrong answer. Reading the live bean also lets
     * the tests exercise the enabled path without a {@code @TestPropertySource}, which would fork a
     * Spring context and a Hikari pool (TEST-CONTEXTS.md).
     */
    public static class BreakGlass {
        private boolean enabled;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public static class Docx {
        private String templatePath;

        public String getTemplatePath() {
            return templatePath;
        }

        public void setTemplatePath(String templatePath) {
            this.templatePath = templatePath;
        }

    }

    public static class Security {
        private final LoginThrottle loginThrottle = new LoginThrottle();

        public LoginThrottle getLoginThrottle() {
            return loginThrottle;
        }
    }

    public static class LoginThrottle {
        private boolean enabled = true;
        private int maxAttempts = 5;
        private Duration lockoutDuration = Duration.ofMinutes(15);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getLockoutDuration() {
            return lockoutDuration;
        }

        public void setLockoutDuration(Duration lockoutDuration) {
            this.lockoutDuration = lockoutDuration;
        }
    }

    public static class Admin {
        private String username;
        private String password;

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }
}
