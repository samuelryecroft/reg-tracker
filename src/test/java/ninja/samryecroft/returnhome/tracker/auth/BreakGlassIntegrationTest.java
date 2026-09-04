package ninja.samryecroft.returnhome.tracker.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.audit.AuditEvent;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventRepository;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventType;
import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T113 Inc 4: the emergency local sign-in cannot be used quietly.
 *
 * <p><b>Runs with {@code app.auth.entra.enabled} at its default of false, and that is the test
 * design rather than a convenience.</b> Break-glass exists for the case where single sign-on is off
 * or broken - §5's rollback is literally "disable Entra, go back to form login" - so the honest
 * scenario for the emergency path is the one where the emergency exists. It is also a structural
 * proof: a WARN line or an audit event accidentally gated behind {@code if (entraEnabled)}, which is
 * where someone tidying Entra code would naturally put it, could not pass in this context at all. A
 * comment saying "don't gate this" would only have argued with a refactor that felt correct at the
 * time.
 *
 * <p>It also costs no Spring context: no {@code @TestPropertySource}, so this joins the main one
 * (TEST-CONTEXTS.md). The break-glass flag is flipped on the live {@code AppProperties} bean and
 * restored afterwards, which is why the gate reads it per request rather than latching it at
 * startup.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BreakGlassIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AuditEventRepository auditEventRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;
    @Autowired
    private AppProperties appProperties;
    @Autowired
    private ApplicationEventPublisher events;

    private ListAppender<ILoggingEvent> logLines;
    private ch.qos.logback.classic.Logger listenerLogger;
    private String username;

    @BeforeEach
    void seedAndCapture() {
        username = "break-glass-" + System.nanoTime();
        User user = new User();
        user.setUsername(username);
        user.setLastName("Emergency");
        user.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        user.setEnabled(true);
        userRepository.save(user);

        listenerLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(BreakGlassAuditListener.class);
        logLines = new ListAppender<>();
        logLines.start();
        listenerLogger.addAppender(logLines);
    }

    @AfterEach
    void restore() {
        listenerLogger.detachAppender(logLines);
        // Restored because this context is shared with ~19 other classes: an emergency credential
        // path left switched on would leak into every one of them.
        appProperties.getAuth().getBreakGlass().setEnabled(false);
    }

    @Test
    void usingTheEmergencyPathLogsTheMarkerTheAlertMatchesOn() {
        appProperties.getAuth().getBreakGlass().setEnabled(true);

        events.publishEvent(new AuthenticationSuccessEvent(formAuthentication()));

        assertThat(warnMessages())
                .anySatisfy(message -> assertThat(message)
                        .contains(BreakGlassAuditListener.ALERT_MARKER)
                        .contains(username));
    }

    /**
     * The paired negative, and the reason the test above means anything. A log assertion is easy to
     * write so that it passes for the wrong reason - the line exists somewhere, the level is wrong,
     * the gate never ran. This shows the marker is absent when the path is closed, so the assertion
     * above is about the control rather than about the logger.
     */
    @Test
    void anOrdinarySignInWithThePathClosedLogsNothingAndRaisesNothing() {
        long before = breakGlassRows();

        events.publishEvent(new AuthenticationSuccessEvent(formAuthentication()));

        assertThat(warnMessages()).noneMatch(m -> m.contains(BreakGlassAuditListener.ALERT_MARKER));
        assertThat(breakGlassRows()).isEqualTo(before);
    }

    @Test
    void usingTheEmergencyPathRaisesItsOwnAuditEventAsWellAsTheOrdinarySignIn() {
        appProperties.getAuth().getBreakGlass().setEnabled(true);

        events.publishEvent(new AuthenticationSuccessEvent(formAuthentication()));

        // Both, deliberately: LOGIN_SUCCESS keeps the sign-in trail uniform, and BREAK_GLASS_LOGIN
        // makes "did anyone use break-glass" a question the feed answers directly rather than by
        // inference over usernames.
        assertThat(rowsFor(AuditEventType.BREAK_GLASS_LOGIN)).isEqualTo(1);
        assertThat(rowsFor(AuditEventType.LOGIN_SUCCESS)).isEqualTo(1);
    }

    /**
     * At most one enabled account may hold a local credential - asserted as a COUNT over every
     * enabled account, not by checking the known break-glass row. "At most one" is a claim about
     * everything; a test that samples proves it about one thing, and the failure this guards is
     * precisely the one where a second account quietly kept its password.
     */
    @Test
    void atMostOneEnabledAccountHoldsALocalCredential() {
        long withCredentials = userRepository.findAll().stream()
                .filter(User::isEnabled)
                .filter(user -> user.getPassword() != null && !user.getPassword().isBlank())
                .count();

        assertThat(withCredentials)
                .as("break-glass is one account, not a habit - every additional enabled account with "
                        + "a password is a way in that survives the identity provider")
                .isLessThanOrEqualTo(1);
    }

    private List<String> warnMessages() {
        return logLines.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private long breakGlassRows() {
        return rowsFor(AuditEventType.BREAK_GLASS_LOGIN);
    }

    private long rowsFor(AuditEventType type) {
        return auditEventRepository.findByEventTypeOrderByOccurredAtDesc(type).stream()
                .map(AuditEvent::getActorUsernameAtTime)
                .filter(username::equals)
                .count();
    }

    private UsernamePasswordAuthenticationToken formAuthentication() {
        UserDetails details = appUserDetailsService.loadUserByUsername(username);
        return new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
    }
}
