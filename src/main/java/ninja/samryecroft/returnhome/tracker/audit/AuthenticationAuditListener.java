package ninja.samryecroft.returnhome.tracker.audit;

import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * Bridges Spring Security's own authentication events into the audit trail (AUDIT-PLAN.md §B.1).
 * Spring Boot auto-configures a {@code DefaultAuthenticationEventPublisher}, so these fire for the
 * current {@code formLogin} setup and would continue to fire unchanged if login later moves to an
 * IdP per {@code AUTH-PROVIDER-OPTIONS.md} (§B.6).
 */
@Component
public class AuthenticationAuditListener {

    private final AuditEventPublisher auditEventPublisher;

    public AuthenticationAuditListener(AuditEventPublisher auditEventPublisher) {
        this.auditEventPublisher = auditEventPublisher;
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        if (event.getAuthentication().getPrincipal() instanceof AppUserPrincipal principal) {
            auditEventPublisher.loginSuccess(principal);
        }
    }

    /**
     * Catches the whole {@code AbstractAuthenticationFailureEvent} hierarchy, so a disabled or
     * locked account is recorded as a failed sign-in attempt just like a bad password would be.
     * The attempted username is kept (it is the brute-force signal worth having) but no credential
     * material ever is.
     */
    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        auditEventPublisher.loginFailure(event.getAuthentication().getName(),
                event.getException().getClass().getSimpleName());
    }
}
