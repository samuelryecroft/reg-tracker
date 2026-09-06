package ninja.samryecroft.returnhome.tracker.user.password;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * T272's rule, as one object.
 *
 * <p>The maximum is tested as a CRASH GUARD rather than a strength control, because that is what it
 * is: Spring Security 7.1.0's BCryptPasswordEncoder THROWS above 72 bytes rather than truncating, so
 * with no maximum on the form any long passphrase produced an unhandled IllegalArgumentException -
 * an error page, not a field error - on admin user-create and user-edit.
 */
class PasswordPolicyTest {

    private static final String APP_NAME = "return-home-tracker";

    private final PasswordPolicy policy = new PasswordPolicy(APP_NAME);

    @Test
    void aLongPassphraseWithNoSymbolsIsFine() {
        assertThat(policy.rejectionFor("correct battery staple horse", PasswordContext.none())).isEmpty();
    }

    @Test
    void elevenCharactersIsTooShort() {
        assertThat(policy.rejectionFor("abcdefghijk", PasswordContext.none()))
                .hasValueSatisfying(message -> assertThat(message).contains("at least 12"));
    }

    /**
     * THE CRASH GUARD, AND THE REASON @Size(max = 72) WOULD NOT HAVE DONE IT. Thirty-seven "é" are
     * 37 CHARACTERS - under any character cap anyone would write - and 74 BYTES, which is over the
     * encoder's ceiling. A character-counting constraint leaves the 500 reachable for exactly the
     * passphrases someone told to "use a long one" is most likely to choose.
     */
    @Test
    void aPassphraseUnderAnyCharacterCapCanStillBeOverTheBYTECeiling() {
        String accented = "é".repeat(37);

        assertThat(accented.length()).isLessThan(PasswordPolicy.MAXIMUM_BYTES);
        assertThat(accented.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(PasswordPolicy.MAXIMUM_BYTES);
        assertThat(policy.rejectionFor(accented, PasswordContext.none()))
                .hasValueSatisfying(message -> assertThat(message).contains("72 bytes"));
    }

    @Test
    void seventyTwoBytesExactlyIsAccepted() {
        assertThat(policy.rejectionFor("a".repeat(72), PasswordContext.none())).isEmpty();
    }

    /**
     * A listed password is refused however it is cased or padded with spaces.
     *
     * <p>The example is a real entry from the bundled list that is ALSO twelve characters, which is
     * a much narrower set than it sounds: see the note in {@code weak-passwords.txt}. Ten of its ten
     * thousand entries are long enough to be submitted at all under the current minimum.
     */
    @Test
    void aListedPasswordIsRefusedWhateverItsCaseOrPadding() {
        assertThat(policy.rejectionFor("unbelievable", PasswordContext.none()))
                .hasValueSatisfying(message -> assertThat(message).contains("commonly used"));
        assertThat(policy.rejectionFor("  UnBelievable  ", PasswordContext.none())).isPresent();
    }

    /**
     * THE PASSWORD THE RULING NAMES. A generic top-10k list does not contain "returnhome2026" - this
     * population's weak password is built from the service, not from "hunter2" - and whole-value
     * matching alone would miss it too, since the application is called "return-home-tracker".
     */
    @Test
    void aPasswordBuiltFromTheServiceNameIsRefused() {
        assertThat(policy.rejectionFor("returnhome2026", PasswordContext.none()))
                .hasValueSatisfying(message -> assertThat(message).contains("return"));
    }

    // --- T280: the minimum manufactures the shape the un-normalised list cannot see ---

    /**
     * THE CASE THE RULING'S OWN EXAMPLE NAMED, WHICH WAS ACCEPTED UNTIL T280. "password" is on the
     * list; "Password1234" is not, and never will be. R1 does not merely fail to catch stem+digits -
     * R1 CAUSES it: tell someone whose password is "password" that they need twelve characters and
     * they produce exactly this.
     */
    @Test
    void aListedPasswordPaddedWithTrailingDigitsIsRefused() {
        assertThat(policy.rejectionFor("Password1234", PasswordContext.none()))
                .hasValueSatisfying(message -> assertThat(message).contains("digits added to the end"));
    }

    /** The two messages are distinguishable, so a log or a support call can tell which fired. */
    @Test
    void theExactHitAndTheStemHitSayDifferentThings() {
        String exact = policy.rejectionFor("unbelievable", PasswordContext.none()).orElseThrow();
        String stem = policy.rejectionFor("unbelievable99", PasswordContext.none()).orElseThrow();

        assertThat(exact).isNotEqualTo(stem);
        assertThat(exact).doesNotContain("digits added to the end");
    }

    /**
     * A STEM THAT STRIPS TO NOTHING MATCHES NOTHING. Without the floor, "123456789012" strips to the
     * empty string - and an empty string is a substring of everything, so a perfectly ordinary
     * all-digit passphrase would be reported as a commonly used password. A guard whose failure mode
     * is "refuse everything" is worse on this population than the gap it closes.
     */
    @Test
    void anAllDigitPasswordDoesNotStripToTheEmptyStringAndMatchEverything() {
        assertThat(policy.rejectionFor("123456789012", PasswordContext.none())).isEmpty();
        assertThat(policy.rejectionFor("ab1234567890", PasswordContext.none())).isEmpty();
    }

    /**
     * The blocklist itself carries no entry short enough to make stem-matching dangerous.
     *
     * <p>This is the arm-able half of the floor in {@code stemOf}. Removing that floor changes no
     * behaviour against today's list, because a Set lookup cannot match an empty stem and no entry
     * is shorter than four characters - so the property is currently held by the DATA. Asserting the
     * data is what keeps the CODE's floor meaningful: add {@code abc} to the file and
     * {@code abc123456789}, an ordinary passphrase, becomes a refusal. This test goes red first.
     */
    @Test
    void noBlocklistEntryIsShortEnoughToMakeStemMatchingDangerous() throws Exception {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                PasswordPolicy.class.getResourceAsStream("/security/weak-passwords.txt"),
                java.nio.charset.StandardCharsets.UTF_8))) {
            assertThat(reader.lines().map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .filter(line -> line.length() < 4)
                    .toList())
                    .as("a blocklist entry shorter than the stem floor would refuse ordinary "
                            + "passphrases that merely end in digits")
                    .isEmpty();
        }
    }

    /**
     * TRAILING DIGITS ONLY - a deliberate stopping point. Trailing punctuation is NOT stripped, and
     * this test exists so that someone "completing" the normalisation has to change a test that says
     * why: every additional rule raises the false-reject rate, and a false rejection here costs a
     * written-down password on a shared device.
     */
    @Test
    void normalisationStopsAtTrailingDigitsOnPurpose() {
        assertThat(policy.rejectionFor("unbelievable!!", PasswordContext.none())).isEmpty();
    }

    /** And leading digits are not stripped either: the stem is what people append to, not prepend. */
    @Test
    void leadingDigitsAreNotStripped() {
        assertThat(policy.rejectionFor("12unbelievable", PasswordContext.none())).isEmpty();
    }

    @Test
    void aPasswordBuiltFromTheUsernameIsRefused() {
        assertThat(policy.rejectionFor("jsmithjsmith99", new PasswordContext("jsmith", null, null)))
                .isPresent();
    }

    @Test
    void aPasswordBuiltFromTheOrganisationIsRefused() {
        assertThat(policy.rejectionFor("harbourside2026", new PasswordContext(null, null, "Harbourside Care")))
                .isPresent();
    }

    /**
     * Only the LOCAL-PART of the address. Every address in one organisation shares a domain, so
     * banning it would ban a word every single user has in common - and a rule that fires for
     * everyone is a rule people route around.
     */
    @Test
    void onlyTheLocalPartOfTheEmailIsBanned() {
        PasswordContext context = new PasswordContext(null, "jsmith@harbourside.example.org", null);

        assertThat(policy.rejectionFor("jsmithlongenough", context)).isPresent();
        assertThat(policy.rejectionFor("example org rocks", context)).isEmpty();
    }

    /**
     * An ordinary English word inside a long passphrase is NOT a violation, even though the service
     * name contains it. Six characters is the threshold for exactly this: at four, "home" and "care"
     * would be banned, and rejecting those costs a care worker a password they had good reason to
     * choose.
     */
    @Test
    void anOrdinaryWordInsideTheServiceNameIsNotBanned() {
        assertThat(policy.rejectionFor("the home fires burn", PasswordContext.none())).isEmpty();
    }

    /**
     * Whether a password is REQUIRED is a different rule, and this object must not silently become
     * the answer to a question it was not asked - the create form allows an account with none.
     */
    @Test
    void anAbsentPasswordIsNotThisRulesBusiness() {
        assertThat(policy.rejectionFor(null, PasswordContext.none())).isEmpty();
        assertThat(policy.rejectionFor("   ", PasswordContext.none())).isEmpty();
    }

    /** One thing to fix at a time: a list of every failure describes our checks, not their choice. */
    @Test
    void onlyTheFirstViolationIsReported() {
        assertThat(policy.rejectionFor("returnhome", PasswordContext.none()))
                .hasValueSatisfying(message -> assertThat(message).contains("at least 12"));
    }
}
