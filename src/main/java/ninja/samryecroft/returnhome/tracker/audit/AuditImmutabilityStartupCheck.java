package ninja.samryecroft.returnhome.tracker.audit;

import java.util.ArrayList;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.config.DeployedEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Verifies that {@code audit_events} is protected by all THREE of the layers T219 ruled on, and
 * refuses to start a deployed environment that is missing any of them.
 *
 * <p><b>Why this exists, and it is the whole point.</b> T219 ruled that audit immutability rests on
 * three layers: the row-level trigger, the {@code REVOKE} of UPDATE/DELETE from the runtime role, and
 * ownership separation so the runtime role cannot simply grant itself back. <b>Nothing verified that
 * three existed.</b> They are also not restored by the same mechanism:
 *
 * <ul>
 *   <li>The <b>trigger</b> is created by a Flyway migration ({@code V11__add_audit_events.sql}), so a
 *       schema rebuild brings it back.</li>
 *   <li>The <b>REVOKE and the ownership separation</b> live in
 *       {@code terraform/modules/postgres/sql/}, applied by Terraform and <b>not</b> by Flyway. A
 *       drop-and-rebuild does not re-run them.</li>
 * </ul>
 *
 * <p><b>So after a rebuild the table can be protected by one layer out of three while looking
 * entirely healthy - because the layer that survives is the visible one.</b> The trigger fires, a
 * manual {@code DELETE} is refused, and everything a person would think to check comes back correct.
 * The two that are gone are precisely the ones nobody inspects. This check exists so that state fails
 * a deployment instead of waiting to be discovered by whoever needs the audit trail to be true.
 *
 * <p>A standing fact worth keeping next to this: <b>{@code TRUNCATE} does not fire row-level
 * triggers.</b> The append-only guarantee therefore stops at TRUNCATE, and what actually holds that
 * line is the grant layer rather than the trigger - which is a second reason the invisible layers are
 * the ones that matter.
 *
 * <p><b>Deployed environments only, and that narrowing is not timidity.</b> Local development and the
 * test suite connect as the owner of a database they created, so two of the three layers are absent
 * by construction there and always will be. A check that failed those would be a guard that misfires
 * on the commonest configuration there is - the mistake this codebase has already made once, in
 * {@code EmergencyAccessStartupCheck}. {@link DeployedEnvironment} answers "is this deployed?" in one
 * place rather than each guard keeping its own list of profile names.
 */
@Component
public class AuditImmutabilityStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(AuditImmutabilityStartupCheck.class);

    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;

    public AuditImmutabilityStartupCheck(JdbcTemplate jdbcTemplate, Environment environment) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
    }

    /** The three facts, read from the database rather than assumed from the deployment process. */
    public record Layers(boolean triggerPresent, boolean mutationRevoked, boolean ownershipSeparated) {
    }

    /**
     * The decision, separated from the reading so it can be exercised in every direction without a
     * database. Returns the layers that are MISSING, named - an empty list means all three are there.
     *
     * <p>Naming which layer is absent rather than returning a boolean is deliberate: whoever reads the
     * failure needs to know whether to re-run a migration or to re-run the Terraform SQL, and those
     * are different people doing different things.
     */
    public static List<String> missingLayers(Layers layers) {
        List<String> missing = new ArrayList<>();
        if (!layers.triggerPresent()) {
            missing.add("the row-level trigger that refuses UPDATE and DELETE (Flyway: "
                    + "V11__add_audit_events.sql)");
        }
        if (!layers.mutationRevoked()) {
            missing.add("the REVOKE of UPDATE/DELETE from the runtime role (Terraform: "
                    + "terraform/modules/postgres/sql/02-audit-events-hardening.sql)");
        }
        if (!layers.ownershipSeparated()) {
            missing.add("ownership separation - the runtime role owns the table and can therefore "
                    + "grant itself back whatever was revoked (Terraform: "
                    + "terraform/modules/postgres/sql/01-roles-and-grants.sql)");
        }
        return missing;
    }

    @EventListener
    public void refuseToStartWithoutAllThreeLayers(ApplicationReadyEvent event) {
        Layers layers = read();
        List<String> missing = missingLayers(layers);
        if (missing.isEmpty()) {
            return;
        }
        if (!DeployedEnvironment.isDeployed(environment)) {
            // Expected here, and saying so is the point: silence would leave a reader unable to tell
            // "not checked" from "checked and fine".
            log.info("audit_events is not fully hardened, which is expected outside a deployed "
                    + "environment (connected as the owner of a local database). Missing: {}", missing);
            return;
        }
        throw new IllegalStateException(
                "audit_events is not protected by all three layers T219 requires, so the audit trail "
                        + "is not append-only in the way this system claims. Missing: " + missing
                        + ". If the database was rebuilt, re-run terraform/modules/postgres/sql/ - "
                        + "Flyway restores the trigger but NOT the grants or the ownership, so a "
                        + "rebuilt database looks healthy while two of the three layers are absent.");
    }

    /**
     * Package-private so the test reads the REAL facts through the same code the boot check uses. A
     * test that re-implemented these queries would be asserting its own SQL, not this class's.
     */
    Layers read() {
        Boolean trigger = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
                        + "WHERE c.relname = 'audit_events' AND NOT t.tgisinternal)", Boolean.class);
        // has_table_privilege answers for the CONNECTED role, which is the one that matters - a
        // REVOKE that left the running application able to update is not a REVOKE.
        Boolean canMutate = jdbcTemplate.queryForObject(
                "SELECT has_table_privilege(current_user, 'audit_events', 'UPDATE') "
                        + "OR has_table_privilege(current_user, 'audit_events', 'DELETE')",
                Boolean.class);
        Boolean ownedByOther = jdbcTemplate.queryForObject(
                "SELECT pg_get_userbyid(c.relowner) <> current_user FROM pg_class c "
                        + "WHERE c.relname = 'audit_events'", Boolean.class);
        return new Layers(Boolean.TRUE.equals(trigger),
                !Boolean.TRUE.equals(canMutate),
                Boolean.TRUE.equals(ownedByOther));
    }
}
