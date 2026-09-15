package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T353h. <b>The interface must not tell anyone they have two factors, because after T353 they do
 * not.</b>
 *
 * <p><b>Why this is a guard and not a fix.</b> T322 §0 objected to self-service reset in these
 * words: <em>"Two factors collapse into one channel, and the login screen goes on saying
 * 'two-step'."</em> That reads as a description of shipped copy, and the obvious T353h task was to
 * go and retract it. <b>Measured on {@code 539b964}: the phrase does not exist.</b> "two-step",
 * "two-factor", "2FA", "MFA" and "multi-factor" appear in no user-facing template. The three
 * matches in the tree are Java comments about unrelated things - the export's two-step download,
 * and a note about an account disabled between two steps. <b>§0 was stating the consequence of
 * building reset, not quoting a string.</b> There was nothing to retract.
 *
 * <p>So the exposure runs the other way, and it is the ordinary kind. The screens today say
 * <em>"security code"</em>, <em>"Check your email"</em>, <em>"We've sent a 6-digit code"</em> -
 * every one of which describes a mechanism and claims nothing about independence. <b>The standard
 * industry vocabulary for that mechanism is "two-factor authentication", and it is exactly what a
 * well-meaning contributor reaches for when tidying security copy or writing a marketing page.</b>
 * That edit would look like a clarification and would be a false claim about the architecture: both
 * hops are email, and after T353 the mailbox can also reset the password.
 *
 * <p><b>The absence is therefore a property worth pinning, not a fact worth recording.</b> An
 * invariant that holds today by nobody's decision is the kind that is lost silently - the same
 * shape as T353's own entry in {@code T206}: a thing that stayed true only while nobody improved it.
 *
 * <p><b>What this test does NOT say.</b> It does not forbid describing the second factor. Naming
 * what happens ("we'll email you a code") is honest and is what every screen already does. It
 * forbids the COUNTING CLAIM - that there are two independent factors - which is the only part the
 * architecture cannot support.
 */
class TheInterfaceDoesNotClaimTwoFactorsTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /**
     * Matched case-insensitively on the rendered words only. {@code \b} on both sides so that
     * "twofactor" inside an id or a class name is not a false positive - this is about what a person
     * READS, and a CSS hook is not read by anyone.
     */
    private static final Pattern CLAIM = Pattern.compile(
            "\\b(two[-\\s]?step|two[-\\s]?factor|2[-\\s]?factor|2fa|mfa|multi[-\\s]?factor)\\b",
            Pattern.CASE_INSENSITIVE);

    /** Thymeleaf comments {@code <!--/* ... *​/-->} are notes to contributors, not rendered text. */
    private static final Pattern THYMELEAF_COMMENT = Pattern.compile("<!--/\\*.*?\\*/-->", Pattern.DOTALL);

    @Test
    @DisplayName("no user-facing template claims the product has two factors")
    void noTemplateClaimsTwoFactors() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                String rendered = renderedTextOf(Files.readString(file, StandardCharsets.UTF_8));
                Matcher m = CLAIM.matcher(rendered);
                while (m.find()) {
                    offenders.add(TEMPLATES.relativize(file) + " says \"" + m.group() + "\"");
                }
            }
        }

        assertThat(offenders)
                .as("""
                    A screen now claims the product has two factors. IT DOES NOT, AND THIS IS NOT A \
                    WORDING PREFERENCE.

                    The sign-in code and the password-reset link and code all arrive in the SAME \
                    MAILBOX. Anyone who reads that mailbox completes every hop unaided, and since \
                    T353 they can also change the password. T322 section 0 predicted precisely this \
                    and the human accepted it knowingly on 2026-09-15 - see the boxes in \
                    T206-DECISION-what-authenticates-the-pilot.md and T322-DESIGN-our-own-second-factor.md.

                    Describing the mechanism is fine and every screen already does it: "we'll email \
                    you a code", "security code", "check your email". What is not fine is COUNTING \
                    the factors, because the second one is not independent of the first.

                    If a real second channel is ever added - SMS to a stored number, or an \
                    authenticator app - this claim becomes true and this test should be deleted in \
                    the same commit that adds it. Until then, delete the claim, not the test.""")
                .isEmpty();
    }

    /**
     * THE POSITIVE CONTROL. Without it this is a negative assertion over a corpus that happens to be
     * clean, and it would pass just as cheerfully if the pattern were wrong, if the comment
     * stripping ate the whole file, or if the walk found no templates at all - a test that cannot
     * fail is not evidence.
     */
    @Test
    @DisplayName("the detector actually catches the claim it exists to catch")
    void theDetectorCatchesWhatItIsFor() {
        assertThat(TEMPLATES).exists();

        for (String claim : List.of("two-step verification", "Two Factor Authentication",
                "2FA is enabled", "we use MFA", "multi-factor sign-in", "two step check")) {
            assertThat(CLAIM.matcher(claim).find())
                    .as("the detector must match %s", claim)
                    .isTrue();
        }

        // And it must NOT match the honest descriptions the app relies on, or the guard would force
        // screens to stop saying true things.
        for (String honest : List.of("We've sent a 6-digit code to the email address on your account.",
                "Security code", "Enter your code", "This is the last step.",
                "Generation and download are deliberately two steps.")) {
            assertThat(CLAIM.matcher(honest).find())
                    .as("the detector must not match honest copy: %s", honest)
                    .isFalse();
        }

        // The comment stripper must strip, or every design note quoting the forbidden phrase - this
        // file's own subject matter - would be reported as a screen making the claim.
        String templateWithClaimInACommentOnly =
                "<!--/* T322 objected that the screen goes on saying \"two-step\". */-->\n<h1>Log in</h1>";
        assertThat(CLAIM.matcher(renderedTextOf(templateWithClaimInACommentOnly)).find())
                .as("a Thymeleaf comment is not rendered text")
                .isFalse();

        // ...but it must not strip so greedily that it swallows the markup between two comments,
        // which is how this whole guard would silently stop looking at anything.
        String claimBetweenTwoComments =
                "<!--/* first */-->\n<p>We use two-factor authentication.</p>\n<!--/* second */-->";
        assertThat(CLAIM.matcher(renderedTextOf(claimBetweenTwoComments)).find())
                .as("stripping comments must not swallow the markup between them")
                .isTrue();
    }

    private static String renderedTextOf(String template) {
        return THYMELEAF_COMMENT.matcher(template).replaceAll(" ").toLowerCase(Locale.ROOT);
    }
}
