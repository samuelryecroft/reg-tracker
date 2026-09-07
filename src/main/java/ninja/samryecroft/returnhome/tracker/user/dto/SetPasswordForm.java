package ninja.samryecroft.returnhome.tracker.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Setting one account's password, on its own (T277).
 *
 * <p><strong>It carries the password and nothing else, and that is the whole point of the card.</strong>
 * A permission to reach a form is a permission to everything in it, so while the credential was a
 * field on {@code EditUserForm} any widening of "who may edit this user" silently widened "who may
 * set their password" - twice already. A rule about WHO MAY EDIT has to be re-audited against the
 * form's CONTENTS every time either changes, and nobody will. Taking the field out of the bundle is
 * what makes that class of mistake unreachable rather than merely unlikely.
 *
 * <p><strong>No {@link ninja.samryecroft.returnhome.tracker.user.password.StrongPassword} annotation
 * here, deliberately.</strong> That constraint reads its context off the form, and this form knows
 * nothing about the account - it has only an id in the path. The controller therefore runs the SAME
 * {@code PasswordPolicy} with the username, email and organisation read from the LOADED ACCOUNT. That
 * is stronger than what it replaces: on the edit form those values arrived in the submission, so a
 * caller could weaken their own policy context by changing the email field in the same post.
 */
public class SetPasswordForm {

    @NotBlank(message = "Enter a new password")
    private String newPassword;

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
