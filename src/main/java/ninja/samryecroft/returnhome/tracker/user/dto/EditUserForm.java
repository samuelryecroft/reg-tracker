package ninja.samryecroft.returnhome.tracker.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.user.Role;

/**
 * <strong>This form no longer sets passwords (T277).</strong> The credential moved to
 * {@link SetPasswordForm} and its own action, so that no future widening of "who may edit this user"
 * can include "and therefore set their password" by accident. It had happened twice.
 */
public class EditUserForm {

    @NotBlank(message = "First name is required")
    @Size(max = 255)
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 255)
    private String lastName;

    /*
     * THERE IS DELIBERATELY NO EMAIL FIELD HERE (T323). DO NOT ADD ONE BACK.
     *
     * A colleague may no longer change another account's email address. This is not a tidy-up and
     * it is not a permissions preference: SECOND-FACTOR CODES ARE SENT TO THAT ADDRESS (T322), and
     * the entire reason emailed codes are acceptable for this product is that the address cannot be
     * redirected by somebody who works alongside you. A manager who could edit a colleague's email
     * could point their codes at an inbox they control and then sign in as them - and a
     * safeguarding record would show that colleague's name against everything they did.
     *
     * IT IS ABSENT FROM THE FORM RATHER THAN GUARDED INSIDE update(), which is T277's lesson
     * applied a second time in the same class. A permission to reach a form is a permission to
     * everything in it, so while a credential lived in this bundle, two separate widenings of "who
     * may edit this user" silently widened "who may set their password". A field that is not here
     * cannot be carried along by the next widening, because there is nothing to carry.
     *
     * Changing an address is its own action on its own screen, for the platform admin only - see
     * UserService.changeEmail for who and why. A field nobody can ever change would be its own
     * defect, so it is narrowed rather than frozen.
     */

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

}
