package ninja.samryecroft.returnhome.tracker.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.config.AdminUserSeeder;
import ninja.samryecroft.returnhome.tracker.security.passwordreset.PasswordResetTokenRepository;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * T364 stage 2: the break-glass account authenticates by its reserved-domain email like every other
 * account, and the unauthenticated reset door still refuses it.
 *
 * <p><b>The sign-in is the new capability.</b> Before this change the emergency account had no
 * address and could be resolved only by its username; now it carries
 * {@link AdminUserSeeder#BREAK_GLASS_EMAIL} (a {@code .invalid} address that can never resolve or be
 * registered) and signs in through the ordinary email lookup. This test drives that path, so the
 * capability is walked rather than assumed - the same reason {@code BreakGlassSecondFactorExemptionTest}
 * exists for the exemption.
 *
 * <p><b>The reset refusal is the hazard the address introduces.</b> Giving the account an address is
 * only safe because the forgot-password door still refuses it: {@code findResettableByEmail} carries
 * {@code and u.breakGlass = false}, so the emergency credential can never be reset through an
 * unauthenticated form. That clause pre-dates this change and exists precisely for the account that
 * now has an address, so this locks it down.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BreakGlassSignsInWithItsReservedEmailTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "emergency-access-password";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    private User seedBreakGlass() {
        User admin = new User();
        admin.setUsername("breakglass-" + System.nanoTime());
        admin.setEmail(AdminUserSeeder.BREAK_GLASS_EMAIL);
        admin.setFirstName("System");
        admin.setLastName("Administrator");
        admin.setBreakGlass(true);
        admin.setPassword(passwordEncoder.encode(PASSWORD));
        admin.setRoles(Set.of(Role.ADMIN));
        admin.setEnabled(true);
        return userRepository.save(admin);
    }

    @Test
    void theBreakGlassAccountSignsInWithItsReservedEmail() throws Exception {
        seedBreakGlass();

        MvcResult login = mockMvc.perform(post("/login").with(csrf())
                        .param("username", AdminUserSeeder.BREAK_GLASS_EMAIL)
                        .param("password", PASSWORD))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        MvcResult landed = mockMvc.perform(get("/").session(session)).andReturn();

        assertThat(landed.getResponse().getRedirectedUrl())
                .as("break-glass must sign in by its reserved email - the whole point of re-homing it")
                .isEqualTo("/admin/users");
    }

    @Test
    void theResetDoorRefusesTheBreakGlassAddress() throws Exception {
        seedBreakGlass();

        // The guard itself: the reset lookup does not return the break-glass row even though it now
        // has an address. Removing "and u.breakGlass = false" from findResettableByEmail turns this
        // red - which is the arming for this assertion.
        assertThat(userRepository.findResettableByEmail(AdminUserSeeder.BREAK_GLASS_EMAIL))
                .as("the emergency credential must never be resettable through the unauthenticated door")
                .isEmpty();

        // And driven through the endpoint: the neutral 'sent' page is returned (no enumeration), and
        // nothing is minted for the break-glass account.
        mockMvc.perform(post("/forgot-password").with(csrf())
                        .param("email", AdminUserSeeder.BREAK_GLASS_EMAIL))
                .andReturn();

        assertThat(passwordResetTokenRepository.count())
                .as("no reset token may be minted for the break-glass address")
                .isZero();
    }
}
