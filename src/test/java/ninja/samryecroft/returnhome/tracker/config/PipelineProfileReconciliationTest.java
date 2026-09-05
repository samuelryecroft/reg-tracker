package ninja.samryecroft.returnhome.tracker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * T189 §7: the profile the pipeline enforces must be one this application treats as deployed.
 *
 * <p><b>This is the structural fix for the root cause, and a comment cannot be it.</b> The defect was
 * two sources for one fact where <em>only one of them binds</em>: {@code deploy.yml} asserts
 * {@code SPRING_PROFILES_ACTIVE} is exactly {@code azure} and fails the deploy otherwise, while the
 * application decided what "deployed" meant from its own lists - which did not contain
 * {@code azure}. Nothing connected the two, so both sides were individually correct and the system
 * was wrong.
 *
 * <p>Worse, they were in active opposition: anyone noticing "production is not marked as production"
 * and adding {@code prod} to the app setting would have <b>failed the deploy</b>. The gate protecting
 * the profile value was enforcing the value that disarmed two security controls.
 *
 * <p><b>On the coupling, which is the fair objection.</b> A unit test that parses a CI file is
 * unusual and will break if that file is renamed or restructured. Taken as an argument against, and
 * rejected: the coupling already exists - it is the whole defect - and this only makes it visible.
 * A break here is the correct outcome, because it means the two halves have moved apart again, which
 * is exactly the event nobody noticed last time. What the test must not do is pass quietly when it
 * cannot find what it is looking for, so every failure to locate the file or the assertion is an
 * explicit failure rather than a skip.
 */
class PipelineProfileReconciliationTest {

    private static final Path DEPLOY_WORKFLOW = Path.of(".github/workflows/deploy.yml");

    /** The literal the workflow compares {@code SPRING_PROFILES_ACTIVE} against. */
    private static final Pattern ENFORCED_PROFILE = Pattern.compile(
            "\\[\\s*\"\\$\\{profiles\\}\"\\s*!=\\s*\"([^\"]+)\"\\s*\\]");

    @Test
    void theProfileTheDeployPipelineEnforcesIsOneTheApplicationTreatsAsDeployed() throws IOException {
        assertThat(DEPLOY_WORKFLOW)
                .as("the deploy workflow must exist for this reconciliation to mean anything - if it "
                        + "moved, this test has to fail rather than quietly stop checking")
                .exists();

        String workflow = Files.readString(DEPLOY_WORKFLOW, StandardCharsets.UTF_8);
        Matcher m = ENFORCED_PROFILE.matcher(workflow);
        assertThat(m.find())
                .as("could not find the SPRING_PROFILES_ACTIVE allowlist assertion in %s. Either it "
                        + "was removed - in which case nothing now pins the deployed profile and "
                        + "this test is the least of it - or it was rewritten, and this pattern "
                        + "needs updating deliberately rather than deleted", DEPLOY_WORKFLOW)
                .isTrue();

        String enforced = m.group(1);
        assertThat(DeployedEnvironment.DEPLOYED_MARKERS)
                .as("the pipeline forces SPRING_PROFILES_ACTIVE='%s', but the application does not "
                        + "treat that as a deployed environment - so every guard keyed on "
                        + "DeployedEnvironment is inert in production, which is exactly the state "
                        + "T189 found and fixed. Add it to DEPLOYED_MARKERS, or change the pipeline; "
                        + "do not change only one", enforced)
                .contains(enforced);
    }
}
