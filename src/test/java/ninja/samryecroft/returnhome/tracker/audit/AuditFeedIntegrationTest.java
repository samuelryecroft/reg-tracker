package ninja.samryecroft.returnhome.tracker.audit;

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
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Roadmap 2.5: the org-wide case-activity feed + its CSV export. Never sign-in activity - proven
 * here by actually logging in during the test and asserting the login never appears.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditFeedIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "CorrectHorse123!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrganisationRepository organisationRepository;
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
    private PasswordEncoder passwordEncoder;

    private String suffix;
    private Organisation careProviderOrg;
    private Organisation supplierOrg;
    private Home home;
    private Long childId;

    private RequestPostProcessor asUser(String username) {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }

    @BeforeEach
    void seedData() {
        suffix = "-" + System.nanoTime();
        supplierOrg = saveOrg("Feed Supplier" + suffix, OrgType.SUPPLIER, null);
        careProviderOrg = saveOrg("Feed Provider" + suffix, OrgType.CARE_PROVIDER, supplierOrg);
        home = saveHome("Feed House" + suffix, careProviderOrg);

        userRepository.save(newUser("feed-home" + suffix, Role.HOME_STAFF, home, null));
        userRepository.save(newUser("feed-orgadmin" + suffix, Role.ORG_ADMIN, null, careProviderOrg));

        Child child = new Child();
        child.setFirstName("Jordan");
        child.setLastName("Feed" + suffix);
        child.setDateOfBirth(LocalDate.of(2012, 2, 2));
        child.setHome(home);
        childId = childRepository.save(child).getId();
    }

    private Organisation saveOrg(String name, OrgType type, Organisation supplier) {
        Organisation org = new Organisation();
        org.setName(name);
        org.setType(type);
        org.setSupplierOrganisation(supplier);
        return organisationRepository.save(org);
    }

    private Home saveHome(String name, Organisation org) {
        Home h = new Home();
        h.setName(name);
        h.setOrganisation(org);
        return homeRepository.save(h);
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
        // can_export defaults false (V12 migration) - HOME_STAFF is excluded by ExportCapability's
        // role ceiling regardless, so setting this true for every fixture here is a test-convenience,
        // not a security-relevant choice for this file (the role ceiling itself is Jim's
        // ExportCapabilityTest's job to prove).
        user.setCanExport(true);
        return user;
    }

    @Test
    void feedShowsCaseActivityButNeverSignInEvents() throws Exception {
        String homeUsername = "feed-home" + suffix;
        mockMvc.perform(post("/login").with(csrf()).param("username", homeUsername).param("password", PASSWORD))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/requests").with(asUser(homeUsername)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-16T20:30"))
                .andExpect(status().is3xxRedirection());

        String html = mockMvc.perform(get("/audit").with(asUser("feed-orgadmin" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Interview requested");
        assertThat(html).doesNotContain("LOGIN_SUCCESS").doesNotContain("Signed in");
    }

    @Test
    void homeFilterNarrowsTheFeedToOneHome() throws Exception {
        mockMvc.perform(post("/requests").with(asUser("feed-home" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-16T20:30"))
                .andExpect(status().is3xxRedirection());

        Home otherHome = saveHome("Other Feed House" + suffix, careProviderOrg);
        userRepository.save(newUser("feed-other-home" + suffix, Role.HOME_STAFF, otherHome, null));
        Child otherChild = new Child();
        otherChild.setFirstName("Alex");
        otherChild.setLastName("Other" + suffix);
        otherChild.setDateOfBirth(LocalDate.of(2013, 3, 3));
        otherChild.setHome(otherHome);
        Long otherChildId = childRepository.save(otherChild).getId();
        mockMvc.perform(post("/requests").with(asUser("feed-other-home" + suffix)).with(csrf())
                        .param("childId", otherChildId.toString())
                        .param("returnedAt", "2026-07-16T20:30"))
                .andExpect(status().is3xxRedirection());

        String html = mockMvc.perform(get("/audit").param("homeId", home.getId().toString())
                        .with(asUser("feed-orgadmin" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The home picker legitimately names every home in scope (including the unselected one),
        // so the leak check is scoped to the results, not the whole page. That reasoning is
        // unchanged by 2g - only the anchor is: the results were a <tbody> and are now the dated
        // feed, and the picker went from a <select> to chips, which name the homes just the same.
        String results = html.substring(html.indexOf("class=\"feed\""));
        assertThat(results).contains(home.getName());
        assertThat(results).doesNotContain(otherHome.getName());
    }

    @Test
    void csvExportRowCountMatchesTheDisplayedCountAndCarriesNoRawMetadata() throws Exception {
        mockMvc.perform(post("/requests").with(asUser("feed-home" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-16T20:30")
                        .param("notes", "Free text that must never reach the CSV export"))
                .andExpect(status().is3xxRedirection());

        String readyHtml = mockMvc.perform(post("/audit/export").with(asUser("feed-orgadmin" + suffix)).with(csrf())
                        .param("purpose", "INTERNAL_SAFEGUARDING_REVIEW"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        java.util.regex.Matcher tokenMatch = java.util.regex.Pattern.compile("/export/download/([^\"]+)").matcher(readyHtml);
        assertThat(tokenMatch.find()).as("download link present on the ready screen").isTrue();
        String token = tokenMatch.group(1);

        var csvResult = mockMvc.perform(get("/export/download/{token}", token).with(asUser("feed-orgadmin" + suffix)))
                .andExpect(status().isOk())
                .andReturn();

        String csv = csvResult.getResponse().getContentAsString();
        assertThat(csv).contains("Interview requested");
        assertThat(csv).doesNotContain("Free text that must never reach the CSV export");
        long dataRows = csv.lines().skip(1).filter(line -> !line.isBlank()).count();
        assertThat(dataRows).isGreaterThan(0);

        AuditEvent exportEvent = auditEventRepository.findByEventTypeOrderByOccurredAtDesc(AuditEventType.AUDIT_QUERY_EXPORTED)
                .stream()
                .filter(e -> e.getActorUsernameAtTime() != null && e.getActorUsernameAtTime().endsWith(suffix))
                .findFirst().orElseThrow();
        assertThat(exportEvent.getMetadata()).contains("rows=" + dataRows);
        assertThat(exportEvent.getMetadata()).doesNotContain("Free text that must never reach the CSV export");
    }

    @Test
    void homeStaffCannotReachTheAuditFeed() throws Exception {
        mockMvc.perform(get("/audit").with(asUser("feed-home" + suffix)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/audit/export.csv").with(asUser("feed-home" + suffix)))
                .andExpect(status().isForbidden());
    }
}
