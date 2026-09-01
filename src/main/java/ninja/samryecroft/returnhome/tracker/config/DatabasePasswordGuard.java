package ninja.samryecroft.returnhome.tracker.config;

import java.util.Arrays;
import java.util.Locale;
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

    /** Local development gets its throwaway credentials from application-dev.properties. */
    static final String DEV_PROFILE = "dev";

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
                        + "There is deliberately no default. For local development run with "
                        + "SPRING_PROFILES_ACTIVE=" + DEV_PROFILE + " instead.");
    }

    private static boolean isExempt(Environment environment) {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.trim().toLowerCase(Locale.ROOT).equals(DEV_PROFILE));
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
