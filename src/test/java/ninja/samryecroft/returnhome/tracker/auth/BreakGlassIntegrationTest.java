package ninja.samryecroft.returnhome.tracker.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.audit.AuditEvent;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventRepository;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventType;
import ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T113 Inc 4: the emergency local sign-in cannot be used quietly.
 *
 * <p>Runs with the path OPEN (see {@link AbstractBreakGlassEnabledTest}). It also used to run with
 * the Entra flag off as a structural proof that neither the WARN line nor the audit event was gated
 * behind {@code if (entraEnabled)}; Entra is gone, so that flag no longer exists and the proof went
 * with it. What is asserted below is unchanged.
 *
 * <p>The closed-path assertions live in {@link BreakGlassClosedIntegrationTest}, which needs no
 * property override and so costs no context. Splitting them is not tidiness: the pair only means
 * something if the same control is observed open and shut, and each half has to run in the
 * configuration it is describing.
 */
class BreakGlassIntegrationTest extends AbstractBreakGlassEnabledTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AuditEventRepository auditEventRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;
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
    void detachAppender() {
        listenerLogger.detachAppender(logLines);
    }

    @Test
    void usingTheEmergencyPathLogsTheMarkerTheAlertMatchesOn() {
        events.publishEvent(new AuthenticationSuccessEvent(formAuthentication()));

        assertThat(warnMessages())
                .anySatisfy(message -> assertThat(message)
                        .contains(BreakGlassAuditListener.ALERT_MARKER)
                        .contains(username));
    }

    @Test
    void usingTheEmergencyPathRaisesItsOwnAuditEventAsWellAsTheOrdinarySignIn() {
        events.publishEvent(new AuthenticationSuccessEvent(formAuthentication()));

        // Both, deliberately: LOGIN_SUCCESS keeps the sign-in trail uniform, and BREAK_GLASS_LOGIN
        // makes "did anyone use break-glass" a question the feed answers directly rather than by
        // inference over usernames.
        assertThat(rowsFor(AuditEventType.BREAK_GLASS_LOGIN)).isEqualTo(1);
        assertThat(rowsFor(AuditEventType.LOGIN_SUCCESS)).isEqualTo(1);
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
