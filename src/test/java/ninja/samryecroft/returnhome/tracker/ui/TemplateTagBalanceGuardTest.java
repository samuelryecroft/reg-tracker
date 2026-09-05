package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T216 (found by Creed reading 4c): a template's tags must balance.
 *
 * <p>{@code login.html} closed its card {@code <div>} twice. Browsers swallow a stray close
 * silently, the page rendered correctly, and every test passed - so nothing anywhere reported it.
 * That is the same class this codebase has spent the week closing: <b>something wrong where every
 * indicator reads healthy.</b>
 *
 * <p>An extra {@code </div>} is cosmetic on a static page. It stops being cosmetic on a template
 * with conditional blocks, where the same mistake moves content out of a container - or out of a
 * {@code th:if} - and the page still renders and the tests still pass. The defect's SHAPE is
 * "markup that lies about its own structure"; today's instance is the harmless end of it.
 *
 * <h2>Whether this could be expressed cleanly enough to be worth building</h2>
 *
 * <p>That was a real question, not a formality: balanced-tag checking over Thymeleaf is complicated
 * by void elements, self-closing tags, {@code th:block}, and conditional attributes, and <b>a guard
 * that cries wolf is worse than no guard.</b> So it was measured before it was written. Against the
 * 33 templates in the tree, this rule reports <b>exactly one file - the real defect - and no others.</b>
 * The complications turned out not to bite: {@code th:block} is an ordinary paired element (85 of
 * them here), conditional attributes are just attributes, and void elements are a closed list.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It is not an HTML validator and must not become one. It checks one property - every element
 * that opens is closed, in order - because that property is mechanical and has no judgement in it.
 * Script and style bodies are skipped entirely: no template needs a {@code <} operator inside one
 * today, but a future one might, and a guard should not fail on valid JavaScript. Comments are
 * stripped for the same reason - markup inside a comment is prose, not structure.
 */
class TemplateTagBalanceGuardTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /** HTML void elements: they never close, so they never enter the stack. A closed list, per spec. */
    private static final Set<String> VOID_ELEMENTS = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input",
            "link", "meta", "param", "source", "track", "wbr");

    private static final Pattern TAG = Pattern.compile(
            "<(/?)([a-zA-Z][a-zA-Z0-9:-]*)((?:\"[^\"]*\"|'[^']*'|[^>\"'])*?)(/?)>", Pattern.DOTALL);
    private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern DOCTYPE = Pattern.compile("<!DOCTYPE[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern RAW_TEXT_ELEMENT =
            Pattern.compile("<(script|style)\\b[^>]*>.*?</\\1\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    @Test
    void everyTemplateClosesTheElementsItOpens() throws IOException {
        List<String> violations = new ArrayList<>();
        int scanned = 0;

        try (Stream<Path> tree = Files.walk(TEMPLATES)) {
            List<Path> templates = tree.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".html"))
                    .sorted()
                    .toList();
            scanned = templates.size();
            for (Path template : templates) {
                for (String problem : unbalancedTagsIn(Files.readString(template, StandardCharsets.UTF_8))) {
                    violations.add(template + ":" + problem);
                }
            }
        }

        // The scan's own floor: a walk that quietly stopped finding templates would report a clean
        // estate, which is this guard's failure mode wearing the costume of a pass.
        assertThat(scanned)
                .describedAs("template walk found almost nothing - the scan is broken, not the estate")
                .isGreaterThan(20);

        assertThat(violations)
                .describedAs("A browser silently swallows unbalanced markup, so the page renders and "
                        + "the tests pass. On a template with conditional blocks the same mistake "
                        + "moves content out of its container, or out of a th:if, just as silently.")
                .isEmpty();
    }

    /**
     * The detector's own negative control, and the half that decides whether this guard is usable.
     * A balanced-tag check that did not understand void elements, self-closing tags or
     * {@code th:block} would produce false reds across a Thymeleaf tree - and the guard would then
     * be deleted by whoever next hit one, taking the real check with it. Each of those is pinned
     * here as something that must NOT be reported.
     */
    @Test
    void theBalanceCheckUnderstandsThymeleafAndHtmlWithoutCryingWolf() {
        // The real defect's shape.
        assertThat(unbalancedTagsIn("<div class=\"card\"><p>x</p></div></div>"))
                .hasSize(1).first().asString().contains("</div> with nothing open");
        assertThat(unbalancedTagsIn("<div><span>x</div>"))
                .hasSize(1).first().asString().contains("<span> was never closed");

        // Everything below is legal and must stay silent.
        assertThat(unbalancedTagsIn("<div><input type=\"text\"/><br><img src=\"a\"><hr></div>")).isEmpty();
        assertThat(unbalancedTagsIn("<th:block th:replace=\"~{f :: g}\"></th:block>")).isEmpty();
        assertThat(unbalancedTagsIn("<th:block th:each=\"r : ${rows}\"><td th:text=\"${r}\">x</td></th:block>")).isEmpty();
        assertThat(unbalancedTagsIn("<div th:if=\"${a > b}\">x</div>")).isEmpty();
        assertThat(unbalancedTagsIn("<div/>")).isEmpty();
        assertThat(unbalancedTagsIn("<!DOCTYPE html><html><body><p>x</p></body></html>")).isEmpty();

        // Structure inside a comment is prose, and a stray tag there must not be read as markup.
        assertThat(unbalancedTagsIn("<div><!--/* was </div> here */--></div>")).isEmpty();

        // A script body is not markup: a comparison operator must not be parsed as a tag.
        assertThat(unbalancedTagsIn("<div><script>if (a < b) { x(); }</script></div>")).isEmpty();
        assertThat(unbalancedTagsIn("<div><style>a{}</style></div>")).isEmpty();

        // A comment MENTIONING a raw-text element must not open one. This is the real
        // fragments/layout.html shape, and it is why comments are stripped before script/style:
        // matched the other way round, this swallows everything up to the real </style>.
        assertThat(unbalancedTagsIn(
                "<head><!--/* a <style> block used to sit here */-->"
                        + "<style>a{}</style></head>")).isEmpty();
    }

    /** Every element that opens must close, in order. Returns one line per problem, or empty. */
    private static List<String> unbalancedTagsIn(String template) {
        // Comments FIRST, and the order is load-bearing. fragments/layout.html carries a comment
        // whose prose contains the literal "<style>"; stripping raw-text elements first matched
        // from that mention through to a real </style> twenty lines later and swallowed <head>
        // along with it. A comment's prose being read as markup is precisely the defect
        // TemplateCommentGuardTest exists to prevent - it reached this guard from the other side.
        String markup = COMMENT.matcher(template).replaceAll("");
        markup = RAW_TEXT_ELEMENT.matcher(markup).replaceAll("");
        markup = DOCTYPE.matcher(markup).replaceAll("");

        Deque<int[]> openLines = new ArrayDeque<>();
        Deque<String> openNames = new ArrayDeque<>();
        List<String> problems = new ArrayList<>();

        Matcher tag = TAG.matcher(markup);
        while (tag.find()) {
            String name = tag.group(2).toLowerCase(java.util.Locale.ROOT);
            boolean closing = !tag.group(1).isEmpty();
            boolean selfClosing = !tag.group(4).isEmpty();
            if (VOID_ELEMENTS.contains(name) || selfClosing) {
                continue;
            }
            int line = (int) markup.substring(0, tag.start()).chars().filter(c -> c == '\n').count() + 1;
            if (!closing) {
                openNames.push(name);
                openLines.push(new int[] {line});
            } else if (!openNames.isEmpty() && openNames.peek().equals(name)) {
                openNames.pop();
                openLines.pop();
            } else if (openNames.contains(name)) {
                while (!openNames.peek().equals(name)) {
                    problems.add(openLines.pop()[0] + "  <" + openNames.pop() + "> was never closed");
                }
                openNames.pop();
                openLines.pop();
            } else {
                problems.add(line + "  </" + name + "> with nothing open");
            }
        }
        while (!openNames.isEmpty()) {
            problems.add(openLines.pop()[0] + "  <" + openNames.pop() + "> was never closed");
        }
        return problems;
    }
}
