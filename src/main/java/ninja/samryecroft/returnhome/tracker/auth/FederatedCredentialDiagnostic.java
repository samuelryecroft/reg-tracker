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
 * "did Entra get past the assertion". Only {@code AADSTS700211} (no matching federated identity
 * record) or a bare {@code invalid_client} mean the federation itself is wrong. This class therefore
 * classifies the outcome rather than logging a status and leaving the next reader to interpret it -
 * the obvious reading sends someone off to mint a certificate we deliberately did not create.
 *
 * <p><b>On {@link ApplicationReadyEvent} rather than an {@code ApplicationRunner}</b>, which is the
 * opposite of the choice made for the key warmup in the same codebase, and for the opposite reason.
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
        if ("AADSTS700211".equals(code) || ("invalid_client".equals(error) && "(no AADSTS code)".equals(code))) {
            log.error("T184 FAIL: the federation itself was rejected. error={}, code={}. Entra found "
                    + "no federated identity record matching the token's issuer and subject logged "
                    + "above - compare them with the credential", error, code);
            return;
        }
        log.info("T184 PASS: the assertion was ACCEPTED - Entra rejected the request on permissions "
                + "rather than on the assertion (error={}, code={}), which it could only do after "
                + "validating it. The app registration deliberately has no API permissions, so this "
                + "is the expected shape of success", error, code);
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
