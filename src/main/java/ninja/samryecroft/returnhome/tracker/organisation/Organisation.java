package ninja.samryecroft.returnhome.tracker.organisation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "organisations")
public class Organisation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrgType type;

    /** Only meaningful when type = CARE_PROVIDER: the Supplier org that serves this Care Provider. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_organisation_id")
    private Organisation supplierOrganisation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() {
        return id;
    }

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

    public Organisation getSupplierOrganisation() {
        return supplierOrganisation;
    }

    public void setSupplierOrganisation(Organisation supplierOrganisation) {
        this.supplierOrganisation = supplierOrganisation;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
