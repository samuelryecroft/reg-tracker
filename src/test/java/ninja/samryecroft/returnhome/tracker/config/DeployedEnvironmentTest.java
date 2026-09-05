package ninja.samryecroft.returnhome.tracker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * T189: one answer to "is this a deployed environment", and what each caller gains from it.
 *
 * <p>There were four answers in three files. Production runs on {@code SPRING_PROFILES_ACTIVE=azure}
 * - {@code deploy.yml} asserts exactly that - and two of the four did not contain {@code azure}, so
 * they had never fired there.
 */
class DeployedEnvironmentTest {

    @Test
    void theProfileProductionActuallyRunsOnCountsAsDeployed() {
        assertThat(DeployedEnvironment.isDeployed(
                new MockEnvironment().withProperty("spring.profiles.active", "azure"))).isTrue();
    }

    @Test
    void aDevelopersMachineIsNotDeployed() {
        assertThat(DeployedEnvironment.isDeployed(new MockEnvironment())).isFalse();
        assertThat(DeployedEnvironment.isDeployed(
                new MockEnvironment().withProperty("spring.profiles.active", "dev"))).isFalse();
    }

    /**
     * {@code app.env} is set nowhere in {@code src/main/resources}, which made it an unexercised
     * branch in a security control - the exact class of thing this consolidation removes. It is kept
     * because it is OR'd and can therefore only ever <em>arm</em> a guard, never disarm one. This
     * test is what stops it being unexercised.
     */
    @Test
    void theEnvironmentPropertyArmsTheAnswerWithNoProfilesAtAll() {
        assertThat(DeployedEnvironment.isDeployed(
                new MockEnvironment().withProperty("app.env", "prod"))).isTrue();
        assertThat(DeployedEnvironment.isDeployed(
                new MockEnvironment().withProperty("APP_ENV", "production"))).isTrue();
    }

    /** Markers are named, because a refusal that will not say what tripped it costs an hour. */
    @Test
    void everyMarkerFoundIsNamed() {
        assertThat(DeployedEnvironment.markersIn(new MockEnvironment()
                .withProperty("spring.profiles.active", "azure")
                .withProperty("app.env", "prod")))
                .containsExactlyInAnyOrder("profile 'azure'", "app.env=prod");
    }

    /**
     * The gap this consolidation was really about. {@code DemoProfileGuard} held
     * {@code {prod, production, staging}} - twice - so {@code azure,demo} did not trip it, and the
     * demo seeder writes fictional children's records.
     *
     * <p>The pipeline asserts the profile is exactly {@code azure}, but App Service settings can be
     * changed in the portal without the pipeline running at all - precisely the case a
     * defence-in-depth guard exists for, and precisely where it did not fire.
     */
    @Test
    void theDemoSeederCanNoLongerRunOnTheProductionProfile() {
        assertThatThrownBy(() -> DemoProfileGuard.verify(
                new MockEnvironment().withProperty("spring.profiles.active", "azure,demo")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("azure");

        assertThatCode(() -> DemoProfileGuard.verify(
                new MockEnvironment().withProperty("spring.profiles.active", "dev,demo")))
                .doesNotThrowAnyException();
    }

    /**
     * <b>The polarity hazard.</b> {@code isExempt} is "positively local AND not deployed", and the
     * halves are not redundant: collapsing it to {@code !isDeployed(...)} - which is what "point them
     * all at the shared method" invites - would exempt a JVM with <em>no profiles at all</em>, so a
     * bare {@code java -jar} would skip the database-password check entirely.
     *
     * <p>A security control silently weakened by a refactor whose entire purpose was to arm security
     * controls. Kevin named the shape before it was written; this pins it.
     */
    @Test
    void aJvmWithNoProfilesIsNotExemptFromTheDatabasePasswordCheck() {
        assertThatThrownBy(() -> DatabasePasswordGuard.verify(new MockEnvironment()))
                .as("no profiles must not read as 'local', or a bare java -jar skips the check")
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> DatabasePasswordGuard.verify(
                new MockEnvironment().withProperty("spring.profiles.active", "dev,azure")))
                .as("a local profile alongside a deployed one must not launder the deployment")
                .isInstanceOf(IllegalStateException.class);

        assertThatCode(() -> DatabasePasswordGuard.verify(
                new MockEnvironment().withProperty("spring.profiles.active", "dev")))
                .as("a genuine developer machine stays exempt")
                .doesNotThrowAnyException();
    }
}
