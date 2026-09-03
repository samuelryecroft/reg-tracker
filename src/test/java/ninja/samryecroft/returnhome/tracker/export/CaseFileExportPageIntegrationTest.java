package ninja.samryecroft.returnhome.tracker.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
 * Roadmap 2.5: the child case-file export SCREEN, calling Jim's real
 * {@code CaseFileExportService}/{@code ExportController} in-process. Pack internals (zip contents,
 * PDF narrative, checksums, passphrase encryption) are already covered by his own
 * {@code CaseFileExportServiceTest}/{@code ExportPackWriterTest} - this test proves the FE layer:
 * the screen renders the manifest, the form calls through correctly, the ready screen's download
 * link actually works, and the role/capability/cross-org gates hold.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaseFileExportPageIntegrationTest extends AbstractIntegrationTest {

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
        supplierOrg = saveOrg("Page Supplier" + suffix, OrgType.SUPPLIER, null);
        careProviderOrg = saveOrg("Page Provider" + suffix, OrgType.CARE_PROVIDER, supplierOrg);
        home = saveHome("Page House" + suffix, careProviderOrg);

        userRepository.save(newUser("page-home" + suffix, Role.HOME_STAFF, home, null, false));
        userRepository.save(newUser("page-orgadmin" + suffix, Role.ORG_ADMIN, null, careProviderOrg, true));
        userRepository.save(newUser("page-orgadmin-noflag" + suffix, Role.ORG_ADMIN, null, careProviderOrg, false));
        userRepository.save(newUser("page-coordinator" + suffix, Role.COORDINATOR, null, supplierOrg, true));
        userRepository.save(newUser("page-visitor" + suffix, Role.VISITOR, null, supplierOrg, true));
        userRepository.save(newUser("page-reviewer" + suffix, Role.REVIEWER, null, supplierOrg, true));

        Child child = new Child();
        child.setFirstName("Priya");
        child.setLastName("Page" + suffix);
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

    private User newUser(String username, Role role, Home userHome, Organisation organisation, boolean canExport) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setFullName(username);
        user.setRoles(Set.of(role));
        user.setHome(userHome);
        user.setOrganisation(organisation);
        user.setEnabled(true);
        user.setCanExport(canExport);
        return user;
    }

    private MockHttpServletRequestBuilder reportFields(MockHttpServletRequestBuilder builder) {
        return builder
                .param("heldAt", "2026-07-20T14:00")
                .param("interviewLocation", "Page House")
                .param("within72Hours", "true")
                .param("previouslyMissing", "false")
                .param("confidentialityExplained", "true")
                .param("interviewAccepted", "true")
                .param("consideredSelfMissing", "false")
                .param("interviewerComments", "Recorded for export page test")
                .param("recommendations", "No further action")
                .param("conductedByStatement", "Conducted by the allocated visitor");
    }

    private Long approvedInterview() throws Exception {
        mockMvc.perform(post("/requests").with(asUser("page-home" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-16T20:30"))
                .andExpect(status().is3xxRedirection());

        Long requestId = interviewRequestRepository.findAllDetailed().stream()
                .filter(r -> r.getChild().getId().equals(childId))
                .findFirst().orElseThrow().getId();

        Long visitorId = userRepository.findByUsername("page-visitor" + suffix).orElseThrow().getId();
        mockMvc.perform(post("/coordinator/requests/{id}/allocate", requestId)
                        .with(asUser("page-coordinator" + suffix)).with(csrf())
                        .param("visitorId", visitorId.toString())
                        .param("scheduledAt", "2026-07-20T14:00"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(reportFields(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("page-visitor" + suffix)).with(csrf())
                        .param("action", "submit")))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(reportFields(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("page-reviewer" + suffix)).with(csrf())
                        .param("action", "approve")))
                .andExpect(status().is3xxRedirection());

        return requestId;
    }

    @Test
    void formShowsTheRealManifestFromJimsService() throws Exception {
        approvedInterview();
        String html = mockMvc.perform(get("/children/{id}/export", childId).with(asUser("page-orgadmin" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("This pack contains special-category personal data about a child.");
        assertThat(html).contains("1 report document(s) will be attached");
    }

    @Test
    void producingThePackReturnsARealZipWithTheNarrativeAndTheOriginalReport() throws Exception {
        approvedInterview();

        String readyHtml = mockMvc.perform(post("/children/{id}/export", childId).with(asUser("page-orgadmin" + suffix)).with(csrf())
                        .param("purpose", "REGULATORY_INSPECTION")
                        .param("reference", "OFSTED-PAGE-1")
                        .param("period", "ALL"))
                        // passphrase checkbox omitted entirely below -> unchecked -> protect param absent -> off
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(readyHtml).contains("Pack ready");
        Matcher tokenMatch = Pattern.compile("/export/download/([^\"]+)").matcher(readyHtml);
        assertThat(tokenMatch.find()).as("download link present").isTrue();
        String token = tokenMatch.group(1);

        byte[] zipBytes = mockMvc.perform(get("/export/download/{token}", token).with(asUser("page-orgadmin" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        Set<String> entryNames = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entryNames.add(entry.getName());
            }
        }
        assertThat(entryNames).contains("case-file.pdf");
        assertThat(entryNames.stream().anyMatch(n -> n.startsWith("reports/") && n.endsWith(".docx"))).isTrue();

        // The link is single-use - a second attempt is a 404 by design.
        mockMvc.perform(get("/export/download/{token}", token).with(asUser("page-orgadmin" + suffix)))
                .andExpect(status().isNotFound());
    }

    @Test
    void homeStaffCannotExportEvenThoughTheyCanViewTheChild() throws Exception {
        approvedInterview();
        mockMvc.perform(get("/children/{id}/export", childId).with(asUser("page-home" + suffix)))
                .andExpect(status().isForbidden());
    }

    @Test
    void eligibleRoleWithoutTheExportFlagIsForbidden() throws Exception {
        approvedInterview();
        mockMvc.perform(get("/children/{id}/export", childId).with(asUser("page-orgadmin-noflag" + suffix)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anotherCareProviderOrgAdminCannotExportThisChild() throws Exception {
        approvedInterview();
        Organisation otherOrg = saveOrg("Other Page Provider" + suffix, OrgType.CARE_PROVIDER, supplierOrg);
        userRepository.save(newUser("page-other-orgadmin" + suffix, Role.ORG_ADMIN, null, otherOrg, true));

        mockMvc.perform(get("/children/{id}/export", childId).with(asUser("page-other-orgadmin" + suffix)))
                .andExpect(status().isForbidden());
    }
}
