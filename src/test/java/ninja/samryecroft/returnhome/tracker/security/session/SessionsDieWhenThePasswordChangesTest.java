package ninja.samryecroft.returnhome.tracker.security.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.security.passwordreset.PasswordResetLinkSender;
import ninja.samryecroft.returnhome.tracker.security.passwordreset.PasswordResetRequestService;
import ninja.samryecroft.returnhome.tracker.security.passwordreset.PasswordResetService;
import ninja.samryecroft.returnhome.tracker.security.secondfactor.SecondFactorService;
import ninja.samryecroft.returnhome.tracker.security.secondfactor.VerificationCodeSender;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import ninja.samryecroft.returnhome.tracker.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * T357: changing an account's password must end the sessions of whoever is already signed in as it.
 *
 * <p><b>The residual risk this closes.</b> Until this exists, an administrator who resets the
 * password of an account they believe is compromised has done the only thing available to them and
 * has not shut the intruder out: the intruder's session goes on working - through a password they no
 * longer know - until it happens to time out. Nothing on any screen says so, which is what makes it
 * worth a test rather than a comment.
 *
 * <p><b>Every sign-in here goes through the SECOND FACTOR on purpose.</b> That is not incidental
 * coverage. The obvious implementation of this card - configure a {@code SessionRegistry} and call
 * {@code expireNow()} - works for a sign-in that completes inside
 * {@code UsernamePasswordAuthenticationFilter} and does nothing at all for one that completes in
 * {@code SecondFactorController}, which is the route production actually uses. A test that signed in
 * with the factor switched off would pass against a build where this feature is entirely inert.
 */
