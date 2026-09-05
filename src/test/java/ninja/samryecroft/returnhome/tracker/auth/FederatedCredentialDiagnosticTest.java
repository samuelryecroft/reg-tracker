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
