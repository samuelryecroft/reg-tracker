package ninja.samryecroft.returnhome.tracker.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.TestLogins;
import ninja.samryecroft.returnhome.tracker.audit.AuditEvent;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventRepository;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventType;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T379 (CODE-REVIEW-2026-09-18 P1.2): a case file, or the audit trail, cannot leave the system
 * without the row that says it did.
 *
 * <p>Until T379 the download token was minted first and the audit row written afterwards, in a
 * listener that logs a failed write and moves on - defensible for every other event, and exactly
 * wrong for the two that record a disclosure. Here the audit store refuses the disclosure row and
 * nothing else: the request fails, no token is issued, no disclosure row exists, and the attempt
 * is still on the record as {@code EXPORT_FAILED}, which the listener path writes as before.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ADisclosureIsNotIssuedWithoutItsAuditRowTest extends AbstractIntegrationTest {

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
    private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean
    private AuditEventRepository auditEventRepository;

    private String exporter;
    private Long childId;
    private Long organisationId;

    @BeforeEach
    void seedAnExporterAndAChild() {
        String suffix = "-" + System.nanoTime();
        Organisation org = new Organisation();
        org.setName("T379 Provider" + suffix);
        org.setType(OrgType.CARE_PROVIDER);
        org = organisationRepository.save(org);
        organisationId = org.getId();
        Home home = new Home();
        home.setName("T379 House" + suffix);
        home.setOrganisation(org);
        home = homeRepository.save(home);

        exporter = "t379-disclosure" + suffix;
        User user = new User();
        user.setUsername(exporter);
        user.setEmail(exporter + "@example.test");
        user.setLastName("Exporter");
        user.setRoles(Set.of(Role.ORG_ADMIN));
        user.setHomes(new HashSet<>());
        user.setOrganisation(org);
        user.setEnabled(true);
        user.setCanExport(true);
        user = userRepository.save(user);

        Child child = new Child();
        child.setFirstName("Undisclosed");
        child.setLastName("Child" + suffix);
        child.setDateOfBirth(LocalDate.of(2011, 4, 4));
        child.setHome(home);
        child = childRepository.save(child);
        childId = child.getId();

        // Visibility is derived from a child's interview requests - a child with none is
        // indistinguishable from no such child, by design - so one is needed to be exportable.
        InterviewRequest request = new InterviewRequest();
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(user);
        request.setReturnedAt(LocalDateTime.now().minusHours(4));
        interviewRequestRepository.save(request);
    }

    private RequestPostProcessor asExporter() {
        UserDetails details = appUserDetailsService.loadUserByUsername(TestLogins.loginIdentifier(exporter));
        return securityContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())));
    }

    private void auditStoreRefuses(AuditEventType type) {
        doThrow(new DataAccessResourceFailureException("audit store unavailable"))
                .when(auditEventRepository).save(argThat((AuditEvent event) -> event.getEventType() == type));
    }

    private long rows(AuditEventType type, String where, Object arg) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from audit_events where event_type = ? and " + where, Long.class, type.name(), arg);
        return count == null ? 0 : count;
    }

    @Test
    void aCaseFileIsNotIssuedWhenItsRowCannotBeWritten() {
        auditStoreRefuses(AuditEventType.CASE_FILE_EXPORTED);

        assertThatThrownBy(() -> mockMvc.perform(post("/export/case-file/{id}", childId)
                        .with(asExporter()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"" + ExportPurpose.values()[0].name() + "\"}")))
                .as("the request fails outright - there is no token in any response")
                .hasRootCauseInstanceOf(DataAccessResourceFailureException.class);

        assertThat(rows(AuditEventType.CASE_FILE_EXPORTED, "target_id = ?", childId)).isZero();
        assertThat(rows(AuditEventType.EXPORT_FAILED, "target_id = ?", childId))
                .as("the attempt itself is still on the record, through the ordinary listener path")
                .isEqualTo(1);
    }

    @Test
    void theAuditTrailIsNotIssuedWhenItsRowCannotBeWritten() {
        auditStoreRefuses(AuditEventType.AUDIT_QUERY_EXPORTED);

        assertThatThrownBy(() -> mockMvc.perform(post("/audit/export").with(asExporter()).with(csrf())
                        .param("purpose", ExportPurpose.values()[0].name())))
                .hasRootCauseInstanceOf(DataAccessResourceFailureException.class);

        assertThat(rows(AuditEventType.AUDIT_QUERY_EXPORTED, "organisation_id = ?", organisationId)).isZero();
    }
}
