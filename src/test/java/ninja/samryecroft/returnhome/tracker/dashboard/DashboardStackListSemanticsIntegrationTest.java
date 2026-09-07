package ninja.samryecroft.returnhome.tracker.dashboard;

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
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestService;
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
 * T300 (spec §8f, Creed's sweep after T290): {@code dashboard/care-provider.html} and
 * {@code dashboard/supplier.html} each render three {@code .stack} containers - the same
 * table-fallback pattern {@code children/detail.html} had before T290, and this codebase's other
 * fix for it (T253) never reached either dashboard. display:flex on a list strips list semantics in
 * Safari/VoiceOver, so a {@code .stack} with no {@code role="list"}/{@code role="listitem"} reads
 * to assistive technology as a a run of unrelated paragraphs rather than the same rows the table
 * beside it announces as a table.
 *
 * <p>Both dashboards share this file rather than getting one each: the three-{@code .stack} shape
 * (ranked / too-few-to-report / recurrence) and the fix are identical on both pages, so testing them
 * once each here is testing the same claim twice, not two different claims.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DashboardStackListSemanticsIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "CorrectHorse123!";

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganisationRepository organisationRepository;
    @Autowired private HomeRepository homeRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private InterviewRequestRepository interviewRequestRepository;
    @Autowired private InterviewRequestService interviewRequestService;
    @Autowired private InterviewReportRepository interviewReportRepository;
    @Autowired private AppUserDetailsService appUserDetailsService;
    @Autowired private PasswordEncoder passwordEncoder;

    private String suffix;
    private Organisation supplierOrg;
    private Organisation careProviderOrg;
    private Home home1;
    private Home home2;
    private User visitor;
    private String orgAdminUsername;
    private String coordinatorUsername;

    @BeforeEach
    void seedOneSupplierOneCareProviderTwoHomes() {
        suffix = "-" + System.nanoTime();
        supplierOrg = saveOrg("T300 Supplier" + suffix, OrgType.SUPPLIER, null);
        careProviderOrg = saveOrg("T300 Provider" + suffix, OrgType.CARE_PROVIDER, supplierOrg);
        home1 = saveHome("T300 Home One" + suffix, careProviderOrg);
        home2 = saveHome("T300 Home Two" + suffix, careProviderOrg);

        visitor = userRepository.save(newUser("t300-visitor" + suffix, Role.VISITOR, null, supplierOrg));
        orgAdminUsername = "t300-orgadmin" + suffix;
        userRepository.save(newUser(orgAdminUsername, Role.ORG_ADMIN, null, careProviderOrg));
        coordinatorUsername = "t300-coordinator" + suffix;
        userRepository.save(newUser(coordinatorUsername, Role.COORDINATOR, null, supplierOrg));
    }

    /**
     * With zero HOMES (not just zero requests - a home with zero completed interviews still counts
     * as "below base" and populates the too-few bucket at 0 completed, so that bucket needs a
     * genuinely homeless org to be empty), every {@code .stack} still renders as a
     * {@code <ul role="list">} - never conditionally omitted, matching the table beside it, which
     * also always renders its headers - and the genuinely-empty ranked/recurrence lists fall back
     * to a single {@code <li class="empty" role="listitem">}, not a sibling div outside the list.
     * The "too few to report" section stays entirely absent here, gated by its own enclosing
     * {@code th:if}, which never needed a {@code role="list"} fix because it can never render with
     * zero candidates.
     */
    @Test
    void withZeroHomesEveryStackStillHasListSemanticsAndTheEmptyStateIsAListItem() throws Exception {
        // A provider with no homes: its OWN view ranks homes, so a homeless provider's candidate
        // set is genuinely empty regardless of which supplier it sits under.
        Organisation homelessProvider = saveOrg("T300 Homeless Provider" + suffix, OrgType.CARE_PROVIDER, supplierOrg);
        String homelessOrgAdmin = "t300-homeless-orgadmin" + suffix;
        userRepository.save(newUser(homelessOrgAdmin, Role.ORG_ADMIN, null, homelessProvider));

        // A supplier with no care providers at all: the supplier's OWN view ranks providers, so a
        // provider that merely has no homes (like the one above) would still populate its "too few"
        // bucket at 0 completed - the candidate set is only genuinely empty with zero providers.
        Organisation providerlessSupplier = saveOrg("T300 Providerless Supplier" + suffix, OrgType.SUPPLIER, null);
        String providerlessCoordinator = "t300-providerless-coordinator" + suffix;
        userRepository.save(newUser(providerlessCoordinator, Role.COORDINATOR, null, providerlessSupplier));

        String careProviderHtml = dashboardHtml(homelessOrgAdmin);
        assertStructurallySound(careProviderHtml, "No home has reached the minimum base yet this period.");

        String supplierHtml = dashboardHtml(providerlessCoordinator);
        assertStructurallySound(supplierHtml, "Nothing has reached the minimum base yet this period.");
    }

    private void assertStructurallySound(String html, String rankedEmptyMessage) {
        assertThat(html).doesNotContain("Too few to report");
        assertThat(html).doesNotContain("<div class=\"stack\">").doesNotContain("<div class=\"srow\"");

        long ulCount = countOccurrences(html, "<ul class=\"stack\" role=\"list\">");
        assertThat(ulCount).as("both the ranked and recurrence .stack blocks must render as role=list uls")
                .isEqualTo(2);
        assertThat(html).contains("<li class=\"empty\" role=\"listitem\">" + rankedEmptyMessage);
        assertThat(html).contains("<li class=\"empty\" role=\"listitem\">"
                + "No recurring missing episodes have been flagged on open or recent requests.");
    }

    /**
     * D-2c-1's row, converted: a real ranked row is now an {@code <li role="listitem">} inside a
     * {@code <ul role="list">}, carrying the same data (name, compliance) the div-based version
     * carried - the conversion changed the container, not the content.
     */
    @Test
    void aRankedRowRendersAsAListItemOnBothDashboards() throws Exception {
        for (int i = 0; i < 5; i++) {
            saveApprovedReport(home1, LocalDateTime.now().minusHours(10), true);
        }

        String careProviderHtml = dashboardHtml(orgAdminUsername);
        assertRankedRowIsAListItem(careProviderHtml, home1.getName());

        String supplierHtml = dashboardHtml(coordinatorUsername);
        assertRankedRowIsAListItem(supplierHtml, careProviderOrg.getName());
    }

    private void assertRankedRowIsAListItem(String html, String rankedRowName) {
        assertThat(html).doesNotContain("<div class=\"stack\">").doesNotContain("<div class=\"srow\"");
        String stack = extractBetween(html, "<ul class=\"stack\" role=\"list\">", "</ul>");
        assertThat(stack).contains("<li class=\"srow\" role=\"listitem\"").contains(rankedRowName);
    }

    /** The base-5 rule's OTHER bucket gets the identical fix - a below-base row is a listitem too. */
    @Test
    void aTooFewToReportRowRendersAsAListItemOnBothDashboards() throws Exception {
        for (int i = 0; i < 2; i++) {
            saveApprovedReport(home2, LocalDateTime.now().minusHours(10), true);
        }

        String careProviderHtml = dashboardHtml(orgAdminUsername);
        assertThat(careProviderHtml).contains("Too few to report");
        String tooFewSection = extractBetween(careProviderHtml, "Too few to report", "</section>");
        assertThat(tooFewSection).doesNotContain("<div class=\"stack\">").doesNotContain("<div class=\"srow\"");
        String stack = extractBetween(tooFewSection, "<ul class=\"stack\" role=\"list\">", "</ul>");
        assertThat(stack).contains("<li class=\"srow\" role=\"listitem\"").contains(home2.getName());
    }

    private String dashboardHtml(String username) throws Exception {
        return mockMvc.perform(get("/dashboard").with(asUser(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static long countOccurrences(String haystack, String needle) {
        long count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private String extractBetween(String html, String startMarker, String endMarker) {
        int start = html.indexOf(startMarker);
        assertThat(start).as("marker not found: " + startMarker).isPositive();
        int end = html.indexOf(endMarker, start);
        return html.substring(start, end);
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
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

    private User newUser(String username, Role role, Home home, Organisation organisation) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setLastName(username);
        user.setRoles(Set.of(role));
        user.setHomes(home == null ? new HashSet<>() : new HashSet<>(Set.of(home)));
        user.setOrganisation(organisation);
        user.setEnabled(true);
        return user;
    }

    private Child saveChild(String firstName, Home home) {
        Child child = new Child();
        child.setFirstName(firstName);
        child.setLastName("T300");
        child.setDateOfBirth(LocalDate.of(2012, 1, 1));
        child.setHome(home);
        return childRepository.save(child);
    }

    /** Mirrors DashboardIntegrationTest's own fixture shape - a completed interview, report approved. */
    private void saveApprovedReport(Home home, LocalDateTime returnedAt, boolean within72) {
        Child child = saveChild("Child", home);
        InterviewRequest request = new InterviewRequest();
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(visitor);
        request.setReturnedAt(returnedAt);
        interviewRequestService.markStatus(request, InterviewStatus.REPORT_APPROVED);
        InterviewRequest savedRequest = interviewRequestRepository.save(request);

        InterviewReport report = new InterviewReport();
        report.setInterviewRequest(savedRequest);
        report.setVisitor(visitor);
        report.setStatus(ReportStatus.APPROVED);
        report.setReviewedAt(LocalDateTime.now());
        report.setHeldAt(returnedAt.plusHours(within72 ? 10 : 100));
        interviewReportRepository.save(report);
    }
}
