package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.TestLogins;
import ninja.samryecroft.returnhome.tracker.security.passwordreset.PasswordResetToken;
import ninja.samryecroft.returnhome.tracker.security.passwordreset.PasswordResetTokenRepository;
import ninja.samryecroft.returnhome.tracker.security.passwordreset.TokenHashing;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * T353g: an administrator resetting a password, or changing an address, retires any self-service
 * reset already in flight for that account. Otherwise the remedy and the hole coexist - an admin
 * "fixing" a compromised account leaves the attacker's live reset link usable, believing they are
 * done.
 */
@SpringBootTest
class AnAdminActionKillsALiveResetLinkTest extends AbstractIntegrationTest {

    private static final String GOOD_PASSWORD = "winter kettle marble lamp";

    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordResetTokenRepository tokens;
    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private final String suffix = "-" + System.nanoTime();

    private User save(String local, Role role) {
        User u = new User();
        u.setUsername(local + suffix);
        u.setFirstName("T");
        u.setLastName("G");
        u.setEmail(local + suffix + "@example.test");
        u.setPassword(passwordEncoder.encode("old-passphrase-xyz-1"));
        u.setRoles(new HashSet<>(Set.of(role)));
        u.setEnabled(true);
        return userRepository.save(u);
    }

    private AppUserPrincipal platformAdmin() {
        User admin = save("g-admin", Role.ADMIN);
        UserDetails details = appUserDetailsService.loadUserByUsername(TestLogins.loginIdentifier(admin.getUsername()));
        return (AppUserPrincipal) details;
    }

    private Long mintLiveToken(Long userId) {
        String raw = "raw" + suffix + "-" + System.nanoTime();
        return tokens.save(new PasswordResetToken(userId, TokenHashing.sha256Hex(raw),
                Instant.now().plus(30, ChronoUnit.MINUTES), "203.0.113.1")).getId();
    }

    private boolean tokenLive(Long tokenId) {
        return tokens.findById(tokenId).orElseThrow().getConsumedAt() == null;
    }

    @Test
    void anAdminSetPasswordConsumesOutstandingResetTokens() {
        AppUserPrincipal admin = platformAdmin();
        User target = save("g-pw-target", Role.ADMIN);
        Long tokenId = mintLiveToken(target.getId());
        assertThat(tokenLive(tokenId)).isTrue();

        userService.setPassword(target.getId(), GOOD_PASSWORD, admin);

        assertThat(tokenLive(tokenId))
                .as("an admin-set password must retire the account's live reset link")
                .isFalse();
    }

    @Test
    void anEmailChangeConsumesOutstandingResetTokens() {
        AppUserPrincipal admin = platformAdmin();
        User target = save("g-email-target", Role.ADMIN);
        Long tokenId = mintLiveToken(target.getId());
        assertThat(tokenLive(tokenId)).isTrue();

        userService.changeEmail(target.getId(), "moved" + suffix + "@example.test", admin);

        assertThat(tokenLive(tokenId))
                .as("changing the address must retire reset links minted against the old one")
                .isFalse();
    }
}
