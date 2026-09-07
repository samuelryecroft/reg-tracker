package ninja.samryecroft.returnhome.tracker.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.audit.AuditEvent;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventRepository;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventType;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestTestFixtures;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
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

/**
 * T170: archiving a young person, and the rule that outranks the feature.
 *
 * <p><strong>Archiving must never hide, alter or make unreachable any of their interview
 * records.</strong> An approved report is a statutory document, and <em>if archiving could take
 * safeguarding history out of view, archiving becomes the way to make it disappear</em> - the worst
 * thing this feature could do. That is the first test here, deliberately, because everything else
 * about the feature is negotiable and this is not.
 */
@SpringBootTest
class ArchivingNeverHidesInterviewRecordsTest extends AbstractIntegrationTest {

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
    private InterviewRequestRepository interviewRequestRepository;
    @Autowired
    private AuditEventRepository auditEventRepository;

    private String suffix;
    private Home home;
    private Child child;
    private User staff;
    private AppUserPrincipal manager;

    @BeforeEach
    void seedAChildWithAFinishedInterview() {
        suffix = "-" + System.nanoTime();
        Organisation org = new Organisation();
        org.setName("T170 Org" + suffix);
        org.setType(OrgType.CARE_PROVIDER);
        org = organisationRepository.save(org);

        home = new Home();
        home.setName("T170 House" + suffix);
        home.setOrganisation(org);
        home = homeRepository.save(home);

        staff = new User();
        staff.setUsername("t170-staff" + suffix);
        staff.setLastName("Staff");
        staff.setRoles(new HashSet<>(Set.of(Role.HOME_STAFF)));
        staff.setHomes(new HashSet<>(Set.of(home)));
        staff.setEnabled(true);
        staff = userRepository.saveAndFlush(staff);

        User admin = new User();
        admin.setUsername("t170-manager" + suffix);
        admin.setLastName("Manager");
        admin.setRoles(new HashSet<>(Set.of(Role.ORG_ADMIN)));
        admin.setOrganisation(org);
        admin.setEnabled(true);
        manager = new AppUserPrincipal(userRepository.saveAndFlush(admin), false);

        child = new Child();
        child.setFirstName("Sasha");
        child.setLastName("T170" + suffix);
        child.setDateOfBirth(LocalDate.of(2011, 6, 1));
        child.setLocalCaseReference("CH-T170" + suffix);
        child.setHome(home);
        child = childRepository.save(child);
    }

    /**
     * THE RULE THAT OUTRANKS THE FEATURE. A finished interview stays exactly as reachable after the
     * archive as before it - same rows, same status, nothing rewritten.
     */
    @Test
    void aFinishedInterviewIsUntouchedAndStillReachableAfterArchiving() {
        InterviewRequest finished = seedInterview(InterviewStatus.REPORT_APPROVED);

        childLifecycleService.archive(child, manager);

        assertThat(interviewRequestRepository.findByChildIdOrderByCreatedAtDesc(child.getId()))
                .as("the safeguarding history must survive the archive intact")
                .extracting(InterviewRequest::getId)
                .containsExactly(finished.getId());
        assertThat(interviewRequestRepository.findById(finished.getId()).orElseThrow().getStatus())
                .as("and must not be rewritten to make the archive possible")
                .isEqualTo(InterviewStatus.REPORT_APPROVED);
    }

    /** And the child themselves stays retrievable by id - archived is not gone. */
    @Test
    void theArchivedChildIsStillRetrievableById() {
        childLifecycleService.archive(child, manager);

        assertThat(childRepository.findDetailedById(child.getId()))
                .get()
                .extracting(Child::isArchived)
                .isEqualTo(true);
    }

    /** They do leave the active list, which is the whole point of the feature. */
    @Test
    void theArchivedChildLeavesTheActiveList() {
        assertThat(childRepository.findByHomeIdInAndArchivedFalse(Set.of(home.getId())))
                .as("present before, or this proves nothing")
                .extracting(Child::getId).contains(child.getId());

        childLifecycleService.archive(child, manager);

        assertThat(childRepository.findByHomeIdInAndArchivedFalse(Set.of(home.getId())))
                .extracting(Child::getId).doesNotContain(child.getId());
    }

    /**
     * BLOCKED WHILE UNFINISHED, and the refusal carries the COUNT and the ROUTE. A bare "cannot
     * archive" leaves someone hunting for which of the interviews on the page is the obstacle.
     */
    @Test
    void anInterviewAwaitingReviewBlocksTheArchiveWithTheCountAndTheRoute() {
        seedInterview(InterviewStatus.REPORT_SUBMITTED);

        assertThatThrownBy(() -> childLifecycleService.archive(child, manager))
                .isInstanceOf(ChildNotArchivableException.class)
                .hasMessageContaining("1 interview is")
                .hasMessageContaining("pending review")
                .hasMessageContaining("Finish the interview, then archive");
    }

    /**
     * AND IT NEVER CASCADES. The refused archive leaves the interview exactly as it was - not
     * cancelled, not closed, not quietened to make the archive possible.
     */
    @Test
    void aRefusedArchiveChangesNothingAtAll() {
        InterviewRequest open = seedInterview(InterviewStatus.ALLOCATED);

        assertThatThrownBy(() -> childLifecycleService.archive(child, manager))
                .isInstanceOf(ChildNotArchivableException.class);

        assertThat(childRepository.findDetailedById(child.getId()).orElseThrow().isArchived()).isFalse();
        assertThat(interviewRequestRepository.findById(open.getId()).orElseThrow().getStatus())
                .isEqualTo(InterviewStatus.ALLOCATED);
    }

    /** Restore is the same shape as archive, and puts them back on the list. */
    @Test
    void restorePutsThemBackOnTheActiveList() {
        childLifecycleService.archive(child, manager);

        childLifecycleService.restore(childRepository.findDetailedById(child.getId()).orElseThrow(), manager);

        assertThat(childRepository.findByHomeIdInAndArchivedFalse(Set.of(home.getId())))
                .extracting(Child::getId).contains(child.getId());
    }

    /** Both directions are audited, and neither row carries the child's details. */
    @Test
    void bothDirectionsAreAuditedAndRecordNoValues() {
        childLifecycleService.archive(child, manager);
        childLifecycleService.restore(childRepository.findDetailedById(child.getId()).orElseThrow(), manager);

        assertThat(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("Child", child.getId()))
                .extracting(AuditEvent::getEventType)
                .contains(AuditEventType.CHILD_ARCHIVED, AuditEventType.CHILD_RESTORED);
        assertThat(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("Child", child.getId()))
                .allSatisfy(event -> assertThat(String.valueOf(event.getMetadata()))
                        .as("the trail says what happened to which record, never the child's details")
                        .doesNotContain(child.getLastName()));
    }

    /** A cancelled interview is finished too, so it does not block. */
    @Test
    void aCancelledInterviewDoesNotBlockTheArchive() {
        seedInterview(InterviewStatus.CANCELLED);

        assertThatCode(() -> childLifecycleService.archive(child, manager)).doesNotThrowAnyException();
    }

    private InterviewRequest seedInterview(InterviewStatus status) {
        InterviewRequest request = InterviewRequestTestFixtures.requestAt(status);
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(staff);
        request.setReturnedAt(LocalDateTime.now().minusDays(2));
        return interviewRequestRepository.save(request);
    }
}
