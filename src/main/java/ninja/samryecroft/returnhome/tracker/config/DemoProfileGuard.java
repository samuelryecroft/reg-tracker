package ninja.samryecroft.returnhome.tracker.config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * Refuses to start the application when the {@code demo} profile is combined with a production
 * marker. The demo profile seeds fictional children's records; DEPLOYMENT-PLAN.md &sect;3 makes it a
 * hard requirement that it can never activate against a real deployment.
 *
 * <p>This is the config half of a two-layer control - the deploy pipeline pins and asserts
 * {@code SPRING_PROFILES_ACTIVE} independently. This half exists so that editing the profile list
 * by hand (a portal edit, an app setting, a stray env var) still cannot seed fake case records into
 * a real database: the app dies at startup instead.
 *
 * <p>It runs on {@link ApplicationEnvironmentPreparedEvent}, i.e. as soon as profiles are resolved
 * and before any bean - including any demo seeder - is created, and is registered for every
 * {@code SpringApplication} via {@code META-INF/spring.factories}.
 *
 * <p>Staging counts as a production marker too: it is a shared deployed environment covered by the
 * same pipeline allowlist, so hand-activating the demo seeder there is equally unintended.
 */
public class DemoProfileGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    public static final String DEMO_PROFILE = "demo";

    /** Profiles that mean "this is a real deployed environment". */
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production", "staging");

    /**
     * Properties naming the deployment tier outside the profile system, so a pipeline that sets
     * only {@code APP_ENV} is still covered. Spring's relaxed binding maps {@code APP_ENV} onto
     * {@code app.env}.
     */
    private static final Set<String> ENVIRONMENT_PROPERTIES = Set.of("app.env", "APP_ENV");

    private static final Set<String> PRODUCTION_ENVIRONMENT_VALUES = Set.of("prod", "production", "staging");

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        verify(event.getEnvironment());
    }

    /**
     * @throws IllegalStateException if the demo profile is active alongside a production marker
     */
    static void verify(Environment environment) {
        Set<String> activeProfiles = new LinkedHashSet<>(Arrays.asList(environment.getActiveProfiles()));
        if (!containsIgnoringCase(activeProfiles, DEMO_PROFILE)) {
            return;
        }
        Set<String> markers = new LinkedHashSet<>();
        for (String profile : activeProfiles) {
            if (PRODUCTION_PROFILES.contains(normalise(profile))) {
                markers.add("profile '" + profile + "'");
            }
        }
        for (String property : ENVIRONMENT_PROPERTIES) {
            String value = environment.getProperty(property);
            if (value != null && PRODUCTION_ENVIRONMENT_VALUES.contains(normalise(value))) {
                markers.add(property + "=" + value);
            }
        }
        if (markers.isEmpty()) {
            return;
        }
        throw new IllegalStateException(
                "Refusing to start: the '" + DEMO_PROFILE + "' profile seeds fictional children's "
                        + "records and must never run in a deployed environment, but a production "
                        + "marker is present (" + String.join(", ", markers) + "). Remove '"
                        + DEMO_PROFILE + "' from the active profiles.");
    }

    private static boolean containsIgnoringCase(Set<String> values, String candidate) {
        return values.stream().anyMatch(value -> normalise(value).equals(candidate));
    }

    private static String normalise(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
