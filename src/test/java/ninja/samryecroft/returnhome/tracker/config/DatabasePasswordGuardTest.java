package ninja.samryecroft.returnhome.tracker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;
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

    /**
     * The point of the guard: every environment that is not on the local allow-list still has to
     * have a password injected. 'demo' used to be in this list and is now exempt - see
     * {@link #allowsTheLocalProfilesWithoutOne()} for why that is not a weakening.
     */
    @Test
    void refusesToStartInAnyEnvironmentThatIsNotLocal() {
        for (String profile : new String[] {"prod", "production", "staging", "azure", "test"}) {
            assertThatThrownBy(() -> DatabasePasswordGuard.verify(environmentWith(null, profile)))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    /** A companion local profile is fine - it is only a deployment marker that revokes the exemption. */
    @Test
    void aLocalProfileKeepsItsExemptionAlongsideOtherLocalProfiles() {
        assertThatCode(() -> DatabasePasswordGuard.verify(environmentWith(null, "dev", "azurite")))
                .doesNotThrowAnyException();
    }

    /** A local profile alongside a deployment profile must not launder the deployment through. */
    @Test
    void anExemptProfileDoesNotCoverADeployedOneAlongsideIt() {
        for (String deployed : new String[] {"prod", "production", "staging", "azure"}) {
            assertThatThrownBy(() -> DatabasePasswordGuard.verify(environmentWith(null, "demo", deployed)))
                    .as("'demo,%s' must still demand an injected password", deployed)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Refusing to start");
        }
    }

    @Test
    void allowsAConfiguredPassword() {
        assertThatCode(() -> DatabasePasswordGuard.verify(environmentWith("from-key-vault", "prod")))
                .doesNotThrowAnyException();
    }

    /**
     * Both local profiles carry their own throwaway container credentials in
     * application-&lt;profile&gt;.properties, so there is nothing for anyone to inject. Exempting
     * them is not a hole: a machine running these is by definition not a deployment, and the
     * credentials they use are committed to the repository in plain sight.
     */
    @Test
    void allowsTheLocalProfilesWithoutOne() {
        for (String profile : new String[] {"dev", "DEV", "demo", "Demo", " demo "}) {
            assertThatCode(() -> DatabasePasswordGuard.verify(environmentWith(null, profile)))
                    .doesNotThrowAnyException();
        }
    }

    /**
     * The exemption exists so the guard understands the demo, but it is not what makes the demo
     * work: application-demo.properties supplies a password of its own. Asserting that here means
     * a future edit cannot quietly leave the demo depending on the exemption alone.
     */
    @Test
    void theDemoProfileSuppliesItsOwnPasswordRatherThanRelyingOnTheExemption() {
        Properties demo = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application-demo.properties")) {
            demo.load(in);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        assertThat(demo.getProperty(DatabasePasswordGuard.PASSWORD_PROPERTY))
                .as("application-demo.properties must carry throwaway credentials so the demo needs no env vars")
                .isNotBlank();
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
