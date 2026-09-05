package ninja.samryecroft.returnhome.tracker.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T184: the parts of the federated-credential diagnostic that can be got wrong silently.
 *
 * <p>The exchange itself cannot be tested here - it needs a managed identity, which exists only
 * inside the App Service application container, which is the entire reason this diagnostic is code
 * rather than a shell command. What is testable is everything that decides <em>what the log line
 * says</em>, and that is where this check can mislead: the outcome is classified rather than
 * reported, because the obvious reading of a refusal is the wrong one, and a false failure here
 * sends someone off to mint a certificate we deliberately did not create.
 */
class FederatedCredentialDiagnosticTest {

    /**
     * The claims are read from an unverified JWT, which is correct here - this reports what the
     * token says, it does not trust it - but it means the decoding has to cope with real tokens,
     * which are base64URL and unpadded.
     */
    @Test
    void claimsAreReadFromAnUnpaddedBase64UrlPayload() {
        String payload = "{\"iss\":\"https://sts.windows.net/78b88d1e/\",\"sub\":\"5c5bfae5\",\"exp\":1788000000}";
        String jwt = "header." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".signature";

        String claims = FederatedCredentialDiagnostic.claimsOf(jwt);

        assertThat(FederatedCredentialDiagnostic.field(claims, "iss"))
                .contains("https://sts.windows.net/78b88d1e/");
        assertThat(FederatedCredentialDiagnostic.field(claims, "sub")).contains("5c5bfae5");
        assertThat(FederatedCredentialDiagnostic.field(claims, "exp")).contains("1788000000");
    }

    /**
     * The expected mismatch, and the reason step one is worth running even if step two never does:
     * a managed-identity token is commonly a v1 token stamped {@code sts.windows.net}, while the
     * credential is configured with the v2 {@code login.microsoftonline.com} form. Federated
     * matching is exact, so those never match - and it is a one-field fix, but only if somebody
     * reads the actual value. This asserts the two forms are genuinely distinguishable rather than
     * both passing some loose comparison.
     */
    @Test
    void theV1AndV2IssuerFormsAreNotTreatedAsTheSame() {
        String v1 = "https://sts.windows.net/78b88d1e-fe6e-4519-8805-155735aa192e/";
        String v2 = "https://login.microsoftonline.com/78b88d1e-fe6e-4519-8805-155735aa192e/v2.0";

        assertThat(new FederatedCredentialDiagnosticProperties().getExpectedIssuer()).isEqualTo(v2);
        assertThat(v1).isNotEqualTo(v2);
    }

    @Test
    void theAadstsCodeIsExtractedFromAnErrorBody() {
        String body = "{\"error\":\"invalid_request\",\"error_description\":\"AADSTS700211: No matching "
                + "federated identity record found for presented assertion. Trace ID: abc\"}";

        assertThat(FederatedCredentialDiagnostic.firstAadstsCode(body)).contains("AADSTS700211");
        assertThat(FederatedCredentialDiagnostic.field(body, "error")).contains("invalid_request");
    }

    /**
     * The defect this classification was written with, found by asking what input would make it
     * report PASS when it should say FAIL.
     *
     * <p>The first version treated only a <em>bare</em> {@code invalid_client} - one carrying no
     * AADSTS code - as a failure. Entra almost always attaches a code, so every realistic assertion
     * failure carried one and came back PASS. {@code AADSTS700027} is a signature that did not
     * validate; {@code AADSTS700024} is an expired assertion. Both are the federation being wrong,
     * and both would have been recorded as proof that it works.
     *
     * <p>A false PASS is the one direction that matters here: it lets a broken federation reach
     * cutover, which is the outage T184 exists to prevent. A false FAIL costs a conversation.
     */
    @Test
    void anAssertionRejectionCarryingAnAadstsCodeIsStillAFailure() {
        assertThat(FederatedCredentialDiagnostic.classify(401, "invalid_client", "AADSTS700027"))
                .isEqualTo(FederatedCredentialDiagnostic.Outcome.FAIL);
        assertThat(FederatedCredentialDiagnostic.classify(401, "invalid_client", "AADSTS700024"))
                .isEqualTo(FederatedCredentialDiagnostic.Outcome.FAIL);
        assertThat(FederatedCredentialDiagnostic.classify(401, "invalid_client", "(no AADSTS code)"))
                .isEqualTo(FederatedCredentialDiagnostic.Outcome.FAIL);
        assertThat(FederatedCredentialDiagnostic.classify(400, "invalid_request", "AADSTS700211"))
                .isEqualTo(FederatedCredentialDiagnostic.Outcome.FAIL);
    }

