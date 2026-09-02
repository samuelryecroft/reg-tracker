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
 * seeder fails safe: it skips and says so loudly, rather than falling back to a baked-in password. A
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
            warnNobodyCanSignIn();
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

    /**
     * Says the quiet part loudly. Skipping is the correct, safe behaviour, but its consequence is
     * that <em>nobody can sign in at all</em> - and the application otherwise starts normally and
     * serves a login page that rejects every attempt. Someone meeting that for the first time has
     * no way to tell a missing seed from a wrong password.
     *
     * <p>ERROR and a banner rather than a single WARN, because this competes with several hundred
     * startup lines and a WARN is exactly what gets scrolled past. The level is about how loud the
     * message needs to be, not about whether the application is broken: it starts fine, and that
     * is the problem.
     */
    private void warnNobodyCanSignIn() {
        log.error("=====================================================================");
        log.error(" NO ADMIN WAS SEEDED - NOBODY CAN SIGN IN.");
        log.error(" No platform admin exists and ADMIN_SEED_PASSWORD is not set, so the");
        log.error(" seeder skipped rather than fall back to a baked-in password.");
        log.error(" The app will start and every login attempt will be rejected.");
        log.error("");
        log.error(" To fix: set ADMIN_SEED_PASSWORD and RESTART the app. Seeding only");
        log.error(" runs at startup, so setting it while running changes nothing.");
        log.error("   export ADMIN_SEED_PASSWORD='<a password you choose>'");
        log.error(" In Azure it is an app setting holding a Key Vault reference.");
        log.error(" There is deliberately no default - a well-known credential on a");
        log.error(" platform-wide account guarding children's records is worse than an");
        log.error(" app that cannot be signed into yet.");
        log.error("=====================================================================");
    }
}
