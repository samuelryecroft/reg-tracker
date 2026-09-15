package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Refuses to start if switching the second factor on would lock the emergency account out.
 *
 * <p><b>Why this exists (T339).</b> The factor was switched on in production and the bootstrap admin
 * - the one account that must never be locked out - was refused at sign-in. We learned it from the
 * person who could not get in, during the incident, rather than from the deployment that caused it.
 * <b>The switch-on itself should have failed closed.</b> This turns "the fire exit is bricked up"
 * from something discovered during a fire into something that fails a boot, at the moment reversing
 * is still one settings change.
 *
 * <p>Same shape as {@link LoggingVerificationCodeSender#refuseToRunInADeployedEnvironment()} and
 * {@code DocumentStorageConfig}: the decision is made where it can still fail loudly, not at the
 * moment somebody needs the door.
 *
 * <p><b>What it actually asserts: with the factor on, there must EXIST an enabled account that the
 * policy exempts.</b> That is the property the fire exit is made of - an account that can sign in
 * without the mail channel - and it is deliberately not the same as the condition this guard was
 * first written with.
 *
 * <p><b>The first attempt could never fire, and that is worth recording rather than quietly
 * replacing.</b> It asked whether the bootstrap admin was "neither exempt nor challengeable" - but it
 * found that account by {@code app.admin.username} while the policy decided the exemption by
 * comparing {@code app.admin.username} to the very same row. <b>The lookup and the exemption shared a
 * key, so they could not disagree</b>: the account it found was always exempt, the condition was
 * always false, and the guard was unfirable - a check whose passing state and whose broken state are
 * indistinguishable, which is the exact shape of the defect it was written to prevent.
 *
 * <p><b>T344 removes that coupling at the root.</b> The row is now found by its
 * {@code is_break_glass} flag while the exemption is decided by the same flag on the loaded row, so
 * the guard asks a question of the TABLE ("is there an enabled emergency account that is still
 * exempt?") rather than re-deriving the answer it is testing.
 *
 * <p>Asking instead whether the exemption still APPLIES to that account is a question that can come
 * out either way, and it covers the two ways the exit actually closes once it exists: the exemption
 * stops applying to it (a future re-gating, which is precisely what T339 was), or the account is
 * disabled.
 *
 * <p><b>A third case was tried and withdrawn, on evidence.</b> An earlier draft also fired when
 * {@code app.admin.username} named no row at all - meaning to catch a mismatch that otherwise fails
 * silently. It failed every second-factor test context in CI, because a missing row is the ordinary
 * state wherever no seed password is configured. A guard that refuses to boot is an outage of its own
 * if it misfires, and that one misfired on the commonest configuration there is. The silent-mismatch
 * risk is therefore NOT covered here; it is the same condition {@code AdminUserSeeder} already warns
 * about, and it wants a different instrument than a fatal boot check.
 *
 * <p><b>Deliberately narrow, because a guard that refuses to boot is an outage of its own if it
 * misfires.</b> It stays silent when the factor is off (nothing to be locked out of) and when no
 * bootstrap admin is configured at all, which is an ordinary local-development state that
 * {@code AdminUserSeeder} already warns about and which turning fatal would make a first run
 * crash-loop.
 *
 * <p>Runs on {@link ApplicationReadyEvent} because {@code AdminUserSeeder} is an
 * {@code ApplicationRunner}: on a first boot the account does not exist until it has run, and a
 * {@code @PostConstruct} here would read an empty table and conclude, wrongly and silently, that
 * there was nothing to check.
 */
@Component
public class EmergencyAccessStartupCheck {

    private final AppProperties appProperties;
    private final UserRepository userRepository;
    private final SecondFactorPolicy policy;

    public EmergencyAccessStartupCheck(AppProperties appProperties, UserRepository userRepository,
            SecondFactorPolicy policy) {
        this.appProperties = appProperties;
        this.userRepository = userRepository;
        this.policy = policy;
    }

    @EventListener
    public void refuseToStartWithNoWayBackIn(ApplicationReadyEvent event) {
        if (!appProperties.getSecurity().getSecondFactor().isEnabled()) {
            return;
        }
        User emergency = userRepository.findByBreakGlassTrue().orElse(null);
        if (emergency == null) {
            // NOT a boot failure, and an earlier draft of this guard had it the other way round -
            // which failed every second-factor test context in CI and would have failed a first boot
            // of any environment without a seed password. No emergency row is the ORDINARY state
            // wherever ADMIN_SEED_PASSWORD is unset or was rejected as weak: AdminUserSeeder chose to
            // let the application start with nobody able to sign in, and said so in the log. Turning
            // that into a fatal error overturns a decision that is not this guard's to make, adds
            // nothing the log does not already say, and converts a warned state into a crash loop.
            return;
        }
        // Asked of the real row rather than reasoned about, so that a future change to how the
        // exemption is decided is caught here instead of at somebody's sign-in.
        if (emergency.isEnabled() && !policy.isRequiredFor(emergency)) {
            return;
        }
        throw new IllegalStateException(
                "The second factor is enabled, but the emergency account cannot sign in without it: "
                        + (emergency.isEnabled()
                                ? "it is no longer exempt from the second factor"
                                : "it is disabled")
                        + ". A mail outage would then lock out everybody, including whoever would "
                        + "restore mail. Refusing to start rather than going live with no way back "
                        + "in. Either restore the exemption for the account flagged is_break_glass, "
                        + "or turn app.security.second-factor.enabled off until it is restored.");
    }
}
