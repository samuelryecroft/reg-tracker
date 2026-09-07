package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
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
 * T322 follow-up: an address is proven DELIVERABLE by the first code that is used, and an address
 * that is never proven stops receiving codes.
 *
 * <p><b>What this is for, stated so the tests are not read as claiming more.</b> It defends against a
 * <em>mistyped</em> address - a likely event, not an adversary. Without it, a typo means sign-in
 * codes for a children's-services system are posted to whoever owns the mistyped mailbox, once per
 * attempt, indefinitely, while the real user reports only that codes never arrive.
 *
 * <p><b>What it explicitly does NOT do</b>, and there is a test below asserting the limit rather than
 * leaving it to a comment: it does not prove the address belongs to the person whose account it is.
 * A confirmation delivered to an address can always be completed by whoever controls that address.
 */
@SpringBootTest(properties = {
        "app.security.second-factor.enabled=true",
        "app.security.second-factor.transport=capturing",
        "app.security.second-factor.max-unverified-challenges=2"
})
@AutoConfigureMockMvc
class EmailVerifiedOnFirstUseTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    @TestConfiguration
    static class CapturingSenderConfig {
        @Bean
        CapturingSender capturingSender() {
            return new CapturingSender();
        }
    }

    static class CapturingSender implements VerificationCodeSender {
        final List<String> codes = new ArrayList<>();
        final List<String> addresses = new ArrayList<>();

        @Override
        public void send(String emailAddress, String code) {
            addresses.add(emailAddress);
            codes.add(code);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private CapturingSender sender;

    @BeforeEach
    void clearSent() {
        sender.codes.clear();
        sender.addresses.clear();
    }

    private User account() {
        User user = new User();
        user.setUsername("verify-" + System.nanoTime());
        user.setFirstName("Ver");
        user.setLastName("Ify");
        user.setEmail("staff@example.test");
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private MockHttpSession passwordStage(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/login").with(csrf())
                        .param("username", user.getUsername())
                        .param("password", PASSWORD))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String currentCode() {
        assertThat(sender.codes).isNotEmpty();
        return sender.codes.get(sender.codes.size() - 1);
    }

    @Test
    void anAddressStartsUnprovenAndIsVerifiedByTheFirstCodeThatIsUsed() throws Exception {
        User user = account();
        assertThat(user.isEmailVerified())
                .as("a new address has never been proven to receive mail")
                .isFalse();

        MockHttpSession session = passwordStage(user);
        mockMvc.perform(post("/login/verify").with(csrf()).session(session)
                .param("code", currentCode()));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isEmailVerified())
                .as("using a code proves the address receives mail, with no extra step for the user")
                .isTrue();
    }

    /**
     * THE ASSERTION THIS EXISTS FOR. A wrong address must stop receiving codes.
     *
     * <p>The cap is 2 here. Three sign-in attempts must produce two messages, not three - otherwise
     * a stranger receives a sign-in code for a child's record on every attempt, forever, and nothing
     * in the product ever notices.
     */
    @Test
    void anAddressThatIsNeverProvenStopsReceivingCodes() throws Exception {
        User user = account();

        for (int i = 0; i < 3; i++) {
            passwordStage(user);
        }

        assertThat(sender.addresses)
                .as("the unproven address must stop receiving codes once its allowance is spent")
                .hasSize(2);
    }

    /** And the account says so, rather than silently failing to send. */
    @Test
    void theRefusalNamesTheAddressRatherThanTheCredentials() throws Exception {
        User user = account();
        passwordStage(user);
        passwordStage(user);

        MvcResult third = mockMvc.perform(post("/login").with(csrf())
                        .param("username", user.getUsername())
                        .param("password", PASSWORD))
                .andReturn();

        assertThat(third.getResponse().getRedirectedUrl())
                .as("a spent allowance must not be reported as a bad password - it is an address "
                        + "problem and only an administrator can fix it")
                .isEqualTo("/login?error=unverified");
    }

    /**
     * Correcting the address must actually unblock the account.
     *
     * <p>If the allowance were not reset with the address, fixing the typo would leave the account
     * still barred by the old address's exhausted count - and the fix would look like it had not
     * worked, which is how a correct remedy gets abandoned.
     */
    @Test
    void correctingTheAddressRestoresTheAllowance() throws Exception {
        User user = account();
        passwordStage(user);
        passwordStage(user);

        User stored = userRepository.findById(user.getId()).orElseThrow();
        stored.setEmail("corrected@example.test");
        stored.resetEmailVerification();
        userRepository.save(stored);

        sender.addresses.clear();
        passwordStage(stored);

        assertThat(sender.addresses)
                .as("the corrected address gets its own allowance")
                .containsExactly("corrected@example.test");
    }

    /**
     * THE LIMIT, asserted rather than left to a comment.
     *
     * <p>Verification proves the address receives mail. It does NOT prove the address belongs to the
     * person whose account it is: whoever controls the mailbox completes it. This test states that
     * by demonstrating that an address nobody has claimed - one an administrator simply typed - is
     * fully verified by someone reading that mailbox. <b>If this test ever looks like a bug, the
     * mistake is the expectation, not the code:</b> no email-delivered confirmation can distinguish
     * the intended recipient from any other holder of the same mailbox.
     */
    @Test
    void verificationProvesDeliverabilityNotOwnership() throws Exception {
        User user = account();
        MockHttpSession session = passwordStage(user);

        // Whoever is reading that mailbox - not necessarily the account holder - enters the code.
        mockMvc.perform(post("/login/verify").with(csrf()).session(session)
                .param("code", currentCode()));

        assertThat(userRepository.findById(user.getId()).orElseThrow().isEmailVerified())
                .as("the address is now 'verified' on the strength of mailbox access alone; this is "
                        + "the documented limit of the mechanism, not a defect")
                .isTrue();
    }
}
