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
import ninja.samryecroft.returnhome.tracker.security.secondfactor.SecondFactorPolicy;
import ninja.samryecroft.returnhome.tracker.security.secondfactor.SecondFactorService;
import ninja.samryecroft.returnhome.tracker.security.secondfactor.SecondFactorSuccessHandler;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.authentication.logout.LogoutFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.session.SimpleRedirectSessionInformationExpiredStrategy;
import ninja.samryecroft.returnhome.tracker.security.session.AuthenticatedSessionRegistrar;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * The register of who is signed in, so that a password change can end their sessions (T357).
     *
     * <p>In-memory and per-instance. Complete on the single instance we run; see
     * {@code SessionTerminationService} for what changes if we scale out.
     */
    @Bean
    public SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /**
     * <b>Not optional, and its absence is invisible.</b> {@code SessionRegistryImpl} learns that a
     * session has ended only from a {@code SessionDestroyedEvent}, and the servlet container
     * publishes one only if this listener is registered. Without it the registry never forgets
     * anybody: it accumulates dead sessions for the life of the process, and "expire this user's
     * sessions" starts walking entries for sessions that stopped existing days ago. Nothing fails -
     * it just grows, and the expiry it reports is partly fiction.
     */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            LoginFailureHandler loginFailureHandler,
            LoginAttemptService loginAttemptService,
            SecondFactorService secondFactorService,
            SecondFactorPolicy secondFactorPolicy,
            SessionRegistry sessionRegistry,
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
                        // T322: /login/verify is permitAll for the same reason /login is - the whole point of
                        // the second-factor step is that it happens while the session is NOT
                        // authenticated. It protects itself: the page is inert without the pending
                        // attribute that only a correct password can put in the session.
                        .requestMatchers("/login", "/login/verify", "/forgot-password", "/reset-password", "/reset-password/**", "/css/**", "/js/**", "/fonts/**", "/icons/**", "/webjars/**", "/error").permitAll()
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
                        // T322: replaces .defaultSuccessUrl("/", false), whose behaviour the handler
                        // reproduces exactly for accounts that do not need a second factor. A
                        // password being correct is no longer the same thing as being signed in.
                        .successHandler(new SecondFactorSuccessHandler(secondFactorService, secondFactorPolicy, sessionRegistry))
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
                .addFilterBefore(lockedAccountFilter, UsernamePasswordAuthenticationFilter.class)
                // T357: a password reset has to end the sessions of whoever is already signed in.
                //
                // maximumSessions(-1) is UNLIMITED - this is deliberately NOT a concurrent-session
                // limit, and nobody is being logged out for signing in twice. The only reason it is
                // configured at all is that it is what puts ConcurrentSessionFilter in the chain,
                // and that filter is the thing that notices SessionInformation.isExpired() and ends
                // the request. Set a registry without it and expireNow() marks a flag that nothing
                // ever reads: every session stays usable and the code looks right.
                .sessionManagement(session -> session
                        .maximumSessions(-1)
                        .sessionRegistry(sessionRegistry)
                        // Spring's DEFAULT here writes a plain-text body with status 200. In an
                        // HTML application that is a blank-looking page in place of the one asked
                        // for, and - measured, not assumed - it made my own first version of the
                        // guard below pass while reading the 200 as "still signed in". Redirecting
                        // to the login page states what happened and gives the person the one
                        // action that helps.
                        .expiredSessionStrategy(
                                new SimpleRedirectSessionInformationExpiredStrategy("/login?expired")))
                // Registration itself cannot rely on the authentication filter's strategy, because
                // the second-factor route does not go through it - see AuthenticatedSessionRegistrar.
                // Placed before LogoutFilter so it runs on every authenticated request in the chain.
                .addFilterBefore(new AuthenticatedSessionRegistrar(sessionRegistry), LogoutFilter.class);

        return http.build();
    }
}
