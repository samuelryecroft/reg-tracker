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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * T380: the verify route refuses a submission over the account cap BEFORE the code is checked,
 * ends the pending sign-in, and audits the refusal. The per-challenge cap (5) is deliberately left
 * above the per-account cap here (3), so what trips is the account bound and nothing else.
 */
@SpringBootTest(properties = {
        "app.security.second-factor.enabled=true",
        "app.security.second-factor.max-verifies-per-user=3",
        "app.security.second-factor.transport=log"
})
@AutoConfigureMockMvc
class SecondFactorVerifyRouteIsThrottledTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aFourthSubmissionForOneAccountIsRefusedAndEndsThePendingSignIn() throws Exception {
        String suffix = "-" + System.nanoTime();
        User user = new User();
        user.setUsername("t380-route" + suffix);
        user.setFirstName("Throttled");
        user.setLastName("Account");
        user.setEmail("t380-route" + suffix + "@example.test");
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        user.setEnabled(true);
        user = userRepository.save(user);

        MvcResult login = mockMvc.perform(post("/login").with(csrf())
                        .param("username", user.getEmail())
                        .param("password", PASSWORD))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertThat(session).as("the password stage leaves a pending session").isNotNull();

        for (int i = 1; i <= 3; i++) {
            MvcResult wrong = mockMvc.perform(post("/login/verify").with(csrf()).session(session)
                            .param("code", "000000"))
                    .andReturn();
            assertThat(wrong.getResponse().getStatus())
                    .as("wrong code %d of 3 re-renders the form; the challenge still has attempts", i)
                    .isEqualTo(200);
        }

        MvcResult fourth = mockMvc.perform(post("/login/verify").with(csrf()).session(session)
                        .param("code", "000000"))
                .andReturn();
        assertThat(fourth.getResponse().getStatus()).isEqualTo(302);
        assertThat(fourth.getResponse().getRedirectedUrl())
                .as("refused the way a burned code is, before the code was looked at")
                .isEqualTo("/login?error=codeburned");

        MvcResult afterwards = mockMvc.perform(get("/login/verify").session(session)).andReturn();
        assertThat(afterwards.getResponse().getRedirectedUrl())
                .as("the pending sign-in is over; the person starts again from the password")
                .isEqualTo("/login");

        Long throttled = jdbcTemplate.queryForObject(
                "select count(*) from audit_events where event_type = 'MFA_FAILURE' "
                        + "and actor_id = ? and metadata like '%verify-throttled%'",
                Long.class, user.getId());
        assertThat(throttled).as("the refusal is on the record with its own reason").isEqualTo(1L);
    }
}
