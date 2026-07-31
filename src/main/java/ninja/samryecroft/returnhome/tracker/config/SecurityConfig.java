package ninja.samryecroft.returnhome.tracker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
                        // Platform-admin-only: creating organisations stays out of org-admins' hands.
                        // Must come before the broader /admin/** rule below. /admin/theme is NOT
                        // restricted here - a Supplier ORG_ADMIN can edit their own org's brand
                        // colours too; ThemeService itself enforces which org they're allowed to touch.
                        .requestMatchers("/admin/organisations/**").hasRole("ADMIN")
                        .requestMatchers("/admin/**").hasAnyRole("ADMIN", "ORG_ADMIN")
                        .requestMatchers("/coordinator/**").hasAnyRole("COORDINATOR", "ADMIN")
                        .requestMatchers("/visitor/**").hasAnyRole("VISITOR", "ADMIN")
                        .requestMatchers("/reviewer/**").hasAnyRole("REVIEWER", "ADMIN")
                        .requestMatchers("/requests/**").hasAnyRole("HOME_STAFF", "ADMIN")
                        .requestMatchers("/children/**").hasAnyRole("HOME_STAFF", "ORG_ADMIN", "VIEWER", "ADMIN")
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
