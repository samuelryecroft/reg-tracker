package ninja.samryecroft.returnhome.tracker.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.user.Role;

public class CreateUserForm {

    @NotBlank
    private String username;

    /**
     * Optional: an account created for Entra sign-in has no local credential at all. Still length-
     * checked when one is supplied, so the only thing that changed is that absent is now allowed -
     * a short password is as invalid as it ever was.
     */
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    @NotBlank
    private String fullName;

    @NotEmpty(message = "Select at least one role")
    private Set<Role> roles = new HashSet<>();

    private Long homeId;

    private Long organisationId;

    private Set<Long> viewerHomeIds = new HashSet<>();

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    /**
     * Blank means "no local credential", not "a zero-length one". An HTML form always submits an
     * empty string for an untouched field, so without this normalisation the {@code @Size} check
     * would reject the credential-less case it is meant to allow, and a blank would reach
     * {@link ninja.samryecroft.returnhome.tracker.user.UserService} as a value worth encoding.
     */
    public void setPassword(String password) {
        this.password = (password == null || password.isBlank()) ? null : password;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles = roles;
    }

    public Long getHomeId() {
        return homeId;
    }

    public void setHomeId(Long homeId) {
        this.homeId = homeId;
    }

    public Long getOrganisationId() {
        return organisationId;
    }

    public void setOrganisationId(Long organisationId) {
        this.organisationId = organisationId;
    }

    public Set<Long> getViewerHomeIds() {
        return viewerHomeIds;
    }

    public void setViewerHomeIds(Set<Long> viewerHomeIds) {
        this.viewerHomeIds = viewerHomeIds;
    }
}
