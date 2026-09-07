package ninja.samryecroft.returnhome.tracker.interview;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * T146 ruled that {@link InterviewStatus#CANCELLED} STAYS. This guards the reasons, not the ruling.
 *
 * <p>The decision rests on measured facts about the codebase, and <b>a decision is only as durable
 * as the facts under it.</b> Most of them defend themselves: deleting the constant breaks
 * compilation, because {@link StatusRail} and {@code ChildLifecycleService.FINISHED} read it; adding
 * an in-edge reddens {@code InterviewStatusTransitionsTest.noTransitionReachesCancelled}.
 *
 * <p><b>One of them defends itself with nothing but a comment, which is why this class exists.</b>
 * The styling is a real reason the constant survives - Creed's D-1a-2a gave the state its own glyph
 * and colour pair, deliberately distinct from {@code REPORT_REJECTED} - and no production path
 * reaches it, so a dead-code sweep looking at coverage or at grep counts would find it and be
 * wrong. Removing it would not fail anything: the server starts, every template renders, nothing
 * throws. It would simply take away the argument for keeping the constant, silently, and the next
 * person to ask "is CANCELLED dead?" would find one fewer reason to say no.
 *
 * <p>Written as a source check for the same reason {@code FrontendSourceGuardTest} is: this class of
 * loss is invisible to every other test in the suite, and the only way to catch it is to look at the
 * file. <b>And it is a test rather than another comment on purpose</b> - a comment saying "do not
 * delete this" stays on the page, unchanged and unread, when somebody deletes it.
 */
class CancelledIsKeptAndTheReasonsAreStillTrueTest {

    private static final Path APP_CSS = Path.of("src/main/resources/static/css/app.css");

    /**
     * The four rail rules and the status chip. Named individually rather than counted, so a failure
     * says which one went.
     */
    @Test
    void theCancelledStylingIsStillThere() throws IOException {
        String css = Files.readString(APP_CSS, StandardCharsets.UTF_8);

        assertThat(css)
                .as("T146 kept CANCELLED partly BECAUSE it is rendered - see InterviewStatus.CANCELLED. "
                        + "If this styling is genuinely no longer wanted, that decision has to be "
                        + "revisited rather than eroded")
                .contains(".status.CANCELLED")
                .contains(".rail li.rail-CANCELLED .rail-marker")
                .contains(".rail li.rail-CANCELLED .rail-connector")
                .contains(".rail li.rail-CANCELLED .rail-label")
                // The states a CANCELLED request puts the LATER positions into. They exist only
                // because cancellation exists, so they go with it.
                .contains("rail-NOT_APPLICABLE");
    }

    /**
     * And the state is still DISTINCT from {@code REPORT_REJECTED}, which is the actual design
     * decision rather than the mere presence of a rule.
     *
     * <p>Creed's D-1a-2a table separates them on purpose: both are "off the happy path", and they
     * mean opposite things to the person reading the screen - one is work coming back, the other is
     * work that will not happen. Collapsing the two selectors into one would keep this file's
     * grep-count intact while losing the thing the grep was standing in for.
     */
    @Test
    void cancelledIsStyledDistinctlyFromSentBack() throws IOException {
        String css = Files.readString(APP_CSS, StandardCharsets.UTF_8);

        // SENT_BACK, not REJECTED - the rail keys on the STEP state, and this codebase renamed the
        // reader-facing vocabulary in D-1a-2 because "Rejected" reads as a verdict where the reality
        // is a request for more detail. I wrote REJECTED here from memory and this assertion caught
        // it, which is the argument for looking rather than remembering in its smallest possible
        // form.
        assertThat(css).contains(".rail li.rail-SENT_BACK .rail-marker");
        assertThat(styleOf(css, ".rail li.rail-CANCELLED .rail-marker"))
                .as("two states that mean opposite things to a reader must not resolve to one look")
                .isNotEqualTo(styleOf(css, ".rail li.rail-SENT_BACK .rail-marker"));
    }

    /** The declaration block for one selector, so the two can be compared rather than merely found. */
    private static String styleOf(String css, String selector) {
        int at = css.indexOf(selector);
        assertThat(at).as("selector not found: " + selector).isGreaterThan(-1);
        return css.substring(at, css.indexOf('}', at) + 1);
    }
}
