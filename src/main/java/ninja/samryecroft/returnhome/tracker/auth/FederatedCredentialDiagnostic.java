package ninja.samryecroft.returnhome.tracker.auth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

/**
 * T184: proves, at runtime and inside the application container, that the federated identity
 * credential the Entra sign-in now depends on actually works.
 *
 * <p>The client secret has been replaced by a federated credential - nothing to rotate, nothing to
 * expire. Entra accepted the configuration, cross-tenant issuer included, which was the doubt. What
 * that does <em>not</em> prove is that the token exchange succeeds at runtime, and the failure mode
 * if it does not is total sign-in loss at cutover for every user at once, with no partial warning
 * and no degraded mode anyone would notice first. A mismatch found on an ordinary deploy is a
 * one-field config edit; the same mismatch found at cutover is an outage.
 *
 * <p><b>Why this is code rather than a shell command.</b> Kevin got shell on the App Service through
 * Kudu and could not run it: {@code IDENTITY_ENDPOINT} and {@code IDENTITY_HEADER} are injected into
 * the <em>application</em> container, not the SCM one, and they are different containers. The
 * managed identity is unreachable from outside the app process, so the check has to live here.
 *
 * <p><b>Reading the result is not obvious, and getting it wrong reports a false failure.</b> The app
 * registration has no API permissions, so step two is <em>expected</em> to be refused - and a
 * refusal about scopes or consent is a PASS, because Entra had to validate the assertion before it
 * could get as far as complaining about permissions. The question is not "did we get a token", it is
 * "did Entra get past the assertion". {@code invalid_client} - OAuth's error for a client that
 * failed to authenticate - or {@code AADSTS700211} mean the federation itself is wrong. This class
 * classifies rather than logging a status and leaving the next reader to interpret it: the obvious
 * reading sends someone off to mint a certificate we deliberately did not create.
 *
 * <p><b>And it recognises a third outcome, which is the important one.</b> PASS is a whitelist of
 * permission-shaped refusals; anything unrecognised is INCONCLUSIVE rather than PASS. T184 exists to
 * stop a broken federation reaching cutover, so an outcome nobody anticipated must never be
 * recordable as proof - erring towards "we do not know" costs a follow-up question, erring towards
 * PASS costs sign-in for every user at once.
 *
 * <p><b>On {@link ApplicationReadyEvent} rather than an {@code ApplicationRunner}</b>, which is the
 * opposite of the choice made for the key warmup in the same codebase, and for the opposite reason.
 * <b>This shape is correct only because this class REPORTS rather than ENFORCES.</b> After-readiness
 * plus swallow-every-failure applied to a control would mean the application serves traffic while
 * the control is unproven - the same pattern inverted into a hole. Nobody should copy this hook to
 * something that has to hold, having seen it blessed here.
 * Warmup must finish <em>before</em> readiness, because its whole purpose is that no request pays
 * the cold start. A diagnostic must run <em>after</em>, because its purpose is to report - and a
 * diagnostic that can delay or block readiness has become a dependency of starting up, which is
 * exactly what a diagnostic must never be.
 *
 * <p><b>What is logged, and what must never be.</b> The step-one token is a bearer credential. This
 * logs its {@code iss}, {@code sub} and expiry, and step two's error code - and nothing else, ever.
 * Application Insights captures INFO and above into a Log Analytics workspace shared across the
 * platform with no field-level encryption, so a token in a log line is the same class of disclosure
 * as a decrypted date of birth in a log line (T179). For the same reason no exception from these
 * calls is logged with its message or its cause: an HTTP failure can embed the request body, and a
 * cause is another author's message carrying their disclosure decisions, not neutral metadata.
 *
 * <p>Off unless switched on, and every failure is swallowed. It is a diagnostic: it must not be able
 * to affect whether the application runs.
 */
public class FederatedCredentialDiagnostic {

    private static final Logger log = LoggerFactory.getLogger(FederatedCredentialDiagnostic.class);
    private static final Pattern AADSTS = Pattern.compile("AADSTS\\d+");

