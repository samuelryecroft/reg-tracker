package ninja.samryecroft.returnhome.tracker.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryEntry;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistorySection;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestTestFixtures;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T274 A5 at the CALLER, which is the only place it can be guarded.
 *
 * <p><strong>Why this test exists and the service-level ones are not enough.</strong> My T274 guard
 * proved {@code AuditHistoryService} <em>can</em> be asked for either scope, and stayed green when I
 * flipped the caller to the wrong one - because a test of the mechanism cannot see which option a
 * caller picks. The ruling here is about a choice: the child page asks for
 * {@code CASE_ACTIVITY_ONLY}, the case-file pack asks for {@code WITH_ACCESS_EVENTS}, and the same
 * method serves both. So the assertion runs through the controller and reads what it put on the
 * model.
 *
 * <p><strong>Display only, and half of a move rather than a subtraction.</strong> Nothing here
 * asserts anything about what is emitted, because nothing about that changed - the access events are
 * still written, and they reached the audit feed first (T274 A1). Removing them from the only place
 * they were visible would have amounted to having quietly stopped recording them, which is the
 * ordering condition the ruling put on this card.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChildPageHidesRecordAccessTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
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
    private AppUserDetailsService appUserDetailsService;
    @Autowired
    private AuditEventPublisher auditEventPublisher;

    private String suffix;
    private String staffUsername;
    private Home home;
    private Child child;
    private InterviewRequest request;
    private User staff;

    @BeforeEach
    void seedAChildWithOneRequest() {
        suffix = "-" + System.nanoTime();
        Organisation careProvider = seededCareProvider();
        home = new Home();
        home.setName("A5 House" + suffix);
        home.setOrganisation(careProvider);
        home = homeRepository.save(home);

        staffUsername = "a5-staff" + suffix;
        staff = new User();
        staff.setUsername(staffUsername);
        staff.setLastName("Staff");
        staff.setRoles(new HashSet<>(Set.of(Role.HOME_STAFF)));
        staff.setHomes(new HashSet<>(Set.of(home)));
        staff.setEnabled(true);
        this.staff = userRepository.save(staff);

        child = new Child();
        child.setFirstName("Jordan");
        child.setLastName("A5" + suffix);
        child.setDateOfBirth(LocalDate.of(2013, 7, 22));
        child.setLocalCaseReference("CH-A5" + suffix);
        child.setHome(home);
        child = childRepository.save(child);

        request = InterviewRequestTestFixtures.requestAt(InterviewStatus.ALLOCATED);
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(staff);
        request.setReturnedAt(LocalDateTime.now().minusHours(10));
        request = interviewRequestRepository.save(request);
    }

    /**
     * The child page shows what happened TO the case and not who read it.
     *
     * <p>Both events are seeded against the same request, so a green run means the page kept one and
     * dropped the other. <strong>An empty history would be indistinguishable from a working filter</strong>,
     * which is why the lifecycle row is asserted present rather than only the access row absent.
     */
    @Test
    void theChildPageKeepsTheLifecycleRowAndDropsTheAccessRow() throws Exception {
        emitTheLifecycleEvent();
        emitTheAccessEvent();

        List<AuditHistoryEntry> rows = caseHistoryOnThePage();

        assertThat(rows).extracting(AuditHistoryEntry::headline).containsExactly("Interview requested");
    }

    /**
     * The access event really is there to be shown, so the row above is absent by CHOICE.
     *
     * <p>Without this, a page that showed one row would look identical whether the filter worked or
     * the second event had simply failed to save - the fixture proving itself rather than the code.
     */
    @Test
    void theAccessEventIsInTheDatabaseAllTheSame() {
        emitTheAccessEvent();

        assertThat(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc(
                        "InterviewRequest", request.getId()))
                .extracting(AuditEvent::getEventType)
                .containsExactly(AuditEventType.AUDIT_VIEW_OPENED);
    }

    /**
     * Emitted through the production publisher rather than assembled here, so the row under test is
     * the row the application writes - including T283's episode marker, which a hand-built event
     * would have quietly lacked.
     */
    private void emitTheAccessEvent() {
        auditEventPublisher.auditViewOpened("InterviewRequest", request.getId(),
                home.getOrganisation().getId(), home.getId(), new AppUserPrincipal(staff));
    }

    private void emitTheLifecycleEvent() {
        auditEventPublisher.interviewRequestCreated(request, new AppUserPrincipal(staff));
    }

    @SuppressWarnings("unchecked")
    private List<AuditHistoryEntry> caseHistoryOnThePage() throws Exception {
        Object attribute = mockMvc.perform(get("/children/{id}", child.getId()).with(asStaff()))
                .andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("caseHistory");
        // Read off the MODEL rather than the HTML: the ruling is about which events the page is
        // given, and a template edit should not be able to turn this guard green or red.
        List<AuditHistorySection> sections = (List<AuditHistorySection>) attribute;
        assertThat(sections).hasSize(1);
        return sections.get(0).entries();
    }

    private RequestPostProcessor asStaff() {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(staffUsername);
        return securityContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities())));
    }
}
