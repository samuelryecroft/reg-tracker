package ninja.samryecroft.returnhome.tracker.organisation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;

public class CreateOrganisationForm {

    @NotBlank
    private String name;

    @NotNull
    private OrgType type;

    /** Only read/required when type = CARE_PROVIDER. */
    private Long supplierOrganisationId;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public OrgType getType() {
        return type;
    }

    public void setType(OrgType type) {
        this.type = type;
    }

    public Long getSupplierOrganisationId() {
        return supplierOrganisationId;
    }

    public void setSupplierOrganisationId(Long supplierOrganisationId) {
        this.supplierOrganisationId = supplierOrganisationId;
    }
}
