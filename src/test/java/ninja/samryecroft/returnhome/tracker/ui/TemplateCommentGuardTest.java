package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

/**
 * T209: a template's explanatory comments must not ship to the browser.
 *
 * <p>Thymeleaf does not strip {@code <!-- ... -->}. A plain HTML comment is markup, so it survives
 * rendering and arrives in the page source of every response that template serves. Thymeleaf's own
 * {@code <!--/* ... *&#47;-->} parser-level comment is removed server-side and never leaves the
 * building - which is what every design-rationale comment in this tree wants to be.
 *
 * <p><strong>Why this is mechanised rather than remembered.</strong> The same defect has landed
 * twice in one session, both times as a test reading its own explanation back as content: a comment
 * quoting a UI literal in prose is indistinguishable, to any assertion over rendered HTML, from the
 * literal actually rendering. The second occurrence was written by the author of the first, who had
 * already recorded it in her own notes as a pattern to watch for. A defect that survives its own
 * author knowing about it is not going to be solved by care.
 *
 * <p>The instance fix both times was to reword the comment, which closes that instance and leaves
 * the trap armed - and quietly obliges everyone afterwards not to use the product's own vocabulary
 * when explaining the product. Comments about UI copy will keep quoting UI copy, because that is
 * what makes them worth reading. Changing the comment SYNTAX removes the trap instead, and costs
 * the author nothing.
 *
 * <p><strong>What this does not claim.</strong> A shipped comment here leaks design rationale and
 * ticket numbers, not personal data - including, on the child record, a note stating which fields
 * are gated behind the reveal and which are not, which is a description of a control rather than
 * the data it protects. That is worth removing on a safeguarding product, but it is not the
 * Article-9 disclosure that T193 was about: T193's defect was {@code children/list.html} rendering
 * an encrypted date of birth and case reference RAW ({@code 844acac}), which is a different and
 * more serious thing than a comment. The proven, repeated harm here is the counting trap; the
 * source leakage is a real second reason, not the headline.
 *
 * <p><strong>What this does not cover.</strong> Only {@code src/main/resources/templates}. Comments
 * in static CSS and JS also ship, but they are authored as client assets and their content is not
 * the design conversation this guards. Nor does it read what a comment SAYS - a plain comment is
 * flagged on its syntax alone, which is the whole point: no judgement call, nothing to argue with,
 * and no way for a future comment's wording to slip past it.
 */
class TemplateCommentGuardTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /**
     * Thymeleaf comment syntaxes, both of which this guard permits:
     * <ul>
     *   <li>{@code <!--/* ... *&#47;-->} parser-level - removed server-side. The one we want.
     *   <li>{@code <!--/&#42;/ ... /&#42;&#47;-->} prototype-only - content is markup that IS meant
     *       to render, hidden only from a designer opening the file in a browser. Deliberate
     *       output, so it is not a leaked comment.
     * </ul>
     * Both begin {@code <!--/*}, which is the whole test.
     */
    private static boolean isThymeleafComment(String line, int at) {
        return line.startsWith("<!--/*", at);
    }

    @Test
    void noTemplateShipsAPlainHtmlComment() throws IOException {
        List<String> violations = new ArrayList<>();
        int filesScanned = 0;

        try (Stream<Path> tree = Files.walk(TEMPLATES)) {
            List<Path> templates = tree.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".html"))
                    .sorted()
                    .toList();
            filesScanned = templates.size();
            for (Path template : templates) {
                List<String> lines = Files.readAllLines(template, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    int at = line.indexOf("<!--");
                    if (at >= 0 && !isThymeleafComment(line, at)) {
                        violations.add(template + ":" + (i + 1) + "  " + line.trim());
                    }
                }
            }
        }

        // A floor on the walk itself: a scan that silently stopped finding templates would
        // otherwise report a clean estate, which is the failure mode this whole class exists to
        // reject in a different costume.
        assertThat(filesScanned)
                .describedAs("template walk found almost nothing - the scan, not the estate, is broken")
                .isGreaterThan(20);

        assertThat(violations)
                .describedAs("These comments ship to the browser. Use Thymeleaf's parser-level form "
                        + "<!--/* ... */--> instead, which is stripped server-side.")
                .isEmpty();
    }

    /**
     * The detector's own negative control. The estate scan above is only worth its words if the
     * detector actually separates the two syntaxes - a check that flagged everything, or nothing,
     * would look identical on a clean tree.
     */
    @Test
    void theDetectorSeparatesThymeleafCommentSyntaxFromPlainHtml() {
        assertThat(flagged("<!-- D-4b-1: was 'Not yet scheduled' here -->")).isTrue();
        assertThat(flagged("    <!-- indented, still ships -->")).isTrue();
        assertThat(flagged("<!--")).isTrue();
        assertThat(flagged("<!--[if lt IE 9]>")).isTrue();

        assertThat(flagged("<!--/* stripped server-side */-->")).isFalse();
        assertThat(flagged("    <!--/* indented parser-level */-->")).isFalse();
        assertThat(flagged("<!--/*/ prototype-only, deliberate output /*/-->")).isFalse();

        // Not a comment at all - the detector must not invent violations out of ordinary markup.
        assertThat(flagged("<p>2 <!== 3</p>")).isFalse();
        assertThat(flagged("<span th:text=\"${x}\">no comment here</span>")).isFalse();
    }

    private static boolean flagged(String line) {
        int at = line.indexOf("<!--");
        return at >= 0 && !isThymeleafComment(line, at);
    }

    /**
     * The premise, proved rather than cited: Thymeleaf really does ship one form and strip the
     * other, and the substitution this guard demands really does remove the counting harm.
     *
     * <p>Renders both syntaxes through a real engine, each quoting the same UI literal in prose,
     * beside one genuine rendering of that literal. If the advice in the failure message above were
     * wrong - or if a future Thymeleaf changed it - the whole guard would be busywork, and this is
     * what would say so. It also pins the harm in the form the two real incidents took: the literal
     * must appear ONCE in the output, not twice, so an assertion counting it reads the page rather
     * than the page's explanation of itself.
     */
    @Test
    void thymeleafShipsAPlainCommentAndStripsTheParserLevelOne() {
        String plain = render("<!-- D-4b-1: was 'Not yet scheduled' in the card stack below -->");
        String parserLevel = render("<!--/* D-4b-1: was 'Not yet scheduled' in the card stack below */-->");

        assertThat(plain)
                .describedAs("a plain HTML comment is markup - Thymeleaf passes it straight through")
                .contains("D-4b-1");
        assertThat(parserLevel)
                .describedAs("the parser-level form must be removed server-side - if this fails, the "
                        + "remedy this guard insists on does not work and the guard is pointless")
                .doesNotContain("D-4b-1");

        // The harm and its removal, in the exact shape both real incidents took: a test counting a
        // string it believed was content. Same page, same comment, same words - one syntax apart.
        assertThat(occurrences(plain, LITERAL))
                .describedAs("the comment's prose is counted alongside the real rendering")
                .isEqualTo(2);
        assertThat(occurrences(parserLevel, LITERAL))
                .describedAs("only the rendering survives, so an assertion counts the page rather "
                        + "than the page's explanation of itself")
                .isEqualTo(1);
    }

    private static final String LITERAL = "Not yet scheduled";

    /** The same fragment either way: one comment, and one genuine rendering of the literal it quotes. */
    private static String render(String comment) {
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine.process("<div>\n" + comment + "\n<span>" + LITERAL + "</span>\n</div>", new Context());
    }

    private static int occurrences(String haystack, String needle) {
        return haystack.split(needle, -1).length - 1;
    }
}
