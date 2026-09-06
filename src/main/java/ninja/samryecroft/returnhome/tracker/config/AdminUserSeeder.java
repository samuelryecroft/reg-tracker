package ninja.samryecroft.returnhome.tracker.config;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.password.PasswordContext;
import ninja.samryecroft.returnhome.tracker.user.password.PasswordPolicy;
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
    private final PasswordPolicy passwordPolicy;

    public AdminUserSeeder(PasswordPolicy passwordPolicy, UserRepository userRepository, PasswordEncoder passwordEncoder,
            AppProperties appProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.appProperties = appProperties;
        this.passwordPolicy = passwordPolicy;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<User> existingAdmins = userRepository.findByRoleOrderByFullName(Role.ADMIN);
        if (!existingAdmins.isEmpty()) {
            logSeedingIsAlreadyDone(existingAdmins);
            return;
        }

        String username = appProperties.getAdmin().getUsername();
        String password = appProperties.getAdmin().getPassword();
        if (password == null || password.isBlank()) {
            warnNobodyCanSignIn();
            return;
        }
        // T272 R5. This password never passes through form validation, so without this check the
        // bootstrap admin was the ONE account that could hold a password the policy forbids - on the
        // most privileged account on the system. A weak value takes the SAME path as an absent one:
        // refuse, say why, and let the app start with nobody able to sign in, because seeding a weak
        // platform admin is the worse of the two failures and the only one that is silent.
        Optional<String> rejection = passwordPolicy.rejectionFor(password,
                new PasswordContext(username, null, null));
        if (rejection.isPresent()) {
            warnSeedPasswordIsRejected(rejection.get());
            return;
        }

        User admin = new User();
        admin.setUsername(username);
        admin.setPassword(passwordEncoder.encode(password));
        admin.setFirstName("System");
        admin.setLastName("Administrator");
        // No email: this account is seeded from an environment secret, not provisioned by a person,
        // so there is no address to record. An admin supplies one on the first edit.
        admin.setRoles(Set.of(Role.ADMIN));
        admin.setEnabled(true);
        userRepository.save(admin);
        log.info("Seeded initial platform admin '{}' from the configured environment secret.", username);
    }

    /**
     * The other way to be stranded, and until now the silent one: an admin exists but nobody knows
     * its password. Setting {@code ADMIN_SEED_PASSWORD} and restarting looks like the obvious fix
     * and does nothing, because this seeder only ever creates the <em>first</em> admin - it has no
     * rotate path, deliberately, since a running app that reassigns its own admin password from an
     * environment variable is a worse problem than a lockout.
     *
     * <p>Names the accounts that actually hold the role rather than the configured
     * {@code app.admin.username}. Those two can differ - the check is by role, not by name - and
     * someone locked out assuming the account is called "admin" is exactly the person reading this.
     * That is also why this reads the rows instead of {@code existsByRole}: one small query at
     * startup buys the only detail that ends the confusion.
     */
    private void logSeedingIsAlreadyDone(List<User> existingAdmins) {
        String usernames = existingAdmins.stream().map(User::getUsername).collect(Collectors.joining(", "));
        log.info("Platform admin already exists ({}), so ADMIN_SEED_PASSWORD is ignored - this seeder "
                + "only creates the first admin and never rotates an existing password. If you are "
                + "locked out, reset it in the database directly; locally, 'docker compose down -v' "
                + "starts over from empty.", usernames);
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
    private void warnSeedPasswordIsRejected(String reason) {
        log.error("=====================================================================");
        log.error(" NO ADMIN WAS SEEDED - THE SEED PASSWORD FAILS THE PASSWORD POLICY.");
        log.error("   {}", reason);
        log.error("");
        log.error(" ADMIN_SEED_PASSWORD is set, but the value would not be accepted on");
        log.error(" any user form, and this is the most privileged account on the system.");
        log.error(" Seeding it anyway would put the one password nobody checked on the one");
        log.error(" account that can do anything.");
        log.error("");
        log.error(" To fix: set ADMIN_SEED_PASSWORD to a value that meets the policy and");
        log.error(" RESTART. Seeding only runs at startup.");
        log.error("=====================================================================");
    }

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
