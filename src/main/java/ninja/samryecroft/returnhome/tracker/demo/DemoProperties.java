package ninja.samryecroft.returnhome.tracker.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Settings for the demo tenancy. Bound only under the {@code demo} profile, so the shared password
 * below has no meaning in any other configuration - and there is no equivalent knob in
 * {@code application.properties} that could accidentally switch it on.
 */
@Component
@Profile("demo")
@ConfigurationProperties(prefix = "app.demo")
public class DemoProperties {

    /**
     * The password every seeded demo account shares. It is a well-known value on purpose: these
     * accounts guard invented data on a throwaway database, and a demo is unusable if the presenter
     * has to look up ten different credentials. Override with {@code DEMO_PASSWORD} if a shared
     * demo box is ever exposed to a network you do not control.
     */
    private String password = "demo1234";

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
