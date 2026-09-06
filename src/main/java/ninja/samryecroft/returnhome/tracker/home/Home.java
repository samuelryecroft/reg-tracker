package ninja.samryecroft.returnhome.tracker.home;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.stream.Stream;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;

@Entity
@Table(name = "homes")
public class Home {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organisation_id", nullable = false)
    private Organisation organisation;

    @Column(name = "address_line_1")
    private String addressLine1;

    @Column(name = "address_line_2")
    private String addressLine2;

    @Column(name = "address_line_3")
    private String addressLine3;

    private String postcode;

    private String what3words;

    @Column(name = "local_authority")
    private String localAuthority;

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

    public Organisation getOrganisation() {
        return organisation;
    }

    /**
     * T237: <b>only a CARE_PROVIDER organisation may hold a home</b>, and this is where that is
     * enforced.
     *
     * <p><b>The argument for it being here was already written one level down.</b> The controller
     * check this joins says <em>"a filtered dropdown is not a constraint - it shapes the form, not
     * the POST"</em>. That sentence applies to the guard it justifies: a controller check is not a
     * constraint either, it shapes one endpoint rather than the data. The reasoning was right and
     * stopped one layer short of its own conclusion - there is no {@code HomeService}, the controller
     * writes through the repository directly, and {@code DemoDataSeeder} is a second write path that
     * takes any organisation at all. Not an exposure today ({@code DemoProfileGuard} keeps that
     * profile out of production), but it is the door a future importer or fixture will resemble.
     *
     * <p><b>Two layers, each doing its own job</b>, the same shape as {@code ExportCapability} being
     * the real gate with the filter chain as defence in depth. The controller keeps its field error
     * so an admin gets a form message rather than a 500; this setter is the invariant, and every
     * write path in the application passes through it.
     *
     * <p><b>Why the setter and not {@code @PrePersist}</b> - the load-bearing detail, checked rather
     * than assumed. This entity's {@code @Id} is on the FIELD, so Hibernate uses field access and
     * <b>never calls setters when hydrating a row</b>. Therefore the guard cannot fire on load: it can
     * never make a pre-existing bad row unreadable, whatever a survey of existing data turns up. And
     * it fires at the point of the mistake with the assignment still on the stack, rather than as a
     * null three frames later. {@code @PrePersist} would instead risk touching a lazy
     * {@code @ManyToOne} proxy inside a flush, which is a worse failure in a worse place.
     *
     * <p>Null is permitted: the controller deliberately nulls this field when it rejects a selection,
     * so that the form can be redisplayed with its error. A missing organisation is caught by the
     * NOT NULL column, which is the right layer for "absent" - this method's job is "wrong kind".
     *
     * @throws IllegalArgumentException if the organisation is not a {@link OrgType#CARE_PROVIDER}.
     *     The message names the organisation, its actual type and the rule, because whoever reads it
     *     is debugging a write they believed was legal.
     */
    public void setOrganisation(Organisation organisation) {
        if (organisation != null && organisation.getType() != OrgType.CARE_PROVIDER) {
            throw new IllegalArgumentException("Home cannot belong to organisation "
                    + organisation.getId() + " (" + organisation.getName() + "): it is a "
                    + organisation.getType() + ", and homes belong to CARE_PROVIDER organisations.");
        }
        this.organisation = organisation;
    }

    public String getAddressLine1() {
        return addressLine1;
    }

    public void setAddressLine1(String addressLine1) {
        this.addressLine1 = addressLine1;
    }

    public String getAddressLine2() {
        return addressLine2;
    }

    public void setAddressLine2(String addressLine2) {
        this.addressLine2 = addressLine2;
    }

    public String getAddressLine3() {
        return addressLine3;
    }

    public void setAddressLine3(String addressLine3) {
        this.addressLine3 = addressLine3;
    }

    public String getPostcode() {
        return postcode;
    }

    public void setPostcode(String postcode) {
        this.postcode = postcode;
    }

    public String getWhat3words() {
        return what3words;
    }

    public void setWhat3words(String what3words) {
        this.what3words = what3words;
    }

    public String getLocalAuthority() {
        return localAuthority;
    }

    public void setLocalAuthority(String localAuthority) {
        this.localAuthority = localAuthority;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /** Comma-joined display form of the structured address, skipping blank parts. */
    public String getFullAddress() {
        String joined = Stream.of(addressLine1, addressLine2, addressLine3, postcode)
                .filter(part -> part != null && !part.isBlank())
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        return joined.isBlank() ? null : joined;
    }
}
