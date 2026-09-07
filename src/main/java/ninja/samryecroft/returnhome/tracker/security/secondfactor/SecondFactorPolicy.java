package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import ninja.samryecroft.returnhome.tracker.user.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The single answer to "does this account have to pass a second factor?".
 *
 * <p><b>One object because two would drift.</b> Three separate places need this answer - the
 * success handler that diverts the sign-in, the controller that completes it, and the audit
 * listener that decides what {@code LOGIN_SUCCESS} means - and {@code User}'s own history records
 * what happens when one question gets expressed in two places: HOME_STAFF and VIEWER held the same
 * relationship two ways, and that is how one of them silently stopped being checked. Here the two
 * copies would disagree about whether somebody is signed in.
 */
@Component
public class SecondFactorPolicy {

    private final AppProperties appProperties;

    /**
     * Read straight from configuration rather than through a service, because this is the same
     * switch {@code BreakGlassAuditListener} reads and they must not be able to disagree about
     * whether the emergency door is open.
     */
    @Value("${app.auth.break-glass.enabled:false}")
    private boolean breakGlassEnabled;

    public SecondFactorPolicy(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * <b>Fails towards asking for the factor.</b> Every path that cannot establish an exemption
     * returns true, because the failure mode of asking someone who did not need to be asked is an
     * inconvenience, and the failure mode of the reverse is an account with one factor.
     */
    public boolean isRequiredFor(User user) {
        if (!appProperties.getSecurity().getSecondFactor().isEnabled()) {
            return false;
        }
        return !isEmergencyExempt(user);
    }

    /**
     * The emergency exemption, and it is deliberately NARROW.
     *
     * <p>D2/D5 kept a local credential because a tenant-wide sign-in outage would otherwise lock out
     * the one person who could fix it. Email is now in that dependency chain: a mail-provider outage
     * with no exemption locks out everybody, including whoever would restore mail.
     *
     * <p><b>But the exemption is one named account, not "break-glass is on".</b> Break-glass is not a
     * distinct account - {@code BreakGlassAuditListener} treats any local sign-in during the
     * emergency window as break-glass - so hanging the exemption on the flag alone would drop the
     * second factor for <em>everyone who signed in during that window</em>. That is the shape
     * {@code application.properties} already warns against: a credential path that comes on with
     * something else is one nobody decided to open. This narrows it to the bootstrap admin, which is
     * the account D2 was actually about, and both enabling and using that path are already audited.
     */
    private boolean isEmergencyExempt(User user) {
        if (!breakGlassEnabled) {
            return false;
        }
        String bootstrapAdmin = appProperties.getAdmin().getUsername();
        return bootstrapAdmin != null
                && !bootstrapAdmin.isBlank()
                && bootstrapAdmin.equalsIgnoreCase(user.getUsername());
    }
}
