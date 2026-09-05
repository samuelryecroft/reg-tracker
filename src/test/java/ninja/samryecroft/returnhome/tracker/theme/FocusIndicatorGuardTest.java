package ninja.samryecroft.returnhome.tracker.theme;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * T188 / WCAG 2.2 AA 2.4.11: a focusable control must never have its focus outline suppressed by an
 * element-qualified {@code :focus} rule.
 *
 * <p><b>Why a guard and not just the deletion.</b> {@code outline: none} is the single most natural
 * thing to write when a designer dislikes the default ring, it reads as tidy, and its consequence -
 * everyone losing the only signal that says where they are - is invisible in a static render,
 * because nothing in one is focused. No test fails and no screenshot moves.
 *
 * <p><b>What the deletion actually bought, which is not what T188 originally claimed.</b> The ring
 * was rendering: the element-qualified {@code input:focus-visible} rule is the same specificity as
 * {@code input:focus} and later in source, so it won. Creed withdrew the "every input fails 2.4.11"
 * claim once we had both walked the cascade. The real defect is that the correctness <em>depended on
 * source order</em>: that {@code :focus-visible} block exists twice, and deleting the later copy -
 * the obvious one, since the earlier reads as the original - would have left the survivor BEFORE the
 * suppression, where equal specificity means earlier loses. <b>Code that is correct only because of
 * the order two equal-specificity rules happen to appear in is invisible to the person tidying it,
 * and "remove the duplicate" is the most likely edit there is.</b>
 *
 * <p><b>What this deliberately permits.</b> The bare {@code :focus \{ outline: none \}} at the top of
 * the stylesheet stays legal, and is the correct pattern: it suppresses the ring for pointer focus
 * and lets {@code :focus-visible} put it back for keyboard focus. What must not happen is an
 * element-qualified rule - {@code input:focus}, {@code .card:focus} - doing the same thing, because
 * that outranks the bare {@code :focus-visible} and takes the ring away from keyboard users too.
 */
class FocusIndicatorGuardTest {

    private static final Path STYLESHEET = Path.of("src/main/resources/static/css/app.css");

    /**
     * An {@code outline: none} on a selector that names something in addition to {@code :focus} -
     * an element, a class, an id. The bare {@code :focus} is the permitted case.
     */
    private static final Pattern QUALIFIED_FOCUS_SUPPRESSION = Pattern.compile(
            "([^{}\n]*[\\w\\]\\)]\\s*:focus\\b[^{}]*)\\{([^{}]*)\\}");

    @Test
    void noKeyboardReachableControlHasItsFocusOutlineSuppressed() throws IOException {
        assertThat(STYLESHEET)
                .as("the stylesheet must exist for this guard to mean anything")
                .exists();
        String css = withoutComments(Files.readString(STYLESHEET, StandardCharsets.UTF_8));

        List<String> offences = new ArrayList<>();
        int rulesSeen = 0;
        Matcher m = QUALIFIED_FOCUS_SUPPRESSION.matcher(css);
        while (m.find()) {
            String selector = m.group(1).trim();
            rulesSeen++;
            if (m.group(2).replace(" ", "").contains("outline:none")
                    && isKeyboardReachable(selector)) {
                offences.add(selector);
            }
        }

        assertThat(rulesSeen)
                .as("the guard must find the :focus rules it is checking - finding none means the "
                        + "pattern has stopped matching and this test is guarding nothing")
                .isGreaterThan(0);

        assertThat(offences)
                .as("this rule removes the focus outline from a specific element. Whether the ring "
                        + "survives then depends on an element-qualified :focus-visible rule existing "
                        + "LATER in the file at equal specificity - correctness resting on source "
                        + "order, which the next person to tidy a duplicate will not see. If it does "
                        + "not survive, the remaining signal is the border colour alone, measuring "
                        + "1.47-1.79:1 focused-vs-unfocused at every brand hue in both appearances "
                        + "against WCAG 2.2 AA 2.4.11's 3:1. The bare ':focus { outline: none }' is "
                        + "the correct place for this; an element-qualified one is not")
                .isEmpty();
    }

    /**
     * Whether a selector names something a keyboard user can actually reach by navigating.
     *
     * <p><b>This is the guard testing what it means rather than what is easy.</b> The first version
     * flagged every element-qualified {@code :focus} suppression and immediately caught
     * {@code .card[id]:focus} - which is correct code: those cards carry {@code tabindex="-1"} and
     * are <em>jump targets</em>, focused programmatically when someone activates a section link so
     * the reading cursor follows the scroll. WCAG 2.4.11 governs components that <em>receive
     * keyboard focus</em>; a {@code tabindex="-1"} element cannot be tabbed to, and outlining a
     * whole card because someone followed an in-page link would be noise rather than a signal.
     *
     * <p>So the answer is derived from the templates instead of from an exclusion list. An exclusion
     * would have recorded that {@code .card[id]} is allowed; this records <em>why</em>, and it stays
     * true if a future rule suppresses the outline on some other jump target - and stops being true
     * the day one of those cards becomes tab-reachable, which is exactly when the guard should fire.
     */
    private static boolean isKeyboardReachable(String selector) throws IOException {
        Matcher cls = Pattern.compile("\\.([\\w-]+)").matcher(selector);
        if (!cls.find()) {
            return true;
        }
        String className = cls.group(1);
        boolean sawOne = false;
        try (var files = Files.walk(Path.of("src/main/resources/templates"))) {
            for (Path template : files.filter(Files::isRegularFile).toList()) {
                // HTML comments stripped first. A comment in report-fields.html illustrates the
                // markup shape with a literal <div class="card" id="..."> and no tabindex, and the
                // first version of this read it as evidence of a tab-reachable card - a scanner
                // taking its own documentation as data, which is the same defect as the CSS
                // comment-stripping above and the T184 guard matching its own log prose.
                String html = Files.readString(template, StandardCharsets.UTF_8)
                        .replaceAll("(?s)<!--.*?-->", "");
                Matcher tag = Pattern.compile("<[^>]*class=\"[^\"]*\\b"
                        + Pattern.quote(className) + "\\b[^\"]*\"[^>]*>").matcher(html);
                while (tag.find()) {
                    String element = tag.group();
                    if (selector.contains("[id]") && !element.contains("id=")) {
                        continue;
                    }
                    sawOne = true;
                    if (!element.contains("tabindex=\"-1\"")) {
                        return true;
                    }
                }
            }
        }
        // Nothing in the templates matched: the selector may be dead, or built by script. Treat it
        // as reachable, because a guard that goes quiet on what it cannot see is the failure mode
        // this codebase has spent a week removing.
        return !sawOne;
    }

    /** The same literal-aware stripping the other source scanners use, for the same reason. */
    private static String withoutComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }
}
