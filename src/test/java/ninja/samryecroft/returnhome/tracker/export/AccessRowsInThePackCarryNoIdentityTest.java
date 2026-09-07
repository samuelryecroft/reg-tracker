package ninja.samryecroft.returnhome.tracker.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.audit.AuditEvent;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventRepository;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventType;
import ninja.samryecroft.returnhome.tracker.audit.AuditFeedScope;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryEntry;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryService;
import ninja.samryecroft.returnhome.tracker.audit.DraftSaveRuns;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestTestFixtures;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The ground the T274 A5 ruling stands on, made into a guard.
 *
 * <p>The case-file pack keeps its record-access rows because it is scoped to one child AND because
 * it names <strong>roles rather than individuals</strong> - so the "second data subject" A3 warns
 * about is absent rather than merely reduced. <strong>That second half is a property of the code,
 * not a promise</strong>, and if it ever stops holding the ruling reopens rather than degrades.
 *
 * <p><strong>{@code detail} is the only way an identity could reach the pack, and that is
 * structural rather than a judgement.</strong> {@link AuditHistoryEntry} has no username component
 * at all, so {@code CaseFileNarrativeWriter} cannot render one however it is written; the event's
 * {@code actorUsernameAtTime} stops at the projection. What remains reachable is free text in
 * {@code detail} - {@code null} for access rows, which T295 made a deliberate choice rather than an
 * accident of the default branch: {@code AUDIT_VIEW_OPENED} now has its own case, and that case
 * passes {@code null}. <strong>Give it a detail that names somebody and the property is defeated by
 * the back door, silently, in a document that goes to a court</strong> - which is exactly why the
 * case having been written makes this guard more necessary rather than less.
 */
@SpringBootTest
class AccessRowsInThePackCarryNoIdentityTest extends AbstractIntegrationTest {

    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private ChildRepository childRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InterviewRequestRepository interviewRequestRepository;
    @Autowired
    private AuditEventRepository auditEventRepository;
    @Autowired
    private AuditEventPublisher auditEventPublisher;
    @Autowired
    private AuditHistoryService auditHistoryService;

    private static final String IDENTIFYING_SURNAME = "Featherstonehaugh";

    private String username;
    private Home home;
    private InterviewRequest request;
    private User actor;

    @BeforeEach
    void seedAChildAndAnActorWithADistinctiveName() {
        String suffix = "-" + System.nanoTime();
        Organisation careProvider = seededCareProvider();
        home = new Home();
        home.setName("Pack Identity House" + suffix);
        home.setOrganisation(careProvider);
        home = homeRepository.save(home);

        username = "pack-reviewer" + suffix;
        actor = new User();
        actor.setUsername(username);
        // Distinctive enough that a substring assertion cannot pass by luck against boilerplate.
        actor.setLastName(IDENTIFYING_SURNAME);
        actor.setRoles(new HashSet<>(Set.of(Role.HOME_STAFF)));
        actor.setHomes(new HashSet<>(Set.of(home)));
        actor.setEnabled(true);
        actor = userRepository.save(actor);

        Child child = new Child();
        child.setFirstName("Jordan");
        child.setLastName("Pack" + suffix);
        child.setDateOfBirth(LocalDate.of(2013, 7, 22));
        child.setLocalCaseReference("CH-PACK" + suffix);
        child.setHome(home);
        child = childRepository.save(child);

        request = InterviewRequestTestFixtures.requestAt(InterviewStatus.ALLOCATED);
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(actor);
        request.setReturnedAt(LocalDateTime.now().minusHours(10));
        request = interviewRequestRepository.save(request);
    }

    /**
     * THE POSITIVE CONTROL, and without it everything below is vacuous.
     *
     * <p>An assertion that the pack's rows do not contain a username proves nothing unless the
     * username was there to be dropped. The audit ROW records who acted - deliberately, that is the
     * point of a trail - and the projection is where it stops.
     */
    @Test
    void theUnderlyingAuditRowDoesRecordWhoLooked() {
        auditEventPublisher.auditViewOpened("InterviewRequest", request.getId(),
                home.getOrganisation().getId(), home.getId(), new AppUserPrincipal(actor));

        assertThat(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc(
                        "InterviewRequest", request.getId()))
                .singleElement()
                .extracting(AuditEvent::getActorUsernameAtTime)
                .isEqualTo(username);
    }

    /** Kevin's condition on the A5 ruling, measured rather than reasoned about. */
    @Test
    void theAccessRowThePackReceivesHasNoDetailAndNamesNobody() {
        auditEventPublisher.auditViewOpened("InterviewRequest", request.getId(),
                home.getOrganisation().getId(), home.getId(), new AppUserPrincipal(actor));

        AuditHistoryEntry accessRow = theAccessRow();

        assertThat(accessRow.detail()).isNull();
        // The property rather than the field: no rendered component of the row names the actor,
        // whatever route a future edit might take to put one there.
        assertThat(List.of(String.valueOf(accessRow.headline()), String.valueOf(accessRow.detail()),
                        String.valueOf(accessRow.actorRole()), String.valueOf(accessRow.when())))
                .noneMatch(field -> field.contains(username))
                .noneMatch(field -> field.contains(IDENTIFYING_SURNAME));
    }

    /** And it still says WHAT it is - identity-free is not the same as contentless. */
    @Test
    void theAccessRowStillCarriesTheActorsRole() {
        auditEventPublisher.auditViewOpened("InterviewRequest", request.getId(),
                home.getOrganisation().getId(), home.getId(), new AppUserPrincipal(actor));

        assertThat(theAccessRow().actorRole()).isEqualTo("Home Staff");
    }

    /**
     * The access row, found by its EVENT ID rather than by its headline.
     *
     * <p>It was found by {@code headline().contains("AUDIT_VIEW_OPENED")} until T295 renamed the copy
     * to "Record viewed" - and this test failed LOUDLY, which is the point: a POSITIVE assertion
     * against a literal breaks visibly when copy moves, where the negative one it replaced in the CSV
     * guard would have passed forever. It is still the wrong anchor though. The subject of this test
     * is a particular EVENT, so the event's identity is what selects it, and copy can now move again
     * without pretending this test has lost its subject.
     */
    private AuditHistoryEntry theAccessRow() {
        Long accessEventId = auditEventRepository
                .findByTargetTypeAndTargetIdOrderByOccurredAtDesc("InterviewRequest", request.getId()).stream()
                .filter(event -> event.getEventType() == AuditEventType.AUDIT_VIEW_OPENED)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no access event was recorded - the fixture, not the code"))
                .getId();
        return packRowsForThisRequest().stream()
                .filter(row -> accessEventId.equals(row.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The pack's scope dropped the access row - "
                        + "if that is now intended, this whole test has lost its subject"));
    }

    private List<AuditHistoryEntry> packRowsForThisRequest() {
        // The scope the pack asks for (T274 A5), not the child page's.
        return auditHistoryService
                .caseHistoryFor(List.of(request), DraftSaveRuns.KEPT_IN_FULL, AuditFeedScope.WITH_ACCESS_EVENTS)
                .get(0).entries();
    }
}
