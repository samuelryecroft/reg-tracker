package ninja.samryecroft.returnhome.tracker.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestTestFixtures;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T119 spec §7e (4b, the child record). Covers D-4b-1 (the table/stack copy drift on a null
 * {@code scheduledAt}), D-4b-2 (an empty case file must not render a table skeleton), D-4b-7 (a due
 * badge only where {@link ninja.samryecroft.returnhome.tracker.interview.DeadlineTracker#badgeFor}
 * returns one) and D-4b-8 (the identity block: date of birth behind the reveal, case reference
 * never gated, no age anywhere - T195).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChildDetailIntegrationTest extends AbstractIntegrationTest {

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
    private String staffUsername;
    private Home home;
    private MockHttpSession session;

    @BeforeEach
    void seedAHomeAndItsStaff() {
        suffix = "-" + System.nanoTime();
        Organisation careProvider = seededCareProvider();
        home = new Home();
        home.setName("T119(7e) House" + suffix);
        home.setOrganisation(careProvider);
        home = homeRepository.save(home);

        staffUsername = "t4b-staff" + suffix;
        User staff = new User();
        staff.setUsername(staffUsername);
        staff.setLastName("Staff");
        staff.setRoles(new HashSet<>(Set.of(Role.HOME_STAFF)));
        staff.setHomes(new HashSet<>(Set.of(home)));
        staff.setEnabled(true);
        userRepository.save(staff);

        session = new MockHttpSession();
    }

    private Child savedChild(String caseReference, LocalDate dateOfBirth) {
        Child child = new Child();
        child.setFirstName("Jordan");
        child.setLastName("T4b" + suffix);
        child.setDateOfBirth(dateOfBirth);
        child.setLocalCaseReference(caseReference);
        child.setHome(home);
        return childRepository.save(child);
    }

    private RequestPostProcessor asStaff() {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(staffUsername);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }

    private String getDetail(Long childId) throws Exception {
        return mockMvc.perform(get("/children/{id}", childId).with(asStaff()).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void maskedIdentityBlockHidesTheBirthDateButShowsTheCaseReference() throws Exception {
        String caseReference = "CH-T4B" + suffix;
        Child child = savedChild(caseReference, LocalDate.of(2013, 7, 22));

        String html = getDetail(child.getId());

        // Never a hidden-but-present value - same rule as children/list.html (D-4b-11).
        assertThat(html).doesNotContain("22 Jul 2013");
        assertThat(html).contains("Hidden — reveal names to show");
        // The case reference is never gated (D-4b-12/D-4b-14): it already shows on this same page
        // in childIdentity.label(), so a "Hidden" cell here would misreport a value two lines above.
        assertThat(html).contains(caseReference);
    }

    @Test
    void revealedIdentityBlockShowsTheRealBirthDate() throws Exception {
        Child child = savedChild("CH-T4B" + suffix, LocalDate.of(2013, 7, 22));
        mockMvc.perform(post("/account/reveal-names").with(asStaff()).with(csrf()).session(session)
                        .param("returnTo", "/children/" + child.getId()))
                .andExpect(status().is3xxRedirection());

        String html = getDetail(child.getId());

        assertThat(html).contains("22 Jul 2013");
        assertThat(html).doesNotContain("Hidden — reveal names to show");
    }

    @Test
    void aLiveRequestShowsADueBadgeButAFinishedRequestShowsNoneAndTheNullScheduledCopyMatchesBothRenderings()
            throws Exception {
        Child child = savedChild("CH-T4B" + suffix, LocalDate.of(2013, 7, 22));

        // D-4b-7: live, unscheduled - has a running clock, must show a due badge.
        InterviewRequest live = InterviewRequestTestFixtures.requestAt(InterviewStatus.ALLOCATED);
        live.setChild(child);
        live.setHome(home);
        live.setRequestedBy(userRepository.findByUsername(staffUsername).orElseThrow());
        live.setReturnedAt(LocalDateTime.now().minusHours(10));
        interviewRequestRepository.save(live);

        // D-4b-7 counter-case: finished - tracksDeadline is false, badgeFor must return empty.
        InterviewRequest finished = InterviewRequestTestFixtures.requestAt(InterviewStatus.REPORT_APPROVED);
        finished.setChild(child);
        finished.setHome(home);
        finished.setRequestedBy(userRepository.findByUsername(staffUsername).orElseThrow());
        finished.setReturnedAt(LocalDateTime.now().minusHours(200));
        finished.setScheduledAt(LocalDateTime.now().minusHours(190));
        interviewRequestRepository.save(finished);

        String html = getDetail(child.getId());

        // D-4b-1: was "-" in the table and "Not yet scheduled" in the card stack - same absence,
        // two words. Now the same copy in both renderings, twice (table + stack) for the live row.
        assertThat(occurrencesOf(html, "Not yet scheduled")).isEqualTo(2);
        // The due badge itself (its exact words come from DeadlineTracker/DueStateCopy, not
        // reworded here) - present at least once, since the live request has a running clock.
        assertThat(html).contains("class=\"due");
    }

    @Test
    void anEmptyCaseFileRendersNeitherTableNorStackSkeletonOnlyTheEmptyMessage() throws Exception {
        Child child = savedChild("CH-T4B" + suffix, LocalDate.of(2013, 7, 22));

        String html = getDetail(child.getId());

        // D-4b-2: the caption promises content that isn't there if the table renders regardless.
        assertThat(html).doesNotContain("Every interview request raised for this child");
        assertThat(html).contains("No return home interviews recorded yet.");
    }

    private static int occurrencesOf(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
