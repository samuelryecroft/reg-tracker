package ninja.samryecroft.returnhome.tracker.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * T218: the expired-export sentence is R-Q13's, byte for byte.
 *
 * <p><b>Why bytes and not "contains roughly this".</b> Creed hexdumped the string rather than
 * retyping it, because retyping is how the previous one drifted - my {@code "No users yet."}
 * quietly displaced the signed-off {@code "No accounts yet."} and survived review. The two
 * characters that drift are invisible in a diff: the apostrophe in <em>child's</em> is ASCII
 * {@code 0x27} and not a typographic quote, and the dash is a real em dash {@code U+2014} with a
 * space either side, not a hyphen and not an en dash. An editor, a paste, or a well-meaning
 * "smart quotes" pass changes either without anyone seeing it.
 *
 * <p><b>What this guards that a rendering test would not.</b> A test asserting the page contains
 * the sentence would pass on a typographic apostrophe, because it would be comparing the same
 * drifted string to itself. This compares the template against the literal below, which is the
 * spec's bytes written out once.
 */
class ExpiredExportCopyGuardTest {

    private static final Path TEMPLATE = Path.of("src/main/resources/templates/export/expired.html");

    /** R-Q13, spec §7r at HEAD 6d3b067. Do not reword, and do not retype - copy it. */
    private static final String RULED_COPY =
            "This export has expired. You can generate it again from the child's record "
                    + "— each export is recorded separately.";

    @Test
    void thePageCarriesTheRuledSentenceExactly() throws IOException {
        String html = Files.readString(TEMPLATE, StandardCharsets.UTF_8);

        assertThat(html)
                .as("R-Q13's copy is signed off, and a builder's near-miss quietly winning is how "
                        + "signed-off copy stops being a source of truth. If this fails, check the "
                        + "apostrophe (ASCII 0x27, not U+2019) and the dash (U+2014 with a space "
                        + "either side) before assuming the wording changed")
                .contains(RULED_COPY);
    }

    @Test
    void theTwoCharactersThatDriftAreTheOnesTheSpecNames() {
        // Asserted on the constant itself, so this test states the requirement rather than merely
        // agreeing with whatever the template happens to hold.
        assertThat(RULED_COPY)
                .as("the apostrophe must be ASCII 0x27 - a typographic quote is the drift that "
                        + "survives review because it is invisible in a diff")
                .contains("child's")
                .doesNotContain("child’s");

        assertThat(RULED_COPY)
                .as("a real em dash, spaced - not a hyphen and not an en dash")
                .contains(" — ")
                .doesNotContain(" - ")
                .doesNotContain(" – ");
    }
}
