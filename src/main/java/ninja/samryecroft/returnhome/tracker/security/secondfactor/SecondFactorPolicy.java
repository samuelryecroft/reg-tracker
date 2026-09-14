package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import ninja.samryecroft.returnhome.tracker.user.User;
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
/*
 * WHAT THE SECOND FACTOR ACTUALLY CHANGES, measured rather than assumed, because two of the three
 * facts below are not claimed by any card and the third is a control somebody will otherwise try to
 * build twice.
 *
 * 1. IT REMOVES A REAL, PRE-EXISTING IMPERSONATION CAPABILITY - and this is the part nobody wrote
 *    down. `UserService.setPassword` has no platform-admin guard, so an ORG_ADMIN may set a
 *    colleague's password; `UserService.changeEmail` DOES have one (T323), so they may not change
 *    that colleague's address. With a second factor in the way, the manager who sets the password
 *    still cannot sign in, because the code goes to the REAL person's mailbox. A manager could
 *    previously become any colleague in their organisation. T322 and T323 close that jointly, and
 *    neither card claims it.
 *
 * 2. THE CREATION-TIME ADDRESS WAS NEVER A HOLE - not "a hole we have not closed yet". A control
 *    against "whoever created the account chose where the first code goes" was specified, signed off
 *    and then abandoned on measurement: `CreateUserForm` carries BOTH password and email, and
 *    `UserService.create` has no platform-admin guard, so whoever sets the address has just set the
 *    password too. The address adds no capability, and a control against it would have been theatre.
 *    (It could not have worked either: a confirmation delivered TO an address is completed by
 *    whoever controls that address - it proves deliverability, never ownership. See V23.)
 *
 * 3. THE IRREDUCIBLE REMAINDER, written here so nobody tries to engineer it away: WHOEVER
 *    PROVISIONS AN ACCOUNT CAN IMPERSONATE THE ACCOUNT THEY PROVISION. That is inherent to
 *    admin-provisioned accounts and no second factor can change it, because the provisioner holds
 *    every input at the moment of creation. THE CONTROL IS ATTRIBUTION, NOT PREVENTION - and we have
 *    it: USER_CREATED, USER_EMAIL_CHANGED, USER_PASSWORD_RESET, MFA_CHALLENGE_ISSUED and
 *    LOGIN_SUCCESS each name an actor.
 */
@Component
public class SecondFactorPolicy {

    private final AppProperties appProperties;

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
     * <p><b>Why it must exist at all, which is stronger than "it is convenient".</b> This second
     * factor is delivered <em>by email</em>, so mail is now a single point of failure for every
     * sign-in in the product - and the outage most likely to take mail down is exactly the kind of
     * incident break-glass exists for. <b>This exemption is the only way back in when the factor's
     * own delivery channel is the thing that is broken.</b> D2/D5 kept a local credential on the same
     * argument for a tenant-wide sign-in outage; email inherits that argument rather than replacing
     * it.
     *
     * <p>That is written here rather than in a ticket because the obvious later tidy-up - "why is
     * this one account exempt? make it consistent" - closes the last door in the building, and it
     * looks like an improvement while doing it. {@code BreakGlassSecondFactorExemptionTest} walks
     * this path with mail unavailable, so the exemption is exercised rather than merely present: an
     * emergency route nobody has ever walked is a measurement that cannot fail.
     *
     * <p><b>But the exemption is one named account, not "break-glass is on".</b> Break-glass is not a
     * distinct account - {@code BreakGlassAuditListener} treats any local sign-in during the
     * emergency window as break-glass - so hanging the exemption on the flag alone would drop the
     * second factor for <em>everyone who signed in during that window</em>. That is the shape
     * {@code application.properties} already warns against: a credential path that comes on with
     * something else is one nobody decided to open. This narrows it to the bootstrap admin, which is
     * the account D2 was actually about, and both enabling and using that path are already audited.
     *
     * <p><b>T344: this asks a COLUMN, not a name.</b> It used to compare {@code app.admin.username} to
     * this row's username. {@code ADMIN_SEED_USERNAME} was never set in production, so that property
     * resolved to its default - the literal {@code admin} - and <b>any account named {@code admin}
     * was permanently exempt from the second factor</b>. Nobody decided that; it was the default
     * value of an environment variable nobody set (T341). A flag cannot be collided with by naming,
     * and it survives usernames ceasing to be login identifiers at all.
     *
     * <p><b>T339: this no longer consults {@code app.auth.break-glass.enabled}, and that removal is
     * the fix for a real lockout.</b> It used to open with {@code if (!breakGlassEnabled) return
     * false;}. Production ships that flag false, so when the factor went live the exemption
     * short-circuited, <b>the username below was never even compared</b>, the factor became required
     * <em>of the emergency account</em>, and the admin was refused for having no address. The missing
     * address was the symptom: with the flag off that account was locked out whether or not it had
     * one - which is why giving it a mailbox was never the fix, since that would make the emergency
     * path depend on the very channel it exists to survive.
     *
     * <p>The deeper reason the gate was wrong is that it made this exemption <b>redundant</b>: arming
     * it took an App Service settings change, and anyone able to make that change could equally have
     * set {@code SECOND_FACTOR_ENABLED=false}. It bought nothing the operator did not already have,
     * while reading - here and in the deployment notes - like a guarantee that stood on its own.
     * <b>A fire exit you must unlock before the fire is not a fire exit.</b>
     *
     * <p>The cost is stated rather than hidden: this one account now permanently holds a single
     * factor. What bounds it is that it has no address to phish, {@code LoginAttemptService} throttles
     * it, and it writes {@code LOGIN_SUCCESS} like any other sign-in. <b>Note what changed about
     * monitoring:</b> a sign-in on this account while break-glass is OFF is no longer flagged
     * {@code BREAK_GLASS_LOGIN}, because break-glass mode genuinely is not on - the ordinary sign-in
     * trail still records it, but the high-attention marker does not fire.
     *
     * <p>Break-glass MODE is untouched by this change. {@code BreakGlassAuditListener} reads the
     * property through its own {@code @Value} and keeps every behaviour it had; the two were never
     * entangled, they only shared a property name.
     */
    private boolean isEmergencyExempt(User user) {
        return user.isBreakGlass();
    }
}
