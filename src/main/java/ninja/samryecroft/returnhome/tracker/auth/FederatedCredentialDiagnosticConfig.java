package ninja.samryecroft.returnhome.tracker.auth;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the T184 diagnostic only when it is explicitly switched on.
 *
 * <p>{@code matchIfMissing = false} is the load-bearing half: a diagnostic that makes an outbound
 * token request on every start, in every environment, by default is not a diagnostic - it is a
 * dependency nobody chose. It goes on for one ordinary deploy, the logs are read, and it goes off.
 */
@Configuration
@EnableConfigurationProperties(FederatedCredentialDiagnosticProperties.class)
@ConditionalOnProperty(prefix = "app.auth.fic-diagnostic", name = "enabled", havingValue = "true")
public class FederatedCredentialDiagnosticConfig {

    @Bean
    FederatedCredentialDiagnostic federatedCredentialDiagnostic(
            FederatedCredentialDiagnosticProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // The managed identity endpoint answers on a link-local address and the token
                // endpoint is public; neither should ever be reached by following a redirect, and a
                // redirect on a credential exchange is worth failing rather than following.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return new FederatedCredentialDiagnostic(properties, httpClient, System::getenv);
    }
}
