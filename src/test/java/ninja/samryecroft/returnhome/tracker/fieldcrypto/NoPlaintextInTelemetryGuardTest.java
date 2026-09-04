package ninja.samryecroft.returnhome.tracker.fieldcrypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T179 - decrypted field content must not be able to reach a log line or an exception message.
 *
 * <p><b>Why this exists.</b> Application Insights captures logs at INFO and above, plus exception
 * traces, into a Log Analytics workspace that is <em>one workspace for the whole platform</em> with
 * no field-level encryption. Per-organisation keys isolate tenants in Postgres and in blob storage;
 * they do not reach telemetry. So a field value that lands in a log is cross-tenant readable
 * plaintext sitting <em>outside</em> the key boundary - it defeats the isolation model, not merely
 * the encryption.
 *
 * <p>The T179 sweep found the estate clean: no {@code toString()} exists on an encrypted entity, and
 * no logging call names an encrypted getter. <b>That cleanliness was a property of care, not of
 * anything enforced.</b> This test is what makes it a property of the build.
 *
 * <p><b>Two guards, because the second one is the one that would have caught the actual bug.</b>
 * This matters more than the code below and is the reason for the length of this comment. The
 * obvious guard - "no encrypted getter appears in a log call" - is {@link
 * #noLoggingOrExceptionMessageNamesAnEncryptedField()}, and it would have passed cleanly against the
 * defect that prompted T179. That defect named no getter at all:
 *
 * <pre>catch (DateTimeParseException e) { throw new FieldCryptoException("Decrypted " + context + " is not a date", e); }</pre>
 *
 * <p>The message was careful and named only the field. The <em>cause</em> was not: {@code
 * DateTimeParseException}'s message is {@code "Text '<value>' could not be parsed"}, and that value
 * was the decrypted plaintext - a child's date of birth. <b>A cause is not neutral metadata; it is
 * another author's message, and wrapping it inherits their disclosure decisions.</b> Sanitising a
 * message and then attaching a cause that echoes its input undoes the sanitising, silently.
 *
 * <p>So {@link #noValueEchoingExceptionIsWrappedAsACause()} guards the second axis. Written first,
 * the first guard alone would have been a control that looked complete and covered the wrong thing.
 *
 * <p><b>What these guards do not cover</b>, stated so their silence is not mistaken for assurance:
 * a third-party library that logs a value we passed it; a value reaching telemetry through a channel
 * that is not a log call or an exception (a metric dimension, a span attribute, an HTTP path); and
 * a catch block containing nested braces, which the second guard's matcher will not parse and will
 * therefore skip rather than fail on.
 */
class NoPlaintextInTelemetryGuardTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");

    /**
     * Exception types whose own message echoes the input they failed to parse.
     *
     * <p>Each is here because {@code getMessage()} quotes the offending value. That is helpful in
     * every ordinary context and disqualifying in this one, where the offending value is a decrypted
     * field. Adding a type here is cheap; the test is only as good as this list, so a new conversion
     * in {@code EncryptedFields.fromString} should bring its parse exception with it.
     */
    private static final List<String> VALUE_ECHOING = List.of(
            "DateTimeParseException", "NumberFormatException", "DateTimeException");

    private static final Pattern ENCRYPTED_FIELD = Pattern.compile(
            "@Encrypted\\([^)]*\\)\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*private\\s+\\S+\\s+(\\w+)\\s*;");

    /**
     * Any SLF4J-shaped logging call, and any exception construction.
     *
     * <p>Deliberately matches the METHOD rather than the receiver. The first version of this
     * required the logger to be named {@code log}, {@code logger} or {@code LOG} - and a mutation
     * survived it: a call on any other receiver (an inline {@code LoggerFactory.getLogger(...)}, an
     * injected field under a different name) was invisible. That would have made the guard's real
     * contract "the logger is conventionally named", which is a convention this codebase happens to
     * keep rather than anything enforced - and a guard resting on a convention goes quiet exactly
     * when someone departs from it.
     */
    private static final Pattern LOG_OR_THROW = Pattern.compile(
            "(?:\\.\\s*(?:trace|debug|info|warn|error)\\s*\\()|(?:new\\s+\\w*Exception\\s*\\()");

    @Test
    void noLoggingOrExceptionMessageNamesAnEncryptedField() throws IOException {
        Set<String> getters = encryptedGetterNames();
        assertThat(getters)
                .as("the guard derives its own subject matter from the @Encrypted annotations, so a "
                        + "new encrypted field is covered automatically. Finding none means the "
                        + "pattern has stopped matching and this test is guarding nothing")
                .isNotEmpty();

        List<String> offences = new ArrayList<>();
        for (Path file : javaSources()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            for (String arguments : argumentListsOfLogAndThrowCalls(source)) {
                for (String getter : getters) {
                    if (arguments.contains(getter + "(")) {
                        offences.add(file.getFileName() + " -> " + getter);
                    }
                }
            }
        }

        assertThat(offences)
                .as("a log line or exception message reads an ENCRYPTED field. Telemetry is a single "
                        + "Log Analytics workspace for the whole platform with no field-level "
                        + "encryption, so this value would be cross-tenant readable plaintext OUTSIDE "
                        + "the per-organisation key boundary - it defeats tenant isolation, not just "
                        + "encryption. Log the id and the field NAME; never the value. If you need "
                        + "the value to diagnose this, you need the database, not the log")
                .isEmpty();
    }

    @Test
    void noValueEchoingExceptionIsWrappedAsACause() throws IOException {
        List<String> offences = new ArrayList<>();
        for (Path file : javaSources()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            for (String type : VALUE_ECHOING) {
                Matcher m = Pattern.compile(
                        "catch\\s*\\(\\s*" + type + "\\s+(\\w+)\\s*\\)\\s*\\{([^{}]*)\\}")
                        .matcher(source);
                while (m.find()) {
                    String variable = m.group(1);
                    String body = m.group(2);
                    if (Pattern.compile(",\\s*" + variable + "\\s*\\)").matcher(body).find()) {
                        offences.add(file.getFileName() + " wraps " + type + " as a cause");
                    }
                }
            }
        }

        assertThat(offences)
                .as("a parse exception is being passed as a CAUSE. Its message quotes the value it "
                        + "failed to parse - and on these paths that value is decrypted field "
                        + "content, so attaching it puts plaintext into telemetry even though the "
                        + "message you wrote is clean. A CAUSE IS ANOTHER AUTHOR'S MESSAGE AND YOU "
                        + "INHERIT THEIR DISCLOSURE DECISIONS. Drop the cause: its only information "
                        + "is the value, which is exactly what must not travel")
                .isEmpty();
    }

    /**
     * The ARGUMENT LISTS of every logging call and exception construction, extracted by matching
     * parentheses rather than by splitting on statement terminators.
     *
     * <p>The cheap version of this - split the file on {@code ;} and look for a getter anywhere in a
     * chunk containing a log or throw - is wrong, and it was wrong on real code the first time this
     * ran. {@code ReportService.reject} opens with
     *
     * <pre>if (form.getReviewComments() == null || form.getReviewComments().isBlank()) { throw new IllegalArgumentException("Comments are required..."); }</pre>
     *
     * <p>There is no {@code ;} between the condition and the throw, so the naive split reads them as
     * one chunk and reports a leak. The getter is in the GUARD, not in the message, and the message
     * is a fixed string. A guard that cries wolf on correct code teaches people to add exclusions,
     * which is how it stops guarding anything - so it has to test what it actually means: does the
     * value appear INSIDE the call's arguments.
     */
    private static List<String> argumentListsOfLogAndThrowCalls(String source) {
        List<String> arguments = new ArrayList<>();
        Matcher call = LOG_OR_THROW.matcher(source);
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

    private static Set<String> encryptedGetterNames() throws IOException {
        Set<String> getters = new LinkedHashSet<>();
        for (Path file : javaSources()) {
            Matcher m = ENCRYPTED_FIELD.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (m.find()) {
                String field = m.group(1);
                getters.add("get" + Character.toUpperCase(field.charAt(0)) + field.substring(1));
            }
        }
        return getters;
    }

    private static List<Path> javaSources() throws IOException {
        assertThat(MAIN_JAVA).as("the scanned source tree must exist - a moved tree must not "
                + "silently turn these guards into no-ops").exists();
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
        }
    }
}
