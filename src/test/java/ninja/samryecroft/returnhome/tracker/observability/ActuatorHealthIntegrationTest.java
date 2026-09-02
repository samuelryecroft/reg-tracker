package ninja.samryecroft.returnhome.tracker.observability;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * WS-C / M4. Verifies the App Service probe contract: the health endpoint and its liveness/readiness
 * groups are reachable UNAUTHENTICATED (probes carry no credentials) and report UP, that anonymous
 * callers see status only - not the component breakdown (show-details=when-authorized) - and that
 * every other actuator endpoint stays locked down.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorHealthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthIsPubliclyUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":\"UP\"")));
    }

    @Test
    void livenessProbeIsPubliclyUp() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":\"UP\"")));
    }

    @Test
    void readinessProbeIsPubliclyUp() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":\"UP\"")));
    }

    @Test
    void anonymousHealthHidesComponentDetails() throws Exception {
        // show-details=when-authorized: an unauthenticated probe must not leak the component
        // breakdown (e.g. the "db" indicator), only the rolled-up status.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("\"components\""))));
    }

    @Test
    void otherActuatorEndpointsAreNotPublicallyReadable() throws Exception {
        // info is exposed but ADMIN-only; an anonymous caller is redirected to login, never served
        // the payload.
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().is3xxRedirection());
    }
}
