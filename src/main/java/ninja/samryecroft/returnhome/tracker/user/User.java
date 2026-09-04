package ninja.samryecroft.returnhome.tracker.user;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    /**
     * Null for an account that has no local credential - one created for Entra sign-in, before
     * {@code password} is dropped altogether in P8. Form login fails closed for such a row:
     * {@code BCryptPasswordEncoder.matches} rejects a null encoding rather than matching anything.
     */
    private String password;

    /**
     * Entra's {@code sub} (or {@code oid}) claim once this account has been linked to a directory
     * identity, null until then. Unique when present.
     *
     * <p>This is the persistent identity key and the only value a login may link on. Email is the
     * admin-entered identifier and the lookup for the one-time link, never the join key - it is
     * mutable and addresses get recycled, so binding identity to it would let a new starter inherit
     * a leaver's access (ENTRA-AUTH-DESIGN.md §3).
     *
     * <p>Nothing reads or writes this yet; the link is P4.
     */
    @Column(name = "idp_subject", unique = true)
    private String idpSubject;

    @Column(name = "first_name")
    private String firstName;

    /**
     * Not null, because every user has at least one name token. A person with a single name has it
     * here with a null {@code firstName} - which is why this is the field the database insists on
     * rather than the pair.
     */
    @Column(name = "last_name", nullable = false)
    private String lastName;

    /**
     * The canonical profile address, entered by an admin. Nullable for rows created before T127.
     *
     * <p>Deliberately not {@code username}, and deliberately not unique. {@code username} stays the
     * login key, and shared mailboxes are ordinary in this sector. This is also the field a future
     * Entra link will sync its {@code email} claim into and look up on for the one-time link, so it
     * is one field rather than a profile copy beside an identity copy - see {@link #idpSubject}.
     *
     * <p>Not encrypted; V17 records why, and it is a property of the per-organisation key model
     * rather than a view about how sensitive this is.
     */
    @Column(name = "email", length = 320)
    private String email;

    /** Optional throughout: a contact number is useful, not something to block an account on. */
    @Column(name = "contact_phone", length = 30)
    private String contactPhone;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Set<Role> roles = new HashSet<>();

    /** Used by ORG_ADMIN, COORDINATOR, VISITOR, REVIEWER, VIEWER. Null for ADMIN (platform-wide)
     * and HOME_STAFF (scoped via {@link #homes}). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organisation_id")
    private Organisation organisation;

    /**
     * The homes this user is attached to - the one mechanism, for every role that has one.
     *
     * <p>HOME_STAFF used to hold a single {@code home_id} while VIEWER held this collection: the
     * same relationship expressed two ways, which is how one of them silently stops being checked.
     * Both now live here (V16). For HOME_STAFF these are the homes they work in; for VIEWER, the
     * homes they may see reports for. Empty for every other role.
     *
     * <p>All of a user's homes belong to one Care Provider organisation - {@code UserService}
     * enforces that on the way in, and org-level scoping and theme resolution both depend on it.
     *
     * <p>Never read directly off a session-loaded/detached User - every access-control check uses a
     * dedicated targeted repository query instead, to avoid the lazy-loading trap that the (EAGER,
     * but still order-of-operations-sensitive) roles collection hit earlier.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_homes", joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "home_id"))
    private Set<Home> homes = new HashSet<>();

    @Column(nullable = false)
    private boolean enabled = true;

    /**
     * Whether this account may extract records as a portable file, which is a separate act from
     * reading them (roadmap 2.5 / D-6). Off unless granted; role eligibility is a further, harder
     * ceiling applied in {@code ExportCapability}.
     */
    @Column(name = "can_export", nullable = false)
    private boolean canExport = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getIdpSubject() {
        return idpSubject;
    }

    public void setIdpSubject(String idpSubject) {
        this.idpSubject = idpSubject;
    }

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

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(String contactPhone) {
        this.contactPhone = contactPhone;
    }

    /**
     * Display name, derived rather than stored - and there is deliberately no setter.
     *
     * <p>Roughly twenty templates and several document-generation paths read {@code fullName}, and
     * keeping it as a read-only derived value means none of them had to change or can drift from
     * the fields that now hold the truth. A stored copy would be a second place for a name to live,
     * and the one that silently goes stale.
     */
    @Transient
    public String getFullName() {
        if (firstName == null || firstName.isBlank()) {
            return lastName;
        }
        return firstName + " " + lastName;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles = roles;
    }

    public boolean hasRole(Role role) {
        return roles.contains(role);
    }


    public Organisation getOrganisation() {
        return organisation;
    }

    public void setOrganisation(Organisation organisation) {
        this.organisation = organisation;
    }

    public Set<Home> getHomes() {
        return homes;
    }

    public void setHomes(Set<Home> homes) {
        this.homes = homes;
    }

    public boolean isCanExport() {
        return canExport;
    }

    public void setCanExport(boolean canExport) {
        this.canExport = canExport;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
