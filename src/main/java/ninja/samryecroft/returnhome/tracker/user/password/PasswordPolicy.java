package ninja.samryecroft.returnhome.tracker.user.password;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The password rule (T272), as ONE object used by every path that sets a password.
 *
 * <p>It was {@code @Size(min = 8)} in two places - {@code CreateUserForm} and {@code EditUserForm} -
 * and <strong>two copies of a rule is how one of them stops being true.</strong> There is one copy
 * now, and the Bean Validation constraint, the admin seeder and the edit path all call it rather
 * than restating it.
 *
 * <p><strong>What the rule deliberately does NOT contain:</strong> composition rules and forced
 * rotation. Those are the two mechanisms that demonstrably push people toward predictable passwords,
 * and on shared devices toward written-down ones - they are the cause of the failure mode this is
 * trying to avoid, not a defence against it (Kevin, T272 R1; NIST SP 800-63B, NCSC).
 *
 * <p><strong>The maximum is a crash guard, not a strength control.</strong> Spring Security 7.1.0's
 * {@code BCryptPasswordEncoder.encode()} THROWS {@code IllegalArgumentException} above 72 bytes - it
 * does not silently truncate (measured by Kevin against the jar we build with). With no maximum on
 * the form, that was an unhandled exception and an error page on admin user-create and user-edit.
 * So this rule is the difference between a validation message and a 500.
 *
 * <p><strong>A MEASURED CAVEAT ON THE BLOCKLIST, recorded because it changes what this rule
 * actually defends.</strong> Only 10 of the bundled list's 10,000 entries are 12 characters or
 * longer, so under the current minimum the other 9,990 can never be submitted - and the ruling's own
 * example, {@code Password1234}, is not in the list at all. A generic list and a length minimum
 * defend against nearly disjoint sets. What carries the blocklist half here is
 * {@link PasswordContext}'s four values, which is what the ruling says matters for this population;
 * the file is the commodity part.
 *
 * <p><strong>And it counts BYTES, not characters.</strong> {@code @Size(max = 72)} does not
 * implement this: {@code @Size} counts characters, and 60 accented characters are under any
 * character cap and over the byte ceiling, leaving the crash reachable for exactly the passphrases
 * most likely to be chosen by someone told to use a long one.
 */
@Component
public class PasswordPolicy {

    /** Long enough to matter, given the blocklist. Below this, R2 would be carrying the whole rule. */
    public static final int MINIMUM_LENGTH = 12;

    /** BCrypt's own ceiling. Measured, not assumed: above it the encoder throws rather than truncates. */
    public static final int MAXIMUM_BYTES = 72;

    private static final String BLOCKLIST_RESOURCE = "/security/weak-passwords.txt";

    private final Set<String> blocked;
    private final String applicationName;

    public PasswordPolicy(@Value("${spring.application.name}") String applicationName) {
        this.applicationName = applicationName;
        this.blocked = loadBlocklist();
    }

