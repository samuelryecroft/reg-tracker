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
import ninja.samryecroft.returnhome.tracker.audit.AuditEventType;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * T322: the second factor is a GATE, not a screen.
 *
 * <p><b>The first test is the one this class exists for.</b> The tempting implementation of a second
 * factor leaves the session half-authenticated between the password and the code, and relies on
 * protected URLs requiring a real role. Measured against the actual rules, that is unsafe here:
 * {@code SecurityConfig} maps {@code /interview-requests/**}, {@code /reports/**} and
 * {@code anyRequest()} to plain {@code authenticated()}, and those are the core child data. A
 * half-authenticated session satisfies all three.
 *
 * <p>So the implementation removes the authentication entirely until the code is accepted, and this
 * test is what holds that decision in place. <b>It would pass just as happily against the unsafe
 * design if the assertion were "the verify page is shown" - which is why it asserts on the
 * PROTECTED URLS instead.</b> What is being tested is not that the user is sent to a code box; it is
 * that the data is unreachable while they are standing at it.
 */
@SpringBootTest(properties = {
        "app.security.second-factor.enabled=true",
        "app.security.second-factor.max-attempts=3",
        // Stands the logging fallback down so this class's capturing sender is the only
        // VerificationCodeSender in the context. Without it there are two, and the failure is an
        // ambiguous-dependency context error rather than anything about the second factor.
        "app.security.second-factor.transport=capturing"
})
@AutoConfigureMockMvc
class SecondFactorGateIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    /**
     * Captures the code instead of mailing it. Registered as a bean so the real
     * {@code @ConditionalOnMissingBean} fallback stands down - the same seam a production sender
     * will use, exercised here rather than mocked around.
     */
    @TestConfiguration
    static class CapturingSenderConfig {
        @Bean
        CapturingSender capturingSender() {
            return new CapturingSender();
        }
    }

    static class CapturingSender implements VerificationCodeSender {
        final List<String> codes = new ArrayList<>();
        String lastAddress;

        @Override
        public void send(String emailAddress, String code) {
            this.lastAddress = emailAddress;
            this.codes.add(code);
        }
    }

    /**
     * The sender is a singleton bean, so its list survives from one test to the next. Clearing it is
     * not tidiness: without this, {@code codes.get(0)} silently means "the first code any test in
     * this class ever sent", and a test asserting a successful sign-in submits a stale code and
     * fails for a reason that has nothing to do with what it is testing.
     */
    @BeforeEach
    void clearSentCodes() {
        sender.codes.clear();
    }

    /** Always the code for the sign-in in progress, never an earlier one. */
    private String currentCode() {
        assertThat(sender.codes).as("a code should have been sent").isNotEmpty();
        return sender.codes.get(sender.codes.size() - 1);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private CapturingSender sender;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String suffix = "-" + System.nanoTime();

    private User account(String email) {
        User user = new User();
        user.setUsername("t322" + suffix);
        user.setFirstName("Two");
        user.setLastName("Factor");
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        user.setEnabled(true);
        return userRepository.save(user);
    }

    /** Signs in with the correct password and returns the resulting half-finished session. */
    private MockHttpSession passwordStage(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/login").with(csrf())
                        .param("username", user.getUsername())
                        .param("password", PASSWORD))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private long auditCount(AuditEventType type) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from audit_events where event_type = ?", Long.class, type.name());
        return count == null ? 0 : count;
    }

    /**
     * THE ASSERTION THAT MATTERS. A correct password alone must not reach child data.
     *
     * <p>Both URLs are checked rather than one, because they are protected by two separate rules
     * that happen to be equally weak, and a fix that upgraded only one would leave the other open
     * while looking finished.
     */
    @Test
    void aPasswordAloneCannotReachChildData() throws Exception {
        User user = account("staff@example.test");
        MockHttpSession session = passwordStage(user);

        assertThat(sender.codes).as("a code should have been sent").isNotEmpty();

        for (String protectedUrl : List.of("/reports/", "/interview-requests/", "/")) {
            MvcResult result = mockMvc.perform(get(protectedUrl).session(session)).andReturn();
            assertThat(result.getResponse().getStatus())
                    .as("%s must not be reachable before the second factor", protectedUrl)
                    .isEqualTo(302);
            assertThat(result.getResponse().getRedirectedUrl())
                    .as("%s must bounce to the login page, not render", protectedUrl)
                    .contains("/login");
        }
    }

    /** The happy path, so the gate is not merely a wall. */
    @Test
    void theCorrectCodeCompletesTheSignIn() throws Exception {
        User user = account("staff@example.test");
        MockHttpSession session = passwordStage(user);

        mockMvc.perform(post("/login/verify").with(csrf()).session(session)
                        .param("code", currentCode()))
                .andReturn();

        // Asserted on the DESTINATION, not on a 200. "/" always redirects for a signed-in user -
        // RootController sends each role to its own landing page, and an ADMIN to /admin/users - so
        // a status assertion here would be testing RootController's routing rather than whether
        // anybody is authenticated. The two states are told apart by WHERE the redirect goes:
        // /login means the gate is still shut, anything else means it opened.
        MvcResult after = mockMvc.perform(get("/").session(session)).andReturn();
        assertThat(after.getResponse().getRedirectedUrl())
                .as("the session should be authenticated once the code is accepted")
                .isEqualTo("/admin/users");
    }

    /**
     * Single use. A code that still works after it has been accepted is a reusable credential, and
     * one that would sit in a mailbox indefinitely.
     */
    @Test
    void anAcceptedCodeCannotBeUsedAgain() throws Exception {
        User user = account("staff@example.test");
        MockHttpSession first = passwordStage(user);
        String code = currentCode();

        mockMvc.perform(post("/login/verify").with(csrf()).session(first).param("code", code));

        MockHttpSession second = passwordStage(user);
        mockMvc.perform(post("/login/verify").with(csrf()).session(second).param("code", code));

        MvcResult after = mockMvc.perform(get("/").session(second)).andReturn();
        assertThat(after.getResponse().getStatus())
                .as("a replayed code must not authenticate a second session")
                .isEqualTo(302);
    }

    /**
     * The challenge BURNS rather than merely slowing down. Six digits is 10^6, which a challenge
     * that survives its own failures eventually gives up.
     */
    @Test
    void tooManyWrongCodesBurnTheChallenge() throws Exception {
        User user = account("staff@example.test");
        MockHttpSession session = passwordStage(user);
        String realCode = currentCode();

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/login/verify").with(csrf()).session(session)
                    .param("code", "000000".equals(realCode) ? "111111" : "000000"));
        }

        assertThat(auditCount(AuditEventType.MFA_LOCKED))
                .as("burning the challenge must be recorded distinctly from an ordinary wrong code")
                .isEqualTo(1);

        MockHttpSession retry = passwordStage(user);
        mockMvc.perform(post("/login/verify").with(csrf()).session(retry).param("code", realCode));
        MvcResult after = mockMvc.perform(get("/").session(retry)).andReturn();
        assertThat(after.getResponse().getStatus())
                .as("the burned code must not work afterwards, even though it was once correct")
                .isEqualTo(302);
    }

    /**
     * An account with no address must be REFUSED, never admitted.
     *
     * <p>This is the bypass the feature would otherwise contain: a missing address is the one
     * condition under which "send a code" cannot happen, and the natural implementation skips the
     * step it cannot perform. That turns the absence of an email address into a way of switching the
     * second factor off for a single account.
     */
    @Test
    void anAccountWithNoAddressIsRefusedRatherThanAdmitted() throws Exception {
        User user = account(null);
        MockHttpSession session = passwordStage(user);

        MvcResult after = mockMvc.perform(get("/").session(session)).andReturn();
        assertThat(after.getResponse().getStatus())
                .as("no address must mean no sign-in, not a skipped factor")
                .isEqualTo(302);
        assertThat(after.getResponse().getRedirectedUrl()).contains("/login");
    }

    /**
     * LOGIN_SUCCESS must keep meaning "this person signed in".
     *
     * <p>Spring publishes its success event when the PASSWORD is accepted. Left alone, the audit
     * trail would record a completed sign-in for somebody who never passed the second factor - and
     * the audit screen would render it as one. The row would not be false about an event; it would
     * be false about the only thing a reader uses it for.
     */
    @Test
    void loginSuccessIsNotRecordedUntilBothFactorsPass() throws Exception {
        User user = account("staff@example.test");
        MockHttpSession session = passwordStage(user);

        assertThat(auditCount(AuditEventType.LOGIN_SUCCESS))
                .as("the password stage alone must not be recorded as a sign-in")
                .isZero();
        assertThat(auditCount(AuditEventType.MFA_CHALLENGE_ISSUED))
                .as("but the password stage must not be silent either")
                .isEqualTo(1);

        mockMvc.perform(post("/login/verify").with(csrf()).session(session)
                .param("code", currentCode()));

        assertThat(auditCount(AuditEventType.LOGIN_SUCCESS))
                .as("the sign-in is recorded once, when it actually happened")
                .isEqualTo(1);
    }
}
