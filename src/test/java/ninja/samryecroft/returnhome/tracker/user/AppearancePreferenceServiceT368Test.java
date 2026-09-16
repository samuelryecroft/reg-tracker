package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * T368: admin-created accounts can have username != email. T344 made {@code principal.getUsername()}
 * return the login identifier (email for normal accounts), not the username column. The fix changes
 * AppearancePreferenceService to look up by getUserId() instead of findByUsername(getUsername()),
 * so it works regardless of whether they differ.
 */
@SpringBootTest
class AppearancePreferenceServiceT368Test extends AbstractIntegrationTest {

    @Autowired
    private AppearancePreferenceService appearancePreferenceService;

    @Autowired
    private UserRepository userRepository;

    @Test
    void updateAppearancePreferenceWorksWhenUsernameAndEmailDiffer() {
        // Create an account where username != email (the gap T368 exploits)
        User account = new User();
        account.setUsername("alice");
        account.setEmail("alice@example.com");
        account.setFirstName("Alice");
        account.setLastName("Test");
        account.setPassword("encoded-password");
        account.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        account.setEnabled(true);
        userRepository.save(account);

        // Reload as principal would see it
        User loaded = userRepository.findByEmailIgnoreCase("alice@example.com").orElseThrow();
        AppUserPrincipal principal = new AppUserPrincipal(loaded);

        // Verify the precondition: principal.getUsername() returns email, not username column
        assertThat(principal.getUsername()).isEqualTo("alice@example.com");
        assertThat(loaded.getUsername()).isEqualTo("alice");

        // Set security context and update the preference
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        appearancePreferenceService.updateOwnPreference(principal, AppearancePreference.DARK);

        // Verify it was saved: this would have failed with the old code
        User reloaded = userRepository.findByEmailIgnoreCase("alice@example.com").orElseThrow();
        assertThat(reloaded.getAppearancePreference()).isEqualTo(AppearancePreference.DARK);
    }

    @Test
    void updateAppearancePreferenceAlsoWorksWhenUsernameEqualsEmail() {
        // Verify the fix doesn't break the common case where username == email
        User account = new User();
        account.setUsername("bob@example.com");
        account.setEmail("bob@example.com");
        account.setFirstName("Bob");
        account.setLastName("Test");
        account.setPassword("encoded-password");
        account.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        account.setEnabled(true);
        userRepository.save(account);

        User loaded = userRepository.findByEmailIgnoreCase("bob@example.com").orElseThrow();
        AppUserPrincipal principal = new AppUserPrincipal(loaded);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        appearancePreferenceService.updateOwnPreference(principal, AppearancePreference.LIGHT);

        User reloaded = userRepository.findByEmailIgnoreCase("bob@example.com").orElseThrow();
        assertThat(reloaded.getAppearancePreference()).isEqualTo(AppearancePreference.LIGHT);
    }
}
