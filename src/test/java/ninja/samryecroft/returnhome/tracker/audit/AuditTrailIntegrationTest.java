package ninja.samryecroft.returnhome.tracker.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Phase-1 audit trail (AUDIT-PLAN.md §A). Drives the real HTTP endpoints so events are published
 * exactly as they would be in production, then asserts the persisted rows.
 *
 * <p>Deliberately not {@code @Transactional}: the audit listener is
 * {@code @TransactionalEventListener(AFTER_COMMIT)}, so a test that rolled its transaction back
 * would observe no audit rows at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuditTrailIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "CorrectHorse123!";

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
    private InterviewReportRepository interviewReportRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Organisation supplierOrg;
    private Organisation careProviderOrg;
    private Home home;
    private Home otherHome;
    private Long childId;
    private Long otherChildId;
    private String suffix;

    private RequestPostProcessor asUser(String username) {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }

    /**
     * The Postgres container is shared across test classes, so every fixture is uniquely suffixed
     * and every assertion is scoped to this run's own rows rather than to a global row count.
     */
    @BeforeEach
    void seedData() {
        suffix = "-" + System.nanoTime();
        supplierOrg = seededSupplier();
        careProviderOrg = seededCareProvider();

        home = new Home();
        home.setName("Audit House" + suffix);
        home.setOrganisation(careProviderOrg);
        home = homeRepository.save(home);

        otherHome = new Home();
        otherHome.setName("Other House" + suffix);
        otherHome.setOrganisation(careProviderOrg);
        otherHome = homeRepository.save(otherHome);

        childId = saveChild("Ada", home).getId();
        otherChildId = saveChild("Grace", otherHome).getId();

        userRepository.save(newUser("audit-home" + suffix, Role.HOME_STAFF, home, null));
        userRepository.save(newUser("audit-coordinator" + suffix, Role.COORDINATOR, null, supplierOrg));
        userRepository.save(newUser("audit-visitor" + suffix, Role.VISITOR, null, supplierOrg));
        userRepository.save(newUser("audit-reviewer" + suffix, Role.REVIEWER, null, supplierOrg));
        userRepository.save(newUser("audit-orgadmin" + suffix, Role.ORG_ADMIN, null, supplierOrg));
    }

    private Child saveChild(String firstName, Home childHome) {
        Child child = new Child();
        child.setFirstName(firstName);
        child.setLastName("Audit");
        child.setDateOfBirth(java.time.LocalDate.of(2010, 5, 6));
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

    /** The newest event of this type raised by one of this run's own uniquely-suffixed users. */
    private AuditEvent latestOwn(AuditEventType type) {
        List<AuditEvent> events = auditEventRepository.findByEventTypeOrderByOccurredAtDesc(type);
        return events.stream()
                .filter(event -> event.getActorUsernameAtTime() != null
                        && event.getActorUsernameAtTime().endsWith(suffix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + type + " audit event for this run"));
    }

    @Test
    void fullReportLifecycleIsAuditedWithActorAndOrgStamping() throws Exception {
        // 1. Home staff raises a request
        mockMvc.perform(post("/requests").with(asUser("audit-home" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-16T20:30")
                        .param("notes", "Audit trail test"))
                .andExpect(status().is3xxRedirection());

        Long requestId = interviewRequestRepository.findAllDetailed().stream()
                .filter(r -> r.getChild().getId().equals(childId))
                .findFirst().orElseThrow().getId();

        AuditEvent created = latestOwn(AuditEventType.INTERVIEW_REQUEST_CREATED);
        assertThat(created.getTargetType()).isEqualTo("InterviewRequest");
        assertThat(created.getTargetId()).isEqualTo(requestId);
        assertThat(created.getActorUsernameAtTime()).isEqualTo("audit-home" + suffix);
        assertThat(created.getActorId())
                .isEqualTo(userRepository.findByUsername("audit-home" + suffix).orElseThrow().getId());
        assertThat(created.getActorRolesAtTime()).isEqualTo("HOME_STAFF");
        assertThat(created.getOrganisationId()).isEqualTo(careProviderOrg.getId());
        assertThat(created.getHomeId()).isEqualTo(home.getId());
        assertThat(created.getOccurredAt()).isNotNull();
        assertThat(created.getMetadata()).contains("childId=" + childId).contains("status=REQUESTED");

        // 2. Coordinator allocates and schedules
        Long visitorId = userRepository.findByUsername("audit-visitor" + suffix).orElseThrow().getId();
        mockMvc.perform(post("/coordinator/requests/{id}/allocate", requestId)
                        .with(asUser("audit-coordinator" + suffix)).with(csrf())
                        .param("visitorId", visitorId.toString())
                        .param("scheduledAt", "2026-07-20T14:00"))
                .andExpect(status().is3xxRedirection());

        AuditEvent allocated = latestOwn(AuditEventType.INTERVIEW_REQUEST_ALLOCATED);
        assertThat(allocated.getTargetId()).isEqualTo(requestId);
        assertThat(allocated.getActorUsernameAtTime()).isEqualTo("audit-coordinator" + suffix);
        assertThat(allocated.getActorRolesAtTime()).isEqualTo("COORDINATOR");
        // Scope follows the request's care-provider home, not the supplier-side actor's own org.
        assertThat(allocated.getOrganisationId()).isEqualTo(careProviderOrg.getId());
        assertThat(allocated.getHomeId()).isEqualTo(home.getId());
        assertThat(allocated.getMetadata())
                .contains("visitorId=" + visitorId)
                .contains("statusBefore=REQUESTED")
                .contains("statusAfter=SCHEDULED");

        // 3. Visitor saves a draft, then submits for review
        mockMvc.perform(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("audit-visitor" + suffix)).with(csrf())
                        .param("action", "draft")
                        .param("interviewLocation", "Audit House"))
                .andExpect(status().is3xxRedirection());

        AuditEvent draft = latestOwn(AuditEventType.REPORT_DRAFT_SAVED);
        assertThat(draft.getActorUsernameAtTime()).isEqualTo("audit-visitor" + suffix);
        assertThat(draft.getTargetType()).isEqualTo("InterviewReport");
        assertThat(draft.getMetadata()).contains("requestId=" + requestId).contains("reportStatus=DRAFT");

        mockMvc.perform(reportFields(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("audit-visitor" + suffix)).with(csrf())
                        .param("action", "submit")))
                .andExpect(status().is3xxRedirection());

        AuditEvent submitted = latestOwn(AuditEventType.REPORT_SUBMITTED);
        assertThat(submitted.getActorUsernameAtTime()).isEqualTo("audit-visitor" + suffix);
        assertThat(submitted.getOrganisationId()).isEqualTo(careProviderOrg.getId());
        assertThat(submitted.getMetadata()).contains("reportStatus=SUBMITTED");

        // 4. Reviewer approves - both the approval and the docx generation are recorded
        mockMvc.perform(reportFields(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("audit-reviewer" + suffix)).with(csrf())
                        .param("action", "approve")))
                .andExpect(status().is3xxRedirection());

        AuditEvent approved = latestOwn(AuditEventType.REPORT_APPROVED);
        assertThat(approved.getActorUsernameAtTime()).isEqualTo("audit-reviewer" + suffix);
        assertThat(approved.getActorRolesAtTime()).isEqualTo("REVIEWER");
        assertThat(approved.getMetadata()).contains("reportStatus=APPROVED");

        String generatedFilename = interviewReportRepository.findByInterviewRequestId(requestId)
                .orElseThrow().getGeneratedDocumentPath();
        AuditEvent generated = latestOwn(AuditEventType.DOCX_GENERATED);
        assertThat(generated.getActorUsernameAtTime()).isEqualTo("audit-reviewer" + suffix);
        assertThat(generated.getMetadata()).contains("filename=" + generatedFilename);

        // 5. Home staff downloads it - who *reads* the document is audited too
        mockMvc.perform(get("/reports/{id}/download", requestId).with(asUser("audit-home" + suffix)))
                .andExpect(status().isOk());

        AuditEvent downloaded = latestOwn(AuditEventType.DOCX_DOWNLOADED);
        assertThat(downloaded.getActorUsernameAtTime()).isEqualTo("audit-home" + suffix);
        assertThat(downloaded.getTargetType()).isEqualTo("InterviewReport");
        assertThat(downloaded.getOrganisationId()).isEqualTo(careProviderOrg.getId());
        assertThat(downloaded.getHomeId()).isEqualTo(home.getId());
        assertThat(downloaded.getMetadata()).contains("filename=" + generatedFilename);
    }

    @Test
    void rejectedReportIsAuditedWithoutCopyingTheReviewComments() throws Exception {
        Long requestId = raiseAllocatedRequest();

        mockMvc.perform(reportFields(post("/visitor/interviews/{id}/report", requestId)
                        .with(asUser("audit-visitor" + suffix)).with(csrf())
                        .param("action", "submit")))
                .andExpect(status().is3xxRedirection());

        String sensitiveComment = "Child disclosed something confidential that must not be duplicated";
        mockMvc.perform(reportFields(post("/reviewer/reports/{id}/review", requestId)
                        .with(asUser("audit-reviewer" + suffix)).with(csrf())
                        .param("action", "reject")
                        .param("reviewComments", sensitiveComment)))
                .andExpect(status().is3xxRedirection());

        AuditEvent rejected = latestOwn(AuditEventType.REPORT_REJECTED);
        assertThat(rejected.getActorUsernameAtTime()).isEqualTo("audit-reviewer" + suffix);
        assertThat(rejected.getMetadata()).contains("reportStatus=REJECTED").contains("commentsProvided=true");
        // AUDIT-PLAN.md §B.5: the trail records that a decision happened, never a second copy of
        // what was said about the child.
        assertThat(rejected.getMetadata()).doesNotContain(sensitiveComment);
        assertThat(rejected.getMetadata()).doesNotContain("confidential");
    }

    @Test
    void loginSuccessAndFailureAreAudited() throws Exception {
        String username = "audit-visitor" + suffix;

        mockMvc.perform(post("/login").with(csrf())
                        .param("username", username)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection());

        AuditEvent success = latestOwn(AuditEventType.LOGIN_SUCCESS);
        assertThat(success.getActorUsernameAtTime()).isEqualTo(username);
        assertThat(success.getActorId()).isEqualTo(userRepository.findByUsername(username).orElseThrow().getId());
        assertThat(success.getOrganisationId()).isEqualTo(supplierOrg.getId());

        mockMvc.perform(post("/login").with(csrf())
                        .param("username", username)
                        .param("password", "wrong-password"))
                .andExpect(status().is3xxRedirection());

        AuditEvent failure = latestOwn(AuditEventType.LOGIN_FAILURE);
        assertThat(failure.getActorUsernameAtTime()).isEqualTo(username);
        // Nothing identifies the account beyond the attempted username - and no credential material.
        assertThat(failure.getActorId()).isNull();
        assertThat(failure.getMetadata()).contains("BadCredentialsException");
        assertThat(failure.getMetadata()).doesNotContain("wrong-password").doesNotContain(PASSWORD);
    }

    @Test
    void accessDeniedIsAudited() throws Exception {
        // Home staff at one home reaching for a child at another - denied inside ChildController,
        // which is the programmatic AccessDeniedException path the advice hooks.
        mockMvc.perform(get("/children/{id}", otherChildId).with(asUser("audit-home" + suffix)))
                .andExpect(status().isForbidden());

        AuditEvent denied = latestOwn(AuditEventType.ACCESS_DENIED);
        assertThat(denied.getActorUsernameAtTime()).isEqualTo("audit-home" + suffix);
        assertThat(denied.getActorRolesAtTime()).isEqualTo("HOME_STAFF");
        assertThat(denied.getHomeId()).isEqualTo(home.getId());
        assertThat(denied.getMetadata())
                .contains("method=GET")
                .contains("path=/children/" + otherChildId);
    }

    @Test
    void userCreationAndRoleChangeAreAudited() throws Exception {
        String newUsername = "audit-created" + suffix;

        mockMvc.perform(post("/admin/users").with(asUser("audit-orgadmin" + suffix)).with(csrf())
                        .param("username", newUsername)
                        .param("password", PASSWORD)
                        .param("firstName", "Created")
                        .param("lastName", "By Audit Test")
                        .param("email", "created.by.audit.test@example.test")
                        .param("roles", Role.COORDINATOR.name()))
                .andExpect(status().is3xxRedirection());

        User created = userRepository.findByUsername(newUsername).orElseThrow();
        AuditEvent createdEvent = latestOwn(AuditEventType.USER_CREATED);
        assertThat(createdEvent.getActorUsernameAtTime()).isEqualTo("audit-orgadmin" + suffix);
        assertThat(createdEvent.getTargetType()).isEqualTo("User");
        assertThat(createdEvent.getTargetId()).isEqualTo(created.getId());
        assertThat(createdEvent.getOrganisationId()).isEqualTo(supplierOrg.getId());
        assertThat(createdEvent.getMetadata()).contains("rolesAssigned=COORDINATOR");
        assertThat(createdEvent.getMetadata()).doesNotContain(PASSWORD);

        // Now change their roles and disable them - the transition itself is what gets recorded.
        mockMvc.perform(post("/admin/users/{id}/edit", created.getId())
                        .with(asUser("audit-orgadmin" + suffix)).with(csrf())
                        .param("firstName", "Created")
                        .param("lastName", "By Audit Test")
                        .param("email", "created.by.audit.test@example.test")
                        .param("roles", Role.REVIEWER.name())
                        .param("enabled", "false"))
                .andExpect(status().is3xxRedirection());

        AuditEvent updatedEvent = latestOwn(AuditEventType.USER_UPDATED);
        assertThat(updatedEvent.getTargetId()).isEqualTo(created.getId());
        assertThat(updatedEvent.getMetadata())
                .contains("rolesBefore=COORDINATOR")
                .contains("rolesAfter=REVIEWER")
                .contains("enabledBefore=true")
                .contains("enabledAfter=false")
                .contains("passwordChanged=false");
    }

    @Test
    void auditRowsCannotBeUpdatedOrDeleted() throws Exception {
        mockMvc.perform(get("/children/{id}", otherChildId).with(asUser("audit-home" + suffix)))
                .andExpect(status().isForbidden());
        Long rowId = latestOwn(AuditEventType.ACCESS_DENIED).getId();

        // AUDIT-PLAN.md §B.4: append-only, enforced by the database rather than by convention.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update audit_events set event_type = 'LOGIN_SUCCESS' where id = ?", rowId))
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbcTemplate.update("delete from audit_events where id = ?", rowId))
                .hasMessageContaining("append-only");

        assertThat(auditEventRepository.findById(rowId)).isPresent();
    }

    /** Raises a request and allocates a visitor to it, returning the request id. */
    private Long raiseAllocatedRequest() throws Exception {
        mockMvc.perform(post("/requests").with(asUser("audit-home" + suffix)).with(csrf())
                        .param("childId", childId.toString())
                        .param("returnedAt", "2026-07-16T20:30"))
                .andExpect(status().is3xxRedirection());

        Long requestId = interviewRequestRepository.findAllDetailed().stream()
                .filter(r -> r.getChild().getId().equals(childId))
                .findFirst().orElseThrow().getId();

        Long visitorId = userRepository.findByUsername("audit-visitor" + suffix).orElseThrow().getId();
        mockMvc.perform(post("/coordinator/requests/{id}/allocate", requestId)
                        .with(asUser("audit-coordinator" + suffix)).with(csrf())
                        .param("visitorId", visitorId.toString())
                        .param("scheduledAt", "2026-07-20T14:00"))
                .andExpect(status().is3xxRedirection());
        return requestId;
    }

    /** The report fields the submit/approve paths validate as required. */
    private MockHttpServletRequestBuilder reportFields(MockHttpServletRequestBuilder builder) {
        return builder
                .param("heldAt", "2026-07-20T14:00")
                .param("interviewLocation", "Audit House")
                .param("within72Hours", "true")
                .param("previouslyMissing", "false")
                .param("confidentialityExplained", "true")
                .param("interviewAccepted", "true")
                .param("consideredSelfMissing", "false")
                .param("interviewerComments", "Recorded for audit test")
                .param("recommendations", "No further action")
                .param("conductedByStatement", "Conducted by the allocated visitor");
    }
}
