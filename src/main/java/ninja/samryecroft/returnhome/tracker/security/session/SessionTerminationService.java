package ninja.samryecroft.returnhome.tracker.security.session;

import java.util.List;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

/**
 * Ends every signed-in session belonging to one account.
 *
 * <p>Called when the account's password changes, so that whoever was already inside stops being
 * inside. <b>Without it a password reset is only half a remedy:</b> the person who reset it believes
 * they have shut an intruder out, and the intruder's existing session keeps working until it happens
 * to time out.
 *
 * <p><b>Why this matches on the user id rather than handing the principal to the registry.</b>
 * {@link SessionRegistry#getAllSessions(Object, boolean)} looks the principal up in a map, so it
 * finds sessions only for an object that is {@code equals} to the registered one.
 * {@link AppUserPrincipal} does not override {@code equals}, so it is identity comparison - and the
 * principal held by the registry was built during that user's sign-in, in another request, and is
 * never the same instance as one built here. Passing it would compile, run, return an empty list and
 * expire nothing, and the call site would look completely correct.
 *
 * <p>Giving {@code AppUserPrincipal} an {@code equals} would fix this call and quietly change
 * behaviour anywhere else principals are compared or put in a set, which is a much larger blast
 * radius than the problem. So the match is made here, explicitly, on the id.
 *
 * <p><b>Known limit, stated rather than discovered later:</b> the registry is in-memory and
 * per-instance ({@code SessionRegistryImpl}; there is no Spring Session store on the classpath). On
 * a single instance - what we run today - this is complete. Scale out and it expires sessions only
 * on the instance that holds them, and the honest fix at that point is a shared session store, not a
 * cleverer loop here.
 */
@Service
public class SessionTerminationService {

    private static final Logger log = LoggerFactory.getLogger(SessionTerminationService.class);

    private final SessionRegistry sessionRegistry;

    public SessionTerminationService(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Expires every session held by this user.
     *
     * @return how many sessions were expired - returned so a caller, a test or a log line can tell
     *         "there were none" from "it did not look", which are the two states that otherwise
     *         present identically
     */
    public int terminateAllSessionsFor(Long userId) {
        if (userId == null) {
            return 0;
        }
        int expired = 0;
        for (Object principal : sessionRegistry.getAllPrincipals()) {
            if (!(principal instanceof AppUserPrincipal appUser)
                    || !userId.equals(appUser.getUserId())) {
                continue;
            }
            // includeExpiredSessions = false: one already expired needs no second marking.
            List<SessionInformation> sessions = sessionRegistry.getAllSessions(principal, false);
            for (SessionInformation session : sessions) {
                session.expireNow();
                expired++;
            }
        }
        if (expired > 0) {
            log.info("Expired {} session(s) for user id {} after a credential change", expired, userId);
        }
        return expired;
    }
}
