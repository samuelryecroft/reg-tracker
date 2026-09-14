package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;

/**
 * The startup guard, armed in both directions.
 *
 * <p><b>A guard that refuses to boot is a deliberate outage if it misfires</b>, so "it fires when it
 * should" is only half the evidence. Each case below also checks <em>which</em> reason fired, because
 * a guard that throws for the wrong reason passes a test that only asks whether it threw - and the
 * first version of this guard could not fire at all, which a one-directional test would have called
 * success.
 *
 * <p>Plain unit tests with hand-built collaborators: the condition is about configuration and one
 * row, and driving it through a Spring context would hide the thing being asserted behind a fixture.
 */
class EmergencyAccessStartupCheckTest {

    private static final String ADMIN = "bootstrap-admin";

    private AppProperties propertiesWith(boolean factorEnabled, String adminUsername) {
        AppProperties properties = new AppProperties();
        properties.getSecurity().getSecondFactor().setEnabled(factorEnabled);
        properties.getAdmin().setUsername(adminUsername);
        return properties;
    }

    private User account(String username, boolean enabled) {
        User user = new User();
        user.setUsername(username);
        user.setEnabled(enabled);
        return user;
    }

    /**
     * Stands in for the repository; nothing here needs a database.
     *
     * <p>A {@link Proxy} rather than a mocking framework, and not only to avoid a dependency: it
     * answers exactly one method and throws on any other, so if this guard ever starts reading
     * something else from the repository this test says so instead of quietly returning a default.
     */
    private UserRepository repositoryReturning(User user) {
        return (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(),
                new Class<?>[] {UserRepository.class},
                (proxy, method, args) -> {
                    if ("findByUsername".equals(method.getName())) {
                        return Optional.ofNullable(user);
                    }
                    throw new UnsupportedOperationException(
                            "the startup check should not be calling " + method.getName());
                });
    }

    private EmergencyAccessStartupCheck check(AppProperties properties, User adminRow) {
        SecondFactorPolicy policy = new SecondFactorPolicy(properties);
        return new EmergencyAccessStartupCheck(properties, repositoryReturning(adminRow), policy);
    }

    /**
     * The healthy configuration: factor on, the exemption applying to a real enabled account. This is
     * the direction that matters most, because a false positive here is a production outage caused by
     * the safety check itself.
     */
    @Test
    void itStaysSilentWhenAnExemptEmergencyAccountExists() {
        AppProperties properties = propertiesWith(true, ADMIN);

        assertThatCode(() -> check(properties, account(ADMIN, true))
                .refuseToStartWithNoWayBackIn(null))
                .doesNotThrowAnyException();
    }

    /**
     * The mismatch that otherwise fails silently: {@code app.admin.username} names nobody. Without
     * this guard the application starts, looks entirely healthy, and has no emergency account at all.
     */
    @Test
    void itRefusesWhenTheNamedEmergencyAccountDoesNotExist() {
        AppProperties properties = propertiesWith(true, "nobody-seeded-this");

        assertThatThrownBy(() -> check(properties, null).refuseToStartWithNoWayBackIn(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no account named 'nobody-seeded-this' exists");
    }

    /** Somebody disabled the emergency account while the factor was on. */
    @Test
    void itRefusesWhenTheEmergencyAccountIsDisabled() {
        AppProperties properties = propertiesWith(true, ADMIN);

        assertThatThrownBy(() -> check(properties, account(ADMIN, false))
                .refuseToStartWithNoWayBackIn(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is disabled");
    }

    /**
     * The regression this whole card is about: the exemption stops applying to the account.
     *
     * <p>Reproduced the only way it can now happen - the policy's idea of the bootstrap admin and the
     * account on the system are different names - which is the same end state T339 reached by a
     * different route, and is what a future re-gating of the exemption would look like from here.
     */
    @Test
    void itRefusesWhenTheExemptionDoesNotApplyToThatAccount() {
        AppProperties properties = propertiesWith(true, ADMIN);
        // The row the lookup returns is NOT the account the policy exempts.
        User someoneElse = account("not-the-bootstrap-admin", true);

        assertThatThrownBy(() -> check(properties, someoneElse).refuseToStartWithNoWayBackIn(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not exempt from the second factor");
    }

    /**
     * Silent when the factor is off. There is nothing to be locked out of, and a guard that fails a
     * boot over a feature nobody switched on is pure misfire.
     */
    @Test
    void itStaysSilentWhileTheFactorIsOff() {
        AppProperties properties = propertiesWith(false, "nobody-seeded-this");

        assertThatCode(() -> check(properties, null).refuseToStartWithNoWayBackIn(null))
                .doesNotThrowAnyException();
    }

    /**
     * Silent when no bootstrap admin is configured at all - an ordinary local-development state that
     * {@code AdminUserSeeder} already warns about, and which turning fatal would make a first run
     * crash-loop on.
     */
    @Test
    void itStaysSilentWhenNoBootstrapAdminIsConfigured() {
        AppProperties properties = propertiesWith(true, "  ");

        assertThatCode(() -> check(properties, null).refuseToStartWithNoWayBackIn(null))
                .doesNotThrowAnyException();
    }

    /**
     * The guard must not be satisfiable by the old, unfirable condition. If someone reinstates the
     * break-glass gate on the exemption, {@code isRequiredFor} becomes true for the emergency account
     * and the first test above starts failing - this records why that failure is correct.
     */
    @Test
    void theExemptionItReliesOnDoesNotDependOnBreakGlassBeingArmed() {
        AppProperties properties = propertiesWith(true, ADMIN);
        SecondFactorPolicy policy = new SecondFactorPolicy(properties);

        assertThat(policy.isRequiredFor(account(ADMIN, true)))
                .as("the bootstrap admin must be exempt with no break-glass flag set anywhere - "
                        + "that gate is what locked the emergency account out of production (T339)")
                .isFalse();
    }
}
