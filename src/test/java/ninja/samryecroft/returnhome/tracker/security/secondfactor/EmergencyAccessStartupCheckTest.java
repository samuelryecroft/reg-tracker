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
 */
class EmergencyAccessStartupCheckTest {

    private AppProperties propertiesWith(boolean factorEnabled) {
        AppProperties properties = new AppProperties();
        properties.getSecurity().getSecondFactor().setEnabled(factorEnabled);
        return properties;
    }

    /** The emergency account as the database now marks it: by a flag, not by its name (T344). */
    private User emergencyAccount(boolean enabled, boolean breakGlass) {
        User user = new User();
        user.setUsername("bootstrap-admin");
        user.setEnabled(enabled);
        user.setBreakGlass(breakGlass);
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
                    if ("findByBreakGlassTrue".equals(method.getName())) {
                        return Optional.ofNullable(user);
                    }
                    throw new UnsupportedOperationException(
                            "the startup check should not be calling " + method.getName());
                });
    }

    private EmergencyAccessStartupCheck check(AppProperties properties, User emergencyRow) {
        SecondFactorPolicy policy = new SecondFactorPolicy(properties);
        return new EmergencyAccessStartupCheck(properties, repositoryReturning(emergencyRow), policy);
    }

    /**
     * The healthy configuration. This is the direction that matters most, because a false positive
     * here is a production outage caused by the safety check itself.
     */
    @Test
    void itStaysSilentWhenAnExemptEmergencyAccountExists() {
        assertThatCode(() -> check(propertiesWith(true), emergencyAccount(true, true))
                .refuseToStartWithNoWayBackIn(null))
                .doesNotThrowAnyException();
    }

    /**
     * No emergency account at all is NOT a boot failure - and this assertion is here because an
     * earlier draft of the guard had it the other way round.
     *
     * <p>That draft failed every second-factor test context in CI, because a missing emergency row is
     * the ordinary state wherever no seed password is configured: {@code AdminUserSeeder} deliberately
     * lets the application start with nobody able to sign in and says so in the log. <b>A guard that
     * refuses to boot is an outage of its own if it misfires, and that one misfired on the commonest
     * configuration there is.</b> Kept as a test rather than a comment so it cannot be quietly widened
     * again.
     */
    @Test
    void itStaysSilentWhenNoEmergencyAccountExists() {
        assertThatCode(() -> check(propertiesWith(true), null).refuseToStartWithNoWayBackIn(null))
                .doesNotThrowAnyException();
    }

    /** Somebody disabled the emergency account while the factor was on. */
    @Test
    void itRefusesWhenTheEmergencyAccountIsDisabled() {
        assertThatThrownBy(() -> check(propertiesWith(true), emergencyAccount(false, true))
                .refuseToStartWithNoWayBackIn(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("it is disabled");
    }

    /**
     * The regression this whole line of work is about: the exemption stops applying to the emergency
     * account. That is the state T339 reached by a gate, and the state a future re-gating would reach
     * again.
     */
    @Test
    void itRefusesWhenTheExemptionDoesNotApplyToThatAccount() {
        assertThatThrownBy(() -> check(propertiesWith(true), emergencyAccount(true, false))
                .refuseToStartWithNoWayBackIn(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no longer exempt from the second factor");
    }

    /**
     * Silent when the factor is off. There is nothing to be locked out of, and a guard that fails a
     * boot over a feature nobody switched on is pure misfire.
     */
    @Test
    void itStaysSilentWhileTheFactorIsOff() {
        assertThatCode(() -> check(propertiesWith(false), null).refuseToStartWithNoWayBackIn(null))
                .doesNotThrowAnyException();
    }

    /**
     * The exemption depends on the FLAG and on nothing else - not on a name, and not on
     * {@code app.auth.break-glass.enabled}.
     *
     * <p>Both of those have locked the emergency account out of production once already: the flag gate
     * in T339, and the name default in T341, where {@code ADMIN_SEED_USERNAME} was unset so any
     * account called {@code admin} was permanently exempt.
     */
    @Test
    void theExemptionDependsOnTheFlagAndOnNothingElse() {
        SecondFactorPolicy policy = new SecondFactorPolicy(propertiesWith(true));

        assertThat(policy.isRequiredFor(emergencyAccount(true, true)))
                .as("a flagged account is exempt with no break-glass property set anywhere")
                .isFalse();
        assertThat(policy.isRequiredFor(emergencyAccount(true, false)))
                .as("an unflagged account is NOT exempt, whatever it happens to be named - that "
                        + "naming coincidence was T341")
                .isTrue();
    }
}
