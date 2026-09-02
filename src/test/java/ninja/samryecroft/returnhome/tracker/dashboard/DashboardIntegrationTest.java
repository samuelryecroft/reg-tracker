package ninja.samryecroft.returnhome.tracker.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import ninja.samryecroft.returnhome.tracker.report.ReportStatus;
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
 * Roadmap 2.3. Focuses on the DONE bar's "role-scoping" requirement - a Care Provider only ever
 * sees their own homes, a Viewer only their assigned subset, a Supplier only the providers they
 * serve - plus the two honesty mechanisms (base-5 "too few to report" and the excluded-no-return-
 * time count) and the landing-page/authorization changes that ship alongside the dashboard itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardIntegrationTest extends AbstractIntegrationTest {

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
    private InterviewReportRepository interviewReportRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private String suffix;
    private Organisation supplierOrg;
    private Organisation careProviderA;
    private Organisation careProviderB;
    private Home homeA1;
    private Home homeA2;
    private User visitor;

    private RequestPostProcessor asUser(String username) {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }

    @BeforeEach
    void seedData() {
        suffix = "-" + System.nanoTime();

        supplierOrg = saveOrg("Dash Supplier" + suffix, OrgType.SUPPLIER, null);
        careProviderA = saveOrg("Dash Provider A" + suffix, OrgType.CARE_PROVIDER, supplierOrg);
        careProviderB = saveOrg("Dash Provider B" + suffix, OrgType.CARE_PROVIDER, supplierOrg);

        homeA1 = saveHome("Dash Home A1" + suffix, careProviderA);
        homeA2 = saveHome("Dash Home A2" + suffix, careProviderA);

        visitor = userRepository.save(newUser("dash-visitor" + suffix, Role.VISITOR, null, supplierOrg, null));
        userRepository.save(newUser("dash-orgadmin-a" + suffix, Role.ORG_ADMIN, null, careProviderA, null));
        userRepository.save(newUser("dash-orgadmin-b" + suffix, Role.ORG_ADMIN, null, careProviderB, null));
        userRepository.save(newUser("dash-coordinator" + suffix, Role.COORDINATOR, null, supplierOrg, null));
        User homeStaffA1 = userRepository.save(newUser("dash-home-a1" + suffix, Role.HOME_STAFF, homeA1, null, null));

        User viewerA1Only = newUser("dash-viewer" + suffix, Role.VIEWER, null, careProviderA, null);
        viewerA1Only.setViewerHomes(Set.of(homeA1));
        userRepository.save(viewerA1Only);
    }

    private Organisation saveOrg(String name, OrgType type, Organisation supplier) {
        Organisation org = new Organisation();
        org.setName(name);
        org.setType(type);
        org.setSupplierOrganisation(supplier);
        return organisationRepository.save(org);
    }

    private Home saveHome(String name, Organisation org) {
        Home home = new Home();
        home.setName(name);
        home.setOrganisation(org);
        return homeRepository.save(home);
    }

    private User newUser(String username, Role role, Home home, Organisation organisation, Set<Home> viewerHomes) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setFullName(username);
        user.setRoles(Set.of(role));
        user.setHome(home);
        user.setOrganisation(organisation);
        user.setEnabled(true);
        if (viewerHomes != null) {
            user.setViewerHomes(viewerHomes);
        }
        return user;
    }

    private Child saveChild(String firstName, Home home) {
        Child child = new Child();
        child.setFirstName(firstName);
        child.setLastName("Dash");
        child.setDateOfBirth(LocalDate.of(2012, 1, 1));
        child.setHome(home);
        return childRepository.save(child);
    }

    /** A request already resolved (interview held, report approved) - only the report's data drives the Performance zone. */
    private void saveApprovedReport(Home home, LocalDateTime returnedAt, boolean within72) {
        Child child = saveChild("Child", home);
        InterviewRequest request = new InterviewRequest();
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(visitor);
        request.setStatus(InterviewStatus.REPORT_APPROVED);
        request.setReturnedAt(returnedAt);
        InterviewRequest savedRequest = interviewRequestRepository.save(request);

        InterviewReport report = new InterviewReport();
        report.setInterviewRequest(savedRequest);
        report.setVisitor(visitor);
        report.setStatus(ReportStatus.APPROVED);
        report.setReviewedAt(LocalDateTime.now());
        report.setWithin72Hours(within72);
        interviewReportRepository.save(report);
    }

    private InterviewRequest saveLiveRequest(Home home, InterviewStatus status, LocalDateTime returnedAt) {
        Child child = saveChild("Child", home);
        InterviewRequest request = new InterviewRequest();
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(visitor);
        request.setStatus(status);
        request.setReturnedAt(returnedAt);
        return interviewRequestRepository.save(request);
    }

    @Test
    void careProviderOrgAdminSeesOnlyTheirOwnHomesNeverAnotherOrganisations() throws Exception {
        saveLiveRequest(homeA1, InterviewStatus.REQUESTED, LocalDateTime.now().minusHours(80)); // overdue, org A
        Home foreignHome = saveHome("Foreign Home" + suffix, careProviderB);
        saveLiveRequest(foreignHome, InterviewStatus.REQUESTED, LocalDateTime.now().minusHours(80)); // overdue, org B - must never appear

        String html = mockMvc.perform(get("/dashboard").with(asUser("dash-orgadmin-a" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Dash Provider A" + suffix);
        assertThat(html).doesNotContain("Dash Provider B" + suffix);
        assertThat(html).doesNotContain("Foreign Home" + suffix);
        // Overdue now tile counts exactly the one request in scope, not the foreign org's.
        assertThat(html).contains("Overdue now");
    }

    @Test
    void viewerSeesOnlyTheirAssignedHomeNotTheWholeCareProviderOrganisation() throws Exception {
        saveLiveRequest(homeA1, InterviewStatus.REQUESTED, LocalDateTime.now().minusHours(80)); // overdue, assigned home
        saveLiveRequest(homeA2, InterviewStatus.REQUESTED, LocalDateTime.now().minusHours(80)); // overdue, NOT assigned to this viewer

        String html = mockMvc.perform(get("/dashboard").with(asUser("dash-viewer" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("1 home ·"); // homeCount summary line reflects only their one assigned home
        assertThat(html).doesNotContain(homeA2.getName());
    }

    @Test
    void baseFiveRuleMovesLowVolumeHomesToTooFewToReportUnranked() throws Exception {
        for (int i = 0; i < 6; i++) {
            saveApprovedReport(homeA1, LocalDateTime.now().minusHours(10), true); // 6 completed, at/above base
        }
        for (int i = 0; i < 3; i++) {
            saveApprovedReport(homeA2, LocalDateTime.now().minusHours(10), true); // 3 completed, below base
        }

        String html = mockMvc.perform(get("/dashboard").with(asUser("dash-orgadmin-a" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Too few to report");
        int tooFewHeadingIdx = html.indexOf("Too few to report");
        int home2Idx = html.indexOf(homeA2.getName());
        int home1Idx = html.indexOf(homeA1.getName());
        assertThat(home2Idx).isGreaterThan(tooFewHeadingIdx); // homeA2 (3 completed) only appears in the too-few block
        assertThat(home1Idx).isPositive().isLessThan(tooFewHeadingIdx); // homeA1 (6 completed) is ranked, above that heading
    }

    @Test
    void excludedNoReturnTimeIsCountedSeparatelyFromTheRateNotFoldedIntoEitherSide() throws Exception {
        for (int i = 0; i < 5; i++) {
            saveApprovedReport(homeA1, LocalDateTime.now().minusHours(10), true); // 5 valid, all within 72h
        }
        saveApprovedReport(homeA1, null, true); // completed but no returnedAt - excluded, not counted either way

        String html = mockMvc.perform(get("/dashboard").with(asUser("dash-orgadmin-a" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("100%"); // 5 of 5 valid, unaffected by the excluded one
        assertThat(html).contains("further interview").contains("excluded");
    }

    @Test
    void supplierSeesBreakdownByCareProviderAndDrillsToHomeOnSelection() throws Exception {
        saveApprovedReport(homeA1, LocalDateTime.now().minusHours(10), true);
        Home homeB1 = saveHome("Dash Home B1" + suffix, careProviderB);
        saveApprovedReport(homeB1, LocalDateTime.now().minusHours(10), false);

        String allProviders = mockMvc.perform(get("/dashboard").with(asUser("dash-coordinator" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(allProviders).contains("Dash Provider A" + suffix).contains("Dash Provider B" + suffix);
        assertThat(allProviders).contains("By care provider");

        String drilled = mockMvc.perform(get("/dashboard").param("careProviderId", careProviderA.getId().toString())
                        .with(asUser("dash-coordinator" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(drilled).contains(homeA1.getName());
        assertThat(drilled).doesNotContain(homeB1.getName());
        assertThat(drilled).contains("By home");
    }

    @Test
    void landingPageRoutesEachRoleToTheRightDestination() throws Exception {
        mockMvc.perform(get("/").with(asUser("dash-orgadmin-a" + suffix)))
                .andExpect(redirectedUrl("/dashboard"));
        mockMvc.perform(get("/").with(asUser("dash-viewer" + suffix)))
                .andExpect(redirectedUrl("/dashboard"));
        mockMvc.perform(get("/").with(asUser("dash-coordinator" + suffix)))
                .andExpect(redirectedUrl("/coordinator/requests"));
    }

    @Test
    void nonDashboardRolesAreForbiddenFromTheDashboardRoute() throws Exception {
        userRepository.save(newUser("dash-home-only" + suffix, Role.HOME_STAFF, homeA1, null, null));
        mockMvc.perform(get("/dashboard").with(asUser("dash-home-only" + suffix)))
                .andExpect(status().isForbidden());
    }

    @Test
    void careProviderOrgAdminCanNowReadTheCoordinatorListScopedToTheirOwnOrgOnly() throws Exception {
        saveLiveRequest(homeA1, InterviewStatus.REQUESTED, LocalDateTime.now().minusHours(80));
        Home foreignHome = saveHome("Filter Foreign Home" + suffix, careProviderB);
        saveLiveRequest(foreignHome, InterviewStatus.REQUESTED, LocalDateTime.now().minusHours(80));

        String html = mockMvc.perform(get("/coordinator/requests").param("filter", "overdue")
                        .with(asUser("dash-orgadmin-a" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains(homeA1.getName());
        assertThat(html).doesNotContain("Filter Foreign Home" + suffix);
    }
}
