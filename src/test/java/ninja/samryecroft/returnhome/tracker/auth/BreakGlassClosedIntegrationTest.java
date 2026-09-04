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

/**
 * T113 Inc 4: the emergency path CLOSED, which is every ordinary deployment.
 *
 * <p>The paired negative for {@link BreakGlassIntegrationTest}, and the reason that file means
 * anything. A log assertion is easy to write so that it passes for the wrong reason - the line
 * exists somewhere, the level is wrong, the gate never ran - so the marker being absent when the
 * path is shut is what makes its presence when the path is open evidence about the control rather
 * than about the logger.
 *
 * <p>No property override, so this runs in the main context and costs no Hikari pool: the default
 * IS closed, which is the configuration being described.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BreakGlassClosedIntegrationTest extends AbstractIntegrationTest {

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
        username = "no-break-glass-" + System.nanoTime();
        User user = new User();
        user.setUsername(username);
        user.setLastName("Ordinary");
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
    void anOrdinarySignInWithThePathClosedLogsNothingAndRaisesNothing() {
        events.publishEvent(new AuthenticationSuccessEvent(formAuthentication()));

        assertThat(warnMessages()).noneMatch(m -> m.contains(BreakGlassAuditListener.ALERT_MARKER));
        assertThat(auditEventRepository.findByEventTypeOrderByOccurredAtDesc(AuditEventType.BREAK_GLASS_LOGIN)
                .stream().map(AuditEvent::getActorUsernameAtTime).filter(username::equals).count())
                .isZero();
    }

    /**
     * At most one enabled account holds a local credential - asserted as a COUNT over every enabled
     * account, not by checking a known row. "At most one" is a claim about everything, and a test
     * that samples proves it about one thing.
     *
     * <p><b>This is the P8-era invariant, and it is not true of production yet. Read that before
     * acting on it.</b> N2 and §5's rollback reasoning keep existing users' passwords deliberately
     * until P8, so that rolling back is a configuration change and one restart rather than a data
     * restore. Between cutover and P8, production will legitimately hold many enabled accounts with
     * credentials. Anyone who reads this as a live invariant and strips those passwords to bring
     * reality into line will destroy the rollback path at the one moment it might be needed - which
     * is a worse outcome than the state they were tidying.
     *
     * <p>What it guards <em>now</em> is narrower and still worth having: that no code path mints
     * credentials beyond the ones fixtures create for themselves. It is asserted here, before P8
     * makes it live, so that the code which would violate it is caught while violating it is still
     * cheap to fix. When P8 lands and the general form-login path goes, this becomes a statement
     * about production and the qualification above can go with it.
     *
     * <p>It lives in the closed-path class because it is true regardless of whether break-glass is
     * switched on, and asserting it in the default configuration asserts it about the configuration
     * nearly every deployment runs.
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

    private UsernamePasswordAuthenticationToken formAuthentication() {
        UserDetails details = appUserDetailsService.loadUserByUsername(username);
        return new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
    }
}
