package ninja.samryecroft.returnhome.tracker.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.user.Role;

public class EditUserForm {

    @NotBlank(message = "First name is required")
    @Size(max = 255)
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 255)
    private String lastName;

    /**
     * Required on the form though nullable in the database: rows predating T127 have no address,
     * and inventing one to satisfy a constraint would put fiction in a statutory record. Every user
     * touched from here on supplies a real one.
     */
    @NotBlank(message = "Email address is required")
    @Email(message = "Enter a valid email address")
    @Size(max = 320)
    private String email;

    /**
     * Optional - a contact number is useful, not a reason to block an account. Only length-checked,
     * with no format pattern: UK numbers are written with spaces, with and without the leading
     * zero, and occasionally in +44 form, and a regex here would reject valid numbers to no
     * safeguarding benefit.
     */
    @Size(max = 30, message = "Contact phone must be 30 characters or fewer")
    private String contactPhone;

    @NotEmpty(message = "Select at least one role")
    private Set<Role> roles = new HashSet<>();

    private Long organisationId;

    private Set<Long> homeIds = new HashSet<>();

    private boolean enabled;

    @Size(min = 8, message = "Password must be at least 8 characters")
    private String newPassword;

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    /** Blank means "not supplied", not an empty number - an untouched HTML field submits "". */
    public void setContactPhone(String contactPhone) {
        this.contactPhone = (contactPhone == null || contactPhone.isBlank()) ? null : contactPhone;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles = roles;
    }

    public Long getOrganisationId() {
        return organisationId;
    }

    public void setOrganisationId(Long organisationId) {
        this.organisationId = organisationId;
    }

    public Set<Long> getHomeIds() {
        return homeIds;
    }

    public void setHomeIds(Set<Long> homeIds) {
        this.homeIds = homeIds;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
