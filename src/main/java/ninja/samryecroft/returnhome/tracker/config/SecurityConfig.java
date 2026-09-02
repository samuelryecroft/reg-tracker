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
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login", "/css/**", "/js/**", "/webjars/**", "/error").permitAll()
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
        return http.build();
    }
}
