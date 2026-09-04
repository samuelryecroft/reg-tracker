package ninja.samryecroft.returnhome.tracker.auth;

import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;

/**
 * A stub Entra registration, shared by every test that needs the OIDC path switched on.
 *
 * <p>Supplied as a bean rather than through properties for one specific reason:
 * <b>{@code end_session_endpoint} is populated only by OIDC discovery.</b> Spring Boot has no
 * property for it, so a registration assembled from hand-written endpoints has empty provider
 * metadata - and {@code OidcClientInitiatedLogoutSuccessHandler} then falls back silently to a local
 * redirect instead of failing. A logout test built that way passes while proving the opposite of its
 * name, which is how this was found. Production uses {@code issuer-uri}, so discovery fills it in.
 *
 * <p>The endpoints are the only thing stubbed: the filter chain, the resolver, PKCE and the logout
 * handler are all the real ones. There is no tenant to talk to, and discovery against a
 * non-existent one is exactly what this avoids.
 */
@TestConfiguration
public class EntraStubTenantConfig {

    @Bean
    ClientRegistrationRepository clientRegistrationRepository() {
        return new InMemoryClientRegistrationRepository(ClientRegistration.withRegistrationId("entra")
                .clientId("test-client-id")
                .clientSecret("test-client-secret")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .authorizationUri("https://tenant.example/oauth2/v2.0/authorize")
                .tokenUri("https://tenant.example/oauth2/v2.0/token")
                .jwkSetUri("https://tenant.example/discovery/v2.0/keys")
                .userNameAttributeName("sub")
                .providerConfigurationMetadata(Map.of(
                        "end_session_endpoint", "https://tenant.example/oauth2/v2.0/logout"))
                .build());
    }
}
