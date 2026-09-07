package ninja.samryecroft.returnhome.tracker.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.child.NameRevealService;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.security.LoginAttemptService;
import ninja.samryecroft.returnhome.tracker.security.LoginFailureHandler;
import ninja.samryecroft.returnhome.tracker.security.secondfactor.SecondFactorPolicy;
import ninja.samryecroft.returnhome.tracker.security.secondfactor.SecondFactorService;
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

    /**
     * T215's failure handler. SecurityConfig now requires it, and this slice imports SecurityConfig
     * without component-scanning, so without a bean here the whole context fails to start and every
     * test in the class errors before asserting anything. Mocked rather than imported: the real one
     * needs LoginAttemptService and AppProperties, and this class is about which routes each role
     * may reach, not about what a failed sign-in renders (that is LoginFailureHandlerTest and
     * LoginLockoutIntegrationTest).
     */
    @MockitoBean
    private LoginFailureHandler loginFailureHandler;

    /**
     * T322. {@code SecurityConfig} builds {@code SecondFactorSuccessHandler} itself and needs both
     * of these to do it - the same "a slice with no bean" shape T215 and T221 hit, and for the same
     * reason: this slice imports {@code SecurityConfig} without component-scanning.
     *
     * <p>Mocked rather than imported, and the mock's behaviour is exactly right for this class: a
     * Mockito {@code SecondFactorPolicy} returns {@code false} from {@code isRequiredFor}, so every
     * test here signs in without a second factor. That is what these tests are about - which routes
     * each role may reach once authenticated - and the gate itself is asserted in
     * {@code SecondFactorGateIntegrationTest} against a real policy and a real database.
     */
    @MockitoBean
    private SecondFactorService secondFactorService;

    @MockitoBean
    private SecondFactorPolicy secondFactorPolicy;

    /**
     * T221. {@code SecurityConfig} builds {@code LockedAccountFilter} itself and needs this to do
     * it, so without a bean here the whole context fails to start and every test in the class errors
     * before asserting anything - the same "a slice with no bean" shape T215 hit.
     *
     * <p><b>The SERVICE is mocked, never the filter.</b> The filter is a link in the chain: a mock of
     * it would be a {@code doFilter} that does nothing and passes nothing on, so every route
     * assertion here would be testing a chain that silently swallows requests. A mock is safe for a
     * collaborator and unsafe for a chain link.
     *
     * <p>The default {@code isLocked} is {@code false}, so the real filter passes everything through
     * and this class keeps testing what it is about - which routes each role may reach. Lockout
     * behaviour belongs to {@code LockedAccountFilterTest} and {@code LockedAccountTimingGuardTest}.
     */
    @MockitoBean
    private LoginAttemptService loginAttemptService;

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
}
