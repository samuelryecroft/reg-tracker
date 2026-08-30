package ninja.samryecroft.returnhome.tracker.security;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * Feeds Spring Security's own authentication events into {@link LoginAttemptService}.
 *
 * <p>Listens specifically to {@code AuthenticationFailureBadCredentialsEvent} rather than the
 * broader {@code AbstractAuthenticationFailureEvent}: a locked account raises a
 * {@code LockedException}, which is itself a failure event, so counting the whole hierarchy would
 * let every rejected attempt during a lockout extend that lockout indefinitely.
 *
 * <p>Integration note for the audit branch (feat/audit-trail): its {@code AuthenticationAuditListener}
 * intentionally does the opposite and listens to the whole hierarchy, because an audit trail
 * <em>should</em> record attempts made against an already-locked account. The two listeners are
 * complementary and can coexist unchanged when the branches merge.
 */
@Component
public class LoginAttemptListener {

    private final LoginAttemptService loginAttemptService;

    public LoginAttemptListener(LoginAttemptService loginAttemptService) {
        this.loginAttemptService = loginAttemptService;
    }

    @EventListener
    public void onFailure(AuthenticationFailureBadCredentialsEvent event) {
        loginAttemptService.recordFailure(event.getAuthentication().getName(),
                ipAddressOf(event.getAuthentication()));
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        loginAttemptService.recordSuccess(event.getAuthentication().getName());
    }

    private String ipAddressOf(Authentication authentication) {
        return authentication.getDetails() instanceof WebAuthenticationDetails details
                ? details.getRemoteAddress() : null;
    }
}
