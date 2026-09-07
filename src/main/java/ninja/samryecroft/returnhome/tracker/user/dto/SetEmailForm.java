package ninja.samryecroft.returnhome.tracker.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One field, on its own screen, for its own action (T323).
 *
 * <p>Modelled on {@link SetPasswordForm} and for the same structural reason: while the address was a
 * field in {@link EditUserForm}, every widening of "who may edit this user" widened "who may
 * redirect their second-factor codes" along with it, invisibly, because the two travelled in one
 * bundle. A form carrying exactly one field cannot be reached by accident on the way to something
 * else.
 *
 * <p>The validation is the same as the address always had on the edit form - required and
 * well-formed - and it moved with the field rather than being rewritten, so an address that was
 * acceptable yesterday is acceptable today.
 */
public class SetEmailForm {

    @NotBlank(message = "Email address is required")
    @Email(message = "Enter a valid email address")
    @Size(max = 320)
    private String email;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
