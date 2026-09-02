package ninja.samryecroft.returnhome.tracker.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
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
 * T38 - the V1 per-record "History" timeline. Two concerns: the new batched repository finder
 * works, and the rendered pages actually stay inside the GDPR allow-list (ids/status/timestamps/
 * actor-role only) - the strongest way to prove that is to put PII-shaped data into the system
 * (a real filename, a submitter's free-text notes, a real login) and assert none of it comes back
 * out through a History section, rather than only asserting what a curated view-model contains.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditHistoryIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "CorrectHorse123!";

    @TempDir
    static Path docxOutputDir;

    @DynamicPropertySource
    static void docxOutputDir(DynamicPropertyRegistry registry) {
        registry.add("app.docx.output-dir", () -> docxOutputDir.toString());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AuditEventRepository auditEventRepository;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private ChildRepository childRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InterviewRequestRepository interviewRequestRepository;
    @Autowired
    private OrganisationRepository organisationRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private Organisation supplierOrg;
    private Organisation careProviderOrg;
    private Home home;
    private Long childId;
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
        supplierOrg = organisationRepository.findByTypeOrderByName(OrgType.SUPPLIER).get(0);
        careProviderOrg = organisationRepository.findByTypeOrderByName(OrgType.CARE_PROVIDER).get(0);

        home = new Home();
        home.setName("History House" + suffix);
        home.setOrganisation(careProviderOrg);
        home = homeRepository.save(home);

        Child child = new Child();
        child.setFirstName("Robin");
        child.setLastName("History");
        child.setDateOfBirth(java.time.LocalDate.of(2010, 3, 4));
        child.setHome(home);
        childId = childRepository.save(child).getId();

        userRepository.save(newUser("hist-home" + suffix, Role.HOME_STAFF, home, null));
        userRepository.save(newUser("hist-coordinator" + suffix, Role.COORDINATOR, null, supplierOrg));
        userRepository.save(newUser("hist-visitor" + suffix, Role.VISITOR, null, supplierOrg));
        userRepository.save(newUser("hist-reviewer" + suffix, Role.REVIEWER, null, supplierOrg));
        userRepository.save(newUser("hist-admin" + suffix, Role.ADMIN, null, null));
    }

    private User newUser(String username, Role role, Home userHome, Organisation organisation) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        // Deliberately shares no substring with the username: interview/detail.html legitimately
        // shows the requester's full name in its own "Requested by" field (unrelated to History),
        // and a fullName built from the username (even "X for " + username) still contains it as a
        // substring, which defeats a test asserting "the raw username never leaks via History".
        user.setFullName(role.name() + " Test Person");
        user.setRoles(Set.of(role));
        user.setHome(userHome);
        user.setOrganisation(organisation);
        user.setEnabled(true);
        return user;
    }

    private MockHttpServletRequestBuilder reportFields(MockHttpServletRequestBuilder builder) {
        return builder
                .param("interviewDate", "2026-07-20")
                .param("interviewLocation", "History House")
                .param("within72Hours", "true")
                .param("previouslyMissing", "false")
                .param("confidentialityExplained", "true")
                .param("interviewAccepted", "true")
                .param("consideredSelfMissing", "false")
                .param("interviewerComments", "Recorded for the history test")
                .param("recommendations", "No further action")
                .param("conductedByStatement", "Conducted by the allocated visitor");
    }

    @Test
    void batchedFinderReturnsEventsAcrossMultipleTargetsInOneQuery() {
        AuditEvent first = auditEventRepository.save(rawEvent("InterviewRequest", 9001L));
        AuditEvent second = auditEventRepository.save(rawEvent("InterviewRequest", 9002L));
        auditEventRepository.save(rawEvent("InterviewRequest", 9003L)); // not in the id list - must not come back
        auditEventRepository.save(rawEvent("InterviewReport", 9001L)); // same id, different type - must not come back

        List<AuditEvent> found = auditEventRepository.findByTargetTypeAndTargetIdInOrderByOccurredAtDesc(
                "InterviewRequest", List.of(9001L, 9002L));

        assertThat(found).extracting(AuditEvent::getId).containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    private AuditEvent rawEvent(String targetType, Long targetId) {
        AuditEventRecord record = AuditEventRecord.of(AuditEventType.INTERVIEW_REQUEST_CREATED)
                .target(targetType, targetId)
                .build();
        return new AuditEvent(record);
    }

    @Test
    void requestHistoryShowsCuratedRowsAndNeverTheUnderlyingFreeText() throws Exception {
        mockMvc.perform(post("/requests").with(asUser("hist-home" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-16T20:30")
                        .param("notes", "This free-text note must never appear in the History section"))
                .andExpect(status().is3xxRedirection());

        Long requestId = interviewRequestRepository.findAllDetailed().stream()
                .filter(r -> r.getChild().getId().equals(childId))
                .findFirst().orElseThrow().getId();

        Long visitorId = userRepository.findByUsername("hist-visitor" + suffix).orElseThrow().getId();
        mockMvc.perform(post("/coordinator/requests/{id}/allocate", requestId)
                        .with(asUser("hist-coordinator" + suffix)).with(csrf())
                        .param("visitorId", visitorId.toString())
                        .param("scheduledAt", "2026-07-20T14:00"))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(reportFields(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("hist-visitor" + suffix)).with(csrf())
                        .param("action", "submit")))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(reportFields(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("hist-reviewer" + suffix)).with(csrf())
                        .param("action", "approve")))
                .andExpect(status().is3xxRedirection());

        String html = mockMvc.perform(get("/interview-requests/{id}", requestId).with(asUser("hist-home" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The curated headlines, roles and status-transition details are present...
        assertThat(html).contains("Interview requested").contains("(Home Staff)");
        assertThat(html).contains("Visitor allocated").contains("(Coordinator)").contains("Requested → Scheduled");
        assertThat(html).contains("Report submitted for review").contains("(Visitor)");
        assertThat(html).contains("Report approved").contains("(Reviewer)").contains("Status: Approved");
        assertThat(html).contains("Report document produced");

        // ...but nothing free-text off the underlying rows ever reaches the page through the History
        // section specifically. Scoped to that section's own markup, not the whole page: the page
        // legitimately shows request.notes in its own "Additional Notes" card and the logged-in
        // user's username in the nav's logout form - neither is a History leak, so a whole-page
        // check would false-positive on both. interviewerComments is never shown anywhere on this
        // page outside History, so it stays a valid whole-page probe too.
        assertThat(html).doesNotContain("Recorded for the history test");
        String historySection = html.substring(html.indexOf("<h2 style=\"margin-top:0\">History</h2>"));
        assertThat(historySection).doesNotContain("hist-home" + suffix)
                .doesNotContain("hist-coordinator" + suffix)
                .doesNotContain("hist-visitor" + suffix)
                .doesNotContain("hist-reviewer" + suffix);
        String generatedFilename = auditEventRepository.findByEventTypeOrderByOccurredAtDesc(AuditEventType.DOCX_GENERATED)
                .stream().filter(e -> e.getActorUsernameAtTime().endsWith(suffix)).findFirst()
                .orElseThrow().getMetadata();
        assertThat(generatedFilename).contains("filename=");
        assertThat(historySection).doesNotContain(generatedFilename.replaceAll(".*filename=", "").split(";")[0]);
    }

    @Test
    void childCaseHistoryGroupsByRequestAndShowsNoActivityForANewChild() throws Exception {
        Child freshChild = new Child();
        freshChild.setFirstName("NoHistory");
        freshChild.setLastName("Yet");
        freshChild.setDateOfBirth(java.time.LocalDate.of(2012, 1, 1));
        freshChild.setHome(home);
        Long freshChildId = childRepository.save(freshChild).getId();

        String emptyHtml = mockMvc.perform(get("/children/{id}", freshChildId).with(asUser("hist-home" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(emptyHtml).contains("Case history").contains("No recorded activity");

        mockMvc.perform(post("/requests").with(asUser("hist-home" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-16T20:30"))
                .andExpect(status().is3xxRedirection());
        Long requestId = interviewRequestRepository.findAllDetailed().stream()
                .filter(r -> r.getChild().getId().equals(childId))
                .findFirst().orElseThrow().getId();

        String html = mockMvc.perform(get("/children/{id}", childId).with(asUser("hist-home" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Case history").contains("Request #" + requestId).contains("Interview requested");
    }

    @Test
    void userHistoryShowsAccountChangesButNeverSignInActivity() throws Exception {
        // Seeded users in @BeforeEach are saved directly via the repository, which never publishes
        // USER_CREATED - go through the real endpoint so this account actually has creation history.
        mockMvc.perform(post("/admin/users").with(asUser("hist-admin" + suffix)).with(csrf())
                        .param("username", "hist-newvisitor" + suffix)
                        .param("password", "CorrectHorse123!")
                        .param("fullName", "History New Visitor")
                        .param("roles", "VISITOR")
                        .param("organisationId", supplierOrg.getId().toString()))
                .andExpect(status().is3xxRedirection());
        Long newUserId = userRepository.findByUsername("hist-newvisitor" + suffix).orElseThrow().getId();

        // Prove exclusion isn't just "there happens to be no login event": actually sign in first.
        mockMvc.perform(post("/login").with(csrf())
                        .param("username", "hist-newvisitor" + suffix)
                        .param("password", "CorrectHorse123!"))
                .andExpect(status().is3xxRedirection());

        String html = mockMvc.perform(get("/admin/users/{id}/edit", newUserId).with(asUser("hist-admin" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("History").contains("User account created").contains("Roles: Visitor");
        assertThat(html).doesNotContain("Signed in").doesNotContain("signed in");
    }
}
