package ninja.samryecroft.returnhome.tracker.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
}
