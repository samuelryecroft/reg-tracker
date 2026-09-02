package ninja.samryecroft.returnhome.tracker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

/**
 * The demo profile seeds fictional children's records, so DEPLOYMENT-PLAN.md &sect;3 forbids it from
 * ever activating in a deployed environment. This covers the config half of that control; the
 * pipeline half is asserted in the deploy workflow.
 */
class DemoProfileGuardTest {

    private MockEnvironment environmentWith(String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        return environment;
    }

    @Test
    void refusesToStartWhenDemoIsCombinedWithAProductionProfile() {
        for (String productionProfile : new String[] {"prod", "production", "staging", "PROD"}) {
            assertThatThrownBy(() -> DemoProfileGuard.verify(environmentWith("demo", productionProfile)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Refusing to start")
                    .hasMessageContaining(productionProfile);
        }
    }

    @Test
    void refusesToStartWhenDemoIsCombinedWithAProductionEnvironmentProperty() {
        MockEnvironment environment = environmentWith("demo");
        environment.setProperty("app.env", "prod");

        assertThatThrownBy(() -> DemoProfileGuard.verify(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.env=prod");
    }

    @Test
    void allowsDemoOnItsOwnAndAlongsideDevelopmentProfiles() {
        assertThatCode(() -> DemoProfileGuard.verify(environmentWith("demo"))).doesNotThrowAnyException();
        assertThatCode(() -> DemoProfileGuard.verify(environmentWith("demo", "dev"))).doesNotThrowAnyException();
        assertThatCode(() -> DemoProfileGuard.verify(environmentWith("demo", "local"))).doesNotThrowAnyException();
    }

    @Test
    void allowsAProductionEnvironmentThatDoesNotActivateDemo() {
        MockEnvironment environment = environmentWith("prod");
        environment.setProperty("app.env", "prod");

        assertThatCode(() -> DemoProfileGuard.verify(environment)).doesNotThrowAnyException();
    }

    /**
     * The guard is only worth anything if it is actually wired in, so drive a real (empty, non-web)
     * SpringApplication rather than calling {@code verify} directly - this fails if the
     * spring.factories registration is dropped.
     */
    @Test
    void isRegisteredSoARealApplicationLaunchFailsFast() {
        SpringApplicationBuilder application = new SpringApplicationBuilder(EmptyConfiguration.class)
                .web(WebApplicationType.NONE)
                .profiles("demo", "prod");

        assertThatThrownBy(application::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must never run in a deployed environment");
    }

    @Test
    void startsNormallyWhenDemoIsNotActive() {
        try (var context = new SpringApplicationBuilder(EmptyConfiguration.class)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .run()) {
            assertThat(context.isActive()).isTrue();
        }
    }

    @Test
    void theDemoProfileIsNotBakedIntoTheDefaultProfileSet() throws IOException {
        String properties = Files.readString(
                Path.of("src/main/resources/application.properties"), StandardCharsets.UTF_8);

        assertThat(properties).doesNotContain("spring.profiles.active");
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {
    }
}
