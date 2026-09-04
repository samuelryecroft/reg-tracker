package ninja.samryecroft.returnhome.tracker.config;

import ninja.samryecroft.returnhome.tracker.auth.EntraOidcUserService;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import ninja.samryecroft.returnhome.tracker.auth.EntraAwareLogoutSuccessHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Entra sign-in, off unless the {@code entra} profile turns it on. See
     * {@code ENTRA-AUTH-DESIGN.md} §6 P3: both authentication paths exist in code, only form login
     * is live, and the build is deployable at any point in between.
     */
    @Value("${app.auth.entra.enabled:false}")
    private boolean entraEnabled;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
            ObjectProvider<EntraOidcUserService> entraOidcUserService) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // T119: /fonts/** and /icons/** are static assets the login page itself
                        // loads (the self-hosted Inter @font-face, the Phosphor sprite) - without
                        // permitAll, an unauthenticated fetch of either is intercepted, saved as a
                        // "continue to this URL" target, and redirects a real login back to a font
                        // or icon file instead of the intended landing page.
                        .requestMatchers("/login", "/css/**", "/js/**", "/fonts/**", "/icons/**", "/webjars/**", "/error").permitAll()
                        // WS-C: the health endpoint (and its liveness/readiness groups) is public so
                        // App Service probes can reach it unauthenticated. show-details=when-authorized
                        // means anonymous callers still only see {"status":"UP"}. Every OTHER actuator
                        // endpoint (metrics, info, ...) is ADMIN-only - never anonymous.
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).hasRole("ADMIN")
                        // Platform-admin-only: creating organisations stays out of org-admins' hands.
                        // Must come before the broader /admin/** rule below. /admin/theme is NOT
                        // restricted here - a Supplier ORG_ADMIN can edit their own org's brand
                        // colours too; ThemeService itself enforces which org they're allowed to touch.
                        .requestMatchers("/admin/organisations/**").hasRole("ADMIN")
                        .requestMatchers("/admin/**").hasAnyRole("ADMIN", "ORG_ADMIN")
                        // Roadmap 2.3: the request list is a real drill-through target for the
                        // dashboard's tiles and breakdown rows, so Care Provider ORG_ADMIN/VIEWER
                        // need read access to it too - narrower than /coordinator/** as a whole,
                        // which stays allocate-capable for COORDINATOR/ADMIN only. Must come first.
                        .requestMatchers(HttpMethod.GET, "/coordinator/requests").hasAnyRole("COORDINATOR", "ADMIN", "ORG_ADMIN", "VIEWER")
                        .requestMatchers("/coordinator/**").hasAnyRole("COORDINATOR", "ADMIN")
                        .requestMatchers("/dashboard/**").hasAnyRole("ORG_ADMIN", "VIEWER", "COORDINATOR")
                        // Roadmap 2.5: the org-wide case-activity feed + its CSV export. Exporting is
                        // a capability separate from viewing (D-6) - ExportCapability narrows this
                        // further per-request; this matcher is just "authenticated enough to try".
                        // HOME_STAFF/VISITOR/REVIEWER excluded - none of them has an org-wide view of
                        // anyone else's case activity anywhere else in the app either.
                        .requestMatchers("/audit/**").hasAnyRole("ORG_ADMIN", "VIEWER", "COORDINATOR", "ADMIN")
                        .requestMatchers("/visitor/**").hasAnyRole("VISITOR", "ADMIN")
                        .requestMatchers("/reviewer/**").hasAnyRole("REVIEWER", "ADMIN")
                        .requestMatchers("/requests/**").hasAnyRole("HOME_STAFF", "ADMIN")
                        .requestMatchers("/children/**").hasAnyRole("HOME_STAFF", "ORG_ADMIN", "VIEWER", "ADMIN")
                        // Defence in depth only - ExportCapability is the real gate, because the
                        // filter chain can express the role ceiling but not the per-account grant.
                        .requestMatchers("/export/**").hasAnyRole("ADMIN", "ORG_ADMIN", "COORDINATOR", "VIEWER")
                        .requestMatchers("/interview-requests/**").authenticated()
                        .requestMatchers("/reports/**").authenticated()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", false)
                        .permitAll())
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .permitAll());

        if (entraEnabled) {
            configureEntraLogin(http, clientRegistrations, entraOidcUserService);
        }
        return http.build();
    }

    /**
     * Adds OIDC sign-in alongside form login, which stays live: the cutover sequence depends on
     * proving an ADMIN can get in through Entra <em>before</em> the local path is removed, and
     * removing it is P8, after cutover has been lived with.
     *
     * <p>Authorization is untouched by this. Entra answers who you are; which organisation you
     * belong to, what you may do and which homes you may see all stay in our own database, so
     * {@code OrganisationAccessService} and every rule above continue to be the only thing deciding
     * access (§3). That is what makes this phase inert rather than merely disabled.
     *
     * <p>The account link itself is P4 and does not exist yet, so with this on today a successful
     * Entra authentication produces a principal with no application user behind it. That is why
     * the flag is off by default and why nothing sets the {@code entra} profile.
     */
    private void configureEntraLogin(HttpSecurity http,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
            ObjectProvider<EntraOidcUserService> entraOidcUserService) throws Exception {
        ClientRegistrationRepository registrations = requireClientRegistrations(clientRegistrations.getIfAvailable());

        // PKCE is not configured here because Spring Security 7 applies it to every client,
        // confidential ones included - an explicit customizer was measurably a no-op. It still
        // matters: it closes authorization-code interception at the redirect, which lands in a
        // browser on a shared device in a care home (§5 D1). EntraLoginEnabledTest asserts the
        // code_challenge is really on the wire, so a downgrade or a future default change shows up
        // as a failing test rather than as a quietly weaker flow.
        // RP-initiated logout, branched: see EntraAwareLogoutSuccessHandler for why an
        // unconditional one would break sign-out for form-login users while both paths are live.
        //
        // {baseUrl} rather than a literal host, matching the redirect-uri in
        // application-entra.properties and for the same reason recorded there - a custom domain is
        // expected (WS-I), and hardcoding here would leave sign-IN surviving that move while
        // sign-OUT quietly stopped matching what the tenant has registered.
        OidcClientInitiatedLogoutSuccessHandler oidcLogout =
                new OidcClientInitiatedLogoutSuccessHandler(registrations);
        oidcLogout.setPostLogoutRedirectUri("{baseUrl}/login?logout");
        http.logout(logout -> logout
                .logoutSuccessHandler(new EntraAwareLogoutSuccessHandler(oidcLogout, "/login?logout"))
                .permitAll());

        http.oauth2Login(oauth2 -> oauth2
                // The same page as form login, so there is one place a signed-out user lands
                // whichever path is live.
                .loginPage("/login")
                // P4: without this the stock OidcUserService returns a DefaultOidcUser, which is not
                // an AppUserPrincipal - and the resulting failure is silent rather than loud. See
                // EntraUserPrincipal's javadoc: 50 @AuthenticationPrincipal parameters would be
                // injected null, and AuthenticationAuditListener would stop writing LOGIN_SUCCESS
                // without anything throwing.
                .userInfoEndpoint(userInfo -> userInfo.oidcUserService(requireOidcUserService(entraOidcUserService)))
                .defaultSuccessUrl("/", false));
    }

    /**
     * Fail fast rather than fall back. A deployment that asked for Entra and did not get it would
     * otherwise start, serve form login, and look healthy - so the misconfiguration would be
     * reported by whoever could not sign in, which for a front door is the worst possible channel.
     * Mirrors {@code DocumentStorageConfig} refusing to start on a production misconfiguration.
     */
    static ClientRegistrationRepository requireClientRegistrations(ClientRegistrationRepository registrations) {
        if (registrations == null) {
            throw new IllegalStateException(
                    "app.auth.entra.enabled is true but no OAuth2 client registration is configured. "
                            + "Activate the 'entra' profile (application-entra.properties), which supplies "
                            + "spring.security.oauth2.client.registration.entra.*, or set the flag back to false.");
        }
        return registrations;
    }

    /**
     * Same fail-fast reasoning as {@link #requireClientRegistrations}: without our user service the
     * OIDC path would silently fall back to a DefaultOidcUser, which is not an AppUserPrincipal -
     * and that failure does not throw. It injects null into every controller and stops the audit
     * listener writing sign-in rows. Refusing to start beats starting into that.
     */
    static EntraOidcUserService requireOidcUserService(ObjectProvider<EntraOidcUserService> provider) {
        EntraOidcUserService service = provider.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException(
                    "app.auth.entra.enabled is true but no EntraOidcUserService is available. Without it "
                            + "a successful Entra sign-in would produce a principal with no application user "
                            + "behind it, injected as null rather than failing.");
        }
        return service;
    }
}
