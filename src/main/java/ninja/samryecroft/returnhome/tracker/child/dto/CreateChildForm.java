package ninja.samryecroft.returnhome.tracker.child.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import java.time.LocalDate;

public class CreateChildForm {

    @NotBlank
    private String firstName;

    @NotBlank
    private String lastName;

    // D-5d-2 (spec §7g): a birth date in the future is impossible - the same impossible-sequence
    // family as D-187-7 and D-5b-4, and the opposite ruling from D-5b-4's own field: a visit time
    // in the past is legitimate (recording after the fact), but a birth date genuinely cannot be
    // in the future. Chosen by which values the world can actually produce, not by the field's
    // type. The controller also sets a same-day `max` on the input - the cheap client-side half.
    @NotNull
    @Past
    private LocalDate dateOfBirth;

    private String localCaseReference;

    /**
     * Read/required only when {@code needsHomePicker} is true - not "when the submitter is
     * ADMIN" (D-5d-1's own defect, corrected here): a HOME_STAFF user holding more than one home
     * needs this too, and the previous comment on this exact field was the misnomer's source.
     */
    private Long homeId;

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

    public Long getHomeId() {
        return homeId;
    }

    public void setHomeId(Long homeId) {
        this.homeId = homeId;
    }
}
