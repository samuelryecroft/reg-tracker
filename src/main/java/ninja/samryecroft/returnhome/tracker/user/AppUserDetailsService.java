package ninja.samryecroft.returnhome.tracker.user;

import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.security.LoginAttemptService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Resolves what was typed at the login form to an account.
 *
 * <p><b>Email is the login identifier (T344).</b> A username was a second thing to remember, to
 * administer and to get wrong; people now sign in with the address we already hold and already send
 * their sign-in codes to.
 *
 * <p><b>One narrow exception, and it is the fire exit.</b> The break-glass account has no email
 * address and must never be given one - a mailbox on that account would make the emergency path
 * depend on the very channel the exemption exists to survive. So it keeps a username, and it is the
 * only row that does. The fallback below is therefore not "try the username too": it resolves a
 * username ONLY to a break-glass row, so a legacy username left on an ordinary account cannot be used
 * to sign in and cannot become a second, quieter way into somebody's record.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;
    private final LoginAttemptService loginAttemptService;

    public AppUserDetailsService(UserRepository userRepository, LoginAttemptService loginAttemptService) {
        this.userRepository = userRepository;
        this.loginAttemptService = loginAttemptService;
    }

    /**
     * @param identifier whatever was typed in the single login field - an email address, or the
     *                   break-glass account's username
     */
    @Override
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        User user = userRepository.findByEmailIgnoreCase(identifier)
                .or(() -> breakGlassNamed(identifier))
                .orElseThrow(() -> new UsernameNotFoundException("No account for: " + identifier));
        return new AppUserPrincipal(user, loginAttemptService.isLocked(identifier));
    }

    /**
     * The emergency account, and only ever that account.
     *
     * <p>Asked for by its flag first and then checked against the typed name, rather than looking a
     * username up and asking whether the result happens to be break-glass. The two are equivalent
     * today; they stop being equivalent the moment an ordinary row still carries an old username,
     * and this order is the one that cannot be talked into returning it.
     */
    private Optional<User> breakGlassNamed(String identifier) {
        return userRepository.findByBreakGlassTrue()
                .filter(user -> user.getUsername() != null
                        && user.getUsername().equalsIgnoreCase(identifier));
    }
}
