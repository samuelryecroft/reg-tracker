package ninja.samryecroft.returnhome.tracker.security.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventRepository;
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
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * T353d: {@code /forgot-password} mints and mails only on a real match, and is indistinguishable
 * either way. These are the enumeration-defence invariants stated as behaviour, not prose.
 */
@SpringBootTest(properties = {
        "app.security.password-reset.max-requests-per-address=3",
        // The throttle is an in-memory singleton shared across this class's methods, all posting
        // from 127.0.0.1 - a low per-IP cap would make later methods fail on accumulated count.
        // Neutralised here so per-address (which each method exercises with its own unique email)
        // is the only cap in play. The per-IP cap runs the same record() path as per-address, so a
        // separate assertion of it here would only re-test that path.
        "app.security.password-reset.max-requests-per-ip=100000"
})
@AutoConfigureMockMvc
class ForgotPasswordRequestTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class Config {
        /** Captures the link instead of sending it; @Primary so it wins over the Logging bean. */
        @Bean
        @Primary
        CapturingLinkSender capturingLinkSender() {
            return new CapturingLinkSender();
        }

        /** Runs the after-commit send inline, so "was a link sent?" is deterministic in the test. */
        @Bean
        @Primary
        TaskExecutor testTaskExecutor() {
            return new SyncTaskExecutor();
        }
    }

    static class CapturingLinkSender implements PasswordResetLinkSender {
        final List<String> recipients = new ArrayList<>();
        final List<String> links = new ArrayList<>();

        @Override
        public void send(String emailAddress, String resetLink) {
            recipients.add(emailAddress);
            links.add(resetLink);
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordResetTokenRepository tokens;
    @Autowired
    private AuditEventRepository auditEvents;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private CapturingLinkSender sender;

    private final String suffix = "-" + System.nanoTime();

    @BeforeEach
    void clear() {
        sender.recipients.clear();
        sender.links.clear();
    }

    private User account(String localPart, boolean enabled, boolean breakGlass) {
        User user = new User();
        user.setUsername(localPart + suffix);
        user.setFirstName("Reset");
        user.setLastName("Request");
        user.setEmail(localPart + suffix + "@example.test");
        user.setPassword(passwordEncoder.encode("correct-horse-battery-staple"));
        user.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        user.setEnabled(enabled);
        user.setBreakGlass(breakGlass);
        return userRepository.save(user);
    }

    private void postForgot(String email) throws Exception {
        mockMvc.perform(post("/forgot-password").with(csrf()).param("email", email))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/forgot-password-sent"));
    }

    private long resetRequestAuditRows() {
        return auditEvents.findByEventTypeOrderByOccurredAtDesc(AuditEventType.PASSWORD_RESET_REQUESTED).size();
    }

    @Test
    void aMatchedRequestMintsATokenSendsALinkAndAuditsTheRequest() throws Exception {
        User user = account("real", true, false);
        long auditBefore = resetRequestAuditRows();

        postForgot(user.getEmail());

        assertThat(tokens.findAll()).anyMatch(t -> t.getUserId().equals(user.getId()));
        assertThat(sender.recipients).containsExactly(user.getEmail());
        assertThat(sender.links).allMatch(l -> l.contains("/reset-password?token="));
        assertThat(resetRequestAuditRows()).isEqualTo(auditBefore + 1);
    }

    @Test
    void anUnmatchedAddressIsIndistinguishableAndWritesNothing() throws Exception {
        long auditBefore = resetRequestAuditRows();
        long tokensBefore = tokens.count();

        // Same status and view as a match (postForgot asserts that), and:
        postForgot("nobody" + suffix + "@example.test");

        assertThat(sender.recipients).as("the non-account must never be mailed").isEmpty();
        assertThat(tokens.count()).as("no token for an address with no account").isEqualTo(tokensBefore);
        assertThat(resetRequestAuditRows())
                .as("no audit row on a no-match - it would be the oracle the response refuses to be")
                .isEqualTo(auditBefore);
    }

    @Test
    void aDisabledAccountGetsTheNeutralResponseAndNothingHappens() throws Exception {
        User disabled = account("disabled", false, false);
        long auditBefore = resetRequestAuditRows();

        postForgot(disabled.getEmail());

        assertThat(sender.recipients).isEmpty();
        assertThat(tokens.findAll()).noneMatch(t -> t.getUserId().equals(disabled.getId()));
        assertThat(resetRequestAuditRows()).isEqualTo(auditBefore);
    }

    @Test
    void aBreakGlassAccountCannotBeResetEvenWithAnAddress() throws Exception {
        // The break-glass row normally has no address (V25), but the WHERE NOT is_break_glass belt
        // must hold even if a future account carries both. Seed exactly that and prove it is excluded.
        User breakGlass = account("breakglass", true, true);

        postForgot(breakGlass.getEmail());

        assertThat(sender.recipients).isEmpty();
        assertThat(tokens.findAll()).noneMatch(t -> t.getUserId().equals(breakGlass.getId()));
    }

    @Test
    void theThrottleStopsMintingAfterTheAddressCap() throws Exception {
        User user = account("throttled", true, false);

        // Cap is 3 per address (set via properties). The fourth must still render the neutral page
        // but must not mint a fourth token.
        for (int i = 0; i < 3; i++) {
            postForgot(user.getEmail());
        }
        long afterCap = tokens.findAll().stream().filter(t -> t.getUserId().equals(user.getId())).count();
        postForgot(user.getEmail());
        long afterFourth = tokens.findAll().stream().filter(t -> t.getUserId().equals(user.getId())).count();

        assertThat(afterCap).isEqualTo(3);
        assertThat(afterFourth).as("a request over the cap mints no token").isEqualTo(3);
    }

    @Test
    void everyOutcomeRendersTheByteIdenticalNeutralResponse() throws Exception {
        // god's ask (and what Oscar verifies MEASURED on T353i): the neutral response must be
        // IDENTICAL across every branch, asserted by comparing the actual bodies and statuses - not
        // five tests that each happen to expect the same literal, which agree only by coincidence.
        User enabled = account("identical-match", true, false);
        User disabled = account("identical-disabled", false, false);
        User breakGlass = account("identical-bg", true, true);
        String throttledEmail = "identical-throttled" + suffix + "@example.test";

        List<int[]> statuses = new ArrayList<>();
        List<String> bodies = new ArrayList<>();

        // Trip the address cap (3) so the fifth capture is a THROTTLED response.
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/forgot-password").with(csrf()).param("email", throttledEmail));
        }

        String[] emails = {
                enabled.getEmail(),                                   // match
                "nobody" + suffix + "@example.test",                  // no-match
                disabled.getEmail(),                                  // disabled
                breakGlass.getEmail(),                                // break-glass
                throttledEmail                                        // throttled (over the cap)
        };
        for (String email : emails) {
            MvcResult result = mockMvc.perform(post("/forgot-password").with(csrf()).param("email", email))
                    .andReturn();
            statuses.add(new int[]{result.getResponse().getStatus()});
            bodies.add(result.getResponse().getContentAsString());
        }

        String firstBody = bodies.get(0);
        int firstStatus = statuses.get(0)[0];
        assertThat(bodies)
                .as("the neutral page must be byte-identical across match/no-match/disabled/break-glass/throttled")
                .allSatisfy(b -> assertThat(b).isEqualTo(firstBody));
        assertThat(statuses).allSatisfy(st -> assertThat(st[0]).isEqualTo(firstStatus));
    }
}
