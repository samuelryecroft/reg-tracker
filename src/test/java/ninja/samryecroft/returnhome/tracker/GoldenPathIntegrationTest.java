package ninja.samryecroft.returnhome.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventRepository;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventType;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
class GoldenPathIntegrationTest extends AbstractIntegrationTest {

    @TempDir
    static Path documentStoreDir;

    @DynamicPropertySource
    static void documentStoreDir(DynamicPropertyRegistry registry) {
        registry.add("app.documents.local.directory", () -> documentStoreDir.toString());
    }

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
    private InterviewReportRepository interviewReportRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;
    @Autowired
    private AuditEventRepository auditEventRepository;

    private Long childId;
    private Long careProviderOrgId;

    /**
     * spring-security-test 7.1 dropped the classic {@code userDetails(username)} request
     * post-processor; this rebuilds the equivalent by loading the user through the app's own
     * {@link AppUserDetailsService} so requests are authenticated as a real {@code AppUserPrincipal}.
     */
    private RequestPostProcessor asUser(String username) {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }

    @BeforeEach
    void seedData() {
        // V5__add_organisations.sql seeds exactly one Supplier and one Care Provider org, linked.
        Organisation supplierOrg = seededSupplier();
        Organisation careProviderOrg = seededCareProvider();

        careProviderOrgId = careProviderOrg.getId();

        Home home = new Home();
        home.setName("Golden Path House");
        home.setOrganisation(careProviderOrg);
        home = homeRepository.save(home);

        Child child = new Child();
        child.setFirstName("Riley");
        child.setLastName("Doe");
        child.setDateOfBirth(java.time.LocalDate.of(2011, 3, 4));
        child.setHome(home);
        child = childRepository.save(child);
        childId = child.getId();

        userRepository.save(newUser("gp-home", Role.HOME_STAFF, home, null));
        userRepository.save(newUser("gp-coordinator", Role.COORDINATOR, null, supplierOrg));
        userRepository.save(newUser("gp-visitor", Role.VISITOR, null, supplierOrg));
        userRepository.save(newUser("gp-reviewer", Role.REVIEWER, null, supplierOrg));
    }

