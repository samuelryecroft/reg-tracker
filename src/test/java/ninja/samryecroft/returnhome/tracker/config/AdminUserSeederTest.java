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
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

    @Test
    void applicationPropertiesContainsNoPlaintextSecret() throws IOException {
        String properties = Files.readString(
                Path.of("src/main/resources/application.properties"), StandardCharsets.UTF_8);

        // An env placeholder with nothing after the colon - i.e. no baked-in default.
        assertThat(properties).contains("app.admin.password=${ADMIN_SEED_PASSWORD:}");
        assertThat(properties).doesNotContain("ChangeMe123");
        // Database credentials are externalised too.
        assertThat(properties).contains("${DB_PASSWORD:");
        assertThat(properties).contains("${DB_USERNAME:");
    }
}