    /**
     * Outcomes that Entra could only have reached <em>after</em> validating our assertion.
     *
     * <p><b>The name is the whole design.</b> This was called {@code PERMISSION_SHAPED} and
     * described as "permission-shaped errors", and that is the wrong question - it asks what an
     * error <em>sounds</em> like. {@code unauthorized_client} sounds like a permissions refusal and
     * is how Entra reports <b>AADSTS700016, "application not found in the directory"</b>: an
     * application that was never found never had its assertion validated against anything, so it is
     * the federation being wrong, and it was classified PASS. T184 would have been recorded as
     * proven for the single most likely way this is misconfigured on cutover day - a client id
     * pointing at the wrong tenant, or at a registration that was recreated. Kevin caught it.
     *
     * <p>The question that keeps this correct is narrower and admits no judgement:
     * <b>which outcomes can Entra only produce once it has already accepted our assertion?</b>
     * Re-derived from that, the set is small, and it is keyed on the <b>AADSTS code</b> rather than
     * the OAuth error, because the code is the field that says which stage failed - the OAuth error
     * is a category that spans stages, which is exactly how {@code unauthorized_client} got in.
     *
     * <p>Anything not listed is INCONCLUSIVE, never PASS. Erring towards "we do not know" costs a
     * follow-up question; erring towards PASS costs sign-in for every user at once.
     *
     * <p><b>Delete this set the day a positive signal becomes available.</b> A token that exists is
     * stronger evidence than the shape of a refusal, and an error taxonomy drifts with the identity
     * provider's release notes while a token does not. There is no positive signal today for a
     * deliberate reason: {@code client_credentials} with {@code .default} needs at least one
     * application permission granted, this registration has none, and the sign-in it exists for uses
     * delegated permissions which yield no client-credentials token. Granting a real privilege to
     * make a test easier to read is the wrong trade. But if this registration ever gains an
     * application permission for a reason of its own, change the proof to "we are holding a token"
     * and this set stops being needed.
     */
    private static final java.util.Set<String> PROVES_ASSERTION_ACCEPTED = java.util.Set.of(
            "AADSTS65001",   // admin consent not granted - the expected result for a registration
                             // that deliberately holds no API permissions, and the most likely PASS
            "AADSTS70011",   // the scope value is not valid
            "AADSTS1002012", // the scope value is not valid
            "AADSTS500011"   // the resource principal was not found in the tenant
    );

    /** Scope validation follows client authentication, so this cannot precede it. */
    private static final String SCOPE_REFUSED = "invalid_scope";

    /**
     * Outcomes that mean the assertion, or the client presenting it, was rejected.
     *
     * <p>{@code invalid_client} is OAuth's error for a client that failed to authenticate, which on
     * this path <em>is</em> the assertion being rejected - it fails regardless of the AADSTS code
     * attached, because reading "a generic invalid_client" as "one carrying no code" made every
     * realistic assertion failure (AADSTS700027, a signature that did not validate; AADSTS700024, an
     * expired assertion) report PASS.
     *
     * <p>The codes are listed as failures rather than merely left out of the PASS set, and that goes
     * one step further than the review asked for: an application that does not exist or is disabled
     * cannot be a "we do not know", it is a definite statement that this federation cannot work. A
     * wrong FAIL costs an investigation; leaving it INCONCLUSIVE costs somebody deciding it was
     * probably fine.
     */
    private static final String ASSERTION_REJECTED = "invalid_client";

    private static final java.util.Set<String> ASSERTION_REJECTED_CODES = java.util.Set.of(
            "AADSTS700211",  // no matching federated identity record for the presented assertion
            "AADSTS700016",  // the application was not found in the directory
            "AADSTS7000112"  // the application is disabled
    );

    private final FederatedCredentialDiagnosticProperties properties;
    private final HttpClient httpClient;
    private final java.util.function.Function<String, String> environment;