    private User newUser(String username, Role role, Home home, Organisation organisation) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("irrelevant-not-checked-by-with-userDetails");
        user.setFullName(username);
        user.setRoles(Set.of(role));
        user.setHome(home);
        user.setOrganisation(organisation);
        user.setEnabled(true);
        return user;
    }

    @Test
    void homeStaffRaisesRequest_coordinatorAllocates_visitorSubmitsReport_reviewerApproves() throws Exception {
        // 1. Home staff raises a request
        mockMvc.perform(post("/requests").with(asUser("gp-home")).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-16T20:30")
                        .param("notes", "Golden path test"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/interview-requests/*"));

        Long requestId = interviewRequestRepository.findAllDetailed().stream()
                .filter(r -> r.getChild().getId().equals(childId))
                .findFirst().orElseThrow().getId();

        assertThat(interviewRequestRepository.findDetailedById(requestId).orElseThrow().getStatus())
                .isEqualTo(InterviewStatus.REQUESTED);

        // 2. Coordinator allocates and schedules
        Long visitorId = userRepository.findByUsername("gp-visitor").orElseThrow().getId();
        mockMvc.perform(post("/coordinator/requests/{id}/allocate", requestId)
                        .with(asUser("gp-coordinator")).with(csrf())
                        .param("visitorId", visitorId.toString())
                        .param("scheduledAt", "2026-07-20T14:00"))
                .andExpect(status().is3xxRedirection());

        assertThat(interviewRequestRepository.findDetailedById(requestId).orElseThrow().getStatus())
                .isEqualTo(InterviewStatus.SCHEDULED);

        // 3. Visitor submits report for review
        mockMvc.perform(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("gp-visitor")).with(csrf())
                        .param("action", "submit")
                        .param("interviewDate", "2026-07-20")
                        .param("interviewLocation", "Golden Path House")
                        .param("within72Hours", "true")
                        .param("consultationWithHomeStaff", "Spoke with key worker")
                        .param("previouslyMissing", "false")
                        .param("confidentialityExplained", "true")
                        .param("interviewAccepted", "true")
                        .param("whereWereYouWhileMissing", "At a friend's house")
                        .param("whatMadeYouGoMissing", "None")
                        .param("consideredSelfMissing", "false")
                        .param("whatDidYouDoWhileMissing", "All is well.")
                        .param("whatHappenedWhenReturned", "Settled back in")
                        .param("risksIdentifiedDuringEpisode", "None")
                        .param("interviewerComments", "Cooperative throughout")
                        .param("recommendations", "No further action")
                        .param("conductedByStatement", "Conducted by the allocated visitor"))
                .andExpect(status().is3xxRedirection());

        assertThat(interviewRequestRepository.findDetailedById(requestId).orElseThrow().getStatus())
                .isEqualTo(InterviewStatus.REPORT_SUBMITTED);

        var submittedReport = interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow();
        assertThat(submittedReport.getGeneratedDocumentPath()).isNull();

        // 4. Nobody can download it yet - it's still pending review, not approved
        mockMvc.perform(get("/reports/{id}/download", requestId).with(asUser("gp-home")))
                .andExpect(status().isNotFound());

        // 5. A different Reviewer approves it - this is the point the docx is actually generated
        mockMvc.perform(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("gp-reviewer")).with(csrf())
                        .param("action", "approve")
                        .param("interviewDate", "2026-07-20")
                        .param("interviewLocation", "Golden Path House")
                        .param("within72Hours", "true")
                        .param("consultationWithHomeStaff", "Spoke with key worker")
                        .param("previouslyMissing", "false")
                        .param("confidentialityExplained", "true")
                        .param("interviewAccepted", "true")
                        .param("whereWereYouWhileMissing", "At a friend's house")
                        .param("whatMadeYouGoMissing", "None")
                        .param("consideredSelfMissing", "false")
                        .param("whatDidYouDoWhileMissing", "All is well.")
                        .param("whatHappenedWhenReturned", "Settled back in")
                        .param("risksIdentifiedDuringEpisode", "None")
                        .param("interviewerComments", "Cooperative throughout")
                        .param("recommendations", "No further action")
                        .param("conductedByStatement", "Conducted by the allocated visitor"))
                .andExpect(status().is3xxRedirection());

        assertThat(interviewRequestRepository.findDetailedById(requestId).orElseThrow().getStatus())
                .isEqualTo(InterviewStatus.REPORT_APPROVED);

        var report = interviewReportRepository.findByInterviewRequestId(requestId).orElseThrow();
        assertThat(report.getGeneratedDocumentPath()).isNotBlank();
        assertThat(documentStoreDir.resolve(report.getGeneratedDocumentPath())).exists();

        // 6. Home staff can now download the generated report
        byte[] downloaded = mockMvc.perform(get("/reports/{id}/download", requestId).with(asUser("gp-home")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .andReturn().getResponse().getContentAsByteArray();

        // 6a. What was downloaded is a real .docx - a zip, so it starts "PK".
        assertThat(downloaded).isNotEmpty();
        assertThat(new String(downloaded, 0, 2, StandardCharsets.UTF_8)).isEqualTo("PK");

        // 6b. What is actually stored is not. This is the assertion the whole encryption
        // workstream exists for, and the only one that would catch a regression to a plain write:
        // the download can look perfect while the bytes on disk are readable to anyone who reaches
        // the storage account.
        byte[] stored = Files.readAllBytes(documentStoreDir.resolve(report.getGeneratedDocumentPath()));
        assertThat(stored).isNotEqualTo(downloaded);
        assertThat(new String(stored, 0, 2, StandardCharsets.UTF_8)).isNotEqualTo("PK");

        // 6c. The storage key names the owning organisation and carries no child identity, and the
        // envelope is stored beside the ciphertext rather than in a key table.
        assertThat(report.getGeneratedDocumentPath())
                .startsWith("org-" + careProviderOrgId + "/")
                .doesNotContain("Riley")
                .doesNotContain("Doe");
        assertThat(documentStoreDir.resolve(report.getGeneratedDocumentPath() + ".meta")).exists();

        // 6d. Both key operations reached the audit trail, scoped to the owning organisation.
        assertThat(auditEventRepository.findByEventTypeOrderByOccurredAtDesc(AuditEventType.DOCUMENT_KEY_WRAPPED))
                .anyMatch(event -> careProviderOrgId.equals(event.getOrganisationId()));
        assertThat(auditEventRepository.findByEventTypeOrderByOccurredAtDesc(AuditEventType.DOCUMENT_KEY_UNWRAPPED))
                .anyMatch(event -> careProviderOrgId.equals(event.getOrganisationId()));

        // 7. The shared detail view is reachable by every role involved in this request,
        // and the old role-specific detail route no longer exists.
        mockMvc.perform(get("/interview-requests/{id}", requestId).with(asUser("gp-home")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/interview-requests/{id}", requestId).with(asUser("gp-coordinator")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/interview-requests/{id}", requestId).with(asUser("gp-visitor")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/requests/{id}", requestId).with(asUser("gp-home")))
                .andExpect(status().isNotFound());
    }
}