    /**
     * The paired positive, and the reason the test above is not simply "everything fails": a refusal
     * about permissions means Entra validated the assertion first, which is exactly what this
     * diagnostic is asking about. The app registration deliberately has no API permissions.
     */
    @Test
    void anOutcomeEntraCouldOnlyReachAfterAcceptingTheAssertionIsASuccess() {
        // The most likely real PASS: a registration that deliberately holds no API permissions gets
        // "admin consent has not been granted", which Entra can only decide after authenticating us.
        assertThat(FederatedCredentialDiagnostic.classify(400, "invalid_grant", "AADSTS65001"))
                .isEqualTo(FederatedCredentialDiagnostic.Outcome.PASS);
        assertThat(FederatedCredentialDiagnostic.classify(400, "invalid_scope", "AADSTS70011"))
                .isEqualTo(FederatedCredentialDiagnostic.Outcome.PASS);
        assertThat(FederatedCredentialDiagnostic.classify(400, "invalid_request", "AADSTS500011"))
                .isEqualTo(FederatedCredentialDiagnostic.Outcome.PASS);
        assertThat(FederatedCredentialDiagnostic.classify(200, "(no error field)", "(no AADSTS code)"))
                .isEqualTo(FederatedCredentialDiagnostic.Outcome.PASS);
    }

    /**
     * Kevin's finding on #73, and it is the same defect I had just fixed one layer up - a broken
     * federation reported as a pass, this time sitting in the SET the fixed code reads rather than
     * in the matching.
     *
     * <p>Entra returns <b>AADSTS700016, "application not found in the directory"</b>, as
     * {@code unauthorized_client}. An application that was never found never had its assertion
     * validated against anything, so this is not a permissions refusal - it is precisely the
     * federation being wrong, and it is the most likely way this is misconfigured on cutover day: a
     * client id pointing at the wrong tenant, or at a registration that was recreated.
     *
     * <p>The review asked for INCONCLUSIVE. These go further, to FAIL, because "the application does
     * not exist" is not a "we do not know" - a wrong FAIL costs an investigation, while INCONCLUSIVE
     * invites somebody to decide it was probably fine.
     */
    @Test
    void anApplicationThatWasNeverFoundIsAFailureNotAPermissionsRefusal() {
        assertThat(FederatedCredentialDiagnostic.classify(400, "unauthorized_client", "AADSTS700016"))
                .isEqualTo(FederatedCredentialDiagnostic.Outcome.FAIL);
        assertThat(FederatedCredentialDiagnostic.classify(400, "unauthorized_client", "AADSTS7000112"))
                .isEqualTo(FederatedCredentialDiagnostic.Outcome.FAIL);
    }

    /**
     * When a response carries both signals, rejection wins - which is what makes the ordering in
     * {@code classify} a decision rather than an accident.
     *
     * <p>It is reachable: {@code firstAadstsCode} takes the first code in the body, and an Entra
     * error description can name more than one. Without this, swapping the two branches leaves every
     * other test in this file passing, and the comment saying the order is deliberate would be an
     * unpinned claim - the same shape as a guard nobody has run a failing control against.
     */
    @Test
    void aResponseCarryingBothSignalsIsReadAsARejection() {
        assertThat(FederatedCredentialDiagnostic.classify(400, "invalid_scope", "AADSTS700016"))
                .isEqualTo(FederatedCredentialDiagnostic.Outcome.FAIL);
        assertThat(FederatedCredentialDiagnostic.classify(401, "invalid_client", "AADSTS65001"))
                .isEqualTo(FederatedCredentialDiagnostic.Outcome.FAIL);
    }

    /**
     * The entries that were in the set because they SOUNDED like permissions errors. Re-derived from
     * "which outcomes can Entra only produce after accepting our assertion?", none of them survives:
     * {@code insufficient_scope} is an RFC 6750 resource-server response that cannot appear at a
     * token endpoint at all, {@code consent_required} and {@code interaction_required} are
     * interactive-flow errors impossible on client_credentials, and a bare {@code unauthorized_client}
     * or {@code invalid_grant} spans both stages - under RFC 7521/7523 an assertion used for client
     * authentication fails as {@code invalid_client}, so {@code invalid_grant} here is ambiguous, and
     * ambiguous is what INCONCLUSIVE is for.
     */
    @Test
    void errorsThatMerelySoundLikePermissionsProveNothing() {
        for (String error : java.util.List.of("insufficient_scope", "consent_required",
                "interaction_required", "access_denied", "unauthorized_client", "invalid_grant")) {
            assertThat(FederatedCredentialDiagnostic.classify(400, error, "(no AADSTS code)"))
                    .as("%s must not be read as proof the assertion was accepted", error)
                    .isEqualTo(FederatedCredentialDiagnostic.Outcome.INCONCLUSIVE);
        }
    }

