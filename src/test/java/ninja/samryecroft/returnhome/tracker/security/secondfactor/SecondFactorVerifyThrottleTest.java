package ninja.samryecroft.returnhome.tracker.security.secondfactor;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T380: the account-level and address-level bounds on code submissions, and that refusals count. */
class SecondFactorVerifyThrottleTest {

    private SecondFactorVerifyThrottle throttle;

    @BeforeEach
    void smallCaps() {
        AppProperties properties = new AppProperties();
        AppProperties.SecondFactor config = properties.getSecurity().getSecondFactor();
        config.setMaxVerifiesPerUser(3);
        config.setMaxVerifiesPerIp(5);
        config.setVerifyWindow(Duration.ofMinutes(15));
        throttle = new SecondFactorVerifyThrottle(properties);
    }

    @Test
    void anAccountIsBoundedAcrossChallengesNotPerChallenge() {
        assertThat(throttle.allow(1L, "10.0.0.1")).isTrue();
        assertThat(throttle.allow(1L, "10.0.0.1")).isTrue();
        assertThat(throttle.allow(1L, "10.0.0.1")).isTrue();
        assertThat(throttle.allow(1L, "10.0.0.1"))
                .as("a fourth submission for the same account inside the window is refused, "
                        + "whichever challenge it is aimed at")
                .isFalse();
    }

    @Test
    void anAddressIsBoundedAcrossAccounts() {
        for (long user = 1; user <= 5; user++) {
            assertThat(throttle.allow(user, "10.0.0.9")).as("submission %d from one address", user).isTrue();
        }
        assertThat(throttle.allow(6L, "10.0.0.9"))
                .as("the sixth account from the same address is refused")
                .isFalse();
        assertThat(throttle.allow(6L, "10.0.0.10"))
                .as("the same account from another address is fine - it is the address that is spent")
                .isTrue();
    }

    @Test
    void aRefusedSubmissionStillCounts() {
        for (int i = 0; i < 3; i++) {
            throttle.allow(7L, "10.0.0.7");
        }
        assertThat(throttle.allow(7L, "10.0.0.7")).isFalse();
        assertThat(throttle.allow(7L, "10.0.0.7"))
                .as("holding a throttle just under its cap must not be possible: refusals advance it too")
                .isFalse();
    }

    @Test
    void aNullAddressIsOneBucketNotAnExemption() {
        for (int i = 0; i < 5; i++) {
            throttle.allow((long) (100 + i), null);
        }
        assertThat(throttle.allow(200L, null)).isFalse();
    }
}
