package ninja.samryecroft.returnhome.tracker.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Failed-login throttling (BUG-REVIEW "no login rate limiting").
 *
 * <p>Uses a deliberately small threshold so the test states the rule rather than grinding through
 * the production default.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.security.login-throttle.enabled=true",
        "app.security.login-throttle.max-attempts=3",
        "app.security.login-throttle.lockout-duration=15m"
})
class LoginThrottlingIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private LoginAttemptService loginAttemptService;

    private String username;

    @BeforeEach
    void seedUser() {
        username = "throttle-" + System.nanoTime();
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setFullName("Throttle Test User");
        user.setRoles(Set.of(Role.VISITOR));
        user.setEnabled(true);
        userRepository.save(user);
    }

    private void attemptLogin(String user, String password) throws Exception {
        mockMvc.perform(post("/login").with(csrf())
                        .param("username", user)
                        .param("password", password))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void accountLocksOutAfterTooManyFailedAttemptsAndRefusesTheCorrectPassword() throws Exception {
        assertThat(loginAttemptService.isLocked(username)).isFalse();

        for (int attempt = 1; attempt <= 3; attempt++) {
            attemptLogin(username, "wrong-password-" + attempt);
        }

        assertThat(loginAttemptService.isLocked(username)).isTrue();

        // The point of a lockout: even the RIGHT password is refused while it holds, so guessing
        // cannot simply continue at speed.
        mockMvc.perform(post("/login").with(csrf())
                        .param("username", username)
                        .param("password", PASSWORD))
                .andExpect(redirectedUrl("/login?error"));
    }

    @Test
    void attemptsBelowTheThresholdDoNotLockAndSuccessClearsTheCount() throws Exception {
        attemptLogin(username, "wrong-password");
        attemptLogin(username, "wrong-password");
        assertThat(loginAttemptService.isLocked(username)).isFalse();

        // A correct password still works, and wipes the slate...
        mockMvc.perform(post("/login").with(csrf())
                        .param("username", username)
                        .param("password", PASSWORD))
                .andExpect(redirectedUrl("/"));
        assertThat(loginAttemptService.isLocked(username)).isFalse();

        // ...so the next two failures start from zero rather than tipping straight over.
        attemptLogin(username, "wrong-password");
        attemptLogin(username, "wrong-password");
        assertThat(loginAttemptService.isLocked(username)).isFalse();
    }

    @Test
    void lockoutIsPerUsernameAndCaseInsensitive() throws Exception {
        String other = "other-" + System.nanoTime();
        for (int attempt = 1; attempt <= 3; attempt++) {
            attemptLogin(username.toUpperCase(java.util.Locale.ROOT), "wrong-password");
        }

        // Varying capitalisation must not buy an attacker a fresh set of attempts.
        assertThat(loginAttemptService.isLocked(username)).isTrue();
        // ...and one locked account must not affect anybody else's.
        assertThat(loginAttemptService.isLocked(other)).isFalse();
    }
}
