package ninja.samryecroft.returnhome.tracker.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Roadmap 2.1: the due-state rule surfaced on the coordinator and home-staff request lists, and
 * the "add return time" no-clock remedy. {@link DeadlineTrackerTest} covers the rule itself in
 * isolation - this drives the real HTTP endpoints so the rendered HTML and the authorization
 * boundary around adding a missing return time are proven end to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeadlineTrackingIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "CorrectHorse123!";

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
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Organisation careProviderOrg;
    private Home home;
    private Home otherHome;
    private String suffix;

    private RequestPostProcessor asUser(String username) {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }

    @BeforeEach
    void seedData() {
        suffix = "-" + System.nanoTime();
        careProviderOrg = seededCareProvider();
        Organisation supplierOrg = seededSupplier();

        home = saveHome("Deadline House" + suffix);
        otherHome = saveHome("Other Deadline House" + suffix);

        userRepository.save(newUser("dl-home" + suffix, Role.HOME_STAFF, home, null));
        userRepository.save(newUser("dl-other-home" + suffix, Role.HOME_STAFF, otherHome, null));
        userRepository.save(newUser("dl-coordinator" + suffix, Role.COORDINATOR, null, supplierOrg));
    }

    private Home saveHome(String name) {
        Home h = new Home();
        h.setName(name);
        h.setOrganisation(careProviderOrg);
        return homeRepository.save(h);
    }

    private Child saveChild(String firstName, Home childHome) {
        Child child = new Child();
        child.setFirstName(firstName);
        child.setLastName("Deadline");
        // T138 1c: coordinator/requests.html (and home-staff/request-list.html) now mask child
        // names by default (spec §2.5) - the case reference is the part of a masked identity that
        // IS shown, so this fixture's own marker string still appears on those pages via it,
        // rather than the tests below needing to know about masking at all.
        child.setLocalCaseReference(firstName);
        child.setDateOfBirth(LocalDate.of(2011, 3, 4));
        child.setHome(childHome);
        return childRepository.save(child);
    }

    private User newUser(String username, Role role, Home userHome, Organisation organisation) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setLastName(username);
        user.setRoles(Set.of(role));
        user.setHomes(userHome == null ? new HashSet<>() : new HashSet<>(Set.of(userHome)));
        user.setOrganisation(organisation);
        user.setEnabled(true);
        return user;
    }

    /** Saved directly via the repository - the HTTP creation flow is exercised elsewhere; here we need precise clock fixtures. */
    private InterviewRequest saveRequest(String childName, Home requestHome, InterviewStatus status, LocalDateTime returnedAt) {
        Child child = saveChild(childName, requestHome);
        User requestedBy = userRepository.findByUsername("dl-home" + suffix).orElseThrow();
        InterviewRequest request = new InterviewRequest();
        request.setChild(child);
        request.setHome(requestHome);
        request.setRequestedBy(requestedBy);
        request.setStatus(status);
        request.setReturnedAt(returnedAt);
        return interviewRequestRepository.save(request);
    }

    @Test
    void aRequestCannotBeRaisedWithoutTheReturnTimeThatStartsTheClock() throws Exception {
        // T97 made returned_at NOT NULL. Enforced in the form as well as the schema so the person
        // raising the request gets a field error, not a constraint violation - and so the "no
        // clock" state, which the deadline tracker used to have to describe, cannot arise at all.
        Child child = saveChild("Clockless Cara" + suffix, home);

        String html = mockMvc.perform(post("/requests").with(asUser("dl-home" + suffix)).with(csrf())
                        .param("childId", child.getId().toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Deliberately not `contains("returnedAt")` alone: the id is on the <input> regardless, so
        // that assertion passes against a page whose validation summary names nothing at all -
        // which is exactly what this page used to do. The summary listed only childId while the
        // form also has @NotNull on returnedAt, so THIS submission rendered "Before this request
        // can be raised" above an EMPTY list: 200, banner present, nothing said, and the missing
        // field was the one the statutory 72-hour clock is measured from. The summary now derives
        // from the binding result (fragments/layout :: errorSummary), so it cannot miss a field.
        assertThat(html)
                .as("the validation summary must NAME the field at fault, not merely appear")
                .contains("href=\"#returnedAt\"");
        assertThat(html).contains("returnedAt");
        assertThat(interviewRequestRepository.findAllDetailed()).isEmpty();

        // ...and it succeeds the moment the clock has a start.
        mockMvc.perform(post("/requests").with(asUser("dl-home" + suffix)).with(csrf())
                        .param("childId", child.getId().toString())
                        .param("returnedAt", "2026-07-16T20:30"))
                .andExpect(status().is3xxRedirection());

        assertThat(interviewRequestRepository.findAllDetailed()).hasSize(1);
    }

    @Test
    void homeStaffListGroupsRequestsByUrgency() throws Exception {
        // The no-clock group that used to sit between these two is gone with T97: returned_at is
        // NOT NULL, so a request without a deadline can no longer be created, and the "Add return
        // time" remedy it existed to offer went with it.
        LocalDateTime now = LocalDateTime.now();
        saveRequest("Overdue Ollie" + suffix, home, InterviewStatus.REQUESTED, now.minusHours(80));
        saveRequest("OnTrack Otis" + suffix, home, InterviewStatus.ALLOCATED, now.minusHours(1));

        String html = mockMvc.perform(get("/requests").with(asUser("dl-home" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Overdue").contains("On track");
        assertThat(html).doesNotContain("Add return time");

        // Never silently sorted to the bottom: the overdue group still heads the page.
        assertThat(html.indexOf("Overdue")).isLessThan(html.indexOf("On track"));
    }

    /**
     * T165: the end of the round trip. {@link DeadlineTrackerTest} proves the badge TEXT carries the
     * state as a word; this proves the glyph that used to carry it is now real, aria-hidden markup
     * on the rendered page - a sprite {@code <use>} that resolves, not a character sitting inside
     * announced text. Both halves are needed: text alone would let the icon quietly vanish, and
     * markup alone would let the announced text go back to two identical sentences.
     */
    @Test
    void deadlineStateReachesThePageAsAWordPlusAnAriaHiddenIconNotAsACharacter() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        saveRequest("Soon Sasha" + suffix, home, InterviewStatus.SCHEDULED, now.minusHours(60)); // 12h left
        saveRequest("Ontrack Odis" + suffix, home, InterviewStatus.REQUESTED, now.minusHours(1)); // 71h left

        String html = mockMvc.perform(get("/coordinator/requests").with(asUser("dl-coordinator" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The state, as a word, in the text a screen reader reads.
        assertThat(html).contains(DueStateCopy.stateWord(DueState.DUE_SOON));
        assertThat(html).contains(DueStateCopy.stateWord(DueState.ON_TRACK));

        // The glyphs that used to be that word are gone from the page entirely - badges AND the
        // group headings, both of which are announced.
        assertThat(html).doesNotContain("\u25b2").doesNotContain("\u25f7").doesNotContain("\u2713");

        // ...and the icon really is there, hidden, and pointing at a symbol that exists in the
        // vendored sprite (a typo'd id renders a silently blank <use>, which no other test sees).
        assertThat(html).contains("#ph-clock-countdown");
        assertThat(html).contains("#ph-check-circle");
        assertThat(html).contains("<svg class=\"icon\" aria-hidden=\"true\">");

        String sprite = Files.readString(Path.of("src/main/resources/static/icons/phosphor.svg"),
                StandardCharsets.UTF_8);
        assertThat(sprite).contains("id=\"ph-clock-countdown\"").contains("id=\"ph-check-circle\"")
                .contains("id=\"ph-warning-circle\"");
    }

    @Test
    void coordinatorListSortsOverdueBeforeDueSoonBeforeOnTrack() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        saveRequest("Soon Sam" + suffix, home, InterviewStatus.SCHEDULED, now.minusHours(60)); // 12h left
        saveRequest("Overdue Owen" + suffix, otherHome, InterviewStatus.ALLOCATED, now.minusHours(90)); // 18h overdue
        saveRequest("Ontrack Olu" + suffix, home, InterviewStatus.REQUESTED, now.minusHours(1)); // 71h left

        String html = mockMvc.perform(get("/coordinator/requests").with(asUser("dl-coordinator" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int overdueIdx = html.indexOf("Overdue Owen" + suffix);
        int dueSoonIdx = html.indexOf("Soon Sam" + suffix);
        int onTrackIdx = html.indexOf("Ontrack Olu" + suffix);

        assertThat(overdueIdx).isPositive();
        assertThat(dueSoonIdx).isPositive();
        assertThat(onTrackIdx).isPositive();
        assertThat(overdueIdx).isLessThan(dueSoonIdx);
        assertThat(dueSoonIdx).isLessThan(onTrackIdx);
    }
}
