package ninja.samryecroft.returnhome.tracker.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.child.NameRevealService;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.theme.ThemeService;
import ninja.samryecroft.returnhome.tracker.user.RoleMatrix;
import ninja.samryecroft.returnhome.tracker.web.LoginController;
import ninja.samryecroft.returnhome.tracker.web.RootController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {RootController.class, LoginController.class})
@Import(SecurityConfig.class)
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ThemeService themeService;

    // GlobalControllerAdvice exposes the role matrix to every page, so the slice needs it too.
    // Mocked rather than imported: this test is about the filter chain's path rules, and a real
    // matrix would quietly make it depend on role-to-capability mapping as well.
    @MockitoBean
    private RoleMatrix roleMatrix;

    // GlobalControllerAdvice is picked up by the @WebMvcTest slice and now depends on this.
    @MockitoBean
    private AuditEventPublisher auditEventPublisher;

    // T119: GlobalControllerAdvice's shellOrg() resolves the sidebar org box off these.
    @MockitoBean
    private OrganisationAccessService organisationAccessService;

    @MockitoBean
    private HomeRepository homeRepository;

    // T138 1c: GlobalControllerAdvice's namesRevealed() model attribute resolves this.
    @MockitoBean
    private NameRevealService nameRevealService;

    @Test
    void anonymousRequestToRootRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/").with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void loginPageIsPubliclyAccessible() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "HOME_STAFF")
    void homeStaffCannotAccessCoordinatorArea() throws Exception {
        mockMvc.perform(get("/coordinator/requests"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "HOME_STAFF")
    void homeStaffCannotAccessAdminArea() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "COORDINATOR")
    void coordinatorCannotAccessAdminArea() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminCanReachAuthorizedRoutes() throws Exception {
        // No controller for /admin/users is loaded in this slice, so a successful pass through the
        // authorization filter surfaces as 404 (no handler), not 403 (denied) or a login redirect.
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isNotFound());
    }

    @Test
    void entraEnabledWithoutAClientRegistrationRefusesToStart() {
        // Deliberately not a silent fall back to form login. A deployment that asked for Entra and
        // did not get it would otherwise start and look healthy, so the misconfiguration would be
        // reported by whoever could not sign in - the worst possible channel for a front door.
        // Asserted on the guard directly: booting a knowingly broken application proves the same
        // thing far more slowly.
        assertThatThrownBy(() -> SecurityConfig.requireClientRegistrations(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.auth.entra.enabled is true")
                .hasMessageContaining("'entra' profile");
    }
}
