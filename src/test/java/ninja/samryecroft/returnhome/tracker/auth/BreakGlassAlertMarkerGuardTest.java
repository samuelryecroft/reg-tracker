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
     * The other half of the fail-open: the alert must be created unconditionally.
     *
     * <p>It was written against {@code entra_enabled} specifically, because the §5 rollback -
     * disable Entra, return to form login - would have destroyed the alert at exactly the moment
     * break-glass became the primary way in. <b>Entra is gone, so that variable no longer exists
     * and the assertion naming it could never fail again.</b> It has been removed rather than kept:
     * an assertion that cannot fail is not a guard, and leaving it would have said this property
     * was verified when nothing was checking it.
     *
     * <p>The {@code count}/{@code for_each} check below is the one that was doing the work all
     * along, and it is untouched - it catches a conditional gate on this resource whatever variable
     * a future author reaches for, which is strictly more than the string check ever did.
     */
    @Test
    void theAlertIsNotConditionallyCreated() throws IOException {
        String terraform = Files.readString(ALERT, StandardCharsets.UTF_8);
        int resourceAt = terraform.indexOf("resource \"azurerm_monitor_scheduled_query_rules_alert_v2\" \"break_glass_login\"");
        String resource = terraform.substring(resourceAt);
        String body = resource.substring(0, resource.indexOf("\n}"));

        // A meta-argument at the start of a line, not the word anywhere - "Count" is the
        // aggregation method and a substring check would fail on the working resource, which is the
        // kind of guard that gets deleted rather than fixed.
        assertThat(body.lines().map(String::strip).toList())
                .as("no count/for_each gate on the break-glass alert - an emergency-access alert "
                        + "that can be switched off by configuration is one that will be off when "
                        + "it is needed")
                .noneMatch(line -> line.startsWith("count ") || line.startsWith("count=")
                        || line.startsWith("for_each ") || line.startsWith("for_each="));
    }
}
