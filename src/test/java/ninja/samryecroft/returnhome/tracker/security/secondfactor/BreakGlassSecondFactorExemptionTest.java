package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The emergency path, walked rather than assumed.
 *
 * <p><b>Why this test exists at all.</b> The second factor is delivered by email, which makes mail a
 * single point of failure for every sign-in in the product - and a mail outage is exactly the kind of
 * incident break-glass is for. The bootstrap admin's exemption is the only way back in when the
 * factor's own delivery channel is what is broken. <b>An emergency route that has never been walked
 * is a measurement that cannot fail</b>, so this signs in with the sender throwing on every call.
 *
 * <p><b>The second test is the one that keeps the exemption honest.</b> Break-glass is not a distinct
 * account - any local sign-in during the emergency window is treated as break-glass - so the
 * dangerous implementation hangs the exemption on the flag and silently drops the second factor for
 * <em>everyone</em> who signs in while it is open. Both tests run with the flag ON and mail DOWN, and
 * they must come out differently.
 */
@SpringBootTest(properties = {
        "app.security.second-factor.enabled=true",
        "app.security.second-factor.transport=unavailable",
        "app.auth.break-glass.enabled=true",
        "app.admin.username=breakglass-admin",
        "app.admin.password=emergency-access-password"
})
@AutoConfigureMockMvc
class BreakGlassSecondFactorExemptionTest extends AbstractIntegrationTest {

    private static final String ADMIN_USERNAME = "breakglass-admin";
    private static final String ADMIN_PASSWORD = "emergency-access-password";
    private static final String ORDINARY_PASSWORD = "correct-horse-battery-staple";

    /** Mail is down. Every attempt to deliver a code fails, for everybody. */
    @TestConfiguration
    static class UnavailableSenderConfig {
        @Bean
        VerificationCodeSender unavailableSender() {
            return (emailAddress, code) -> {
                throw new VerificationCodeDeliveryException(
                        "simulated mail outage", new IllegalStateException("no transport"));
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private MvcResult signIn(String username, String password) throws Exception {
        MvcResult login = mockMvc.perform(post("/login").with(csrf())
                        .param("username", username)
                        .param("password", password))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        return mockMvc.perform(get("/").session(session)).andReturn();
    }

    /**
     * The bootstrap admin gets in with mail unavailable. This is the whole point of the exemption:
     * if this fails, a mail outage is unrecoverable and nobody can restore mail.
     */
    @Test
    void theBootstrapAdminCanSignInWhileMailIsDown() throws Exception {
        MvcResult landed = signIn(ADMIN_USERNAME, ADMIN_PASSWORD);

        assertThat(landed.getResponse().getRedirectedUrl())
                .as("the emergency account must get in when the factor's own channel is broken")
                .isEqualTo("/admin/users");
    }

    /**
     * Everybody else is still refused - during the same emergency window, with the same outage.
     *
     * <p>This is the assertion that stops the exemption widening into "break-glass turns the second
     * factor off". Without it, the implementation that drops the factor for every account during an
     * emergency passes the test above and looks finished.
     */
    @Test
    void anOrdinaryAccountIsStillRefusedDuringTheSameEmergency() throws Exception {
        User user = new User();
        user.setUsername("ordinary-" + System.nanoTime());
        user.setFirstName("Ord");
        user.setLastName("Inary");
        user.setEmail("ordinary@example.test");
        user.setPassword(passwordEncoder.encode(ORDINARY_PASSWORD));
        user.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        user.setEnabled(true);
        userRepository.save(user);

        MvcResult landed = signIn(user.getUsername(), ORDINARY_PASSWORD);

        assertThat(landed.getResponse().getRedirectedUrl())
                .as("break-glass being open must not drop the second factor for everyone else")
                .contains("/login");
    }
}
