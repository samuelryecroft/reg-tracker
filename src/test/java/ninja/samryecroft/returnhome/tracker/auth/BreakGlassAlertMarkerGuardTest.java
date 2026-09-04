package ninja.samryecroft.returnhome.tracker.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The break-glass alert matches on a string that lives in two languages, and the duplication fails
 * open.
 *
 * <p>{@code BreakGlassAuditListener.ALERT_MARKER} is derived from the audit type, so renaming the
 * enum breaks compilation - <b>on the Java side only</b>. The Terraform keeps the old literal, the
 * KQL stops matching, and nothing complains. <b>Silence is this alert's normal state, so a query
 * that no longer matches looks exactly like a quiet week.</b> An enum rename is the confident
 * refactor someone does without hesitating, and it is precisely the direction the compiler cannot
 * see.
 *
 * <p>So this reads the Terraform, the same technique {@code InterviewStatusWriterGuardTest} and
 * {@code FrontendSourceGuardTest} use for duplications the compiler cannot reach - here across
 * languages rather than across packages. With it, both directions fail loudly: rename the enum and
 * Java stops compiling; reword the query and this goes red.
 */
class BreakGlassAlertMarkerGuardTest {

    private static final Path ALERT = Path.of("terraform/modules/observability/main.tf");

    @Test
    void theAlertQueryMatchesTheMarkerTheApplicationActuallyLogs() throws IOException {
        String terraform = Files.readString(ALERT, StandardCharsets.UTF_8);

        // Anchored on the resource, so this cannot pass because the marker happens to appear in a
        // comment elsewhere in the file - which it does, deliberately, explaining this coupling.
        int resourceAt = terraform.indexOf("resource \"azurerm_monitor_scheduled_query_rules_alert_v2\" \"break_glass_login\"");
        assertThat(resourceAt)
                .as("the break-glass alert resource is gone from %s - the alert cannot fire at all, "
                        + "and no other test would notice", ALERT)
                .isNotNegative();

        String resource = terraform.substring(resourceAt);
        String query = resource.substring(resource.indexOf("query"), resource.indexOf("time_aggregation_method"));

        assertThat(query)
                .as("the KQL must match the marker BreakGlassAuditListener actually logs; if these "
                        + "drift the alert stops firing and the failure is indistinguishable from "
                        + "nobody having used break-glass")
                .contains(BreakGlassAuditListener.ALERT_MARKER);
    }

    /**
     * The other half of the fail-open: gating the alert on {@code entra_enabled} would destroy it
     * exactly when the §5 rollback - disable Entra, return to form login - makes break-glass the
     * primary way in. That rollback is written into the plan as something we might deliberately do,
     * so this is a reachable state rather than a hypothetical one.
     */
    @Test
    void theAlertIsNotConditionalOnEntraBeingEnabled() throws IOException {
        String terraform = Files.readString(ALERT, StandardCharsets.UTF_8);
        int resourceAt = terraform.indexOf("resource \"azurerm_monitor_scheduled_query_rules_alert_v2\" \"break_glass_login\"");
        String resource = terraform.substring(resourceAt);
        String body = resource.substring(0, resource.indexOf("\n}"));

        // A meta-argument at the start of a line, not the word anywhere - "Count" is the
        // aggregation method and a substring check would fail on the working resource, which is the
        // kind of guard that gets deleted rather than fixed.
        assertThat(body.lines().map(String::strip).toList())
                .as("no count/for_each gate on the break-glass alert - it must survive the Entra "
                        + "rollback that makes break-glass the way in")
                .noneMatch(line -> line.startsWith("count ") || line.startsWith("count=")
                        || line.startsWith("for_each ") || line.startsWith("for_each="));
        assertThat(body)
                .as("the alert must not reference the Entra flag in any form")
                .doesNotContain("entra_enabled");
    }
}
