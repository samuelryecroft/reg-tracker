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
