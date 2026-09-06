package ninja.samryecroft.returnhome.tracker.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's source of "now", as an injectable bean.
 *
 * <p><b>T241.</b> Screens that show time remaining against the statutory 72 hours read the wall clock
 * directly, which leaves the tests for them <em>anchored to it</em>:
 * {@code ConfirmVisitTimeIntegrationTest} built its fixture from {@code LocalDateTime.now()} and then
 * asserted a specific "61h 59m left", a figure that is only correct while the seconds discarded by
 * truncation plus the time the test itself takes stay inside one minute. It passes today and drifts
 * on a slow runner.
 *
 * <p><b>Why not just loosen the assertion.</b> That is the anti-pattern Kevin ruled against for
 * T221's guard, and it is worse here than the flake it fixes: a threshold widened until it cannot
 * fail is coverage that has stopped being a test while still being counted as one. And this test is
 * in the <b>blocking</b> lane, so the alternative failure mode has teeth - <b>a flaky test in a
 * blocking lane trains people to re-run CI on red, and a lane whose red is routinely re-run has
 * stopped being a gate while still looking like one.</b>
 *
 * <p>{@code DeadlineTracker} was already written to take {@code now} as a parameter rather than
 * reading it, so every deadline calculation in the application is already clock-agnostic. The wall
 * clock is read only where a request begins. Making that read injectable is the smallest change that
 * lets a test pin the instant instead of chasing it.
 *
 * <p><b>The default is the system clock in the system zone</b>, so nothing about production timing
 * changes. Only tests supply anything else.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