    public FederatedCredentialDiagnostic(FederatedCredentialDiagnosticProperties properties,
            HttpClient httpClient, java.util.function.Function<String, String> environment) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        try {
            String identityEndpoint = environment.apply("IDENTITY_ENDPOINT");
            String identityHeader = environment.apply("IDENTITY_HEADER");
            if (identityEndpoint == null || identityHeader == null) {
                log.warn("T184 federated-credential diagnostic: no managed identity in this "
                        + "container (IDENTITY_ENDPOINT/IDENTITY_HEADER absent), so nothing to test");
                return;
            }

            reportConfigurationAgreement();

            String assertion = requestManagedIdentityToken(identityEndpoint, identityHeader);
            if (assertion == null) {
                log.warn("T184 step 1 FAILED: could not obtain a managed-identity token for {}",
                        properties.getExchangeResource());
                return;
            }
            reportClaims(assertion);
            reportExchange(exchange(assertion));
        } catch (RuntimeException e) {
            // Class name only - see the class comment on why neither the message nor the cause may
            // be logged on this path.
            log.warn("T184 federated-credential diagnostic did not complete: {}",
                    e.getClass().getSimpleName());
        }
    }

    /**
     * Says whether this check is even asking about the configuration production will use.
     *
     * <p><b>Kevin's finding on #73, and it undercuts what a PASS means.</b> This class holds its own
     * copy of the client id and the token endpoint, while {@code application-entra.properties} reads
     * {@code ENTRA_CLIENT_ID} and derives the token endpoint from OIDC discovery - with a comment
     * there saying why: so the endpoints come from the tenant's own metadata rather than being
     * hand-copied into config. <b>This diagnostic hand-copies them.</b> Two independent copies of the
     * same two facts, with nothing pinning them together.
     *
     * <p>So a PASS proves that <em>the diagnostic's</em> client id federates against <em>the
     * diagnostic's</em> endpoint. If {@code ENTRA_CLIENT_ID} is unset, stale, or names a
     * registration that was recreated, this passes and cutover still fails - in exactly the way the
     * classification above now correctly calls a FAIL, for an application this check would never
     * name. And the reverse is worse to act on: a typo in this class's own property would report
     * "the federation does not work" while production is fine, and somebody could reasonably go and
     * change production to match a broken test.
     *
     * <p><b>It compares rather than depends.</b> Reading {@code ENTRA_CLIENT_ID} as the source would
     * stop the diagnostic running at all where the app setting does not exist yet - the entra
     * profile is not active - and this check gets one shot on one deploy. So it does what step 1
     * does with {@code iss} and {@code sub}: reads both and states the comparison either way. The
     * fix is also the measurement - nobody currently knows whether {@code ENTRA_CLIENT_ID} is set on
     * that App Service, and this answers it on the same run.
     */
    private void reportConfigurationAgreement() {
        compare("clientId", properties.getClientId(), environment.apply("ENTRA_CLIENT_ID"));
        compare("token endpoint tenant", hostOf(properties.getTokenEndpoint()),
                hostOf(environment.apply("ENTRA_ISSUER_URI")));
    }

    /** What this run can claim about production, separated from the logging so it can be tested. */
    enum ConfigVerdict { PRODUCTION_VALUE_NOT_SET, MATCHES_PRODUCTION, DIFFERS_FROM_PRODUCTION }

    static ConfigVerdict configVerdict(String diagnosticValue, String productionValue) {
        if (productionValue == null || productionValue.isBlank()) {
            return ConfigVerdict.PRODUCTION_VALUE_NOT_SET;
        }
        return productionValue.equals(diagnosticValue)
                ? ConfigVerdict.MATCHES_PRODUCTION : ConfigVerdict.DIFFERS_FROM_PRODUCTION;
    }

    private void compare(String what, String diagnosticValue, String productionValue) {
        if (configVerdict(diagnosticValue, productionValue) == ConfigVerdict.PRODUCTION_VALUE_NOT_SET) {
            log.warn("T184 config: {} - the production setting is NOT SET, so whatever this run "
                    + "reports proves nothing about the value production will use", what);
            return;
        }
        if (configVerdict(diagnosticValue, productionValue) == ConfigVerdict.MATCHES_PRODUCTION) {
            log.info("T184 config: {} matches production", what);
            return;
        }
        // Both values are identifiers rather than secrets - the same class of thing as the iss and
        // sub logged below - and a mismatch is useless without them.
        log.error("T184 config: {} DOES NOT MATCH production. This check would test '{}' while "
                + "production uses '{}', so a PASS below would prove nothing about production",
                what, diagnosticValue, productionValue);
    }

    /** The host of a URL, or the value itself if it will not parse - a comparison is still better than nothing. */
    private static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    private String requestManagedIdentityToken(String identityEndpoint, String identityHeader) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(identityEndpoint + "?resource=" + properties.getExchangeResource()
                        + "&api-version=2019-08-01"))
                .header("X-IDENTITY-HEADER", identityHeader)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        if (response == null || response.statusCode() != 200) {
            return null;
        }
        return field(response.body(), "access_token").orElse(null);
    }

    /**
     * The half worth running on its own: it either confirms the credential's configuration or hands
     * back the exact value to correct it with.
     *
     * <p>The expected mismatch is the issuer. A managed-identity token is commonly a v1 token
     * stamped {@code https://sts.windows.net/{tenant}/}, and federated-credential matching is exact,
     * so the v2 form configured on the credential would never match. That is a one-field fix - but
     * only if somebody reads the actual value, which is why it is logged whether it matches or not
     * rather than only on failure.
     */
    private void reportClaims(String assertion) {
        String payload = claimsOf(assertion);
        String issuer = field(payload, "iss").orElse("(absent)");
        String subject = field(payload, "sub").orElse("(absent)");
        String expiry = field(payload, "exp").map(FederatedCredentialDiagnostic::asInstant).orElse("(absent)");

        boolean issuerMatches = properties.getExpectedIssuer().equals(issuer);
        boolean subjectMatches = properties.getExpectedSubject().equals(subject);
        log.info("T184 step 1: managed-identity token obtained. iss={} ({}), sub={} ({}), expires={}",
                issuer, issuerMatches ? "matches the credential" : "DOES NOT MATCH the credential",
                subject, subjectMatches ? "matches" : "DOES NOT MATCH", expiry);
    }

    private HttpResponse<String> exchange(String assertion) {
        String body = "grant_type=client_credentials"
                + "&client_id=" + properties.getClientId()
                + "&client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
                + "&client_assertion=" + assertion
                + "&scope=" + java.net.URLEncoder.encode(properties.getScope(), StandardCharsets.UTF_8);
        return send(HttpRequest.newBuilder()
                .uri(URI.create(properties.getTokenEndpoint()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    /**
     * Classifies rather than reports, because the obvious reading of this response is wrong. A
     * refusal about scopes or consent means the assertion was accepted and the federation works;
     * only a missing federated identity record, or a bare invalid_client, means it does not.
     */
    private void reportExchange(HttpResponse<String> response) {
        if (response == null) {
            log.warn("T184 step 2 INCONCLUSIVE: the token endpoint could not be reached");
            return;
        }
        if (response.statusCode() == 200) {
            log.info("T184 PASS: the assertion was accepted and a token was issued");
            return;
        }
        String code = firstAadstsCode(response.body()).orElse("(no AADSTS code)");
        String error = field(response.body(), "error").orElse("(no error field)");
        switch (classify(response.statusCode(), error, code)) {
            case FAIL -> log.error("T184 FAIL: the ASSERTION was rejected, so the federation itself "
                    + "is wrong. error={}, code={}. Compare the iss and sub logged above with the "
                    + "credential - an issuer in the v1 sts.windows.net form is the likely cause and "
                    + "is a one-field fix", error, code);
            case PASS -> log.info("T184 PASS: the assertion was ACCEPTED - Entra refused on "
                    + "permissions rather than on the assertion (error={}, code={}), which it could "
                    + "only do after validating it. The app registration deliberately has no API "
                    + "permissions, so this is the expected shape of success", error, code);
            case INCONCLUSIVE -> log.warn("T184 INCONCLUSIVE: status={}, error={}, code={} - this is "
                    + "neither a recognised permissions refusal nor a recognised assertion rejection, "
                    + "so it does NOT prove the federation either way. Report the code rather than "
                    + "reading it as a pass", response.statusCode(), error, code);
        }
    }

    enum Outcome { PASS, FAIL, INCONCLUSIVE }

    /**
     * The judgement this class exists to make, separated from the logging so it can be tested
     * against the responses Entra actually returns.
     */
    static Outcome classify(int status, String error, String code) {
        if (status == 200) {
            return Outcome.PASS;
        }
        // Rejection is tested BEFORE acceptance, deliberately: a response carrying both an
        // assertion failure and a scope complaint is an assertion failure.
        if (ASSERTION_REJECTED.equals(error) || ASSERTION_REJECTED_CODES.contains(code)) {
            return Outcome.FAIL;
        }
        if (PROVES_ASSERTION_ACCEPTED.contains(code) || SCOPE_REFUSED.equals(error)) {
            return Outcome.PASS;
        }
        return Outcome.INCONCLUSIVE;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** The decoded payload of a JWT, without verifying it - this reads claims, it does not trust them. */
    static String claimsOf(String jwt) {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return "";
        }
        return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
    }

    /**
     * A single JSON string or number field, read without a JSON parser because the only inputs are
     * a token response and a JWT payload, and pulling a dependency in for a diagnostic that is
     * deleted after cutover would outlive its reason.
     */
    static Optional<String> field(String json, String name) {
        if (json == null) {
            return Optional.empty();
        }
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(?:\"([^\"]*)\"|(\\d+))").matcher(json);
        return m.find() ? Optional.ofNullable(m.group(1) != null ? m.group(1) : m.group(2)) : Optional.empty();
    }

    static Optional<String> firstAadstsCode(String body) {
        if (body == null) {
            return Optional.empty();
        }
        Matcher m = AADSTS.matcher(body);
        return m.find() ? Optional.of(m.group()) : Optional.empty();
    }

    private static String asInstant(String epochSeconds) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(epochSeconds)).toString();
        } catch (NumberFormatException e) {
            return "(unreadable)";
        }
    }
}
