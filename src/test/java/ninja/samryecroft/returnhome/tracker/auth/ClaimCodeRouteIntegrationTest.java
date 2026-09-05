package ninja.samryecroft.returnhome.tracker.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T197 §6a, at the route level: <b>the pre-redemption state must not be able to reach any
 * application route.</b>
 *
 * <p>The unit control next door proves no principal is created. This proves the other half - that
 * holding the session which carries the pending {@code oid} buys nothing. It is the assertion that
 * would fail if somebody later "simplified" the flow by authenticating first, which is the
 * implementation the design warned would pass every functional test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClaimCodeRouteIntegrationTest extends AbstractIntegrationTest {

    private static final String OID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @Autowired
    private MockMvc mockMvc;

    /**
     * Matches both the bare {@code /login} and an absolute one.
     *
     * <p>Written as a matcher rather than {@code redirectedUrlPattern("**/login")}, which was the
     * first attempt and does not match {@code /login} at all - the Ant {@code **}{@code /} requires a
     * preceding segment. That failed in CI on the assertion's syntax while the behaviour under test
     * was correct all along.
     */
    private static org.springframework.test.web.servlet.ResultMatcher redirectsToLogin() {
        return result -> assertThat(result.getResponse().getRedirectedUrl())
                .as("an unauthenticated route must send the caller to sign in")
                .isNotNull()
                .endsWith("/login");
    }

    /** A session in exactly the state the failure handler leaves behind. */
    private MockHttpSession pendingRedemption() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(ClaimCodeController.OBJECT_ID_ATTRIBUTE, OID);
        return session;
    }

    @Test
    void thePendingSessionCannotReachAnyApplicationRoute() throws Exception {
        for (String route : java.util.List.of("/", "/children", "/admin/users", "/visitor/interviews",
                "/coordinator/requests", "/reports/1/view")) {
            mockMvc.perform(get(route).session(pendingRedemption()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectsToLogin());
        }
    }

    /** The screen it CAN reach, so the test above is about authority and not about everything 302ing. */
    @Test
    void thePendingSessionCanReachTheClaimCodeScreen() throws Exception {
        mockMvc.perform(get("/onboarding/claim").session(pendingRedemption()))
                .andExpect(status().isOk());
    }

    /**
     * And without a completed sign-in there is no identity to pin anything to, so the screen is not
     * a way in for anyone who simply navigates to it.
     */
    @Test
    void theClaimScreenIsNotReachableWithoutACompletedSignIn() throws Exception {
        mockMvc.perform(get("/onboarding/claim"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectsToLogin());

        mockMvc.perform(post("/onboarding/claim").with(
                        org.springframework.security.test.web.servlet.request
                                .SecurityMockMvcRequestPostProcessors.csrf())
                        .param("code", "ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZ"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectsToLogin());
    }
}
