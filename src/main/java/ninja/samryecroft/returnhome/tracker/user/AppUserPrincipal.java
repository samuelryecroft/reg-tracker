package ninja.samryecroft.returnhome.tracker.user;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public class AppUserPrincipal implements UserDetails {

    private final User user;
    private final boolean locked;

    public AppUserPrincipal(User user) {
        this(user, false);
    }

    public AppUserPrincipal(User user, boolean locked) {
        this.user = user;
        this.locked = locked;
    }

    public User getUser() {
        return user;
    }

    public Long getUserId() {
        return user.getId();
    }

    public Set<Role> getRoles() {
        return user.getRoles();
    }

    public boolean hasRole(Role role) {
        return user.hasRole(role);
    }

    // There is deliberately no getHomeId() any more, and no getHomeIds() either.
    //
    // The single-valued accessor was what made the one-home-per-user assumption structural: every
    // scoping decision that consumed it inherited the assumption without restating it, so widening
    // the model would have left those paths quietly checking the wrong thing. Removing it forces
    // each one to be visited (T116).
    //
    // The replacement is not a collection on the principal. This object is built from a
    // session-loaded, detached User whose homes are LAZY, and the entity's own javadoc records why
    // reading that collection here is a trap. Home access is answered by targeted queries on
    // OrganisationAccessService, which also keeps the database - not a login-time snapshot - as the
    // authority on who may see what.

    public Long getOrganisationId() {
        return user.getOrganisation() != null ? user.getOrganisation().getId() : null;
    }

    public OrgType getOrganisationType() {
        return user.getOrganisation() != null ? user.getOrganisation().getType() : null;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return user.getRoles().stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .collect(Collectors.toSet());
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /**
     * False while failed-login throttling has this account locked out (see LoginAttemptService).
     *
     * <p><b>This flag now does one job, and it used to look like two.</b> It is what ENFORCES the
     * lockout: Spring's {@code preAuthenticationChecks} reads it and throws {@code LockedException}
     * before the password is ever compared, which is why <em>a correct password is still refused
     * while the lock holds</em>. That is the whole point of a lockout and this is the only thing
     * that delivers it.
     *
     * <p>What it no longer does is decide what the user is TOLD. Before T215 the
     * {@code LockedException} this produces was the only in-band signal that an account was locked,
     * so it was the obvious thing for a failure handler to branch on -
     * {@link ninja.samryecroft.returnhome.tracker.security.LoginFailureHandler} deliberately does
     * not, because <b>an unknown username that is equally locked never gets this far</b>: it fails
     * in {@code loadUserByUsername} with {@code BadCredentialsException}, so selecting the message
     * from the exception would show the locked banner only for accounts that exist.
     *
     * <p><b>So do not read "the handler no longer uses this" as "this is redundant".</b> The
     * handler picks a page after authentication has already failed; only this refuses the
     * authentication. Delete it and every locked account starts accepting its correct password
     * again, with the banner still cheerfully saying sign-in is paused.
     *
     * <p>The invariant is pinned, not merely asserted here:
     * {@code LoginThrottlingIntegrationTest#accountLocksOutAfterTooManyFailedAttemptsAndRefusesTheCorrectPassword}
     * goes red if this stops refusing.
     *
     * <p>One subtlety worth knowing, because it makes the two look inconsistent when they are not:
     * this flag and the handler read the SAME source - {@code LoginAttemptService.isLocked} - but at
     * different moments, here at user-load and there at failure-handling. {@code isLocked} clears an
     * elapsed lockout as it reads, so the two can legitimately disagree across that boundary. They
     * are two reads of one truth, not two truths.
     */
    @Override
    public boolean isAccountNonLocked() {
        return !locked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return user.isEnabled();
    }
}
