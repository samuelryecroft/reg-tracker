package ninja.samryecroft.returnhome.tracker.fieldcrypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import ninja.samryecroft.returnhome.tracker.report.ReportStatus;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The end-to-end claim: what reaches the database is ciphertext.
 *
 * <p>Deliberately checked with raw SQL rather than through the repository. Reading a Child back
 * through JPA proves only that the round trip works - it would pass just as happily if the column
 * held plaintext all along, because the entity would return the same string either way. The only
 * assertion that means anything here is one made against the bytes in the column, which is why this
 * test holds a JdbcTemplate.
 */
@SpringBootTest
class FieldEncryptionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private ChildRepository childRepository;

    @Autowired
    private HomeRepository homeRepository;


    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InterviewRequestRepository interviewRequestRepository;

    @Autowired
    private InterviewReportRepository interviewReportRepository;

    @Autowired
    private UserRepository userRepository;

    private Child saveChild(String first, String last, LocalDate dob, Home home) {
        Child child = new Child();
        child.setFirstName(first);
        child.setLastName(last);
        child.setDateOfBirth(dob);
        child.setHome(home);
        return childRepository.save(child);
    }

    /**
     * Created here rather than looked up: the base class truncates everything except the reference
     * organisations before each test, so there is no home to find. The organisation matters though -
     * it is what selects the field key.
     */
    private Home anyHome() {
        Organisation organisation =
                seededCareProvider();
        Home home = new Home();
        home.setName("Encryption Test House");
        home.setOrganisation(organisation);
        return homeRepository.save(home);
    }

    @Test
    void storesCiphertextInTheDatabaseAndReadsBackPlaintext() {
        Child saved = saveChild("Jamie", "Okafor", LocalDate.of(2010, 4, 12), anyHome());

        String storedFirstName = jdbc.queryForObject(
                "select first_name_enc from children where id = ?", String.class, saved.getId());
        String storedDob = jdbc.queryForObject(
                "select date_of_birth_enc from children where id = ?", String.class, saved.getId());

        assertThat(storedFirstName)
                .as("the column must hold ciphertext, not the name")
                .isNotNull()
                .doesNotContain("Jamie")
                .startsWith(FieldCipher.PREFIX);
        assertThat(storedDob).doesNotContain("2010");

        Child reloaded = childRepository.findDetailedById(saved.getId()).orElseThrow();
        assertThat(reloaded.getFirstName()).isEqualTo("Jamie");
        assertThat(reloaded.getLastName()).isEqualTo("Okafor");
        assertThat(reloaded.getDateOfBirth()).isEqualTo(LocalDate.of(2010, 4, 12));
    }

    /**
     * The initials are the display tradeoff, so they must be readable without a key - which means
     * asserting them against the raw column, not against the entity.
     */
    @Test
    void storesInitialsInPlaintextForListsAndHeadings() {
        Child saved = saveChild("Jamie", "Okafor", LocalDate.of(2010, 4, 12), anyHome());

        assertThat(jdbc.queryForObject("select first_name_initial from children where id = ?",
                String.class, saved.getId())).isEqualTo("J");
        assertThat(jdbc.queryForObject("select last_name_initial from children where id = ?",
                String.class, saved.getId())).isEqualTo("O");
        assertThat(saved.getInitials()).isEqualTo("J.O.");
    }

    /** Renaming a child has to move the initial with it, or a list quietly shows the old one. */
    @Test
    void keepsInitialsInStepWhenANameChanges() {
        Child saved = saveChild("Jamie", "Okafor", LocalDate.of(2010, 4, 12), anyHome());

        saved.setLastName("Brennan");
        childRepository.saveAndFlush(saved);

        assertThat(jdbc.queryForObject("select last_name_initial from children where id = ?",
                String.class, saved.getId())).isEqualTo("B");
    }

    /**
     * Two children in one organisation must not share ciphertext for the same name - the check that
     * randomized encryption really is being used all the way through the stack, not just in the
     * cipher's own unit test.
     */
    @Test
    void twoChildrenWithTheSameNameDoNotShareCiphertext() {
        Home home = anyHome();
        Child one = saveChild("Sam", "Taylor", LocalDate.of(2011, 1, 1), home);
        Child two = saveChild("Sam", "Taylor", LocalDate.of(2012, 2, 2), home);

        String first = jdbc.queryForObject("select first_name_enc from children where id = ?",
                String.class, one.getId());
        String second = jdbc.queryForObject("select first_name_enc from children where id = ?",
                String.class, two.getId());

        assertThat(first).isNotEqualTo(second);
    }

    /** One wrapped key per organisation, created on first use and reused after that. */
    @Test
    void createsExactlyOneWrappedKeyPerOrganisation() {
        Home home = anyHome();
        saveChild("Ada", "Nkemelu", LocalDate.of(2009, 9, 9), home);
        saveChild("Bea", "Nkemelu", LocalDate.of(2009, 9, 9), home);

        Long organisationId = home.getOrganisation().getId();
        Integer keys = jdbc.queryForObject(
                "select count(*) from org_field_key where organisation_id = ?", Integer.class,
                organisationId);

        assertThat(keys).isEqualTo(1);
    }

    /**
     * The wrapped key is the one row whose loss is unrecoverable, and losing it fails <em>quietly</em>:
     * the application creates an organisation's key on first use, so a missing row looks like a new
     * organisation rather than an error. It would mint a fresh key and every value written before
     * would stop being readable while everything reported itself healthy. The trigger is what turns
     * that into a loud refusal, so it is worth an actual attempt to delete rather than trust.
     */
    @Test
    void refusesToDeleteAWrappedKey() {
        Home home = anyHome();
        saveChild("Rae", "Iwu", LocalDate.of(2011, 3, 3), home);
        Long organisationId = home.getOrganisation().getId();

        assertThatThrownBy(() -> jdbc.update("delete from org_field_key where organisation_id = ?",
                organisationId))
                .hasMessageContaining("cannot be deleted");

        assertThat(jdbc.queryForObject("select count(*) from org_field_key where organisation_id = ?",
                Integer.class, organisationId)).isEqualTo(1);
    }

    /**
     * Rotation re-wraps this row under a new KEK version, so UPDATE has to stay permitted - the
     * trigger must refuse deletion without also making rotation impossible.
     */
    @Test
    void stillAllowsAWrappedKeyToBeReWrappedForRotation() {
        Home home = anyHome();
        saveChild("Rae", "Iwu", LocalDate.of(2011, 3, 3), home);
        Long organisationId = home.getOrganisation().getId();

        int updated = jdbc.update("update org_field_key set key_version = ? where organisation_id = ?",
                "a-newer-version", organisationId);

        assertThat(updated).isEqualTo(1);
    }

    /**
     * The four columns added on review (T103). Worth an end-to-end rather than trusting the shared
     * mechanism, because reviewComments is the one field authored by a SUPPLIER reviewer while
     * encrypting under the CARE PROVIDER's key - the key follows the row's owning organisation,
     * which is the data owner. Asserting it here pins that intent.
     */
    @Test
    void encryptsTheReviewerAuthoredNarrativeToo() {
        Home home = anyHome();
        Child child = saveChild("Nia", "Adeyemi", LocalDate.of(2010, 6, 1), home);
        User staff = userRepository.save(newUser("staff-" + System.nanoTime()));

        InterviewRequest request = new InterviewRequest();
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(staff);
        request.setReturnedAt(LocalDateTime.now().minusHours(4));
        request = interviewRequestRepository.save(request);

        InterviewReport report = new InterviewReport();
        report.setInterviewRequest(request);
        report.setVisitor(staff);
        report.setStatus(ReportStatus.DRAFT);
        report.setReviewComments("Approved; the safeguarding actions are proportionate.");
        report.setRecommendations("Review the placement plan at the next meeting.");
        report = interviewReportRepository.save(report);

        String storedReview = jdbc.queryForObject(
                "select review_comments_enc from interview_reports where id = ?", String.class,
                report.getId());
        String storedRecommendations = jdbc.queryForObject(
                "select recommendations_enc from interview_reports where id = ?", String.class,
                report.getId());

        assertThat(storedReview).isNotNull().doesNotContain("proportionate")
                .startsWith(FieldCipher.PREFIX);
        assertThat(storedRecommendations).isNotNull().doesNotContain("placement");

        InterviewReport reloaded = interviewReportRepository.findById(report.getId()).orElseThrow();
        assertThat(reloaded.getReviewComments())
                .isEqualTo("Approved; the safeguarding actions are proportionate.");
        assertThat(reloaded.getRecommendations())
                .isEqualTo("Review the placement plan at the next meeting.");
    }

    private User newUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("irrelevant-to-this-test");
        user.setLastName("Test Staff");
        user.setRoles(java.util.Set.of(Role.HOME_STAFF));
        user.setEnabled(true);
        return user;
    }
}
