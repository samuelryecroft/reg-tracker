package ninja.samryecroft.returnhome.tracker.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T268: the running artefact says which commit it is.
 *
 * <p>The prod app is a JAR on App Service, so there is no image digest; the deployment log records
 * only "OneDeploy"; there was no commit or build app-setting. <strong>The running artefact carried
 * no recoverable provenance</strong>, so "which commit is in production?" could only be answered
 * from a tag somebody remembered to move. The DATABASE side has been fully auditable all along -
 * Flyway records what actually ran - and that asymmetry is the whole defect.
 *
 * <p><strong>No endpoint was opened for this.</strong> {@code /actuator/info} was already exposed
 * and already ADMIN-only: {@code SecurityConfig} permits only {@code HealthEndpoint} anonymously and
 * gates {@code EndpointRequest.toAnyEndpoint()} on ROLE_ADMIN. This card fills a surface that was
 * already authenticated rather than adding one.
 *
 * <p><strong>Known limitation, stated because a wrong stamp is worse than none.</strong> In a
 * LINKED GIT WORKTREE the plugin resolves to the main repository and stamps ITS head - measured, and
 * not fixed by {@code useNativeGit}. A plain checkout is correct, verified by cloning and running
 * the real lifecycle build, so CI and every release build - the only artefacts that reach an App
 * Service - carry the right commit. These tests therefore assert the stamp is PRESENT and
 * WELL-FORMED, which is true everywhere; they do not assert it equals HEAD, because that guard
 * would fail every worktree build on this floor. See the pom for the measurement.
 *
 * <p>That is also why the anonymous half is asserted here even though
 * {@link ActuatorHealthIntegrationTest} already checks the redirect: <strong>the risk changed when
 * the payload did.</strong> A redirect on an empty endpoint and a redirect on one that now names a
 * commit are the same status code protecting very different things, and only one of them was ever
 * a disclosure.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BuildProvenanceIntegrationTest extends AbstractIntegrationTest {

    /** A full git object name. Anchored, so a truncated or placeholder value is not a pass. */
    private static final String FULL_SHA = "^[0-9a-f]{40}$";

    /** Local, not a bean: this app wires no {@code ObjectMapper} bean (Spring MVC's Jackson message
     *  converter makes its own), and all this needs is to read a two-field response back. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired(required = false)
    private GitProperties gitProperties;
    @Autowired(required = false)
    private BuildProperties buildProperties;

    /**
     * The beans exist at all. {@code required = false} above is deliberate: without it a missing
     * stamp fails as a context-startup error naming an unsatisfied dependency, which reads like a
     * wiring mistake. This fails saying the build did not stamp itself, which is the actual defect.
     */
    @Test
    void theBuildStampedItsCommitAndItsBuildTime() {
        assertThat(gitProperties)
                .as("no GitProperties bean - the build produced no git.properties, so the jar does "
                        + "not know which commit it is, which is the state T268 exists to end")
                .isNotNull();
        assertThat(gitProperties.getCommitId())
                .as("git.commit.id must be a full object name; an abbreviated or absent id makes "
                        + "the answer ambiguous exactly when someone is trying to be precise")
                .matches(FULL_SHA);
        assertThat(buildProperties).isNotNull();
        assertThat(buildProperties.getTime()).isNotNull();
    }

    /** An ADMIN can read it back from a running instance - the card's actual output. */
    @Test
    void anAdminCanReadTheCommitBackFromARunningInstance() throws Exception {
        String body = mockMvc.perform(get("/actuator/info").with(user("provenance").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(gitProperties.getCommitId());
        assertThat(body).contains("\"build\"");
    }

    /**
     * And an anonymous caller cannot. Asserted on the BODY as well as the status: the point is not
     * that the request was redirected, it is that the commit was not served.
     */
    @Test
    void anAnonymousCallerIsNotToldWhichCommitIsRunning() throws Exception {
        String body = mockMvc.perform(get("/actuator/info"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(gitProperties.getCommitId());
    }

    /**
     * The artefact carries the commit and nothing about who wrote it.
     *
     * <p>{@code git-commit-id}'s DEFAULT property set includes {@code git.commit.user.name} and
     * {@code git.commit.user.email}, plus the full commit message. Stamping a person's email address
     * into a deployed artefact is personal data this system has no reason to ship, and the question
     * this card answers is <em>which commit</em>. The pom's {@code includeOnlyProperties} allow-list
     * is what keeps that true, and an allow-list is exactly the kind of config that gets widened by
     * someone who wants one more field - so it is asserted rather than trusted.
     */
    @Test
    void theStampCarriesTheCommitAndNothingAboutTheCommitter() throws Exception {
        String body = mockMvc.perform(get("/actuator/info").with(user("provenance").roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("committer identity or commit prose reached a deployed artefact's info payload")
                .doesNotContain("user.email")
                .doesNotContain("user.name")
                .doesNotContain("message");
    }

    /**
     * T375: the check the operator can actually run. Unlike {@code /actuator/info} above, this is
     * served to an ANONYMOUS caller - the release operator at cutover holds no credential - and it
     * still names the running commit. This is the running-process layer of provenance, distinct
     * from the Kudu on-disk {@code sha256} (which proves the bytes on disk, not the bytes the JVM
     * loaded) and from the build-time stamp (which proves the artefact, not that it is the one
     * serving).
     */
    @Test
    void theVersionEndpointNamesTheRunningCommitToAnAnonymousCaller() throws Exception {
        String body = mockMvc.perform(get("/version"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, String> payload = objectMapper.readValue(body, new TypeReference<>() {});

        assertThat(payload.get("commit"))
                .as("the anonymous /version check must return the full running commit id, or it is "
                        + "not the running-process provenance the operator needs")
                .matches(FULL_SHA)
                .isEqualTo(gitProperties.getCommitId());
        assertThat(payload.get("buildTime"))
                .as("the build time completes the provenance answer and must be a real value, not "
                        + "the 'unknown' a stampless build would report")
                .isNotBlank()
                .isNotEqualTo("unknown");
    }

    /**
     * <strong>Field-creep guard.</strong> The anonymous payload is exactly {@code commit} and
     * {@code buildTime} and must stay that way. Every field added here is served to an
     * unauthenticated caller, so the guard is a build-time gate on that disclosure rather than a
     * comment asking people not to widen it: add a field to {@link ninja.samryecroft.returnhome.tracker.web.VersionController}
     * and this fails, naming the field that leaked. {@code /actuator/info} is where a richer,
     * ADMIN-only view already lives; the anonymous surface deliberately does not grow toward it.
     */
    @Test
    void theVersionPayloadIsExactlyCommitAndBuildTime() throws Exception {
        String body = mockMvc.perform(get("/version"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        Map<String, String> payload = objectMapper.readValue(body, new TypeReference<>() {});

        assertThat(payload.keySet())
                .as("the anonymous /version payload grew a field; every key here is served to an "
                        + "unauthenticated caller, so a new one is a widened disclosure, not a feature")
                .containsExactlyInAnyOrder("commit", "buildTime");
    }
}
