package ninja.samryecroft.returnhome.tracker.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The half {@link AuditImmutabilityStartupCheckTest} cannot cover: that the three facts are read from
 * the database rather than invented.
 *
 * <p>The decision logic is a pure function and is armed in every direction there. If nothing exercised
 * the queries, all six of those tests could pass against a probe that returned constants.
 */
@SpringBootTest
class AuditImmutabilityLayersAreReadFromTheDatabaseTest extends AbstractIntegrationTest {

    @Autowired
    private AuditImmutabilityStartupCheck check;

    /**
     * The trigger is real and the query finds it.
     *
     * <p>This is the positive control for the whole probe: {@code V11__add_audit_events.sql} creates
     * the trigger, this database has run every migration, so a probe that could not see it would be
     * broken rather than reporting a broken database.
     */
    @Test
    void theTriggerIsFoundInARealDatabaseThatHasRunTheMigrations() {
        assertThat(check.read().triggerPresent())
                .as("V11 creates this trigger and every migration has run here - if this is false the "
                        + "QUERY is wrong, not the database")
                .isTrue();
    }

    /**
     * And the grant-based layers are honestly reported as absent here.
     *
     * <p>A test database is created and owned by the role that connects to it, so the REVOKE and the
     * ownership separation cannot exist. <b>This asserting false is what proves the probe reads real
     * state</b>: a probe that optimistically returned true everywhere would pass the test above and
     * fail this one.
     */
    @Test
    void theGrantLayersAreReportedAbsentBecauseThisDatabaseIsOwnedByTheConnectedRole() {
        AuditImmutabilityStartupCheck.Layers layers = check.read();

        assertThat(layers.mutationRevoked())
                .as("the test role owns this table, so UPDATE/DELETE cannot have been revoked from it")
                .isFalse();
        assertThat(layers.ownershipSeparated())
                .as("the test role owns this table, so ownership is by definition not separated")
                .isFalse();
    }

    /**
     * And the guard stays SILENT about it, because this is not a deployed environment.
     *
     * <p>This is the misfire direction, and it is the one that matters most for a check that refuses
     * to boot. Two of three layers are genuinely absent right now; if the narrowing were wrong, this
     * context - and every other integration test - would fail to start.
     */
    @Test
    void theGuardDoesNotFailABootOutsideADeployedEnvironment() {
        assertThatCode(() -> check.refuseToStartWithoutAllThreeLayers(null))
                .as("local and test databases are owned by the connecting role by construction; a "
                        + "guard that failed those would misfire on the commonest configuration there "
                        + "is - the mistake EmergencyAccessStartupCheck already made once")
                .doesNotThrowAnyException();
    }
}
