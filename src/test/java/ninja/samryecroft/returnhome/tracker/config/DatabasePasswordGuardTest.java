package ninja.samryecroft.returnhome.tracker.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

/**
 * A deployment that forgets to inject DB_PASSWORD must fail to start rather than reach the driver
 * with an empty password (which dies confusingly, or not at all against a trust-auth database).
 */
class DatabasePasswordGuardTest {

    private MockEnvironment environmentWith(String password, String... activeProfiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(activeProfiles);
        if (password != null) {
            environment.setProperty(DatabasePasswordGuard.PASSWORD_PROPERTY, password);
        }
        return environment;
    }

    @Test
    void refusesToStartWhenNoPasswordIsConfigured() {
        for (String missing : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> DatabasePasswordGuard.verify(environmentWith(missing)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Refusing to start")
                    .hasMessageContaining("DB_PASSWORD")
                    .hasMessageContaining("Key Vault");
        }
    }

    @Test
    void refusesToStartWhenTheProfileIsAnythingOtherThanDev() {
        for (String profile : new String[] {"prod", "staging", "demo"}) {
            assertThatThrownBy(() -> DatabasePasswordGuard.verify(environmentWith(null, profile)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void allowsAConfiguredPassword() {
        assertThatCode(() -> DatabasePasswordGuard.verify(environmentWith("from-key-vault", "prod")))
                .doesNotThrowAnyException();
    }

    /** Local development gets its throwaway credentials from application-dev.properties. */
    @Test
    void allowsTheDevProfileWithoutOne() {
        assertThatCode(() -> DatabasePasswordGuard.verify(environmentWith(null, "dev")))
                .doesNotThrowAnyException();
        assertThatCode(() -> DatabasePasswordGuard.verify(environmentWith(null, "DEV")))
                .doesNotThrowAnyException();
    }

    /** Fails if the spring.factories registration is dropped. */
    @Test
    void isRegisteredSoARealApplicationLaunchFailsFast() {
        SpringApplicationBuilder application = new SpringApplicationBuilder(EmptyConfiguration.class)
                .web(WebApplicationType.NONE)
                .profiles("prod");

        // The build hands the test JVM a DB_PASSWORD, so blank it on the command line - the highest
        // precedence source - to reproduce a deployment that never injected one.
        assertThatThrownBy(() -> application.run("--spring.datasource.password="))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no database password is configured");
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {
    }
}
