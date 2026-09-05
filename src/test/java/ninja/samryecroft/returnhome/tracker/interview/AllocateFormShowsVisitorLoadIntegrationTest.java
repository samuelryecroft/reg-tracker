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
 * D-4a-2 (T173/spec §7b): "a coordinator allocating blind cannot load-balance, and an overloaded
 * visitor is how a 72-hour deadline gets missed" - the visitor list must show each one's current
 * load, sorted least-loaded first, not a bare name list.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AllocateFormShowsVisitorLoadIntegrationTest extends AbstractIntegrationTest {

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
    private Long requestId;

    @BeforeEach
    void seedTwoVisitorsWithDifferentLoads() {
        suffix = "-" + System.nanoTime();
        Organisation supplier = seededSupplier();
        Organisation careProvider = seededCareProvider();

        Home home = new Home();
        home.setName("T173(4a) House" + suffix);
        home.setOrganisation(careProvider);
        home = homeRepository.save(home);

        saveUser("t4a-coordinator" + suffix, Set.of(Role.COORDINATOR), supplier, null);
        User busyVisitor = saveUser("t4a-busy-visitor" + suffix, Set.of(Role.VISITOR), supplier, null);
        User freeVisitor = saveUser("t4a-free-visitor" + suffix, Set.of(Role.VISITOR), supplier, null);

        // The busy visitor carries two OPEN allocations (ALLOCATED, SCHEDULED) plus one CLOSED one
        // (REPORT_APPROVED) - the count must include the first two and exclude the third, or the
        // whole point of the feature (load-balancing against real open work) is wrong.
        seedAllocation(home, busyVisitor, InterviewStatus.ALLOCATED, "Busy1");
        seedAllocation(home, busyVisitor, InterviewStatus.SCHEDULED, "Busy2");
        seedAllocation(home, busyVisitor, InterviewStatus.REPORT_APPROVED, "BusyClosed");
        // The free visitor has zero.

        // The actual request under test - a fresh, unallocated one to reach the allocate form for.
        Child child = new Child();
        child.setFirstName("Sasha");
        child.setLastName("T4a" + suffix);
        child.setDateOfBirth(LocalDate.of(2011, 6, 1));
        child.setHome(home);
        Child savedChild = childRepository.save(child);

        InterviewRequest request = InterviewRequestTestFixtures.requestAt(InterviewStatus.REQUESTED);
        request.setChild(savedChild);
        request.setHome(home);
        request.setRequestedBy(userRepository.findByUsername("t4a-coordinator" + suffix).orElseThrow());
        request.setReturnedAt(LocalDateTime.of(2026, 8, 1, 9, 0));
        requestId = interviewRequestRepository.save(request).getId();
    }

    private void seedAllocation(Home home, User visitor, InterviewStatus status, String childFirstName) {
        Child child = new Child();
        child.setFirstName(childFirstName);
        child.setLastName("Load" + suffix);
        child.setDateOfBirth(LocalDate.of(2011, 1, 1));
        child.setHome(home);
        Child savedChild = childRepository.save(child);

        InterviewRequest request = InterviewRequestTestFixtures.requestAt(status);
        request.setChild(savedChild);
        request.setHome(home);
        request.setRequestedBy(userRepository.findByUsername("t4a-coordinator" + suffix).orElseThrow());
        request.setAllocatedVisitor(visitor);
        request.setReturnedAt(LocalDateTime.of(2026, 7, 1, 9, 0));
        interviewRequestRepository.save(request);
    }

    @Test
    void theFreeVisitorRendersFirstWithLoadFiguresAndClosedWorkIsNotCounted() throws Exception {
        String html = mockMvc.perform(get("/coordinator/requests/{id}/allocate", requestId)
                        .with(asUser("t4a-coordinator" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // D-4a-4: zero reads as an answer ("No open allocations"), never a measurement ("0 ...").
        assertThat(html).contains("No open allocations");
        assertThat(html).contains("2 open allocations");
        // Sorted least-loaded first: the free visitor's row must appear before the busy one's.
        int freeIndex = html.indexOf("t4a-free-visitor");
        int busyIndex = html.indexOf("t4a-busy-visitor");
        assertThat(freeIndex).isGreaterThan(-1);
        assertThat(busyIndex).isGreaterThan(-1);
        assertThat(freeIndex).as("the least-loaded visitor must render first").isLessThan(busyIndex);

        // D-4a-3: the button no longer claims an effect ("& schedule") that doesn't happen when
        // the time is left blank, and the actual consequence is named instead of the mechanism.
        assertThat(html).doesNotContain("Allocate &amp; schedule");
        assertThat(html).contains(">Allocate<");
        assertThat(html).contains("Allocated");
        assertThat(html).contains("Scheduled");

        // The radio list, not a dropdown - a dropdown option can't carry the load figure. The
        // actual tag boundary, not a bare substring: a comment explaining the change is free to
        // mention the word without tripping this the way an actual rendered element would.
        assertThat(html).doesNotContain("<select ").doesNotContain("<select>");
        assertThat(html).contains("class=\"radio-list\"");
    }

    private User saveUser(String username, Set<Role> roles, Organisation organisation, Home home) {
        User user = new User();
        user.setUsername(username);
        user.setLastName(username);
        user.setRoles(new HashSet<>(roles));
        user.setOrganisation(organisation);
        user.setHomes(home == null ? new HashSet<>() : new HashSet<>(Set.of(home)));
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
