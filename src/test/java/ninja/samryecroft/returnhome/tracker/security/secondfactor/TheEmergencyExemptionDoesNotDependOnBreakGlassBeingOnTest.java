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
 * The emergency path walked <b>in production's configuration</b> - which is to say, with break-glass
 * OFF.
 *
 * <p><b>This test exists because its absence cost an incident (T339).</b> The second factor was
 * switched on and the bootstrap admin - the one account that was never supposed to be locked out -
 * was refused at sign-in. {@code isEmergencyExempt} short-circuited to false unless
 * {@code app.auth.break-glass.enabled} was true, and production ships it false, so the exemption was
 * never reached and the username was never even compared.
 *
 * <p><b>Why {@link BreakGlassSecondFactorExemptionTest} did not catch it, which is the part worth
 * remembering.</b> That test sets {@code app.auth.break-glass.enabled=true} in its own fixture - the
 * single variable production leaves false. It is not wrong about its own claim: it proves the
 * exemption works <em>when break-glass is already on</em>, which was never in doubt. But the claim
 * everyone read off it - "the emergency admin can always get in when mail is broken" - had never been
 * executed by anything. <b>A fixture that pins the deciding variable to the safe value is not a test
 * of the dangerous case; it is a restatement of the assumption.</b>
 *
 * <p>So the one property this file must never acquire is that fixture's flag. If a future change
 * makes this test need {@code break-glass.enabled=true} to pass, the defect is back and this file has
 * stopped being able to see it.
 */
@SpringBootTest(properties = {
        "app.security.second-factor.enabled=true",
        "app.security.second-factor.transport=unavailable",
        // FALSE, and deliberately stated rather than left to the default - this single line is the
        // entire difference between this test and the one that passed throughout the defect's life.
        "app.auth.break-glass.enabled=false",
        "app.admin.username=locked-out-admin",
        "app.admin.password=emergency-access-password"
})
@AutoConfigureMockMvc
class TheEmergencyExemptionDoesNotDependOnBreakGlassBeingOnTest extends AbstractIntegrationTest {

    private static final String ADMIN_USERNAME = "locked-out-admin";
    private static final String ADMIN_PASSWORD = "emergency-access-password";
    private static final String ORDINARY_PASSWORD = "correct-horse-battery-staple";

    /** Mail is down, for everybody, on every call. */
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
     * The measurement that never existed: the bootstrap admin reaches an authenticated session with
     * the factor ON, mail DOWN, and break-glass OFF.
     *
     * <p>The seeded admin has no email address - {@code AdminUserSeeder} sets a username, a password
     * and a last name, and deliberately no mailbox, because this is the one account exempt from the
     * per-person-address requirement. So this is the real account in the real shape, not a stand-in
     * that happens to carry an address the production row does not have.
     */
    @Test
    void theBootstrapAdminGetsInWithBreakGlassOff() throws Exception {
        MvcResult landed = signIn(ADMIN_USERNAME, ADMIN_PASSWORD);

        assertThat(landed.getResponse().getRedirectedUrl())
                .as("the emergency account must get in when the factor's own channel is broken, "
                        + "WITHOUT anyone having armed break-glass first. If this fails, the fire "
                        + "exit needs unlocking before the fire and there is no way back in")
                .isEqualTo("/admin/users");
    }

    /**
     * The control, and this test is worth little without it.
     *
     * <p>Everyone else is still refused, in the same context, with the same outage. Without this, the
     * assertion above would pass just as happily if the second factor were switched off altogether -
     * and "the admin got in" would be evidence of nothing.
     */
    @Test
    void everyoneElseIsStillRefusedInTheSameContext() throws Exception {
        User ordinary = new User();
        ordinary.setUsername("ordinary-" + System.nanoTime());
        ordinary.setLastName("Ordinary");
        ordinary.setPassword(passwordEncoder.encode(ORDINARY_PASSWORD));
        ordinary.setEmail("ordinary@example.test");
        ordinary.setEnabled(true);
        ordinary.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        userRepository.save(ordinary);

        MvcResult landed = signIn(ordinary.getUsername(), ORDINARY_PASSWORD);

        assertThat(landed.getResponse().getRedirectedUrl())
                .as("the factor must genuinely be ON in this context - otherwise the admin's "
                        + "sign-in above proves nothing at all")
                .isNotEqualTo("/admin/users");
    }
}
