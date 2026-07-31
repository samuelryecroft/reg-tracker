package ninja.samryecroft.returnhome.tracker.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ninja.samryecroft.returnhome.tracker.theme.ThemeService;
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
