package ninja.samryecroft.returnhome.tracker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * What {@link TimeZoneGuard} is worth is entirely in <em>when it refuses</em>, so both outcomes are
 * asserted against the same zone: a UTC default boots locally and is refused on {@code azure}.
 *
 * <p>The zone is passed in rather than set on the JVM. {@code TimeZone.setDefault} is process-global
 * and surefire forks are shared, so a test that mutated it would change the clock under whatever ran
 * next in the same fork - a flake with no obvious cause, in a suite whose subject is time.
 */
class TimeZoneGuardTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private MockEnvironment deployed() {
        return new MockEnvironment().withProperty("spring.profiles.active", "azure");
    }

    @Test
    @DisplayName("a deployed app whose zone is UTC is refused, naming the setting that fixes it")
    void refusesUtcWhenDeployed() {
        assertThatThrownBy(() -> TimeZoneGuard.verify(deployed(), UTC))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Europe/London")
                .hasMessageContaining(TimeZoneGuard.PLATFORM_SETTING)
                .hasMessageContaining("profile 'azure'");
    }

    @Test
    @DisplayName("a deployed app in Europe/London starts")
    void allowsRequiredZoneWhenDeployed() {
        assertThatCode(() -> TimeZoneGuard.verify(deployed(), TimeZoneGuard.REQUIRED_ZONE))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("undeployed environments are whatever zone they are - CI and laptops are not pinned")
    void ignoresZoneWhenNotDeployed() {
        assertThatCode(() -> TimeZoneGuard.verify(new MockEnvironment(), UTC))
                .doesNotThrowAnyException();
        assertThatCode(() -> TimeZoneGuard.verify(
                new MockEnvironment().withProperty("spring.profiles.active", "dev"), UTC))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the app.env marker arms it too, not only the profile")
    void armsOnEnvironmentPropertyMarker() {
        assertThatThrownBy(() -> TimeZoneGuard.verify(
                new MockEnvironment().withProperty("app.env", "prod"), UTC))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.env=prod");
    }

    @Test
    @DisplayName("a fixed-offset zone is rejected even when its offset matches today's UK offset")
    void rejectsFixedOffsetZoneThatHappensToMatchToday() {
        // The reason REQUIRED_ZONE is compared by identity and not by current offset: whichever
        // fixed offset you pick, it is correct for part of the year and wrong for the rest. This
        // asserts the guard does not accept the one that is right *today*, whichever half that is.
        ZoneId matchingToday =
                ZoneId.of(ZonedDateTime.now(TimeZoneGuard.REQUIRED_ZONE).getOffset().getId());
        assertThat(matchingToday).isNotEqualTo(TimeZoneGuard.REQUIRED_ZONE);
        assertThatThrownBy(() -> TimeZoneGuard.verify(deployed(), matchingToday))
                .isInstanceOf(IllegalStateException.class);
    }
}
