package ninja.samryecroft.returnhome.tracker.config;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
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


    /**
     * Properties naming the deployment tier outside the profile system, so a pipeline that sets
     * only {@code APP_ENV} is still covered. Spring's relaxed binding maps {@code APP_ENV} onto
     * {@code app.env}.
     */
    private static final Set<String> ENVIRONMENT_PROPERTIES = Set.of("app.env", "APP_ENV");


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
        // One answer to "is this deployed", shared with DatabasePasswordGuard and
        // DocumentStorageConfig. This class previously held that set TWICE - once for profiles and
        // once for app.env, byte-identical, four lines apart - and neither copy contained 'azure',
        // the profile production actually runs on. So this guard never fired there, and
        // SPRING_PROFILES_ACTIVE=azure,demo would have started the seeder that writes fictional
        // children's records. The pipeline asserts the profile is exactly 'azure', but App Service
        // settings can be changed in the portal without the pipeline running at all - which is
        // precisely the case this guard is the defence in depth for, and precisely where it failed.
        List<String> markers = DeployedEnvironment.markersIn(environment);
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
