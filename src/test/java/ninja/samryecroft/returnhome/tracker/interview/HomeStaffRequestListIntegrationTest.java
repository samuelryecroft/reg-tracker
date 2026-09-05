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
 * T119 spec §7b/§7q (5a, home staff's requests). Covers R-Q12 (cards for cases, the table this
 * page rendered alongside its own card stack is gone) and D-5e-4/D-5e-5 (spec §7q): the empty
 * state needs two branches - R-Q13's own sentence is only followable when a child already exists
 * to select on the form it links to, so a home with no children at all gets a different state
 * instead of a rewritten one.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HomeStaffRequestListIntegrationTest extends AbstractIntegrationTest {

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

    private Home savedHome(String suffix) {
        Home home = new Home();
        home.setName("T5a House" + suffix);
        home.setOrganisation(seededCareProvider());
        return homeRepository.save(home);
    }

    private String savedHomeStaff(Home home, String suffix) {
        String username = "t5a-staff" + suffix;
        User staff = new User();
        staff.setUsername(username);
        staff.setLastName("Staff");
        staff.setRoles(new HashSet<>(Set.of(Role.HOME_STAFF)));
        staff.setHomes(new HashSet<>(Set.of(home)));
        staff.setEnabled(true);
        userRepository.save(staff);
        return username;
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }

    @Test
    void aHomeWithARequestRendersNoTableAtAllOnlyTheCaseCard() throws Exception {
        String suffix = "-" + System.nanoTime();
        Home home = savedHome(suffix);
        String username = savedHomeStaff(home, suffix);

        Child child = new Child();
        child.setFirstName("Sam");
        child.setLastName("T5a" + suffix);
        child.setDateOfBirth(LocalDate.of(2014, 3, 3));
        child.setLocalCaseReference("CH-T5A" + suffix);
        child.setHome(home);
        childRepository.save(child);

        InterviewRequest request = InterviewRequestTestFixtures.requestAt(InterviewStatus.REQUESTED);
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(userRepository.findByUsername(username).orElseThrow());
        request.setReturnedAt(LocalDateTime.now().minusHours(5));
        interviewRequestRepository.save(request);

        String html = mockMvc.perform(get("/requests").with(asUser(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("<table");
        assertThat(html).contains("CH-T5A" + suffix);
    }

    @Test
    void aHomeWithNoChildrenAtAllGetsTheChildlessEmptyStateNotTheGenericOne() throws Exception {
        String suffix = "-" + System.nanoTime();
        Home home = savedHome(suffix);
        String username = savedHomeStaff(home, suffix);

        String html = mockMvc.perform(get("/requests").with(asUser(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("No children are recorded for this home yet");
        assertThat(html).contains("Add a child before you can raise an interview request");
        assertThat(html).doesNotContain("If a child has returned from being missing");
    }

    @Test
    void aHomeWithChildrenButNoRequestsGetsRQ13sOwnSentence() throws Exception {
        String suffix = "-" + System.nanoTime();
        Home home = savedHome(suffix);
        String username = savedHomeStaff(home, suffix);

        Child child = new Child();
        child.setFirstName("Robin");
        child.setLastName("T5a" + suffix);
        child.setDateOfBirth(LocalDate.of(2015, 6, 1));
        child.setHome(home);
        childRepository.save(child);

        String html = mockMvc.perform(get("/requests").with(asUser(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("No open requests for this home. If a child has returned from "
                + "being missing, raise a request now.");
        assertThat(html).doesNotContain("No children are recorded for this home yet");
    }

    @Test
    void theRaiseFormExplainsAnEmptyChildDropdownRatherThanShowingOnlyAPlaceholder() throws Exception {
        String suffix = "-" + System.nanoTime();
        Home home = savedHome(suffix);
        String username = savedHomeStaff(home, suffix);

        String html = mockMvc.perform(get("/requests/new").with(asUser(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("No children are recorded for this home yet");
        assertThat(html).contains("Add a child before you can raise an interview request");
    }
}
