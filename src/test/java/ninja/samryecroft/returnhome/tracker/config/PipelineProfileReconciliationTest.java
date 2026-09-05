package ninja.samryecroft.returnhome.tracker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * T189 §7: every profile the pipeline PERMITS must be one this application treats as deployed.
 *
 * <p><b>This is the structural fix for the root cause, and a comment cannot be it.</b> The defect was
 * two sources for one fact where <em>only one of them binds</em>: {@code deploy.yml} asserts what
 * {@code SPRING_PROFILES_ACTIVE} may be and fails the deploy otherwise, while the application decided
 * what "deployed" meant from its own lists - which did not contain {@code azure}. Nothing connected
 * the two, so both sides were individually correct and the system was wrong.
 *
 * <p>Worse, they were in active opposition: anyone noticing "production is not marked as production"
 * and adding {@code prod} to the app setting would have <b>failed the deploy</b>. The gate protecting
 * the profile value was enforcing the value that disarmed two security controls.
 *
 * <p><b>The pipeline now permits a SET, not a single value.</b> deploy.yml's 5.1 gate was widened for
 * the Entra cutover from "exactly {@code azure}" to an explicit allowlist {@code {azure, "azure,entra"}}.
 * The reconciliation therefore changes shape: it is no longer "the one enforced value is a marker" but
 * "every permitted profile string carries AT LEAST ONE deployed marker". Not every token - {@code
 * entra} is a feature profile and correctly is not a marker - but at least one, because a permitted
 * profile with no marker at all would run in production with the storage/keys/demo guards <b>disarmed</b>,
 * which is exactly the T189 defect ({@code azure} was the real prod profile and nothing recognised it).
 * The marker set is read from {@link DeployedEnvironment} - the single source of truth from #78 - never
 * hardcoded here, so the two cannot drift.
 *
 * <p><b>On the coupling, which is the fair objection.</b> A unit test that parses a CI file is unusual
 * and will break if that file is renamed or restructured. Taken as an argument against, and rejected:
 * the coupling already exists - it is the whole defect - and this only makes it visible. A break here
 * is the correct outcome, because it means the two halves have moved apart again, which is exactly the
 * event nobody noticed last time. What the test must not do is pass quietly when it cannot find what it
 * is looking for, so every failure to locate the file or the allowlist is an explicit failure, not a skip.
 */
class PipelineProfileReconciliationTest {

    private static final Path DEPLOY_WORKFLOW = Path.of(".github/workflows/deploy.yml");

    /**
     * The allowlist arm of the {@code case "${profiles}" in ...} block - the pipe-separated set of
     * permitted {@code SPRING_PROFILES_ACTIVE} values. Keyed on the bare {@code "${profiles}"} so it
     * matches the allowlist block and NOT the {@code ",${profiles},"} demo-refusal block above it.
     */
    private static final Pattern ALLOWLIST_ARM = Pattern.compile(
            "case\\s+\"\\$\\{profiles\\}\"\\s+in\\s+([^)]+?)\\)");

    @Test
    void everyProfileTheDeployPipelinePermitsIsOneTheApplicationTreatsAsDeployed() throws IOException {
        assertThat(DEPLOY_WORKFLOW)
                .as("the deploy workflow must exist for this reconciliation to mean anything - if it "
                        + "moved, this test has to fail rather than quietly stop checking")
                .exists();

        String workflow = Files.readString(DEPLOY_WORKFLOW, StandardCharsets.UTF_8);
        Matcher m = ALLOWLIST_ARM.matcher(workflow);
        assertThat(m.find())
                .as("could not find the SPRING_PROFILES_ACTIVE allowlist (the `case \"${profiles}\" in "
                        + "<profiles>)` block) in %s. Either it was removed - in which case nothing now "
                        + "pins the deployed profile and this test is the least of it - or it was "
                        + "rewritten, and this pattern needs updating deliberately rather than deleted",
                        DEPLOY_WORKFLOW)
                .isTrue();

        List<String> permittedProfiles = Arrays.stream(m.group(1).trim().split("\\|"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        assertThat(permittedProfiles)
                .as("parsed no permitted profiles out of the allowlist arm in %s - the block shape "
                        + "changed and this parser needs updating deliberately, not deleting", DEPLOY_WORKFLOW)
                .isNotEmpty();

        for (String profile : permittedProfiles) {
            List<String> markerTokens = Arrays.stream(profile.split(","))
                    .map(token -> token.trim().toLowerCase(Locale.ROOT))
                    .filter(DeployedEnvironment.DEPLOYED_MARKERS::contains)
                    .toList();
            assertThat(markerTokens)
                    .as("the pipeline permits SPRING_PROFILES_ACTIVE='%s', but none of its profiles is "
                            + "one the application treats as a deployed environment - so every guard "
                            + "keyed on DeployedEnvironment would be inert in production under that value, "
                            + "which is exactly the state T189 found and fixed. A permitted profile must "
                            + "carry at least one of %s (feature profiles like 'entra' may ride alongside, "
                            + "but cannot be the only token). Add a marker to the profile, or change the "
                            + "pipeline; do not change only one", profile, DeployedEnvironment.DEPLOYED_MARKERS)
                    .isNotEmpty();
        }
    }
}
