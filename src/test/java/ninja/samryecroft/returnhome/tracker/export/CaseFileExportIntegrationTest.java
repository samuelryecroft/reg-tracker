package ninja.samryecroft.returnhome.tracker.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.audit.AuditEvent;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventRepository;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventType;
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
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Roadmap 2.5: the child case-file export. Drives the real HTTP endpoints - raise, allocate,
 * submit, approve - so the pack is built from a genuinely encrypted, genuinely stored report,
 * exactly as it would be in production.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaseFileExportIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "CorrectHorse123!";

    @TempDir
    static Path docxOutputDir;

    @DynamicPropertySource
    static void docxOutputDir(DynamicPropertyRegistry registry) {
        registry.add("app.documents.local.directory", () -> docxOutputDir.toString());
    }

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
        supplierOrg = saveOrg("Export Supplier" + suffix, OrgType.SUPPLIER, null);
        careProviderOrg = saveOrg("Export Provider" + suffix, OrgType.CARE_PROVIDER, supplierOrg);
        home = saveHome("Export House" + suffix, careProviderOrg);

        userRepository.save(newUser("export-home" + suffix, Role.HOME_STAFF, home, null));
        userRepository.save(newUser("export-orgadmin" + suffix, Role.ORG_ADMIN, null, careProviderOrg));
        userRepository.save(newUser("export-coordinator" + suffix, Role.COORDINATOR, null, supplierOrg));
        userRepository.save(newUser("export-visitor" + suffix, Role.VISITOR, null, supplierOrg));
        userRepository.save(newUser("export-reviewer" + suffix, Role.REVIEWER, null, supplierOrg));

        Child child = new Child();
        child.setFirstName("Priya");
        child.setLastName("Export" + suffix);
        child.setDateOfBirth(java.time.LocalDate.of(2011, 4, 4));
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
        user.setFullName(username);
        user.setRoles(Set.of(role));
        user.setHome(userHome);
        user.setOrganisation(organisation);
        user.setEnabled(true);
        return user;
    }

    private MockHttpServletRequestBuilder reportFields(MockHttpServletRequestBuilder builder) {
        return builder
                .param("interviewDate", "2026-07-20")
                .param("interviewLocation", "Export House")
                .param("within72Hours", "true")
                .param("previouslyMissing", "false")
                .param("confidentialityExplained", "true")
                .param("interviewAccepted", "true")
                .param("consideredSelfMissing", "false")
                .param("interviewerComments", "Recorded for export test")
                .param("recommendations", "No further action")
                .param("conductedByStatement", "Conducted by the allocated visitor");
    }

    /** Raises, allocates, submits and approves a report - returns the request id. Produces exactly one approved report. */
    private Long approvedInterviewFor(String reviewComments) throws Exception {
        mockMvc.perform(post("/requests").with(asUser("export-home" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-16T20:30"))
                .andExpect(status().is3xxRedirection());

        Long requestId = interviewRequestRepository.findAllDetailed().stream()
                .filter(r -> r.getChild().getId().equals(childId))
                .findFirst().orElseThrow().getId();

        Long visitorId = userRepository.findByUsername("export-visitor" + suffix).orElseThrow().getId();
        mockMvc.perform(post("/coordinator/requests/{id}/allocate", requestId)
                        .with(asUser("export-coordinator" + suffix)).with(csrf())
                        .param("visitorId", visitorId.toString())
                        .param("scheduledAt", "2026-07-20T14:00"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(reportFields(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("export-visitor" + suffix)).with(csrf())
                        .param("action", "submit")))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(reportFields(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("export-reviewer" + suffix)).with(csrf())
                        .param("action", "approve")
                        .param("reviewComments", reviewComments == null ? "" : reviewComments)))
                .andExpect(status().is3xxRedirection());

        return requestId;
    }

    @Test
    void formShowsTheManifestBeforeAnyCommitment() throws Exception {
        approvedInterviewFor(null);
        String html = mockMvc.perform(get("/children/{id}/export", childId).with(asUser("export-orgadmin" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("This pack contains special-category personal data about a child.");
        assertThat(html).contains("1 files"); // one approved report
        assertThat(html).contains("Produce the pack");
    }

    @Test
    void producingThePackReturnsAZipWithCoverSheetTimelineAndReportAndRecordsTheExport() throws Exception {
        String sensitiveComment = "The child disclosed something highly sensitive that must never leave this row";
        approvedInterviewFor(sensitiveComment);

        var result = mockMvc.perform(post("/children/{id}/export", childId).with(asUser("export-orgadmin" + suffix)).with(csrf())
                        .param("purpose", ExportOptions.PURPOSES.get(0))
                        .param("reference", "OFSTED-TEST-1")
                        .param("period", "ALL"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] zipBytes = result.getResponse().getContentAsByteArray();
        Set<String> entryNames = new HashSet<>();
        StringBuilder allText = new StringBuilder();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryNames.add(entry.getName());
                if (entry.getName().endsWith(".txt")) {
                    allText.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }

        assertThat(entryNames).contains("cover-sheet.txt", "timeline.txt");
        assertThat(entryNames.stream().anyMatch(n -> n.startsWith("reports/") && n.endsWith(".docx"))).isTrue();

        // GDPR: the pack's own text files never carry the underlying free-text review comment,
        // only the curated allow-list projection (same discipline as AuditHistoryService itself).
        assertThat(allText.toString()).doesNotContain(sensitiveComment).doesNotContain("highly sensitive");
        assertThat(allText.toString()).contains("Report approved").contains("Report submitted for review");
        assertThat(allText.toString()).contains("OFSTED-TEST-1");

        List<AuditEvent> exported = auditEventRepository.findByEventTypeOrderByOccurredAtDesc(AuditEventType.CASE_FILE_EXPORTED);
        AuditEvent event = exported.stream()
                .filter(e -> e.getActorUsernameAtTime() != null && e.getActorUsernameAtTime().endsWith(suffix))
                .findFirst().orElseThrow();
        assertThat(event.getMetadata()).contains("success=true").contains("reportCount=1").contains("interviewCount=1");
        assertThat(event.getMetadata()).doesNotContain(sensitiveComment);
    }

    @Test
    void homeStaffCannotExportEvenThoughTheyCanViewTheChild() throws Exception {
        approvedInterviewFor(null);
        mockMvc.perform(get("/children/{id}/export", childId).with(asUser("export-home" + suffix)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/children/{id}/export", childId).with(asUser("export-home" + suffix)).with(csrf())
                        .param("purpose", ExportOptions.PURPOSES.get(0)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anotherCareProviderOrgAdminCannotExportThisChild() throws Exception {
        approvedInterviewFor(null);
        Organisation otherOrg = saveOrg("Other Provider" + suffix, OrgType.CARE_PROVIDER, supplierOrg);
        userRepository.save(newUser("export-other-orgadmin" + suffix, Role.ORG_ADMIN, null, otherOrg));

        mockMvc.perform(get("/children/{id}/export", childId).with(asUser("export-other-orgadmin" + suffix)))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectingWithoutAValidPurposeShowsTheFormAgainRatherThanProducingAPack() throws Exception {
        approvedInterviewFor(null);
        mockMvc.perform(post("/children/{id}/export", childId).with(asUser("export-orgadmin" + suffix)).with(csrf())
                        .param("purpose", "Not a real purpose"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentType()).contains("html"));
    }
}
