package ninja.samryecroft.returnhome.tracker.child;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.audit.AuditEvent;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventRepository;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventType;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T376 (CODE-REVIEW-2026-09-18 P0.1): a child edit that changes ONLY an encrypted field reaches the
 * database.
 *
 * <p>{@code Child}'s plaintext fields are {@code @Transient}; only the {@code *_enc} ciphertext
 * columns are mapped, and {@code children} has no {@code updated_at}. So an edit that changed
 * nothing mapped - a date of birth, a case reference, a name keeping its initial - left the row
 * clean in Hibernate's eyes: no UPDATE, while {@code CHILD_UPDATED} went into the append-only audit
 * trail claiming the field had changed. Reproduced on 2026-09-19: all three cases below failed.
 *
 * <p>The existing edit test passed by luck - it changes a surname's initial, which IS a mapped
 * column. Every assertion here reloads from the database through a fresh transaction, and the
 * last one bypasses the service entirely, because the fix lives in the encryption listener and
 * must hold for a managed entity nobody explicitly saves.
 */
@SpringBootTest
class ChildEditPersistsTransientOnlyChangeTest extends AbstractIntegrationTest {

    @Autowired
    private ChildLifecycleService childLifecycleService;
    @Autowired
    private ChildRepository childRepository;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private OrganisationRepository organisationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AuditEventRepository auditEventRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private DataSource dataSource;

    private Child child;
    private AppUserPrincipal manager;

    @BeforeEach
    void seedAChild() {
        String suffix = "-" + System.nanoTime();
        Organisation org = new Organisation();
        org.setName("T376 Org" + suffix);
        org.setType(OrgType.CARE_PROVIDER);
        org = organisationRepository.save(org);

        Home home = new Home();
        home.setName("T376 House" + suffix);
        home.setOrganisation(org);
        home = homeRepository.save(home);

        User user = new User();
        user.setUsername("t376-manager" + suffix);
        user.setEmail("t376-manager" + suffix + "@example.test");
        user.setLastName("Manager");
        user.setRoles(new HashSet<>(Set.of(Role.ORG_ADMIN)));
        user.setOrganisation(org);
        user.setEnabled(true);
        manager = new AppUserPrincipal(userRepository.saveAndFlush(user), false);

        child = new Child();
        child.setFirstName("Sasha");
        child.setLastName("Original" + suffix);
        child.setDateOfBirth(LocalDate.of(2011, 6, 1));
        child.setLocalCaseReference("CH-T376" + suffix);
        child.setHome(home);
        child = childRepository.save(child);
    }

    @Test
    void aDateOfBirthOnlyCorrectionIsPersisted() {
        LocalDate corrected = LocalDate.of(2011, 6, 2);

        childLifecycleService.update(child.getId(), child.getFirstName(), child.getLastName(),
                corrected, child.getLocalCaseReference(), manager);

        assertThat(reload().getDateOfBirth())
                .as("the corrected date of birth, read back from the database")
                .isEqualTo(corrected);
    }

    @Test
    void aCaseReferenceOnlyCorrectionIsPersisted() {
        String corrected = child.getLocalCaseReference() + "-CORRECTED";

        childLifecycleService.update(child.getId(), child.getFirstName(), child.getLastName(),
                child.getDateOfBirth(), corrected, manager);

        assertThat(reload().getLocalCaseReference())
                .as("the corrected case reference, read back from the database")
                .isEqualTo(corrected);
    }

    @Test
    void aFirstNameCorrectionThatKeepsTheInitialIsPersisted() {
        childLifecycleService.update(child.getId(), "Sam", child.getLastName(),
                child.getDateOfBirth(), child.getLocalCaseReference(), manager);

        assertThat(reload().getFirstName())
                .as("a name change that keeps the same initial, read back from the database")
                .isEqualTo("Sam");
    }

    /**
     * The audit half of the same defect: the trail must name exactly the field that changed, and
     * now that the change genuinely persists, the row it describes is true.
     */
    @Test
    void aDateOfBirthOnlyCorrectionIsAuditedAsThatFieldAlone() {
        childLifecycleService.update(child.getId(), child.getFirstName(), child.getLastName(),
                LocalDate.of(2011, 6, 2), child.getLocalCaseReference(), manager);

        List<AuditEvent> updates = auditEventRepository
                .findByTargetTypeAndTargetIdOrderByOccurredAtDesc("Child", child.getId()).stream()
                .filter(row -> row.getEventType() == AuditEventType.CHILD_UPDATED)
                .toList();
        assertThat(updates).hasSize(1);
        assertThat(updates.get(0).getMetadata())
                .contains("dateOfBirth")
                .doesNotContain("firstName")
                .doesNotContain("lastName")
                .doesNotContain("localCaseReference")
                .doesNotContain("2011-06-02");
    }

    /**
     * THE PROPERTY, stated without the service in the way: a managed child whose only change is a
     * transient plaintext value is updated at commit, with no explicit save and no other column
     * touched. This is what makes the fix hold for the next encrypted entity too.
     */
    @Test
    void aManagedChildWhoseOnlyChangeIsAPlaintextValueIsUpdatedAtCommitWithoutAnExplicitSave() {
        LocalDate corrected = LocalDate.of(2011, 6, 3);

        transactionTemplate.executeWithoutResult(tx -> {
            Child managed = childRepository.findDetailedById(child.getId()).orElseThrow();
            managed.setDateOfBirth(corrected);
        });

        assertThat(reload().getDateOfBirth()).isEqualTo(corrected);
    }

    /** And nothing is rewritten when nothing changed - the ciphertext at rest stays byte-identical. */
    @Test
    void aFlushWithNoPlaintextChangeLeavesTheStoredCiphertextUntouched() {
        String before = ciphertextOfDateOfBirth();

        transactionTemplate.executeWithoutResult(tx -> {
            Child managed = childRepository.findDetailedById(child.getId()).orElseThrow();
            managed.setDateOfBirth(managed.getDateOfBirth());
        });

        assertThat(ciphertextOfDateOfBirth())
                .as("a fresh IV would make any rewrite visible; an unchanged value must not be rewritten")
                .isEqualTo(before);
    }

    private Child reload() {
        return childRepository.findDetailedById(child.getId()).orElseThrow();
    }

    private String ciphertextOfDateOfBirth() {
        return new JdbcTemplate(dataSource).queryForObject(
                "select date_of_birth_enc from children where id = ?", String.class, child.getId());
    }
}
