package ninja.samryecroft.returnhome.tracker.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.TestLogins;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T379 (CODE-REVIEW-2026-09-18 P1.2): the operator's free-text reference is bounded at every entry
 * point, and both forms say so with the same number.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExportReferenceIsBoundedTest extends AbstractIntegrationTest {

    private static final String TOO_LONG = "x".repeat(ExportReference.MAX_LENGTH + 1);

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

    private String exporter;
    private Long childId;

    @BeforeEach
    void seedAnExporterAndAChild() {
        String suffix = "-" + System.nanoTime();
        Organisation org = new Organisation();
        org.setName("T379 Provider" + suffix);
        org.setType(OrgType.CARE_PROVIDER);
        org = organisationRepository.save(org);
        Home home = new Home();
        home.setName("T379 House" + suffix);
        home.setOrganisation(org);
        home = homeRepository.save(home);

        exporter = "t379-orgadmin" + suffix;
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
        child.setFirstName("Ref");
        child.setLastName("Bound" + suffix);
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

    @Test
    void theCaseFileEndpointRefusesAnOverlongReferenceBeforeAnythingIsProduced() throws Exception {
        MvcResult result = mockMvc.perform(post("/export/case-file/{id}", childId).with(asExporter()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"" + ExportPurpose.values()[0].name()
                                + "\",\"reference\":\"" + TOO_LONG + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains(String.valueOf(ExportReference.MAX_LENGTH));
    }

    @Test
    void theCaseFilePageShowsTheRefusalAsAFormError() throws Exception {
        MvcResult result = mockMvc.perform(post("/children/{id}/export", childId).with(asExporter()).with(csrf())
                        .param("purpose", ExportPurpose.values()[0].name())
                        .param("reference", TOO_LONG))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .as("the form comes back with the reason, not an export")
                .contains(ExportReference.tooLongMessage());
    }

    @Test
    void theAuditCsvExportRefusesAnOverlongReference() throws Exception {
        MvcResult result = mockMvc.perform(post("/audit/export").with(asExporter()).with(csrf())
                        .param("purpose", ExportPurpose.values()[0].name())
                        .param("reference", TOO_LONG))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    /** The number in the templates is the number in the code, or the browser and the server disagree. */
    @Test
    void bothFormsCarryTheSameBoundAsMaxlength() throws Exception {
        for (String template : new String[] {"audit/feed.html", "export/case-file-form.html"}) {
            String markup = Files.readString(Path.of("src/main/resources/templates", template), StandardCharsets.UTF_8);
            assertThat(markup)
                    .as("%s bounds the reference input to ExportReference.MAX_LENGTH", template)
                    .contains("name=\"reference\" type=\"text\" maxlength=\"" + ExportReference.MAX_LENGTH + "\"");
        }
    }
}
