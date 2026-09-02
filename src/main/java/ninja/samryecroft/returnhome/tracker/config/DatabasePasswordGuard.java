package ninja.samryecroft.returnhome.tracker.config;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * Refuses to start a deployed application when the database password is missing.
 *
 * <p>{@code application.properties} deliberately gives {@code spring.datasource.password} no
 * fallback, so a deployment that forgets to inject {@code DB_PASSWORD} cannot come up on a
 * well-known credential. On its own that produces a confusing failure: an unresolved placeholder
 * reaches the driver as an empty string, so the app dies deep in Flyway with
 * "password authentication failed", and against a database using {@code trust} authentication it
 * would not die at all. This turns both cases into one legible startup error.
 *
 * <p>Runs on {@link ApplicationEnvironmentPreparedEvent}, before the DataSource is built, and is
 * registered for every {@code SpringApplication} via {@code META-INF/spring.factories}. It has no
 * awareness of tests: the build gives the test JVM a DB_PASSWORD like any other environment (see
 * the surefire configuration in pom.xml), so this stays a single rule with no special cases.
 */
public class DatabasePasswordGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    static final String PASSWORD_PROPERTY = "spring.datasource.password";

    /**
     * Profiles that only ever run on a developer's machine, against a throwaway container database
     * whose credentials live in the matching {@code application-<profile>.properties}. They are
     * exempt because there is nothing to inject: the password is already in the repository, on
     * purpose, and it is not a secret.
     *
     * <p>This is deliberately an allow-list rather than a "deployed?" test. Adding a profile here
     * is a visible decision with a reviewer attached; inferring locality from the absence of a
     * marker would silently exempt any environment nobody remembered to label.
     */
    static final Set<String> LOCAL_PROFILES = Set.of("dev", "demo");

    /**
     * Profiles that mean a real deployment, which no local profile alongside them can excuse. This
     * exists so that {@code SPRING_PROFILES_ACTIVE=prod,dev} does not launder a deployment through
     * the local exemption - the point of the guard is precisely that environment.
     *
     * <p>Overlaps with {@code DemoProfileGuard}'s own list, and stays separate on purpose: that one
     * asks "may fictional records be seeded here", this one asks "must a password be injected here",
     * and {@code azure} answers them differently.
     */
    private static final Set<String> DEPLOYED_PROFILES = Set.of("prod", "production", "staging", "azure");

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        verify(event.getEnvironment());
    }

    /**
     * @throws IllegalStateException if a deployed application has no database password configured
     */
    static void verify(Environment environment) {
        if (isExempt(environment)) {
            return;
        }
        if (!isBlank(resolvePassword(environment))) {
            return;
        }
        throw new IllegalStateException(
                "Refusing to start: no database password is configured. Set the DB_PASSWORD "
                        + "environment variable - in Azure App Service that is a Key Vault reference, "
                        + "@Microsoft.KeyVault(SecretUri=...), resolved by the app's managed identity. "
                        + "There is deliberately no default. To run locally, use one of the "
                        + "profiles that carries throwaway credentials instead: "
                        + String.join(", ", LOCAL_PROFILES.stream().sorted().toList()) + ".");
    }


    private static boolean isExempt(Environment environment) {
        Set<String> active = Arrays.stream(environment.getActiveProfiles())
                .map(profile -> profile.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return active.stream().anyMatch(LOCAL_PROFILES::contains)
                && active.stream().noneMatch(DEPLOYED_PROFILES::contains);
    }

    /**
     * An unresolvable placeholder throws rather than returning null, and that means the same thing
     * here as an empty value: nothing injected the password.
     */
    private static String resolvePassword(Environment environment) {
        try {
            return environment.getProperty(PASSWORD_PROPERTY);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
