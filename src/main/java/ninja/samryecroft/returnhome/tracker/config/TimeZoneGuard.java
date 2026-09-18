package ninja.samryecroft.returnhome.tracker.config;

import java.time.ZoneId;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * Refuses to start a deployed application whose JVM default zone is not UK civil time.
 *
 * <p><b>The bug this exists to make impossible.</b> Every operator-entered time in this application -
 * {@code returnedAt}, {@code missingSince}, {@code heldAt}, {@code scheduledAt} - is typed by staff in
 * UK wall-clock and stored as a {@code LocalDateTime} in a {@code TIMESTAMP WITHOUT TIME ZONE} column.
 * The statutory 72-hour deadline compares those values against "now". App Service Linux runs UTC
 * unless {@code WEBSITE_TIME_ZONE} is set, so with it unset the comparison is an hour out for the
 * ~7 months of BST, in the direction that <em>under</em>-reports lateness: {@code DeadlineTracker}
 * reports {@code DUE_SOON} where the case is genuinely {@code OVERDUE}, the dashboard's overdue tile
 * undercounts, compliance over-reports, and in a marginal case {@code SeventyTwoHourReading} prints
 * <em>"Within 72 hours"</em> into a document bound for a court or an IRO for an interview that was not.
 *
 * <p><b>Why a guard and not just the setting.</b> The setting is {@code WEBSITE_TIME_ZONE} in the
 * {@code app_service} Terraform module, and the failure mode of losing it is silent: nothing throws,
 * no alert fires, the screens stay plausible, and every affected number is wrong by exactly one hour
 * for part of the year. {@code T354} is the recorded precedent for app settings being changed out of
 * band and then planned away by a later apply - so "it is in Terraform" is not on its own a guarantee
 * that it is on the running instance. This turns a silent one-hour error into a refused boot.
 *
 * <p><b>Why {@code ZoneId} equality and not an offset check.</b> A fixed-offset zone cannot be
 * correct here: {@code UTC} is wrong for BST and {@code GMT+1} is wrong for GMT, so anything that
 * merely matched today's offset would pass in one half of the year and fail in the other. Only a
 * region zone that carries the UK's transition rules is right, and there is exactly one.
 *
 * <p><b>Why it arms only when deployed.</b> Developer machines and CI runners are whatever zone they
 * are - the test suite is zone-agnostic because {@code DeadlineTracker} takes {@code now} as a
 * parameter and {@link ClockConfig} makes the wall-clock read injectable. Pinning the zone matters
 * where the statutory clock is actually read against real records, which is exactly
 * {@link DeployedEnvironment}'s question.
 *
 * <p>Runs on {@link ApplicationEnvironmentPreparedEvent} via {@code META-INF/spring.factories}, for
 * the same reason as {@link DatabasePasswordGuard}: before anything reads a clock.
 */
public class TimeZoneGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    /**
     * The statutory zone. Deliberately a constant and not a property: a configurable expected zone
     * would let the thing being asserted be set by the same mechanism that gets it wrong, which is no
     * assertion at all. A genuine non-UK deployment changes this line, with a reviewer attached.
     */
    static final ZoneId REQUIRED_ZONE = ZoneId.of("Europe/London");

    /** The App Service setting that produces it - named in the refusal so the fix is in the error. */
    static final String PLATFORM_SETTING = "WEBSITE_TIME_ZONE";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        verify(event.getEnvironment());
    }

    /**
     * @throws IllegalStateException if a deployed application's default zone is not {@link #REQUIRED_ZONE}
     */
    static void verify(Environment environment) {
        verify(environment, ZoneId.systemDefault());
    }

    /**
     * Zone passed in so a test can assert both outcomes without mutating the JVM's default, which is
     * process-global and would leak into whatever ran next in the same fork.
     *
     * @throws IllegalStateException if a deployed application's default zone is not {@link #REQUIRED_ZONE}
     */
    static void verify(Environment environment, ZoneId actualZone) {
        if (!DeployedEnvironment.isDeployed(environment)) {
            return;
        }
        if (REQUIRED_ZONE.equals(actualZone)) {
            return;
        }
        throw new IllegalStateException(
                "Refusing to start: this deployed application's default time zone is '" + actualZone
                        + "', not '" + REQUIRED_ZONE + "'. Every 72-hour statutory deadline compares "
                        + "operator-entered UK wall-clock times against this zone, so starting would "
                        + "silently mis-state lateness by the UTC offset - under-reporting it through "
                        + "BST, including in generated documents. Set the " + PLATFORM_SETTING
                        + " app setting to '" + REQUIRED_ZONE + "' (it is declared in the app_service "
                        + "Terraform module; if it is missing on the instance, something removed it "
                        + "out of band). Deployment markers found: "
                        + DeployedEnvironment.markersIn(environment) + ".");
    }
}
