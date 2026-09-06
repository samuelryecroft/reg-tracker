package ninja.samryecroft.returnhome.tracker.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * T223 / spec §7w: the two export success pages, and the trap worth more than the fix.
 *
 * <p><b>The defect this replaces.</b> Both pages named ONE of the link's two independent limits -
 * {@code audit/export-ready.html} said "after expiry", {@code export/case-file-ready.html} said
 * "after that" (the expiry badge above it). A link that is single-use AND time-limited, described
 * by only its timing, tells the reader the other limit does not exist: "after expiry" read as
 * PERMISSION to re-download before it, not as a narrower fact. These success pages are the upstream
 * cause of every arrival at {@code export/expired.html} (T218) - the false expectation is set here,
 * two screens earlier, in the moment nobody reads carefully.
 *
 * <p><b>D-5e-3g, the trap: the countdown badge and this sentence are a PAIR, not two independent
 * facts.</b> "Link expires in N minutes" is true and forward-looking on its own, which is exactly
 * why it looks safe to a reviewer - but a countdown alone is a strong affordance for "valid until
 * then". It is only safe because THIS sentence, right beneath it, carries the other limit
 * (single-use). Delete the sentence in some future tidy-up - shorten the card, dedupe with the
 * badge, whatever the reasonable-sounding reason - and the badge becomes the exact defect being
 * fixed here, with nothing in the diff that looks like a defect. So this test pins both halves of
 * the pair on both pages, not just the sentence: a change that removes either one fails here.
 *
 * <p><b>Byte-exact, not "contains roughly this".</b> Same drift risk as T218's expired-page guard
 * (Jim, {@code ExpiredExportCopyGuardTest}): the two pages' sentences differ in exactly one clause
 * ("audit trail" vs "this child's case history"), which is precisely the kind of near-miss that
 * survives review because it reads as correct. Copied from spec §7w (HEAD {@code cd2bbfb}), not
 * retyped.
 */
class ExpiredSuccessCopyGuardTest {

    private static final Path AUDIT_EXPORT_READY =
            Path.of("src/main/resources/templates/audit/export-ready.html");
    private static final Path CASE_FILE_READY =
            Path.of("src/main/resources/templates/export/case-file-ready.html");

    /** Spec §7w, ruled by Oscar. Shared clause across both pages: "works once, and only for you". */
    private static final String SHARED_OPENING =
            "This link works once, and only for you. If you need the file again, create a new "
                    + "export; if a colleague needs it, they should create their own.";

    private static final String AUDIT_RULED_COPY =
            SHARED_OPENING + " Each one writes its own row in the audit trail, which is the point.";

    private static final String CASE_FILE_RULED_COPY =
            SHARED_OPENING + " Each one is recorded on this child's case history, which is the point.";

    /**
     * The literal segment of the countdown badge's {@code th:text} expression - present in the raw
     * template source even though the rendered number is computed. Checking for it (rather than
     * for the whole {@code <span class="expires">} element, which carries no text of its own)
     * proves the badge itself, not just some unrelated "expires" class name, is what is paired.
     */
    private static final String BADGE_LITERAL = "'Link expires in '";

    @Test
    void auditExportReadyCarriesTheRuledSentenceExactly() throws IOException {
        assertRuledCopyPresent(AUDIT_EXPORT_READY, AUDIT_RULED_COPY);
    }

    @Test
    void caseFileReadyCarriesTheRuledSentenceExactly() throws IOException {
        assertRuledCopyPresent(CASE_FILE_READY, CASE_FILE_RULED_COPY);
    }

    @Test
    void neitherPageNamesOnlyOneLimitAnyMore() throws IOException {
        String audit = read(AUDIT_EXPORT_READY);
        String caseFile = read(CASE_FILE_READY);

        assertThat(audit)
                .as("'after expiry' granted a permission that doesn't exist - it told the reader "
                        + "re-downloading BEFORE expiry was fine, when the link is single-use")
                .doesNotContain("after expiry");
        assertThat(caseFile)
                .as("'after that' named expiry as the trigger when a second click is the common one")
                .doesNotContain("After that you can generate it again");
    }

    @Test
    void theBadgeAndTheSentenceTravelTogetherOnBothPages() throws IOException {
        // D-5e-3g: the badge alone is a strong affordance for "valid until then". It is only true
        // in combination with the sentence beneath it - so if a future edit keeps one and drops the
        // other, on EITHER page, that edit has reintroduced the defect this ticket fixed, even
        // though nothing in that diff looks like the string that was wrong.
        assertPairIntact(AUDIT_EXPORT_READY, AUDIT_RULED_COPY);
        assertPairIntact(CASE_FILE_READY, CASE_FILE_RULED_COPY);
    }

    private void assertPairIntact(Path template, String ruledCopy) throws IOException {
        String html = read(template);
        boolean hasBadge = html.contains(BADGE_LITERAL);
        boolean hasSentence = html.contains(ruledCopy);

        assertThat(hasBadge)
                .as(template + ": the countdown badge is expected on this page - if it was "
                        + "deliberately removed, this guard's premise (badge + sentence, together) "
                        + "no longer applies and should be reconsidered, not silently left failing")
                .isTrue();
        assertThat(hasSentence)
                .as(template + ": the badge is present but the sentence that makes it safe to show "
                        + "on its own is not - this is exactly D-5e-3g, reintroduced")
                .isTrue();
    }

    private void assertRuledCopyPresent(Path template, String ruledCopy) throws IOException {
        assertThat(read(template))
                .as("spec §7w's copy is signed off, and a near-miss paraphrase quietly winning is "
                        + "how signed-off copy stops being a source of truth")
                .contains(ruledCopy);
    }

    private String read(Path template) throws IOException {
        return Files.readString(template, StandardCharsets.UTF_8);
    }
}
