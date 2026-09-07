package ninja.samryecroft.returnhome.tracker.audit;

import ninja.samryecroft.returnhome.tracker.security.secondfactor.SecondFactorPolicy;
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
    private final SecondFactorPolicy secondFactorPolicy;

    public AuthenticationAuditListener(AuditEventPublisher auditEventPublisher,
            SecondFactorPolicy secondFactorPolicy) {
        this.auditEventPublisher = auditEventPublisher;
        this.secondFactorPolicy = secondFactorPolicy;
    }

    /**
     * <b>T322: this event no longer means the sign-in finished.</b> Spring publishes
     * {@code AuthenticationSuccessEvent} when the PASSWORD is accepted, which was the whole of a
     * sign-in until a second factor existed. Left alone, LOGIN_SUCCESS would now be written for
     * somebody who knew a password and then failed - or never attempted - the second step, while
     * the audit screen goes on rendering that row as "signed in". The row would not be false about
     * an event; it would be false about the only thing a reader uses it for.
     *
     * <p>So when a second factor is required, the event is deferred to
     * {@code SecondFactorController}, which writes it once both factors have passed. The password
     * stage is not silent in the meantime - MFA_CHALLENGE_ISSUED records it, and an issued
     * challenge with no MFA_SUCCESS after it is the pattern worth being able to see.
     *
     * <p>Both sites ask {@link SecondFactorPolicy} rather than re-deciding, so they cannot disagree
     * about whether anyone is signed in.
     */
    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        if (event.getAuthentication().getPrincipal() instanceof AppUserPrincipal principal) {
            if (secondFactorPolicy.isRequiredFor(principal.getUser())) {
                return;
            }
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
