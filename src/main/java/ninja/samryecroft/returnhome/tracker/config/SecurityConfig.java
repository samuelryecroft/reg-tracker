package ninja.samryecroft.returnhome.tracker.config;

import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import ninja.samryecroft.returnhome.tracker.security.LoginFailureHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import ninja.samryecroft.returnhome.tracker.security.LockedAccountFilter;
import ninja.samryecroft.returnhome.tracker.security.LoginAttemptService;
import org.springframework.context.ApplicationEventPublisher;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            LoginFailureHandler loginFailureHandler,
            LoginAttemptService loginAttemptService,
            ApplicationEventPublisher eventPublisher) throws Exception {
        // Constructed here rather than injected as a bean: Boot auto-registers Filter BEANS into the
        // servlet chain as well, which would place this ahead of Spring Security's chain entirely and
        // make its real position differ from the one addFilterBefore states. See LockedAccountFilter.
        LockedAccountFilter lockedAccountFilter =
                new LockedAccountFilter(loginAttemptService, loginFailureHandler, eventPublisher);
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
                        // T215: without this, EVERY AuthenticationException lands on /login?error
                        // and a locked-out user is told to check their password - the one thing
                        // that cannot work - on every attempt for the whole window.
                        .failureHandler(loginFailureHandler)
                        .permitAll())
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .permitAll())
                // T221: BEFORE the authentication filter, and the position is the entire point.
                // NOT a fix for a timing oracle - there isn't one. This comment used to say a locked
                // real account paid no hash while a locked unknown one paid a full BCrypt, ~53ms
                // apart; spring-security-core 7.1.0 does NOT do that. performPreCheck catches the
                // LockedException and runs additionalAuthenticationChecks anyway, because the
                // constructor sets alwaysPerformAdditionalChecksOnUser = true - a deliberate
                // timing-equalisation mitigation, on by default. Both locked paths already cost one
                // hash (measured: 76ms vs 87ms).
                // What this buys is defence in depth: that setter is public and one call from off,
                // and nothing here sets it, so the equalisation is a default we INHERIT rather than
                // a property we ASSERT. Rejecting here makes it ours, costs zero hashes instead of
                // one wasted one, and LockedAccountTimingGuardTest would catch the default flipping.
                // It cannot live in LoginFailureHandler: by the time a failure handler runs, the
                // hash has already happened or already been skipped.
                // Full disassembly and the two rejected alternatives: see LockedAccountFilter.
                .addFilterBefore(lockedAccountFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
