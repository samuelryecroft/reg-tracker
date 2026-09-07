package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
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
 * T286 (spec §5h.4, Creed 4/6 Sep): a sweep of six {@code .banner.warn} uses found three describing
 * non-problems and one at the wrong tier - "warn is for something that has gone wrong, or is about
 * to. A state the system reached correctly, or that the user chose, is not a warning, however much
 * you want it noticed." This pins the four reclassifications and, as a regression guard, the two
 * that stay {@code warn} because they ARE legitimate.
 *
 * <p>Icon travels with classification (§5h.4: "both move, or neither does") - each reclassified
 * banner is checked for its new icon and the absence of {@code ph-warning-circle}, not just its new
 * class name, so a fix that recoloured the banner but left the problem-glyph behind would still
 * fail here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BannerClassificationIntegrationTest extends AbstractIntegrationTest {

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

    @BeforeEach
    void freshSuffix() {
        suffix = "-" + System.nanoTime();
    }

    /**
     * D-5e-5: an empty children list on the request-raising form is a state the system reached
     * correctly (nobody has added a child yet), with a next action offered - not a warning.
     */
    @Test
    void theEmptyChildrenBannerOnTheRequestFormIsInfoNotWarn() throws Exception {
        Organisation careProvider = seededCareProvider();
        Home home = new Home();
        home.setName("T286 Empty House" + suffix);
        home.setOrganisation(careProvider);
        home = homeRepository.save(home);
        saveUser("t286-staff" + suffix, Set.of(Role.HOME_STAFF), null, home);

        String html = mockMvc.perform(get("/requests/new").with(asUser("t286-staff" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("No children are recorded for this home yet");
        int bannerStart = html.indexOf("No children are recorded for this home yet");
        int bannerDivStart = html.lastIndexOf("<div class=\"banner", bannerStart);
        String bannerTag = html.substring(bannerDivStart, html.indexOf('>', bannerDivStart) + 1);
        assertThat(bannerTag).contains("banner info").doesNotContain("banner warn");
        int bannerEnd = html.indexOf("</div>", html.indexOf("</div>", bannerStart) + 1);
        String bannerBlock = html.substring(bannerDivStart, bannerEnd);
        assertThat(bannerBlock).doesNotContain("ph-warning-circle");
    }

    /**
     * D-1b-7/T286: separation of duties refusing a self-reviewer is the rule working, not a
     * warning - and shares its icon (ph-lock-simple) with the page's other permission-fact banner
     * (D-1b-9, "can't be edited here") rather than a bespoke one.
     */
    @Test
    void theSelfReviewBannerIsInfoWithTheSharedLockIconNotWarn() throws Exception {
        Organisation supplier = seededSupplier();
        Home home = new Home();
        home.setName("T286 Review House" + suffix);
        home.setOrganisation(seededCareProvider());
        home = homeRepository.save(home);

        Child child = new Child();
        child.setFirstName("Robin");
        child.setLastName("T286" + suffix);
        child.setDateOfBirth(LocalDate.of(2012, 3, 4));
        child.setHome(home);
        Long childId = childRepository.save(child).getId();

        saveUser("t286-staff" + suffix, Set.of(Role.HOME_STAFF), null, home);
        saveUser("t286-coordinator" + suffix, Set.of(Role.COORDINATOR), supplier, null);
        saveUser("t286-visitor-reviewer" + suffix, Set.of(Role.VISITOR, Role.REVIEWER), supplier, null);

        mockMvc.perform(post("/requests").with(asUser("t286-staff" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-18T19:00"))
                .andExpect(status().is3xxRedirection());
        Long requestId = interviewRequestRepository.findAllDetailed().stream()
                .filter(r -> r.getChild().getId().equals(childId))
                .findFirst().orElseThrow().getId();

        Long visitorId = userRepository.findByUsername("t286-visitor-reviewer" + suffix).orElseThrow().getId();
        mockMvc.perform(post("/coordinator/requests/{id}/allocate", requestId)
                        .with(asUser("t286-coordinator" + suffix)).with(csrf())
                        .param("visitorId", visitorId.toString())
                        .param("scheduledAt", "2026-07-22T11:00"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("t286-visitor-reviewer" + suffix)).with(csrf())
                        .param("action", "submit")
                        .param("heldAt", "2026-07-22T11:00")
                        .param("interviewLocation", "The home's quiet room")
                        .param("previouslyMissing", "false")
                        .param("confidentialityExplained", "true")
                        .param("interviewAccepted", "true")
                        .param("consideredSelfMissing", "false")
                        .param("whereWereYouWhileMissing", "At a friend's house")
                        .param("interviewerComments", "Settled on return")
                        .param("recommendations", "No further action")
                        .param("conductedByStatement", "Conducted by the allocated visitor"))
                .andExpect(status().is3xxRedirection());

        String html = mockMvc.perform(get("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("t286-visitor-reviewer" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("You can't decide this report");
        int bannerStart = html.indexOf("You can't decide this report");
        int bannerDivStart = html.lastIndexOf("<div class=\"banner", bannerStart);
        String bannerTag = html.substring(bannerDivStart, html.indexOf('>', bannerDivStart) + 1);
        assertThat(bannerTag).contains("banner info").doesNotContain("banner warn");
        int bannerEnd = html.indexOf("</div>", html.indexOf("</div>", bannerStart) + 1);
        String bannerBlock = html.substring(bannerDivStart, bannerEnd);
        assertThat(bannerBlock).contains("#ph-lock-simple").doesNotContain("ph-warning-circle");
    }

    /**
     * D-1a-2a/T156/T286: being sent back is the process working. Reclassified from warn to the
     * dedicated sent-back variant that has existed, unused, since D-1a-2a's tokens were locked.
     */
    @Test
    void theSentBackBannerUsesTheSentBackVariantNotWarn() throws Exception {
        Organisation supplier = seededSupplier();
        Home home = new Home();
        home.setName("T286 SentBack House" + suffix);
        home.setOrganisation(seededCareProvider());
        home = homeRepository.save(home);

        Child child = new Child();
        child.setFirstName("Sam");
        child.setLastName("T286b" + suffix);
        child.setDateOfBirth(LocalDate.of(2010, 1, 15));
        child.setHome(home);
        Long childId = childRepository.save(child).getId();

        saveUser("t286b-staff" + suffix, Set.of(Role.HOME_STAFF), null, home);
        saveUser("t286b-coordinator" + suffix, Set.of(Role.COORDINATOR), supplier, null);
        saveUser("t286b-visitor" + suffix, Set.of(Role.VISITOR), supplier, null);
        saveUser("t286b-reviewer" + suffix, Set.of(Role.REVIEWER), supplier, null);

        mockMvc.perform(post("/requests").with(asUser("t286b-staff" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-18T19:00"))
                .andExpect(status().is3xxRedirection());
        Long requestId = interviewRequestRepository.findAllDetailed().stream()
                .filter(r -> r.getChild().getId().equals(childId))
                .findFirst().orElseThrow().getId();

        Long visitorId = userRepository.findByUsername("t286b-visitor" + suffix).orElseThrow().getId();
        mockMvc.perform(post("/coordinator/requests/{id}/allocate", requestId)
                        .with(asUser("t286b-coordinator" + suffix)).with(csrf())
                        .param("visitorId", visitorId.toString())
                        .param("scheduledAt", "2026-07-22T11:00"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("t286b-visitor" + suffix)).with(csrf())
                        .param("action", "submit")
                        .param("heldAt", "2026-07-22T11:00")
                        .param("interviewLocation", "The home's quiet room")
                        .param("previouslyMissing", "false")
                        .param("confidentialityExplained", "true")
                        .param("interviewAccepted", "true")
                        .param("consideredSelfMissing", "false")
                        .param("whereWereYouWhileMissing", "At a friend's house")
                        .param("interviewerComments", "Settled on return")
                        .param("recommendations", "No further action")
                        .param("conductedByStatement", "Conducted by the allocated visitor"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("t286b-reviewer" + suffix)).with(csrf())
                        .param("action", "reject")
                        .param("reviewComments", "Needs more detail in section 3"))
                .andExpect(status().is3xxRedirection());

        String html = mockMvc.perform(get("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("t286b-visitor" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Sent back for revision");
        assertThat(html).contains("Needs more detail in section 3");
        int bannerStart = html.indexOf("Sent back for revision");
        int bannerDivStart = html.lastIndexOf("<div class=\"banner", bannerStart);
        String bannerTag = html.substring(bannerDivStart, html.indexOf('>', bannerDivStart) + 1);
        assertThat(bannerTag).contains("banner sent-back").doesNotContain("banner warn");
        int bannerEnd = html.indexOf("</div>", html.indexOf("</div>", bannerStart) + 1);
        String bannerBlock = html.substring(bannerDivStart, bannerEnd);
        assertThat(bannerBlock).contains("#ph-arrow-u-up-left").doesNotContain("ph-warning-circle");
        // Not an interruption - a fact about a completed, correct process step (§5h.4).
        assertThat(bannerTag).doesNotContain("role=\"alert\"");
    }

    /** D-1b-etc./T286: a failed activation is a failed ACTION - wrong tier, not wrong colour. */
    @Test
    void theActivationFailedBannerIsErrNotWarn() throws Exception {
        saveUser("t286d-admin" + suffix, Set.of(Role.ADMIN), null, null);

        String html = mockMvc.perform(get("/admin/organisations")
                        .with(asUser("t286d-admin" + suffix))
                        .flashAttr("activationError", "The key does not exist yet."))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Encryption key not provisioned");
        int bannerStart = html.indexOf("Encryption key not provisioned");
        int bannerDivStart = html.lastIndexOf("<div class=\"banner", bannerStart);
        String bannerTag = html.substring(bannerDivStart, html.indexOf('>', bannerDivStart) + 1);
        assertThat(bannerTag).contains("banner err").doesNotContain("banner warn").contains("role=\"alert\"");
        int bannerEnd = html.indexOf("</div>", html.indexOf("</div>", bannerStart) + 1);
        String bannerBlock = html.substring(bannerDivStart, bannerEnd);
        assertThat(bannerBlock).doesNotContain("ph-warning-circle");
    }

    /**
     * Regression guard for the two banners the sweep confirmed ARE legitimate warnings: an
     * unconfirmed encryption key and a partial export scope are both things that are, or might be,
     * wrong - not states the system reached correctly. A fix that over-corrected the sweep and
     * touched these too would fail here.
     */
    @Test
    void theTwoLegitimateWarnBannersAreUntouched() throws Exception {
        Organisation careProvider = seededCareProvider();
        saveUser("t286c-admin" + suffix, Set.of(Role.ADMIN), null, null);

        String html = mockMvc.perform(get("/admin/organisations")
                        .with(asUser("t286c-admin" + suffix))
                        .flashAttr("kekWarning", "The care provider's encryption key could not be confirmed."))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Encryption key could not be confirmed");
        int bannerStart = html.indexOf("Encryption key could not be confirmed");
        int bannerDivStart = html.lastIndexOf("<div class=\"banner", bannerStart);
        String bannerTag = html.substring(bannerDivStart, html.indexOf('>', bannerDivStart) + 1);
        assertThat(bannerTag).contains("banner warn");
    }

    private void saveUser(String username, Set<Role> roles, Organisation organisation, Home home) {
        User user = new User();
        user.setUsername(username);
        user.setLastName(username);
        user.setRoles(new HashSet<>(roles));
        user.setOrganisation(organisation);
        user.setHomes(home == null ? new HashSet<>() : new HashSet<>(Set.of(home)));
        user.setEnabled(true);
        userRepository.save(user);
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails details = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        return securityContext(context);
    }
}
