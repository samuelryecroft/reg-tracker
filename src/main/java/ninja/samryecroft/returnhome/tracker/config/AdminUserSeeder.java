package ninja.samryecroft.returnhome.tracker.config;

import java.util.Set;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates the very first platform ADMIN so a fresh deployment can be logged into at all.
 *
 * <p>The seed password is supplied by the environment ({@code ADMIN_SEED_PASSWORD}, an Azure Key
 * Vault reference in the deployed setup) and has <strong>no default</strong>. If it is missing the
 * seeder fails safe: it skips and warns, rather than falling back to a baked-in password. A
 * well-known credential on a platform-wide account guarding children's records is worse than an
 * app that cannot be signed into yet, and the previous committed default meant any deployment that
 * forgot to override it shipped with a publicly-known admin login.
 */
@Component
public class AdminUserSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminUserSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties appProperties;

    public AdminUserSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder,
            AppProperties appProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.appProperties = appProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRole(Role.ADMIN)) {
            return;
        }

        String username = appProperties.getAdmin().getUsername();
        String password = appProperties.getAdmin().getPassword();
        if (password == null || password.isBlank()) {
            log.warn("No platform admin exists and ADMIN_SEED_PASSWORD is not set - skipping admin "
                    + "seeding. Nobody can sign in until it is set and the app restarted. Set it via "
                    + "the environment (a Key Vault reference in Azure); there is deliberately no "
                    + "default password.");
            return;
        }

        User admin = new User();
        admin.setUsername(username);
        admin.setPassword(passwordEncoder.encode(password));
        admin.setFullName("System Administrator");
        admin.setRoles(Set.of(Role.ADMIN));
        admin.setEnabled(true);
        userRepository.save(admin);
        log.info("Seeded initial platform admin '{}' from the configured environment secret.", username);
    }
}
