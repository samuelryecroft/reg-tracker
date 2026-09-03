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

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false)
    private Set<Role> roles = new HashSet<>();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_id")
    private Home home;

    /** Used by ORG_ADMIN, COORDINATOR, VISITOR, REVIEWER, VIEWER. Null for ADMIN (platform-wide)
     * and HOME_STAFF (scoped via home). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organisation_id")
    private Organisation organisation;

    /** Only meaningful for VIEWER: which specific homes (within their Care Provider org) they can
     * see reports for. Never read directly off a session-loaded/detached User - every access-control
     * check uses a dedicated targeted repository query instead, to avoid the lazy-loading trap that
     * the (EAGER, but still order-of-operations-sensitive) roles collection hit earlier. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_viewer_homes", joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "home_id"))
    private Set<Home> viewerHomes = new HashSet<>();

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

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
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

    public Home getHome() {
        return home;
    }

    public void setHome(Home home) {
        this.home = home;
    }

    public Organisation getOrganisation() {
        return organisation;
    }

    public void setOrganisation(Organisation organisation) {
        this.organisation = organisation;
    }

    public Set<Home> getViewerHomes() {
        return viewerHomes;
    }

    public void setViewerHomes(Set<Home> viewerHomes) {
        this.viewerHomes = viewerHomes;
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