    /**
     * The single reason this password is unacceptable, or empty if it is fine.
     *
     * <p>Returns the FIRST violation rather than all of them: a person choosing a password gets one
     * thing to fix at a time, and a list that says "too short, and also on a blocklist" tells them
     * more about our checks than about their choice.
     *
     * @param password the candidate. A null or blank value is NOT a violation here - whether a
     *                 password is required at all is a different rule, held by the forms, and this
     *                 object must not silently become the answer to a question it was not asked.
     * @param context  who it is for. Every field is optional; a missing one is simply not checked,
     *                 and each caller documents which it can supply.
     */
    public Optional<String> rejectionFor(String password, PasswordContext context) {
        if (password == null || password.isBlank()) {
            return Optional.empty();
        }
        if (password.length() < MINIMUM_LENGTH) {
            return Optional.of("Password must be at least " + MINIMUM_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAXIMUM_BYTES) {
            return Optional.of("Password must be " + MAXIMUM_BYTES + " bytes or fewer. Accented or "
                    + "non-Latin characters use more than one byte each, so a long passphrase can "
                    + "exceed this while looking shorter");
        }
        String normalised = password.trim().toLowerCase(Locale.ROOT);
        if (blocked.contains(normalised)) {
            return Optional.of("That password appears on a list of commonly used passwords. "
                    + "Choose something else - length matters more than symbols");
        }
        // The exact value is checked FIRST and the stem second, so the message above can stay
        // specific about what was recognised rather than both cases sharing one vaguer sentence.
        if (blocked.contains(stemOf(normalised))) {
            return Optional.of("That is a commonly used password with digits added to the end. "
                    + "The digits do not help - choose different words instead");
        }
        for (String value : context.significantValues(applicationName)) {
            if (normalised.contains(value)) {
                return Optional.of("Password must not contain \"" + value + "\". A password built "
                        + "from the account or the service is guessable by anyone who knows either");
            }
        }
        return Optional.empty();
    }

    /**
     * The password with trailing digits removed, for the second blocklist check (T280).
     *
     * <p><strong>This exists because R1 CAUSES the shape it catches.</strong> Tell someone whose
     * password is {@code password} that they now need twelve characters and they produce
     * {@code password1234}. The minimum manufactures exactly the pattern an un-normalised list
     * cannot see - so this is not an enhancement to the blocklist, it is the blocklist being aimed
     * at where the minimum moved the target. It also explains the measurement that prompted it: only
     * 10 of the bundled list's 10,000 entries are 12 characters or longer, because the list is full
     * of the STEMS people then pad.
     *
     * <p><strong>TRAILING DIGITS ONLY, AND THIS IS A DELIBERATE STOPPING POINT RATHER THAN AN
     * UNFINISHED ONE.</strong> Not trailing punctuation, not leading digits, not l33t substitution.
     * Every additional normalisation raises the FALSE-REJECT rate, and on this population a false
     * rejection costs us a written-down password on a shared device - the exact failure the whole
     * policy is calibrated to avoid. Lower-casing already happens above. <em>Do not "complete" this
     * by adding more.</em>
     *
     * <p>A stem that strips to nothing, or to almost nothing, matches NOTHING: {@code 123456789012}
     * must not become a blocklist hit by way of the empty string, which every {@code contains} call
     * would otherwise answer for.
     */
    private String stemOf(String normalised) {
        int end = normalised.length();
        while (end > 0 && Character.isDigit(normalised.charAt(end - 1))) {
            end--;
        }
        String stem = normalised.substring(0, end);
        return stem.length() >= SHORTEST_MEANINGFUL_STEM ? stem : NO_STEM;
    }

    /**
     * Below this a stem is not a word anyone chose, it is what is left after stripping. Four keeps
     * real short entries on the list ({@code love}, {@code pass}) while refusing to treat the
     * remains of {@code ab123456789} as a password somebody picked.
     */
    private static final int SHORTEST_MEANINGFUL_STEM = 4;

    /** A value no blocklist entry can equal, so a too-short stem matches nothing rather than everything. */
    private static final String NO_STEM = "\u0000";

    /**
     * Read once at startup and held in memory: ~10k short strings, and the alternative is file I/O
     * on a form submission. A missing or unreadable resource is a startup failure ON PURPOSE - the
     * blocklist half of the rule silently not loading would leave the length check looking like the
     * whole policy, and nothing would say so.
     */
    private Set<String> loadBlocklist() {
        try (InputStream in = PasswordPolicy.class.getResourceAsStream(BLOCKLIST_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("The weak-password blocklist " + BLOCKLIST_RESOURCE
                        + " is missing from the classpath. It is half of the password rule (T272 R2), "
                        + "so the application refuses to start rather than enforce length alone.");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                    Stream<String> lines = reader.lines()) {
                Set<String> entries = new HashSet<>();
                lines.map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .map(line -> line.toLowerCase(Locale.ROOT))
                        .forEach(entries::add);
                return Set.copyOf(entries);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the weak-password blocklist", e);
        }
    }
}
