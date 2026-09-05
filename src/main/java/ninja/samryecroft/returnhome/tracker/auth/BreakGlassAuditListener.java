package ninja.samryecroft.returnhome.tracker.auth;

import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventType;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Records, and announces, every use of the emergency local credential path.
 *
 * <p>Break-glass exists because a tenant-wide single sign-on failure would otherwise lock out the
 * one person who could fix it (D2/D5). Its cost is an account that can sign in without the identity
 * provider, so the compensating control is that nobody can use it quietly.
 *
 * <p><b>Ungated, and that is the point rather than an oversight.</b> It was written ungated because
 * the Entra rollback was "disable Entra, go back to form login" - so a control conditional on Entra
 * being on would have disappeared at the moment break-glass became the primary way in. Entra is
 * gone and form login is the only path now, which retires that specific argument but not the rule:
 * an emergency-access alert conditional on anything is an alert that can be turned off.
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

    /**
     * Startup-bound, matching {@code SecurityConfig.entraEnabled}.
     *
     * <p>A per-request read was considered and rejected. It would have bought "close the emergency
     * path without a restart", which is a real thing to want of an emergency credential - but not
     * here: there is no {@code @RefreshScope} and no Spring Cloud, actuator exposes only
     * {@code health} and {@code info} so there is no refresh endpoint, and the value arrives as an
     * App Service app setting, changing which restarts the application anyway. So the flag is
     * startup-bound in production however this is written, and the per-request version would have
     * been mutable only from a test - buying nothing real in exchange for mutable state in a Spring
     * context shared with the rest of the suite.
     *
     * <p>The other reason it was tempting is worth naming so it is not re-argued: a per-request read
     * would have let the enabled-path tests avoid forking a Spring context, and so a Hikari pool.
     * That is not a reason. A test-infrastructure budget must not shape a security control's runtime
     * semantics - the sixth pool is spent deliberately and recorded in {@code TEST-CONTEXTS.md}
     * instead.
     */
    @Value("${app.auth.break-glass.enabled:false}")
    private boolean breakGlassEnabled;

    private final AuditEventPublisher auditEventPublisher;

    public BreakGlassAuditListener(AuditEventPublisher auditEventPublisher) {
        this.auditEventPublisher = auditEventPublisher;
    }

    /**
     * Announced at startup rather than only on use, because the enabling is the act worth catching
     * early - before someone signs in, not after. On App Service a configuration change restarts the
     * application, so startup is when the change becomes real.
     */
    @EventListener
    public void onReady(ApplicationReadyEvent event) {
        if (breakGlassEnabled) {
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
     * A local sign-in while the emergency path is open, attributable to one of our accounts.
     *
     * <p>There used to be a third clause excluding an {@code OAuth2AuthenticationToken}, because an
     * authentication that went through the identity provider is never break-glass by definition.
     * It went with the Entra removal: no OAuth2 authentication can be produced any more, so the
     * clause could never be false and keeping it would have described a case that cannot occur.
     * <b>Widening it is safe precisely because the excluded case no longer exists</b> - if an
     * external identity path is ever reintroduced, this exclusion has to come back with it.
     */
    private boolean isBreakGlass(Authentication authentication) {
        return breakGlassEnabled
                && authentication.getPrincipal() instanceof AppUserPrincipal;
    }
}
