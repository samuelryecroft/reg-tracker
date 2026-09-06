package ninja.samryecroft.returnhome.tracker.fieldcrypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.fieldcrypto.EncryptedEntity;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestTestFixtures;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Each encrypted entity is not merely <em>listed</em> by the probe - its path leads to the RIGHT
 * organisation.
 *
 * <p><b>Why the coverage test is not enough, and this is god's question rather than my own
 * foresight.</b> {@code EncryptedDataProbeCoverageTest} proves every encrypted entity appears in the
 * probe's map. It cannot prove any of those paths is correct. A path pointing at the wrong
 * association - or at a plausible neighbouring one - leaves the list complete and the probe wrong,
 * <b>and wrong in the fail-open direction</b>: it answers "no encrypted data" for an organisation
 * that has some, and the guard then waves through the mint it exists to refuse. <b>A completeness
 * check passes happily over a wrong path.</b>
 *
 * <p><b>The oracle is the entity's own answer.</b> Every encrypted entity can already name its
 * owning organisation in Java, through {@link EncryptedEntity#owningOrganisationId()}, and that is
 * the definition the encryption itself uses. So the assertion is that the QUERY agrees with the
 * OBJECT - one source of truth, checked from both ends, rather than two hand-written statements of
 * the same association hoping to match.
 *
 * <p>The negative half matters as much: a path that resolved to "any organisation with data" would
 * satisfy the positive assertion for every entity and still be useless, so each row is also asserted
 * NOT to be found under an unrelated organisation.
 */
@SpringBootTest
class EncryptedDataProbeFindsEachEntityTest extends AbstractIntegrationTest {

    @Autowired private EncryptedDataProbe probe;
    @Autowired private OrganisationRepository organisationRepository;
    @Autowired private HomeRepository homeRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private InterviewRequestRepository interviewRequestRepository;
    @Autowired private InterviewReportRepository interviewReportRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private String suffix;
    private Organisation owner;
    private Organisation stranger;
    private Home home;

    @BeforeEach
    void seed() {
        suffix = "-" + System.nanoTime();
        owner = organisationRepository.save(careProvider("Owner Org" + suffix));
        stranger = organisationRepository.save(careProvider("Stranger Org" + suffix));

        home = new Home();
        home.setName("Probe House" + suffix);
        home.setOrganisation(owner);
        home = homeRepository.save(home);
    }

    @Test
    void aChildIsFoundUnderItsOwnOrganisationAndNoOther() {
        Child child = childRepository.saveAndFlush(child());
        assertFoundUnderItsOwner(child);
    }

    @Test
    void anInterviewRequestIsFoundUnderItsOwnOrganisationAndNoOther() {
        InterviewRequest request = interviewRequestRepository.saveAndFlush(request());
        assertFoundUnderItsOwner(request);
    }

    /**
     * The deepest path of the three - a report reaches its organisation through its request's home -
     * and therefore the one where a plausible-looking association is most likely to be wrong.
     */
    @Test
    void anInterviewReportIsFoundUnderItsOwnOrganisationAndNoOther() {
        InterviewRequest request = interviewRequestRepository.saveAndFlush(request());
        InterviewReport report = new InterviewReport();
        report.setInterviewRequest(request);
        report.setVisitor(request.getAllocatedVisitor());
        report.setStatus(ninja.samryecroft.returnhome.tracker.report.ReportStatus.DRAFT);
        report.setInterviewLocation("The quiet room");
        assertFoundUnderItsOwner(interviewReportRepository.saveAndFlush(report));
    }

    private void assertFoundUnderItsOwner(EncryptedEntity entity) {
        String entityName = entity.getClass().getSimpleName();
        Long owningOrganisationId = entity.owningOrganisationId();
        assertThat(owningOrganisationId)
                .as("the entity must be able to name its own organisation, or this test has no "
                        + "oracle and proves nothing")
                .isEqualTo(owner.getId());

        // Asked of THIS path by name, never through organisationHoldsEncryptedRows. That method
        // short-circuits on the first match, so a Child row would answer "yes" whatever the
        // report's path said - and the first version of this test did exactly that, passing while
        // the report's path pointed at the supplier organisation instead of the care provider.
        // A test that cannot attribute its own "yes" is not a correctness check.
        assertThat(probe.holdsRowsOf(entityName, owningOrganisationId))
                .as("%s's path must reach the organisation the entity itself names - a path to the "
                        + "wrong association leaves the coverage check passing and the guard "
                        + "fail-open", entityName)
                .isTrue();

        assertThat(probe.holdsRowsOf(entityName, stranger.getId()))
                .as("and it must not find this row under an unrelated organisation - a path that "
                        + "resolved to 'anyone with data' would satisfy the assertion above for "
                        + "every entity and still be useless")
                .isFalse();
    }

    private Organisation careProvider(String name) {
        Organisation organisation = new Organisation();
        organisation.setName(name);
        organisation.setType(OrgType.CARE_PROVIDER);
        organisation.setSupplierOrganisation(seededSupplier());
        return organisation;
    }

    private Child child() {
        Child child = new Child();
        child.setFirstName("Probe");
        child.setLastName("Child");
        child.setDateOfBirth(LocalDate.of(2011, 6, 2));
        child.setHome(home);
        return child;
    }

    private InterviewRequest request() {
        User visitor = new User();
        visitor.setUsername("probe-visitor" + suffix);
        visitor.setPassword(passwordEncoder.encode("password123"));
        visitor.setFirstName("Probe");
        visitor.setLastName("Visitor");
        visitor.setEmail("probe" + suffix + "@example.test");
        visitor.setRoles(new HashSet<>(Set.of(Role.VISITOR)));
        visitor.setHomes(new HashSet<>(Set.of(home)));
        visitor = userRepository.save(visitor);

        InterviewRequest request = InterviewRequestTestFixtures.requestAt(InterviewStatus.SCHEDULED);
        request.setChild(childRepository.saveAndFlush(child()));
        request.setHome(home);
        request.setRequestedBy(visitor);
        request.setAllocatedVisitor(visitor);
        request.setMissingSince(LocalDateTime.now().minusDays(3));
        request.setReturnedAt(LocalDateTime.now().minusHours(10));
        return request;
    }
}
