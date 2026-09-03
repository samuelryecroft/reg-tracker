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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getFullName() {
        return firstName + " " + lastName;
    }
}
