package ninja.samryecroft.returnhome.tracker.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Docx docx = new Docx();
    private final Admin admin = new Admin();
    private final Security security = new Security();

    public Docx getDocx() {
        return docx;
    }

    public Admin getAdmin() {
        return admin;
    }

    public Security getSecurity() {
        return security;
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
        private final SecondFactor secondFactor = new SecondFactor();

        public LoginThrottle getLoginThrottle() {
            return loginThrottle;
        }

        public SecondFactor getSecondFactor() {
            return secondFactor;
        }
    }

    /**
     * T322: the emailed one-time code. Off by default, and deliberately so - a deployment that has
     * not yet arranged a sending identity would otherwise turn a working login into a locked door
     * on upgrade. Turning it ON is the decision; nothing else switches it on as a side effect.
     */
    public static class SecondFactor {
        private boolean enabled = false;
        private int codeLength = 6;
        private Duration codeValidity = Duration.ofMinutes(10);

        /**
         * Attempts before the challenge is BURNED, not merely delayed. Six digits is 10^6, which a
         * challenge that survives its own failures gives up eventually.
         */
        private int maxAttempts = 5;

        /** Resends allowed inside {@link #resendWindow}, so the form is not a mail amplifier. */
        private int maxResends = 3;

        /**
         * How many codes may be sent to an address that has never been proven to receive mail
         * (T322). This is what bounds a mistyped address: without a ceiling, a wrong mailbox gets a
         * sign-in code on every attempt, forever. Small on purpose - a correct address is proven by
         * the first code that is used.
         */
        private int maxUnverifiedChallenges = 3;
        private Duration resendWindow = Duration.ofMinutes(15);

        /** From-address for the code. No default: a deployment must state who the mail is from. */
        private String fromAddress;

        /**
         * Which sender delivers the code. Named explicitly rather than inferred, matching
         * {@code app.documents.storage}: "log" writes codes to the application log and is for local
         * development only - it refuses to start a deployed environment with the factor switched on.
         */
        private String transport = "log";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getCodeLength() {
            return codeLength;
        }

        public void setCodeLength(int codeLength) {
            this.codeLength = codeLength;
        }

        public Duration getCodeValidity() {
            return codeValidity;
        }

        public void setCodeValidity(Duration codeValidity) {
            this.codeValidity = codeValidity;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public int getMaxResends() {
            return maxResends;
        }

        public int getMaxUnverifiedChallenges() {
            return maxUnverifiedChallenges;
        }

        public void setMaxUnverifiedChallenges(int maxUnverifiedChallenges) {
            this.maxUnverifiedChallenges = maxUnverifiedChallenges;
        }

        public void setMaxResends(int maxResends) {
            this.maxResends = maxResends;
        }

        public Duration getResendWindow() {
            return resendWindow;
        }

        public void setResendWindow(Duration resendWindow) {
            this.resendWindow = resendWindow;
        }

        public String getFromAddress() {
            return fromAddress;
        }

        public void setFromAddress(String fromAddress) {
            this.fromAddress = fromAddress;
        }

        public String getTransport() {
            return transport;
        }

        public void setTransport(String transport) {
            this.transport = transport;
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
