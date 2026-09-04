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

    /**
     * T168(b). Initialised to PENDING, and that initialiser is load-bearing rather than tidy.
     *
     * <p>V19 drops the column's DB default after backfilling, so an insert that forgets to set a
     * status fails NOT NULL loudly instead of quietly becoming ACTIVE. But that only catches inserts
     * which BYPASS this entity - and essentially nothing does, so without this initialiser the
     * hardening would cover the path nobody uses while the ordinary path defaulted to whatever the
     * field happened to hold. PENDING is also simply true: a newly created organisation has no
     * confirmed KEK yet.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrgStatus status = OrgStatus.PENDING;

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

    public OrgStatus getStatus() {
        return status;
    }

    /**
     * Package-private: a lifecycle transition is not a setter. Everything goes through
     * {@link OrganisationLifecycleService}, which is where the KEK is verified and the transition is
     * audited - a public setter would let a caller reach ACTIVE without either.
     */
    void setStatus(OrgStatus status) {
        this.status = status;
    }

    /** Whether this organisation may hold records. The single question the guard asks. */
    public boolean isActive() {
        return status == OrgStatus.ACTIVE;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
