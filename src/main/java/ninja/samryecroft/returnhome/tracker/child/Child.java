package ninja.samryecroft.returnhome.tracker.child;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDate;
import java.time.LocalDateTime;
import ninja.samryecroft.returnhome.tracker.fieldcrypto.Encrypted;
import ninja.samryecroft.returnhome.tracker.fieldcrypto.EncryptedEntity;
import ninja.samryecroft.returnhome.tracker.fieldcrypto.EncryptedFieldListener;
import ninja.samryecroft.returnhome.tracker.home.Home;

/**
 * A child's identifying details are encrypted at rest under their organisation's field key
 * (COLUMN-ENCRYPTION-OPTIONS.md tier 2). The names, date of birth and case reference live in
 * transient fields in memory and in {@code *_enc} columns in the database; the getters and setters
 * are unchanged, so nothing that uses a Child had to be rewritten.
 *
 * <p>The initials are the deliberate exception. They are stored in <strong>plaintext</strong> so a
 * list, tile or page heading can show "J.S." without unwrapping a key and decrypting every row, and
 * so those screens keep working when a name cannot be decrypted at all. It is an accepted leak: a
 * first letter, agreed as the price of a usable interface. Everything past the first letter stays
 * encrypted.
 */
@Entity
@Table(name = "children")
@EntityListeners(EncryptedFieldListener.class)
public class Child implements EncryptedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "first_name_enc", nullable = false)
    private String firstNameCiphertext;

    @Transient
    @Encrypted(ciphertextField = "firstNameCiphertext")
    private String firstName;

    @Column(name = "last_name_enc", nullable = false)
    private String lastNameCiphertext;

    @Transient
    @Encrypted(ciphertextField = "lastNameCiphertext")
    private String lastName;

    @Column(name = "date_of_birth_enc", nullable = false)
    private String dateOfBirthCiphertext;

    @Transient
    @Encrypted(ciphertextField = "dateOfBirthCiphertext")
    private LocalDate dateOfBirth;

    /**
     * Plaintext, on purpose, and kept in step with the name by the setters rather than by anyone
     * remembering to. Deriving it at display time would mean decrypting, which is the cost these
     * columns exist to avoid.
     */
    @Column(name = "first_name_initial", length = 1)
    private String firstNameInitial;

    @Column(name = "last_name_initial", length = 1)
    private String lastNameInitial;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_id", nullable = false)
    private Home home;

    @Column(name = "local_case_reference_enc")
    private String localCaseReferenceCiphertext;

    @Transient
    @Encrypted(ciphertextField = "localCaseReferenceCiphertext")
    private String localCaseReference;

    /**
     * WHEN this young person was taken off the active lists, or {@code null} while they are on them
     * (T170, reworked by T321).
     *
     * <p><strong>A timestamp rather than a boolean, and null rather than a companion flag.</strong>
     * A boolean records THAT a record was archived; this records WHEN, and on a safeguarding record
     * the when is the part somebody needs later - a question asked about a young person's care in
     * 2029 is a question about dates. The absence of a value <em>is</em> "not archived", so there is
     * one column rather than two that have to agree, and {@link #isArchived()} is derived from it
     * rather than stored beside it. Two fields that must agree are two fields that can disagree.
     *
     * <p><strong>Never a physical delete.</strong> The human asked to "remove" a child; a button
     * saying Delete while the record survives teaches him something false about his own data, so the
     * word on the screen is ARCHIVE and this is what it sets.
     *
     * <p><strong>Archiving must never hide, alter or make unreachable any of this child's interview
     * records.</strong> An approved report is a statutory document, and if archiving could take
     * safeguarding history out of view then archiving becomes the way to make it disappear. This
     * column is read by the LISTS; it is deliberately not read by anything that resolves a record
     * for viewing or export.
     *
     * <p><strong>It is the CURRENT state's date, not the history</strong>, which is why
     * {@code restore} clears it rather than keeping the last value. Every archive and every restore
     * is an audit event with its own timestamp, on a table that refuses UPDATE and DELETE by
     * trigger, so nothing is lost by this column forgetting. Asking it to remember previous
     * archivings would be asking the state to be the trail - the same mistake {@code OrgStatus}
     * refuses in its own javadoc, where intent is a property of the EVENT rather than of the state.
     *
     * <p>The setter is package-private on purpose, the same way {@code Organisation.setStatus} is:
     * every transition goes through {@code ChildLifecycleService}, so the blocking rule cannot be
     * reached past by a caller that simply sets the field.
     */
    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Resolved from the domain model, independently of whatever access check let this request
     * through - the same walk the document path uses. A scoping bug therefore produces a failed
     * decrypt rather than another organisation's child, because the key is chosen by a different
     * route than the permission was.
     *
     * <p>{@code getOrganisation().getId()} does not initialise the Organisation proxy - Hibernate
     * keeps the identifier on the proxy itself - but reaching it does initialise {@code home}. That
     * is one extra select per child unless the query fetches the home, which is why the list
     * queries in {@code ChildRepository} do.
     */
    @Override
    public Long owningOrganisationId() {
        if (home == null || home.getOrganisation() == null) {
            return null;
        }
        return home.getOrganisation().getId();
    }

    /** Display-only, and null-safe: a child with no surname recorded simply has no initial. */
    private static String initialOf(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.strip().substring(0, 1).toUpperCase(java.util.Locale.UK);
    }

    public String getFirstNameInitial() {
        return firstNameInitial;
    }

    public String getLastNameInitial() {
        return lastNameInitial;
    }

    /** "J.S." for a list or a page heading, without decrypting anything. */
    public String getInitials() {
        String first = firstNameInitial == null ? "" : firstNameInitial + ".";
        String last = lastNameInitial == null ? "" : lastNameInitial + ".";
        return (first + last).isEmpty() ? "?" : first + last;
    }

    public Long getId() {
        return id;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
        this.firstNameInitial = initialOf(firstName);
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
        this.lastNameInitial = initialOf(lastName);
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public void setDateOfBirth(LocalDate dateOfBirth) {
        this.dateOfBirth = dateOfBirth;
    }

    public Home getHome() {
        return home;
    }

    public void setHome(Home home) {
        this.home = home;
    }

    public String getLocalCaseReference() {
        return localCaseReference;
    }

    public void setLocalCaseReference(String localCaseReference) {
        this.localCaseReference = localCaseReference;
    }

    /**
     * Derived, never stored: a young person is archived exactly when they have an archive date.
     *
     * <p>Kept as a method because that is the question every caller actually asks, and deriving it
     * is what makes a second column impossible to get out of step. <strong>Note that Spring Data
     * cannot see this</strong> - derived query names resolve against the persistent attributes, not
     * the getters - which is why {@link ChildRepository} asks {@code ArchivedAtIsNull}. That is a
     * feature here rather than a nuisance: the queries name the column that exists.
     */
    public boolean isArchived() {
        return archivedAt != null;
    }

    public LocalDateTime getArchivedAt() {
        return archivedAt;
    }

    void setArchivedAt(LocalDateTime archivedAt) {
        this.archivedAt = archivedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getFullName() {
        return firstName + " " + lastName;
    }
}
