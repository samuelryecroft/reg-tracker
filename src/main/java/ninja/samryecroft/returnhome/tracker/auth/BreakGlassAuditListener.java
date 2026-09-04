package ninja.samryecroft.returnhome.tracker.auth;

import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventType;
import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Records, and announces, every use of the emergency local credential path.
 *
 * <p>Break-glass exists because a tenant-wide single sign-on failure would otherwise lock out the
 * one person who could fix it (D2/D5). Its cost is an account that can sign in without the identity
 * provider, so the compensating control is that nobody can use it quietly.
 *
 * <p><b>Not gated on {@code app.auth.entra.enabled}, and that is the point rather than an
 * oversight.</b> The rollback in §5 is "disable Entra, go back to form login" - so the moment
 * break-glass becomes the primary way in is exactly the moment the Entra flag is off. A control
 * conditional on Entra being on would disappear when it matters most. The tests run in the flag-off
 * context for the same reason: it is both the honest scenario and a structural proof that no such
 * gate crept in.
 */
@Component
public class BreakGlassAuditListener {

    private static final Logger log = LoggerFactory.getLogger(BreakGlassAuditListener.class);

    /**
     * The token the alert rule matches on, derived from the audit type rather than written out as
     * prose, so a rename breaks compilation here instead of silently un-matching the query.
     *
     * <p>That only guards the Java side. The same token also lives in the Terraform KQL, where a
     * rename leaves the old literal behind and the alert simply stops firing - and
     * <b>silence is the alert's normal state, so a query that stops matching is the one failure
     * that looks exactly like everything being fine.</b> {@code BreakGlassAlertMarkerGuardTest}
     * reads the Terraform and asserts it contains this value; the two must be changed together.
     */
    public static final String ALERT_MARKER = AuditEventType.BREAK_GLASS_LOGIN.name();

    private final AppProperties appProperties;
    private final AuditEventPublisher auditEventPublisher;

    public BreakGlassAuditListener(AppProperties appProperties, AuditEventPublisher auditEventPublisher) {
        this.appProperties = appProperties;
        this.auditEventPublisher = auditEventPublisher;
    }

    /**
     * Announced at startup rather than only on use, because the enabling is the act worth catching
     * early - before someone signs in, not after. On App Service a configuration change restarts the
     * application, so startup is when the change becomes real.
     */
    @EventListener
    public void onReady(ApplicationReadyEvent event) {
        if (appProperties.getAuth().getBreakGlass().isEnabled()) {
            log.warn("{} enabled: the emergency local sign-in path is available", ALERT_MARKER);
            auditEventPublisher.breakGlassEnabled();
        }
    }

    @EventListener
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        Authentication authentication = event.getAuthentication();
        if (!isBreakGlass(authentication)) {
            return;
        }
        AppUserPrincipal principal = (AppUserPrincipal) authentication.getPrincipal();

        // The username and nothing else. It is already in the audit row, but that is not the reason
        // this is safe: audit rows sit behind the application's own authorisation and this line goes
        // to Application Insights, behind Azure RBAC - a DIFFERENT audience, not the same fact
        // repeated. At our scale that audience is us and the value is one break-glass username, so
        // the disclosure is worth the ability to name the account in the alert itself rather than
        // sending whoever is paged to the database first. That is a judgement about scale, not a
        // no-op, which is why it is written down: the instinct when something goes wrong is to add
        // context, and that is how a line like this grows an organisation, a home, or a child.
        log.warn("{}: emergency local sign-in used by {}", ALERT_MARKER, principal.getUsername());
        auditEventPublisher.breakGlassLogin(principal);
    }

    /**
     * A local sign-in while the emergency path is open. An OAuth2 authentication is never
     * break-glass however the flag is set - it went through the identity provider by definition -
     * and a principal that is not ours cannot be attributed to an account.
     */
    private boolean isBreakGlass(Authentication authentication) {
        return appProperties.getAuth().getBreakGlass().isEnabled()
                && !(authentication instanceof OAuth2AuthenticationToken)
                && authentication.getPrincipal() instanceof AppUserPrincipal;
    }
}
