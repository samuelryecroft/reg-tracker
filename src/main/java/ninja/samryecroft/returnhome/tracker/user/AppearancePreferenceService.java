package ninja.samryecroft.returnhome.tracker.user;

import java.time.LocalDateTime;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T138 batch 1b: updates the SIGNED-IN user's own appearance preference.
 *
 * <p>Takes no user id - the row touched is always {@code principal.getUserId()}, resolved from the
 * authenticated session rather than a request parameter. That is what makes this safe to expose
 * with nothing else guarding it: there is no id an attacker could substitute to target someone
 * else's account, unlike the admin-driven {@code UserService} methods, which take an id and check
 * authorization against it.
 */
@Service
public class AppearancePreferenceService {

    private final UserRepository userRepository;

    public AppearancePreferenceService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Persists the preference, then refreshes the session's {@code Authentication} in place so the
     * very next render - the redirect this triggers - already reflects it. Without this, the
     * signed-in {@link AppUserPrincipal} Spring Security holds for the rest of the session is the
     * {@link User} snapshot {@link AppUserDetailsService} loaded at login time (that class is only
     * consulted at authentication, never per request), so writing straight to the database here
     * would be correct in the database and stale everywhere this session reads {@code
     * principal.getUser()} - which is everywhere, per {@link
     * ninja.samryecroft.returnhome.tracker.web.GlobalControllerAdvice}'s own pattern - until the
     * user's next login.
     *
     * <p>Re-fetches with {@link UserRepository#findByUsername}'s own entity graph (the exact query
     * {@link AppUserDetailsService} runs at login) rather than a bare {@code getReferenceById},
     * deliberately: a lazy reference is fine to mutate and save inside this transaction, but handing
     * one to a new {@link AppUserPrincipal} that outlives the transaction is the lazy-initialization
     * trap this codebase has hit before - {@code roles}/{@code organisation}/{@code homes} need to
     * already be real, loaded objects before the persistence context that could load them closes.
     */
    @Transactional
    public void updateOwnPreference(AppUserPrincipal principal, AppearancePreference preference) {
        User user = userRepository.findByUsername(principal.getUsername())
                .orElseThrow(() -> new IllegalStateException(
                        "Signed-in user '" + principal.getUsername() + "' no longer exists"));
        user.setAppearancePreference(preference);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        boolean locked = !principal.isAccountNonLocked();
        AppUserPrincipal refreshed = new AppUserPrincipal(user, locked);
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(refreshed, current.getCredentials(), current.getAuthorities()));
    }
}
