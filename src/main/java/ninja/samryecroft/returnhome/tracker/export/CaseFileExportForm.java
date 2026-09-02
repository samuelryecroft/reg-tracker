package ninja.samryecroft.returnhome.tracker.export;

import jakarta.validation.constraints.NotBlank;

public class CaseFileExportForm {

    @NotBlank
    private String purpose;

    private String reference;

    /** {@code ALL}, {@code LAST_12_MONTHS} or {@code LAST_24_MONTHS} - "Custom range" is out of MVP. */
    private String period = "ALL";

    public String getPurpose() {
        return purpose;
    }

    public void setPurpose(String purpose) {
        this.purpose = purpose;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }
}
