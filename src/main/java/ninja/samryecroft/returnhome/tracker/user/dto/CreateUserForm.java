package ninja.samryecroft.returnhome.tracker.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.password.PasswordCandidate;
import ninja.samryecroft.returnhome.tracker.user.password.StrongPassword;

@StrongPassword
public class CreateUserForm implements PasswordCandidate {

    @NotBlank
    private String username;

    /**
     * Optional, and length-checked only when supplied - a short password is as invalid as it ever
     * was.
     *
     * <p>The LENGTH rule that used to sit here as {@code @Size(min = 8)} is now the class-level
     * {@link StrongPassword} constraint, which is the same rule {@code EditUserForm} and
     * {@code AdminUserSeeder} apply. Two copies of a rule is how one of them stops being true.
     *
     * <p><b>The reason it is optional has gone, and the rule has deliberately been left alone.</b>
     * It was optional because an account created for Entra sign-in had no local credential at all.
     * With Entra removed, form login is the only way in, so an account saved without a password
     * cannot sign in by any route. Making it mandatory is a behaviour change to live account
     * creation and is out of scope here; it is recorded rather than silently made, because the
     * justification above it no longer holds and a reader would otherwise inherit it as current.
     */
    private String password;

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

    // --- PasswordCandidate (T272). This form knows the username, the email and the organisation,
    // so it can supply all three context values; nothing here returns a placeholder. ---

    @Override
    public String passwordBeingSet() {
        return password;
    }

    @Override
    public String passwordFieldName() {
        return "password";
    }

    @Override
    public String usernameForPolicy() {
        return username;
    }

    @Override
    public String emailForPolicy() {
        return email;
    }

    @Override
    public Long organisationIdForPolicy() {
        return organisationId;
    }
}