    /**
     * PASS is a whitelist, so anything unrecognised is INCONCLUSIVE rather than PASS. This is the
     * assertion that keeps the whole check honest: T184 must never be recorded as proven on an
     * outcome nobody anticipated. Erring towards "we do not know" costs a follow-up question;
     * erring towards PASS costs sign-in for every user at once at cutover.
     */
    @Test
    void anUnrecognisedRefusalProvesNothingRatherThanPassing() {
        assertThat(FederatedCredentialDiagnostic.classify(500, "temporarily_unavailable", "AADSTS90033"))
                .isEqualTo(FederatedCredentialDiagnostic.Outcome.INCONCLUSIVE);
        assertThat(FederatedCredentialDiagnostic.classify(400, "(no error field)", "(no AADSTS code)"))
                .isEqualTo(FederatedCredentialDiagnostic.Outcome.INCONCLUSIVE);
    }

    @Test
    void anAbsentFieldIsAbsentRatherThanEmpty() {
        assertThat(FederatedCredentialDiagnostic.field("{}", "iss")).isEmpty();
        assertThat(FederatedCredentialDiagnostic.firstAadstsCode("{}")).isEmpty();
        assertThat(FederatedCredentialDiagnostic.field(null, "iss")).isEmpty();
    }

    /**
     * The constraint that outlives this ticket. The step-one token is a bearer credential, and
     * Application Insights captures INFO and above into a Log Analytics workspace shared across the
     * platform with no field-level encryption - so a token in a log line is the same class of
     * disclosure as a decrypted date of birth in one (T179).
     *
     * <p>Written as a source scan for the same reason the T179 guards are: what must hold is that
     * nobody logs the assertion, and that is a property of the source. It scans for the assertion
     * and body variables appearing inside a logging call at all, rather than for a particular
     * formatting, because the mistake this prevents is someone adding {@code log.debug("...", body)}
     * while debugging a failed exchange - which is exactly when they would most want to.
     */
    @Test
    void neitherTheAssertionNorAResponseBodyIsEverLogged() throws IOException {
        Path source = Path.of("src/main/java/ninja/samryecroft/returnhome/tracker/auth/"
                + "FederatedCredentialDiagnostic.java");
        assertThat(source).as("the diagnostic must exist for this guard to mean anything").exists();
        String text = Files.readString(source, StandardCharsets.UTF_8);

        List<String> offences = new java.util.ArrayList<>();
        for (String arguments : logCallArguments(text)) {
            // String literals are removed BEFORE looking, because the first version of this guard
            // matched the word "assertion" inside the log messages themselves - "the assertion was
            // accepted" - and reported correct code as a leak. A guard that cries wolf teaches
            // people to add exclusions, which is how it stops guarding anything, so it has to test
            // what it means: is the VALUE passed, not does the WORD appear.
            String code = withoutStringLiterals(arguments);
            for (String forbidden : List.of("assertion", "body", "getMessage()", "getCause()")) {
                if (java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(forbidden))
                        .matcher(code).find()) {
                    offences.add("a log call passes " + forbidden);
                }
            }
        }

        assertThat(offences)
                .as("the assertion is a bearer credential and a raw body or exception can echo the "
                        + "request that produced it. Telemetry is one Log Analytics workspace for "
                        + "the whole platform with no field-level encryption. Log the claims, the "
                        + "expiry and the error code - never the token, the body, or an exception's "
                        + "message or cause")
                .isEmpty();
    }

    /** Everything outside string literals - see the guard above for why this is not optional. */
    private static String withoutStringLiterals(String source) {
        StringBuilder code = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            code.append(c);
        }
        return code.toString();
    }

    /** Argument lists of logging calls, by matching parentheses - the T179 technique. */
    private static List<String> logCallArguments(String source) {
        List<String> arguments = new java.util.ArrayList<>();
        java.util.regex.Matcher call = java.util.regex.Pattern
                .compile("\\.\\s*(?:trace|debug|info|warn|error)\\s*\\(").matcher(source);
        while (call.find()) {
            int depth = 1;
            int i = call.end();
            while (i < source.length() && depth > 0) {
                char c = source.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
                i++;
            }
            if (depth == 0) {
                arguments.add(source.substring(call.end(), i - 1));
            }
        }
        return arguments;
    }

    /**
     * Off unless switched on. A diagnostic that makes an outbound token request on every start of
     * every environment by default is not a diagnostic, it is a dependency nobody chose.
     */
    @Test
    void theDiagnosticIsOffByDefault() throws IOException {
        assertThat(new FederatedCredentialDiagnosticProperties().isEnabled()).isFalse();

        try (Stream<Path> files = Files.walk(Path.of("src/main/resources"))) {
            for (Path file : files.filter(p -> p.getFileName().toString().startsWith("application")).toList()) {
                assertThat(Files.readString(file, StandardCharsets.UTF_8))
                        .as("%s must not switch the diagnostic on - it is enabled per deploy, "
                                + "deliberately, and turned off again", file)
                        .doesNotContain("fic-diagnostic.enabled: true")
                        .doesNotContain("fic-diagnostic.enabled=true");
            }
        }
    }
}
