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
     *
     * <p><strong>Well-known is not the same as weak, and T312 is the difference.</strong> This was
     * {@code demo1234} - eight characters against a twelve-character minimum - so the policy refused
     * it on the one seeding path that checks it, and the demo admin could not sign in at all. A demo
     * credential still has to be a credential the product itself would accept, or the demo is not
     * demonstrating the product.
     *
     * <p>This default and the one in {@code application-demo.properties} are two spellings of one
     * value, which is the shape that drifts. They are asserted equal by
     * {@code TheDemoCredentialsStillSatisfyThePasswordPolicyTest} rather than trusted to agree.
     */
    private String password = "demo-walkthrough";

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
