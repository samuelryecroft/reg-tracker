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
 * replacing.</b> It asked whether the bootstrap admin was "neither exempt nor challengeable". But it
 * finds that account BY {@code app.admin.username}, and {@link SecondFactorPolicy} decides the
 * exemption by comparing {@code app.admin.username} to the very same row - <b>the lookup and the
 * exemption share a key, so they cannot disagree</b>. The account it found was therefore always
 * exempt, the condition was always false, and the guard was unfirable: a check whose passing state
 * and whose broken state are indistinguishable, which is the exact shape of the defect it was written
 * to prevent.
 *
 * <p>Asking instead whether such an account EXISTS is a question that can come out either way, and it
 * covers the three ways the exit actually closes: the exemption stops applying to this account (a
 * future re-gating, which is precisely what T339 was), {@code app.admin.username} names a row that is
 * not there - <b>a mismatch that otherwise fails silently and looks identical to health</b> - or the
 * account is disabled.
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
        String username = appProperties.getAdmin().getUsername();
        if (username == null || username.isBlank()) {
            return;
        }
        User admin = userRepository.findByUsername(username).orElse(null);
        // isRequiredFor is asked of the real row rather than reasoned about, so that a future change
        // to how the exemption is decided is caught here instead of at somebody's sign-in.
        if (admin != null && admin.isEnabled() && !policy.isRequiredFor(admin)) {
            return;
        }

        String because;
        if (admin == null) {
            because = "no account named '" + username + "' exists";
        } else if (!admin.isEnabled()) {
            because = "the account '" + username + "' is disabled";
        } else {
            because = "the account '" + username + "' is not exempt from the second factor";
        }
        throw new IllegalStateException(
                "The second factor is enabled, but there is no emergency account that could sign in "
                        + "without it: " + because + ". A mail outage would then lock out everybody, "
                        + "including whoever would restore mail. Refusing to start rather than going "
                        + "live with no way back in. Either make app.admin.username name an enabled "
                        + "account the exemption applies to, or turn "
                        + "app.security.second-factor.enabled off until it does.");
    }
}
