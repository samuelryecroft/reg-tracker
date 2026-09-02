package ninja.samryecroft.returnhome.tracker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The admin bootstrap credential is externalised (BUG-REVIEW HIGH: a committed default admin
 * password meant any deployment that forgot to override it shipped with a publicly-known
 * platform-wide login).
 *
 * <p>Driven directly rather than through a Spring context on purpose: the Testcontainers database
 * is shared across test classes, so asserting "no admin row exists" against it would depend on
 * which other test class seeded one first.
 */
class AdminUserSeederTest {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AppProperties propertiesWith(String username, String password) {
        AppProperties properties = new AppProperties();
        properties.getAdmin().setUsername(username);
        properties.getAdmin().setPassword(password);
        return properties;
    }

    @Test
    void failsSafeAndSeedsNothingWhenTheSeedPasswordIsUnset() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(false);

        new AdminUserSeeder(userRepository, passwordEncoder, propertiesWith("admin", null)).run(null);
        new AdminUserSeeder(userRepository, passwordEncoder, propertiesWith("admin", "")).run(null);
        new AdminUserSeeder(userRepository, passwordEncoder, propertiesWith("admin", "   ")).run(null);

        // No fallback to a baked-in default: an app nobody can sign into yet beats a platform-wide
        // account with a well-known password guarding children's records.
        verify(userRepository, never()).save(any());
    }

    @Test
    void seedsAdminWithTheSuppliedSecretWhenItIsProvided() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(false);

        new AdminUserSeeder(userRepository, passwordEncoder, propertiesWith("boss", "from-the-env")).run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getUsername()).isEqualTo("boss");
        assertThat(saved.getValue().getRoles()).containsExactly(Role.ADMIN);
        assertThat(saved.getValue().isEnabled()).isTrue();
        // Stored hashed, never in the clear.
        assertThat(saved.getValue().getPassword()).isNotEqualTo("from-the-env");
        assertThat(passwordEncoder.matches("from-the-env", saved.getValue().getPassword())).isTrue();
    }

    @Test
    void doesNothingWhenAnAdminAlreadyExists() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(true);

        new AdminUserSeeder(userRepository, passwordEncoder, propertiesWith("admin", "irrelevant")).run(null);

        verify(userRepository, never()).save(any());
    }

    /**
     * The skip is correct but silent-by-consequence: the app starts normally and serves a login
     * page that rejects everyone, which is indistinguishable from a wrong password. So the
     * announcement is part of the behaviour, and asserting it is what stops someone quietly
     * demoting it back to a WARN that scrolls past.
     */
    @Test
    void saysLoudlyThatNobodyCanSignInWhenItSkips() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(false);

        List<ILoggingEvent> events = captureLogsOf(AdminUserSeeder.class, () ->
                new AdminUserSeeder(userRepository, passwordEncoder, propertiesWith("admin", null)).run(null));

        assertThat(events)
                .as("the skip must be announced at ERROR - a WARN is what gets scrolled past")
                .isNotEmpty()
                .allMatch(event -> event.getLevel() == Level.ERROR);

        String announcement = events.stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
        assertThat(announcement)
                .as("must name the consequence, the variable, and that a restart is required")
                .contains("NOBODY CAN SIGN IN")
                .contains("ADMIN_SEED_PASSWORD")
                .containsIgnoringCase("restart");
    }

    /** Nothing to announce on the happy path - a banner on every boot would train people to ignore it. */
    @Test
    void staysQuietAtErrorLevelWhenItActuallySeeds() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.existsByRole(Role.ADMIN)).thenReturn(false);

        List<ILoggingEvent> events = captureLogsOf(AdminUserSeeder.class, () ->
                new AdminUserSeeder(userRepository, passwordEncoder, propertiesWith("boss", "from-the-env"))
                        .run(null));

        assertThat(events).noneMatch(event -> event.getLevel() == Level.ERROR);
    }

    private List<ILoggingEvent> captureLogsOf(Class<?> type, Runnable action) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list;
    }

    /**
     * Guards the committed config itself, not just the seeder: the original bug was a baked-in
     * default that shipped in the image. Asserted structurally rather than by blocklisting the one
     * historical literal, so any future default - on any password property - trips this too.
     */
    @Test
    void noPasswordPropertyInTheCommittedConfigHasADefaultValue() throws IOException {
        String properties = Files.readString(
                Path.of("src/main/resources/application.properties"), StandardCharsets.UTF_8);

        // The seed password is an env placeholder with nothing after the colon - no baked default.
        assertThat(properties).contains("app.admin.password=${ADMIN_SEED_PASSWORD:}");
        // The database password has no fallback at all, so a deployment that forgets to inject it
        // fails to start rather than coming up on a well-known credential.
        assertThat(properties).contains("spring.datasource.password=${DB_PASSWORD}");

        Pattern passwordProperty = Pattern.compile("^\\s*([\\w.-]*password[\\w.-]*)\\s*=\\s*(.+)$",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        Matcher matcher = passwordProperty.matcher(properties);
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = matcher.group(2).trim();
            // Either a bare placeholder, or a placeholder whose default is empty. Anything else -
            // a literal, or ${VAR:something} - is a credential baked into the image.
            assertThat(value)
                    .as("%s must resolve from the environment with no default value", key)
                    .matches("\\$\\{[A-Za-z0-9_.-]+:?\\}");
        }
    }

    /** The local-dev credentials moved out of the base config; they must stay out of it. */
    @Test
    void localDevelopmentCredentialsLiveOnlyInTheDevProfile() throws IOException {
        String base = Files.readString(
                Path.of("src/main/resources/application.properties"), StandardCharsets.UTF_8);
        String dev = Files.readString(
                Path.of("src/main/resources/application-dev.properties"), StandardCharsets.UTF_8);

        assertThat(base).doesNotContain("${DB_PASSWORD:");
        assertThat(base).doesNotContain("ChangeMe123");
        assertThat(dev).contains("spring.datasource.password=tracker");
    }
}