@SpringBootTest(properties = {
        "app.security.second-factor.enabled=true",
        "app.security.second-factor.transport=capturing"
})
@AutoConfigureMockMvc
class SessionsDieWhenThePasswordChangesTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String NEW_PASSWORD = "a-completely-different-passphrase";

    @TestConfiguration
    static class CapturingSenderConfig {
        @Bean
        CapturingSender capturingSender() {
            return new CapturingSender();
        }

        /** {@code @Primary} because the factory always supplies one - its own javadoc says a
         * capturing test wins this way rather than by making the factory aware of tests. */
        @Bean
        @Primary
        CapturingLinkSender capturingLinkSender() {
            return new CapturingLinkSender();
        }
    }

    static class CapturingLinkSender implements PasswordResetLinkSender {
        final List<String> links = new ArrayList<>();

        @Override
        public void send(String emailAddress, String resetLink) {
            links.add(resetLink);
        }
    }

    static class CapturingSender implements VerificationCodeSender {
        final List<String> codes = new ArrayList<>();

        @Override
        public void send(String emailAddress, String code) {
            codes.add(code);
        }
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private UserService userService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SessionRegistry sessionRegistry;
    @Autowired private SessionTerminationService sessionTerminationService;
    @Autowired private CapturingSender sender;
    @Autowired private CapturingLinkSender linkSender;
    @Autowired private PasswordResetRequestService resetRequests;
    @Autowired private PasswordResetService resets;

    private User subject;
    private AppUserPrincipal administrator;

    @BeforeEach
    void seed() {
        sender.codes.clear();
        linkSender.links.clear();
        // The registry is a singleton in a cached Spring context and survives resetDatabase(),
        // which restarts the identity sequence - so a user id from an earlier test is handed out
        // again here and would match somebody else's leftover entry. Clearing is test hygiene, not
        // a property of the feature: real ids are never reissued.
        sessionRegistry.getAllPrincipals().forEach(principal ->
                sessionRegistry.getAllSessions(principal, true)
                        .forEach(info -> sessionRegistry.removeSessionInformation(info.getSessionId())));
        subject = account("t357-subject");
        administrator = new AppUserPrincipal(account("t357-admin"));
    }

    private User account(String name) {
        User user = new User();
        String unique = name + "-" + System.nanoTime();
        user.setUsername(unique);
        user.setEmail(unique + "@example.test");
        user.setFirstName("Sess");
        user.setLastName("Ion");
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        user.setEnabled(true);
        return userRepository.save(user);
    }

    /** A full two-step sign-in, ending in a session that is genuinely authenticated. */
    private MockHttpSession signIn(User user) throws Exception {
        MvcResult passwordStage = mockMvc.perform(post("/login").with(csrf())
                        .param("username", user.getEmail())
                        .param("password", PASSWORD))
                .andReturn();
        MockHttpSession session = (MockHttpSession) passwordStage.getRequest().getSession(false);

        assertThat(sender.codes)
                .as("the second factor must actually have been reached - a sign-in that skipped it "
                        + "would not exercise the route this test exists for")
                .isNotEmpty();

        mockMvc.perform(post("/login/verify").with(csrf()).session(session)
                        .param("code", sender.codes.get(sender.codes.size() - 1)))
                .andReturn();

        assertThat(isAuthorised(session))
                .as("PRECONDITION: the session must be signed in before anything is measured")
                .isTrue();
        return session;
    }

    /**
     * Whether a request carrying this session is SERVED, rather than bounced to the login page.
     *
     * <p><b>Read the second condition before simplifying this.</b> My first version returned
     * {@code status == 200}, and it was wrong in the direction that hides the bug: Spring Security's
     * default expired-session strategy writes its notice with <b>HTTP 200</b>, so a session that had
     * just been correctly killed read as signed in, and the guard below went green against a build
     * where expiry worked. The strategy is now a redirect (see {@code SecurityConfig}), but the
     * helper still checks where the response points rather than only what it numbered, because the
     * lesson is about the instrument and not about the one default that taught it.
     */
    private boolean isAuthorised(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/").session(session)).andReturn();
        String redirect = result.getResponse().getRedirectedUrl();
        boolean bounced = redirect != null && redirect.contains("/login");
        return !bounced && result.getResponse().getStatus() < 400;
    }

    /**
     * THE ASSERTION THIS CARD EXISTS FOR.
     */
    @Test
    void aSessionOpenBeforeThePasswordChangedStopsWorkingAfterIt() throws Exception {
        MockHttpSession intruderSession = signIn(subject);

        userService.setPassword(subject.getId(), NEW_PASSWORD, administrator);

        assertThat(isAuthorised(intruderSession))
                .as("the session held from BEFORE the reset must be refused - otherwise resetting a "
                        + "compromised account's password removes the attacker's knowledge of the "
                        + "password and not their access")
                .isFalse();
    }

    /**
     * THE CARD'S OWN ASSERTION, through the SELF-SERVICE reset rather than the administrator one.
     *
     * <p>Both paths end in the same {@code SessionTerminationService} call, but they reach it through
     * different code owned by different cards, and this is the path T353 built: request a link, set a
     * new password, verify the code, and only then does the password change. The session opened
     * beforehand must not survive it.
     */
    @Test
    void aSelfServiceResetAlsoEndsTheSessionThatWasAlreadyOpen() throws Exception {
        MockHttpSession sessionOpenedBefore = signIn(subject);

        resetRequests.requestReset(subject.getEmail(), "203.0.113.7", "https://example.test");
        assertThat(linkSender.links)
                .as("PRECONDITION: a reset link must actually have been issued")
                .hasSize(1);
        String token = linkSender.links.get(0).substring(linkSender.links.get(0).indexOf("token=") + 6);

        sender.codes.clear();
        assertThat(resets.submitNewPassword(token, NEW_PASSWORD).result().name())
                .as("PRECONDITION: the new password must be accepted and a code issued")
                .isEqualTo("OK");
        assertThat(sender.codes).as("PRECONDITION: a reset code must have been sent").isNotEmpty();

        assertThat(resets.verifyAndApply(token, sender.codes.get(sender.codes.size() - 1)))
                .isEqualTo(SecondFactorService.Outcome.PASSED);

        assertThat(isAuthorised(sessionOpenedBefore))
                .as("a self-service reset must end the sessions that were already open - otherwise "
                        + "the person it protects changes their password and the intruder stays in")
                .isFalse();
    }

    /**
     * POSITIVE CONTROL for the test above.
     *
     * <p>Without this, a bug that refused every session - a broken filter, a misconfigured registry,
     * a test that never really signed in - would make the assertion above pass while the feature did
     * nothing. This proves the refusal is caused by the RESET and not by the arrangement.
     */
    @Test
    void anotherUsersSessionIsUntouchedBySomebodyElsesReset() throws Exception {
        MockHttpSession bystander = signIn(subject);
        User somebodyElse = account("t357-other");

        userService.setPassword(somebodyElse.getId(), NEW_PASSWORD, administrator);

        assertThat(isAuthorised(bystander))
                .as("a reset on a DIFFERENT account must leave this session alone; if this goes red "
                        + "the expiry is indiscriminate and the test above proves nothing")
                .isTrue();
    }

    /**
     * ARMING THE MECHANISM: the registry must actually contain the session.
     *
     * <p>This is the failure the configuration alone produces and the one nothing else would show.
     * {@code SecondFactorController} establishes authentication by hand, after
     * {@code request.changeSessionId()}, without running any session authentication strategy - so
     * the registration Spring Security would normally do never happens, and the id the password
     * stage might have registered is not the id the session ends up with. Expiry would then run
     * against an empty register, return cleanly, and expire nothing.
     *
     * <p>The count is asserted rather than the refusal, because "there were no sessions" and "it did
     * not look for any" are the two states that otherwise present identically.
     */
    @Test
    void theRegistryKnowsAboutASessionThatSignedInThroughTheSecondFactor() throws Exception {
        MockHttpSession session = signIn(subject);

        assertThat(sessionRegistry.getSessionInformation(session.getId()))
                .as("a session established by the second-factor controller must be registered, or "
                        + "expiry is a method that runs and does nothing")
                .isNotNull();

        assertThat(sessionTerminationService.terminateAllSessionsFor(subject.getId()))
                .as("the service must FIND the session, not merely complete without error")
                .isEqualTo(1);

        assertThat(sessionRegistry.getSessionInformation(session.getId()).isExpired())
                .as("and the entry it expired must be THIS session's, not some leftover id")
                .isTrue();
    }

    /**
     * Exactly one entry per signed-in session, including across the id change that signing in
     * through the second factor performs.
     *
     * <p>{@code SecondFactorController} calls {@code request.changeSessionId()}. The session is not
     * destroyed, so no {@code SessionDestroyedEvent} is published and nothing would ever clear the
     * entry filed under the old id. The register would then grow an entry per sign-in that names no
     * session, and the count returned by an expiry would be partly fictional - which is worse than
     * an undercount, because it reads as evidence that something was shut down.
     */
    @Test
    void changingTheSessionIdAtSignInDoesNotLeaveAnOrphanBehind() throws Exception {
        signIn(subject);

        long entriesForThisUser = sessionRegistry.getAllPrincipals().stream()
                .filter(p -> p instanceof AppUserPrincipal a && subject.getId().equals(a.getUserId()))
                .mapToLong(p -> sessionRegistry.getAllSessions(p, true).size())
                .sum();

        assertThat(entriesForThisUser)
                .as("one sign-in, one live entry - the pre-changeSessionId id must not linger")
                .isEqualTo(1);
    }

    /**
     * The registry is matched on user id, never on principal identity - asserted, because the
     * alternative compiles, runs and silently expires nothing.
     *
     * <p>{@code AppUserPrincipal} has no {@code equals}, so the registry's map lookup is identity
     * comparison and a principal rebuilt here is never the instance stored at sign-in. If somebody
     * later "simplifies" {@code SessionTerminationService} to
     * {@code sessionRegistry.getAllSessions(principal, false)}, this is the test that explains why
     * the reset quietly stopped ending sessions.
     */
    @Test
    void aFreshlyBuiltPrincipalIsNotTheOneTheRegistryHolds() throws Exception {
        signIn(subject);

        AppUserPrincipal rebuilt = new AppUserPrincipal(userRepository.findById(subject.getId())
                .orElseThrow());

        assertThat(sessionRegistry.getAllSessions(rebuilt, false))
                .as("identity comparison means a rebuilt principal finds nothing - which is exactly "
                        + "why the service matches on the user id instead")
                .isEmpty();
        assertThat(sessionTerminationService.terminateAllSessionsFor(subject.getId()))
                .as("...while the id-based lookup finds it")
                .isEqualTo(1);
    }

    /** A user nobody has signed in as has nothing to expire, and says so rather than erroring. */
    @Test
    void anAccountWithNoLiveSessionsExpiresNothing() {
        assertThat(sessionTerminationService.terminateAllSessionsFor(subject.getId())).isZero();
        assertThat(sessionTerminationService.terminateAllSessionsFor(null)).isZero();
    }
}
