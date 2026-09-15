package ninja.samryecroft.returnhome.tracker.security.passwordreset;

import jakarta.validation.constraints.NotBlank;

/**
 * Step C's form (T353e): the token (a hidden field carried from the link) and the chosen new
 * password. Strength is checked against {@code PasswordPolicy} in the service, with the username and
 * email read from the LOADED account behind the token - never from anything the form could carry -
 * so the policy's context cannot be spoofed by the submitter.
 */
public class ResetPasswordForm {

    @NotBlank
    private String token;

    @NotBlank(message = "Enter a new password")
    private String newPassword;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
