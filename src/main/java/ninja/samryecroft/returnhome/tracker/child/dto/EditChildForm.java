package ninja.samryecroft.returnhome.tracker.child.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import java.time.LocalDate;

/**
 * Correcting a young person's own details (T170).
 *
 * <p><strong>Ordinary and necessary rather than exceptional:</strong> typos, legal name changes, a
 * case reference issued after the record was created. Oscar's scope, and the fields are exactly his.
 *
 * <p><strong>The home is deliberately NOT here.</strong> Moving a young person between homes is a
 * different decision from correcting their name - it changes who can see them and which
 * organisation's staff can act on their record - and bundling it into a details form is the shape
 * T277 exists to refuse: a permission to reach a form is a permission to everything in it.
 */
public class EditChildForm {

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @NotNull(message = "Date of birth is required")
    @Past(message = "Date of birth must be in the past")
    private LocalDate dateOfBirth;

    private String localCaseReference;

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

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public String getLocalCaseReference() {
        return localCaseReference;
    }

    public void setLocalCaseReference(String localCaseReference) {
        this.localCaseReference = localCaseReference;
    }
}
