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

    /** False while failed-login throttling has this account locked out (see LoginAttemptService). */
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
