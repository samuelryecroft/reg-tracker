package ninja.samryecroft.returnhome.tracker.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import ninja.samryecroft.returnhome.tracker.audit.AuditImmutabilityStartupCheck.Layers;
import org.junit.jupiter.api.Test;

/**
 * The three-layer check, armed by breaking each layer in turn.
 *
 * <p>Asserting only "it complained" would pass just as well for a check that names the wrong layer,
 * and naming the wrong one sends the wrong person to fix the wrong thing - a migration is re-run by
 * one hand and the Terraform SQL by another. <b>So every case below asserts WHICH layer is reported,
 * not merely that something was.</b>
 *
 * <p>The decision is a pure function of three facts, which is why it can be exercised in all
 * directions here without a database. {@code AuditImmutabilityLayersAreReadFromTheDatabaseTest} covers
 * the other half - that the three facts are read from real state rather than invented.
 */
class AuditImmutabilityStartupCheckTest {

    private static final String TRIGGER = "row-level trigger";
    private static final String REVOKE = "REVOKE of UPDATE/DELETE";
    private static final String OWNERSHIP = "ownership separation";

    @Test
    void allThreeLayersPresentReportsNothing() {
        assertThat(AuditImmutabilityStartupCheck.missingLayers(new Layers(true, true, true)))
                .as("a fully hardened table must not fail a boot - a guard that refuses to start is "
                        + "an outage of its own if it misfires")
                .isEmpty();
    }

    @Test
    void aMissingTriggerIsReportedAsTheTrigger() {
        assertThat(AuditImmutabilityStartupCheck.missingLayers(new Layers(false, true, true)))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains(TRIGGER)
                .contains("V11__add_audit_events.sql");
    }

    @Test
    void aMissingRevokeIsReportedAsTheRevoke() {
        assertThat(AuditImmutabilityStartupCheck.missingLayers(new Layers(true, false, true)))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains(REVOKE)
                .contains("02-audit-events-hardening.sql");
    }

    @Test
    void ownershipNotSeparatedIsReportedAsOwnership() {
        assertThat(AuditImmutabilityStartupCheck.missingLayers(new Layers(true, true, false)))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains(OWNERSHIP)
                .contains("01-roles-and-grants.sql");
    }

    /**
     * The rebuild case, which is the one this check was written for: Flyway restores the trigger, the
     * Terraform SQL is not re-run, and the table is left protected by the one layer a person would
     * think to check.
     */
    @Test
    void aRebuiltDatabaseReportsExactlyTheTwoInvisibleLayers() {
        List<String> missing =
                AuditImmutabilityStartupCheck.missingLayers(new Layers(true, false, false));

        assertThat(missing)
                .as("after a schema rebuild the trigger comes back and the grants do not - if this "
                        + "ever reports the trigger too, the migration stopped creating it")
                .hasSize(2);
        assertThat(String.join(" | ", missing)).contains(REVOKE).contains(OWNERSHIP);
        assertThat(String.join(" | ", missing)).doesNotContain(TRIGGER);
    }

    /** Nothing at all - reported in full rather than stopping at the first thing found. */
    @Test
    void anEntirelyUnprotectedTableReportsAllThree() {
        assertThat(AuditImmutabilityStartupCheck.missingLayers(new Layers(false, false, false)))
                .hasSize(3);
    }
}
