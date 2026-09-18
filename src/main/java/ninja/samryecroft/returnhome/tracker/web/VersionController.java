package ninja.samryecroft.returnhome.tracker.web;

import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T375: the anonymous running-process provenance check.
 *
 * <p>T268 gave the artefact a commit stamp and exposed it on {@code /actuator/info}. But that
 * endpoint is ADMIN-only ({@code SecurityConfig} gates {@code EndpointRequest.toAnyEndpoint()} on
 * ROLE_ADMIN), so the release operator - who runs anonymously at cutover - gets a redirect to the
 * login page, not the commit. The check our release discipline called the strongest is one the
 * operator cannot perform. R21 only got a running-process proof by luck, because it happened to
 * carry a user-visible behavioural change to probe; a pure refactor would have had none, and the
 * on-disk {@code sha256} read back from Kudu proves the bytes on DISK, not the bytes the running
 * JVM loaded (which differ during the overlapped restart).
 *
 * <p>This is that check: a plain, unauthenticated GET the RUNNING process answers with its own
 * commit id. It is deliberately <strong>not</strong> an actuator endpoint - the ADMIN gate on
 * {@code /actuator/**} is left exactly as it was, so this adds a surface rather than widening one.
 *
 * <p><strong>What an anonymous caller learns is exactly two things:</strong> the 40-char commit id
 * and the build time - nothing else. Against a private repository the commit id is an opaque
 * fingerprint that grants no access and names no person (committer identity was already stripped
 * from the stamp at T268, see the pom's {@code includeOnlyProperties}). It is a precise version
 * fingerprint, and that is a real if small disclosure; it is strictly less than {@code git.properties}
 * already commits into the artefact and less than {@code /actuator/info} serves (which also carries
 * branch, dirty, the full {@code build.*} set and {@code info.env}). The trade - one opaque sha for
 * a check the operator can actually run every release - is deliberate.
 *
 * <p>The payload is held to those two fields on purpose. A field that can only ever hold one value
 * on a release build ({@code git.dirty} is always {@code false}) is a field someone later
 * "improves"; the guard in {@code BuildProvenanceIntegrationTest} asserts the key set is exactly
 * {@code {commit, buildTime}} so any added field fails the build rather than silently widening the
 * anonymous disclosure.
 */
@RestController
public class VersionController {

    private final ObjectProvider<GitProperties> gitProperties;
    private final ObjectProvider<BuildProperties> buildProperties;

    // ObjectProvider, not the beans directly: a build made outside a git checkout (a source
    // tarball, a release rebuild) produces no stamp and so no bean. The honest answer then is
    // "unknown" - different from, and never mistakable for, a stale commit - rather than a startup
    // failure that reads like a wiring mistake.
    VersionController(ObjectProvider<GitProperties> gitProperties,
            ObjectProvider<BuildProperties> buildProperties) {
        this.gitProperties = gitProperties;
        this.buildProperties = buildProperties;
    }

    @GetMapping(path = "/version", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> version() {
        GitProperties git = gitProperties.getIfAvailable();
        BuildProperties build = buildProperties.getIfAvailable();

        String commit = (git != null && git.getCommitId() != null) ? git.getCommitId() : "unknown";
        String buildTime = (build != null && build.getTime() != null)
                ? build.getTime().toString()
                : "unknown";

        return Map.of("commit", commit, "buildTime", buildTime);
    }
}
