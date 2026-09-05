package ninja.samryecroft.returnhome.tracker.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService;
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
 * D-4a-4 (T119 spec §7c): a bare open-allocation count is blunt on a 72-hour clock, so the load
 * figure also names the worst due-state tier among a visitor's open work - "N overdue" or "N due
 * within {@link DeadlineTracker#DUE_SOON_THRESHOLD} hours" - with its own count, never a
 * three-way breakdown, and nothing when there's nothing urgent. Zero reads "No open allocations".
 */
@SpringBootTest
@AutoConfigureMockMvc
class AllocateFormShowsDeadlineAwareLoadIntegrationTest extends AbstractIntegrationTest {

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
    private AppUserDetailsService appUserDetailsService;

    private String suffix;
    private String coordinatorUsername;
    private Home home;
    private LocalDateTime now;

    @BeforeEach
    void seedHomeAndCoordinator() {
        suffix = "-" + System.nanoTime();
        now = LocalDateTime.now();
        Organisation supplier = seededSupplier();
        Organisation careProvider = seededCareProvider();

        home = new Home();
        home.setName("T119(7c) House" + suffix);
        home.setOrganisation(careProvider);
        home = homeRepository.save(home);

        coordinatorUsername = "t4a4-coordinator" + suffix;
        saveUser(coordinatorUsername, Set.of(Role.COORDINATOR), supplier, null);
    }

    @Test
    void theWorstTierIsNamedWithItsOwnCountNotABreakdown() throws Exception {
        User overdueVisitor = saveUser("t4a4-overdue-visitor" + suffix, Set.of(Role.VISITOR), null, null);
        // One overdue, one due-soon: the worst tier (overdue) is named with ITS OWN count (1),
        // not the total open count and not a breakdown of both tiers.
        seedAllocation(overdueVisitor, InterviewStatus.ALLOCATED, now.minusHours(80));
        seedAllocation(overdueVisitor, InterviewStatus.SCHEDULED, now.minusHours(60));

        User dueSoonVisitor = saveUser("t4a4-duesoon-visitor" + suffix, Set.of(Role.VISITOR), null, null);
        seedAllocation(dueSoonVisitor, InterviewStatus.SCHEDULED, now.minusHours(55));
        seedAllocation(dueSoonVisitor, InterviewStatus.SCHEDULED, now.minusHours(50));
        seedAllocation(dueSoonVisitor, InterviewStatus.ALLOCATED, now.minusHours(5));

        // A distinct count (4) from the other visitors below, so its "no tier" text can be checked
        // for the absence of a " · " suffix without colliding with another visitor's own count.
        User onTrackVisitor = saveUser("t4a4-ontrack-visitor" + suffix, Set.of(Role.VISITOR), null, null);
        seedAllocation(onTrackVisitor, InterviewStatus.ALLOCATED, now.minusHours(1));
        seedAllocation(onTrackVisitor, InterviewStatus.ALLOCATED, now.minusHours(2));
        seedAllocation(onTrackVisitor, InterviewStatus.ALLOCATED, now.minusHours(3));
        seedAllocation(onTrackVisitor, InterviewStatus.ALLOCATED, now.minusHours(4));

        saveUser("t4a4-free-visitor" + suffix, Set.of(Role.VISITOR), null, null);

        String html = renderAllocateForm();

        assertThat(html).contains("2 open allocations · 1 overdue");
        // Reuses DeadlineTracker.DUE_SOON_THRESHOLD rather than a hardcoded "24".
        assertThat(html).contains("3 open allocations · 2 due within " + DeadlineTracker.DUE_SOON_THRESHOLD.toHours() + " hours");
        // On-track: nothing urgent to name, so no " · " suffix at all.
        assertThat(html).contains("4 open allocations");
        assertThat(html).doesNotContain("4 open allocations ·");
        // Zero reads like an answer, never a measurement.
        assertThat(html).contains("No open allocations");
    }

    // NO_CLOCK has no end-to-end test here: `returned_at` has been NOT NULL since V15 (the schema
    // itself enforces it), so a freshly-seeded row can never reach that state - it can only exist
    // as historical pre-V15 data, per §7c's own note. DeadlineTrackerTest already covers the
    // classification; visitorOption's overdueCount/dueSoonCount only ever count OVERDUE/DUE_SOON
    // explicitly, so a NO_CLOCK (or REPORT_REJECTED, covered below) row is excluded by construction
    // rather than by a special case that a schema change could silently make untested.

    @Test
    void aReportSentBackForRewriteCountsButHasAlreadyPassedTheClockItDoesNotContribute() throws Exception {
        User visitor = saveUser("t4a4-rejected-visitor" + suffix, Set.of(Role.VISITOR), null, null);
        // REPORT_REJECTED: the interview already happened, so it no longer tracks the pre-interview
        // clock (DeadlineTracker.tracksDeadline is false for it) even though this returnedAt is
        // long past - it must not be able to read as "overdue" here.
        seedAllocation(visitor, InterviewStatus.REPORT_REJECTED, now.minusHours(200));
        seedAllocation(visitor, InterviewStatus.ALLOCATED, now.minusHours(60)); // due soon

        String html = renderAllocateForm();

        assertThat(html).contains("2 open allocations · 1 due within " + DeadlineTracker.DUE_SOON_THRESHOLD.toHours() + " hours");
    }

    @Test
    void atEqualCountsTheLeastUrgentVisitorSortsFirst() throws Exception {
        User overdueVisitor = saveUser("t4a4-tiebreak-overdue" + suffix, Set.of(Role.VISITOR), null, null);
        seedAllocation(overdueVisitor, InterviewStatus.ALLOCATED, now.minusHours(80));

        User onTrackVisitor = saveUser("t4a4-tiebreak-ontrack" + suffix, Set.of(Role.VISITOR), null, null);
        seedAllocation(onTrackVisitor, InterviewStatus.ALLOCATED, now.minusHours(1));

        String html = renderAllocateForm();

        int onTrackIndex = html.indexOf("t4a4-tiebreak-ontrack");
        int overdueIndex = html.indexOf("t4a4-tiebreak-overdue");
        assertThat(onTrackIndex).isGreaterThan(-1);
        assertThat(overdueIndex).isGreaterThan(-1);
        assertThat(onTrackIndex).as("equal counts: the least urgent visitor renders first").isLessThan(overdueIndex);
    }

    private String renderAllocateForm() throws Exception {
        Child child = new Child();
        child.setFirstName("Riley");
        child.setLastName("T4a4" + suffix);
        child.setDateOfBirth(LocalDate.of(2012, 3, 4));
        child.setHome(home);
        Child savedChild = childRepository.save(child);

        InterviewRequest request = InterviewRequestTestFixtures.requestAt(InterviewStatus.REQUESTED);
        request.setChild(savedChild);
        request.setHome(home);
        request.setRequestedBy(userRepository.findByUsername(coordinatorUsername).orElseThrow());
        request.setReturnedAt(now.minusDays(1));
        Long requestId = interviewRequestRepository.save(request).getId();

        return mockMvc.perform(get("/coordinator/requests/{id}/allocate", requestId)
                        .with(asUser(coordinatorUsername)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void seedAllocation(User visitor, InterviewStatus status, LocalDateTime returnedAt) {
        Child child = new Child();
        child.setFirstName("Load");
        child.setLastName(visitor.getUsername());
        child.setDateOfBirth(LocalDate.of(2011, 1, 1));
        child.setHome(home);
        Child savedChild = childRepository.save(child);

        InterviewRequest request = InterviewRequestTestFixtures.requestAt(status);
        request.setChild(savedChild);
        request.setHome(home);
        request.setRequestedBy(userRepository.findByUsername(coordinatorUsername).orElseThrow());
        request.setAllocatedVisitor(visitor);
        request.setReturnedAt(returnedAt);
        interviewRequestRepository.save(request);
    }

    private User saveUser(String username, Set<Role> roles, Organisation organisation, Home userHome) {
        User user = new User();
        user.setUsername(username);
        user.setLastName(username);
        user.setRoles(new HashSet<>(roles));
        user.setOrganisation(organisation == null ? seededSupplier() : organisation);
        user.setHomes(userHome == null ? new HashSet<>() : new HashSet<>(Set.of(userHome)));
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails details = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        return securityContext(context);
    }
}
