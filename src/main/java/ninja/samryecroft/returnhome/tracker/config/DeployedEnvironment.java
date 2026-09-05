package ninja.samryecroft.returnhome.tracker.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * The one answer to "is this a deployed environment".
 *
 * <p><b>There were four of them, and two were wrong in the only environment that matters.</b>
 * {@code DatabasePasswordGuard} had {@code {prod, production, staging, azure}} and was armed;
 * {@code DemoProfileGuard} held {@code {prod, production, staging}} <em>twice</em>, four lines apart,
 * one for profiles and one for {@code app.env}; {@code DocumentStorageConfig} had
 * {@code {prod, production}}. Production runs on {@code SPRING_PROFILES_ACTIVE=azure} - {@code
 * deploy.yml} asserts exactly that and fails the deploy otherwise - so the last two had never fired
 * there. The credential was the one guard with an observable consequence, which is the only reason
 * this was caught at all: App Insights showed the live app authenticating with the wrong credential
 * and paying a 32-second cold start.
 *
 * <p><b>The correct answer was already in the codebase.</b> Somebody worked it out, wrote it in
 * {@code DatabasePasswordGuard}, and it did not propagate - so fixing the other lists without
 * removing the duplication would leave the mechanism that produced them intact, and there would be a
 * fifth. {@code DeployedEnvironmentConsolidationGuardTest} fails when one appears.
 *
 * <p><b>The name is part of the cause.</b> "Production markers" invites the reading that
 * {@code azure} is a platform while {@code prod} is a tier, so adding it looks like a category error
 * rather than a correction. "Deployed" does not - and that is the list that got it right.
 *
 * <p><b>Why a static utility rather than a bean.</b> Two of the three callers are registered in
 * {@code META-INF/spring.factories} and run on {@code ApplicationEnvironmentPreparedEvent}, before
 * the application context exists - which is the entire point of the demo guard, since it has to beat
 * the demo seeder. Anything injectable is unavailable to them, so the earliest caller sets the shape.
 */
public final class DeployedEnvironment {

    /**
     * Lifted verbatim from {@code DatabasePasswordGuard}, which had it right.
     *
     * <p>{@code staging} arms nothing today - there is no staging pipeline, and the word appears in
     * {@code deploy.yml} only inside a commented-out slot-swap design. It stays because it is correct
     * if one ever exists: dropping the entry that is not currently exercised would be fitting the set
     * to today's pipeline rather than to the question it answers.
     */
    static final Set<String> DEPLOYED_MARKERS = Set.of("prod", "production", "staging", "azure");

    /**
     * Checked alongside the profiles, never instead of them.
     *
     * <p>This is set nowhere in {@code src/main/resources} - an unexercised branch in a security
     * control, which is the exact class of thing this consolidation removes. It is kept because it is
     * OR'd with the profile check, so it can only ever <em>arm</em> a guard and never disarm one; and
     * it stops being unexercised, because a test now sets it with no active profiles at all.
     */
    private static final Set<String> ENVIRONMENT_PROPERTIES = Set.of("app.env", "APP_ENV");

    private DeployedEnvironment() {
    }

    /** True if any active profile, or any environment marker property, names a deployed environment. */
    public static boolean isDeployed(Environment environment) {
        return !markersIn(environment).isEmpty();
    }

    /**
     * Every marker found, described well enough to put in a refusal message - {@code profile 'azure'}
     * or {@code app.env=prod}.
     *
     * <p>{@link #isDeployed} is defined as "this found nothing" rather than being a second walk over
     * the same data. {@code DemoProfileGuard} has to name what it found, and a boolean plus a
     * separately-maintained naming loop is exactly how the duplication this class exists to remove
     * got started.
     */
    public static List<String> markersIn(Environment environment) {
        if (environment == null) {
            return List.of();
        }
        List<String> markers = new ArrayList<>();
        for (String profile : environment.getActiveProfiles()) {
            if (DEPLOYED_MARKERS.contains(normalise(profile))) {
                markers.add("profile '" + profile + "'");
            }
        }
        for (String property : ENVIRONMENT_PROPERTIES) {
            String value = environment.getProperty(property);
            if (value != null && DEPLOYED_MARKERS.contains(normalise(value))) {
                markers.add(property + "=" + value);
            }
        }
        return markers;
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
