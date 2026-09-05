package ninja.samryecroft.returnhome.tracker.security;

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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T215 / D-4c-1: a real locked account and an unknown-but-locked username must be
 * <b>indistinguishable</b>.
 *
 * <p>This is the assertion god called the guard on the whole card, and the reason is that the
 * defect it prevents is invisible by construction. A handler that selects the message by exception
 * type passes every manual test in which the tester types a username they know - and shows the
 * locked banner only for accounts that exist, turning a security fix into a username enumeration
 * oracle. <b>Nothing about the page looks wrong when it is wrong.</b>
 *
 * <p><b>The comparison is of OBSERVABLES, not of the body alone.</b> Fixing the body and forgetting
 * the rest is the usual failure: status, {@code Location} and the rendered page are all compared,
 * because any one of them differing is enough to answer "does this account exist?".
 */
@SpringBootTest
@AutoConfigureMockMvc
class LoginLockoutIntegrationTest extends AbstractIntegrationTest {

    private static final int MAX_ATTEMPTS = 5;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final String suffix = "-" + System.nanoTime();

    private String realUser() {
        String username = "t215-real" + suffix;
        User user = new User();
        user.setUsername(username);
        user.setFirstName("Rea");
        user.setLastName("List");
        user.setPassword(passwordEncoder.encode("correct-horse-battery"));
        user.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        user.setEnabled(true);
        userRepository.save(user);
        return username;
    }

    private MockHttpServletResponse attempt(String username) throws Exception {
        return mockMvc.perform(post("/login").with(csrf())
                        .param("username", username)
                        .param("password", "definitely-not-the-password"))
                .andReturn().getResponse();
    }

    /** Fails enough times to trip the lock, then returns the response to ONE further attempt. */
    private MockHttpServletResponse attemptAfterLocking(String username) throws Exception {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            attempt(username);
        }
        return attempt(username);
    }

    private String pageAt(String location) throws Exception {
        return mockMvc.perform(get(location)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void aLockedRealAccountAndALockedUnknownUsernameAreIndistinguishable() throws Exception {
        MockHttpServletResponse real = attemptAfterLocking(realUser());
        MockHttpServletResponse unknown = attemptAfterLocking("t215-ghost" + suffix);

        assertThat(unknown.getStatus())
                .as("status must not depend on whether the account exists")
                .isEqualTo(real.getStatus());
        assertThat(unknown.getRedirectedUrl())
                .as("an unknown username never reaches Spring's lock check, so it fails with "
                        + "BadCredentialsException while a real locked account fails with "
                        + "LockedException. If the handler reads the exception rather than asking "
                        + "LoginAttemptService, THESE TWO DIVERGE HERE - and the divergence is the "
                        + "disclosure, whatever the wording of either page says")
                .isEqualTo(real.getRedirectedUrl());

        assertThat(pageAt(unknown.getRedirectedUrl()))
                .as("and the pages they land on must be the same page")
                .isEqualTo(pageAt(real.getRedirectedUrl()));
    }

    @Test
    void aLockedAccountIsToldSignInIsPausedRatherThanToCheckItsPassword() throws Exception {
        MockHttpServletResponse locked = attemptAfterLocking(realUser());
        String page = pageAt(locked.getRedirectedUrl());

        // The defect the card exists to remove: the reader was told to do the one thing that
        // cannot work, on every attempt, for the whole window.
        assertThat(page).contains("Too many sign-in attempts");
        assertThat(page)
                .as("the generic banner must not also render - ?error=locked satisfies "
                        + "${param.error} too, so without an exclusive test the reader is told to "
                        + "try again directly beneath being told that trying again is paused")
                .doesNotContain("Check your username and password and try again.");

        // Creed: naming the remaining time lets an attacker schedule.
        assertThat(page).doesNotContain("15 min").doesNotContain("minutes");
    }

    @Test
    void anOrdinaryWrongPasswordStillGetsTheGenericBanner() throws Exception {
        MockHttpServletResponse response = attempt(realUser());
        String page = pageAt(response.getRedirectedUrl());

        assertThat(page).contains("Check your username and password and try again.");
        assertThat(page).doesNotContain("Too many sign-in attempts");
    }
}
