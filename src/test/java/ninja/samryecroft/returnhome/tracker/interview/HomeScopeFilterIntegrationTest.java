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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T303 (spec §5h.6, Creed): {@code coordinator/requests.html}'s home-scope filter, rebuilt as a
 * chip row in {@code audit/feed.html}'s own idiom rather than a banner - the banner stated the
 * filter stage in running text (a duplicate of the stage chip row's own aria-current) and
 * "Clear filters" was only load-bearing because the home dimension had no control of its own.
 *
 * <p>Also pins the id-leak fix folded into the same change: the deleted banner printed {@code 'One
 * home only (id ' + homeId + ')'} - a raw database id in user-facing copy, T265's defect in a
 * second costume. The chip must name the home.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HomeScopeFilterIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "CorrectHorse123!";

    @Autowired private MockMvc mockMvc;
    @Autowired private HomeRepository homeRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private InterviewRequestRepository interviewRequestRepository;
    @Autowired private AppUserDetailsService appUserDetailsService;
    @Autowired private PasswordEncoder passwordEncoder;

    private Organisation careProviderOrg;
    private Home oakwood;
    private Home marisco;
    private String suffix;
    private String coordinatorUsername;

    @BeforeEach
    void seedTwoHomes() {
        suffix = "-" + System.nanoTime();
        careProviderOrg = seededCareProvider();
        Organisation supplierOrg = seededSupplier();
        oakwood = saveHome("Oakwood House" + suffix);
        marisco = saveHome("Marisco Lodge" + suffix);
        String oakStaff = "t303-oak" + suffix;
        String marStaff = "t303-mar" + suffix;
        userRepository.save(newUser(oakStaff, Role.HOME_STAFF, oakwood, null));
        userRepository.save(newUser(marStaff, Role.HOME_STAFF, marisco, null));
        coordinatorUsername = "t303-coord" + suffix;
        userRepository.save(newUser(coordinatorUsername, Role.COORDINATOR, null, supplierOrg));

        saveRequest("T303-OAK", oakwood, oakStaff);
        saveRequest("T303-MAR", marisco, marStaff);
    }

    @Test
    void theOldBannerIsGoneEntirely() throws Exception {
        String html = queueHtml("?homeId=" + oakwood.getId());

        assertThat(html).doesNotContain("Showing a filtered view");
        assertThat(html).doesNotContain("banner info");
    }

    /**
     * T265 in a second costume: the deleted banner printed the raw id, never the name. Scoped to
     * the home-filter nav itself, not the page as a whole - the case card below independently
     * renders the home's name in its own meta line regardless of what the chip says, so an
     * unscoped "contains the name" assertion would pass even if the chip fell back to the id.
     */
    @Test
    void theHomeChipNamesTheHomeNeverTheRawId() throws Exception {
        String html = queueHtml("?homeId=" + oakwood.getId());
        String homeNav = extractBetween(html, "aria-label=\"Filter by home\"", "</nav>");

        assertThat(homeNav).contains("Oakwood House" + suffix);
        assertThat(homeNav).doesNotContain("id " + oakwood.getId()).doesNotContain("One home only");
        assertThat(html).doesNotContain("One home only");
    }

    @Test
    void bothHomesGetAChipAndAllHomesIsSelectedWithNoHomeIdParam() throws Exception {
        String html = queueHtml("");

        assertThat(html).contains("aria-label=\"Filter by home\"");
        assertThat(html).contains("Oakwood House" + suffix).contains("Marisco Lodge" + suffix);
        String homeNav = extractBetween(html, "aria-label=\"Filter by home\"", "</nav>");
        int allHomesIndex = homeNav.indexOf("All homes");
        int allHomesTagStart = homeNav.lastIndexOf("<a ", allHomesIndex);
        assertThat(homeNav.substring(allHomesTagStart, allHomesIndex))
                .as("no home selected: the All-homes chip carries aria-current")
                .contains("aria-current=\"true\"");
    }

    @Test
    void selectingAHomeMarksItsOwnChipCurrentAndNarrowsTheQueue() throws Exception {
        String html = queueHtml("?homeId=" + marisco.getId());

        assertThat(html).contains("T303-MAR");
        assertThat(html).doesNotContain("T303-OAK");
        String homeNav = extractBetween(html, "aria-label=\"Filter by home\"", "</nav>");
        int mariscoIndex = homeNav.indexOf("Marisco Lodge" + suffix);
        int mariscoTagStart = homeNav.lastIndexOf("<a ", mariscoIndex);
        assertThat(homeNav.substring(mariscoTagStart, mariscoIndex)).contains("aria-current=\"true\"");
    }

    /**
     * D-2a-6/T303: the two dimensions (stage, home) are independent - switching one must not drop
     * the other, matching the stage row's own already-established homeId preservation.
     */
    @Test
    void switchingHomeKeepsTheAppliedStageFilterAndViceVersa() throws Exception {
        String htmlWithStageOnly = queueHtml("?filter=unallocated");
        String homeNav = extractBetween(htmlWithStageOnly, "aria-label=\"Filter by home\"", "</nav>");
        assertThat(homeNav).as("the home chip's own link must carry the currently-applied stage filter")
                .contains("filter=unallocated");

        String htmlWithBoth = queueHtml("?homeId=" + oakwood.getId() + "&filter=unallocated");
        String stageNav = extractBetween(htmlWithBoth, "aria-label=\"Filter these requests\"", "</nav>");
        assertThat(stageNav).as("the stage chip's own link must carry the currently-applied home filter")
                .contains("homeId=" + oakwood.getId());
    }

    /** The empty branch (R-Q13) is untouched by this change - still the only place running text belongs. */
    @Test
    void theEmptyBranchStillOffersClearFilters() throws Exception {
        String html = queueHtml("?filter=awaitingReview");

        assertThat(html).contains("No interviews match these filters.");
        assertThat(html).contains("Clear filters");
    }

    private String extractBetween(String html, String startMarker, String endMarker) {
        int start = html.indexOf(startMarker);
        assertThat(start).as("marker not found: " + startMarker).isPositive();
        int end = html.indexOf(endMarker, start);
        return html.substring(start, end);
    }

    private String queueHtml(String query) throws Exception {
        return mockMvc.perform(get("/coordinator/requests" + query).with(asUser(coordinatorUsername)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails details = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        return securityContext(context);
    }

    private Home saveHome(String name) {
        Home h = new Home();
        h.setName(name);
        h.setOrganisation(careProviderOrg);
        return homeRepository.save(h);
    }

    private InterviewRequest saveRequest(String reference, Home home, String requestedByUsername) {
        Child child = new Child();
        child.setFirstName("T303");
        child.setLastName("Child" + suffix);
        child.setLocalCaseReference(reference);
        child.setDateOfBirth(LocalDate.of(2011, 3, 4));
        child.setHome(home);
        child = childRepository.save(child);

        InterviewRequest request = new InterviewRequest();
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(userRepository.findByUsername(requestedByUsername).orElseThrow());
        request.setStatus(InterviewStatus.REQUESTED);
        request.setReturnedAt(LocalDateTime.now().minusHours(10));
        return interviewRequestRepository.save(request);
    }

    private User newUser(String username, Role role, Home userHome, Organisation organisation) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setLastName(username);
        user.setRoles(Set.of(role));
        user.setOrganisation(organisation);
        user.setHomes(userHome == null ? new HashSet<>() : new HashSet<>(Set.of(userHome)));
        user.setEnabled(true);
        return user;
    }
}
