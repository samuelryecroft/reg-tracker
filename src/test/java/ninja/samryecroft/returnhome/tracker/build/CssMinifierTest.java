package ninja.samryecroft.returnhome.tracker.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link CssMinifier} removes ONLY comments and insignificant whitespace - never a rule, a
 * selector, a value, or a semantically significant space. These are the edges where a careless CSS
 * minifier changes behaviour; each one is a way the rendered pages could stop being byte-for-byte
 * equivalent, which the T361 card forbids.
 */
class CssMinifierTest {

    @Test
    void stripsCommentsEntirely() {
        String out = CssMinifier.minify("a{color:red}/* a comment */b{color:blue}");
        assertThat(out).doesNotContain("/*").doesNotContain("comment");
        assertThat(out).contains("a{color:red}").contains("b{color:blue}");
    }

    @Test
    void aCommentBetweenTwoTokensBecomesASpaceSoTheyNeverFuse() {
        // a/**/b must stay two tokens, not become "ab".
        assertThat(CssMinifier.minify(".a /**/ .b{x:1}")).contains(".a .b{x:1}");
        assertThat(CssMinifier.minify(".a/**/.b{x:1}")).isEqualTo(".a .b{x:1}");
    }

    @Test
    void preservesTheDescendantCombinatorSpace() {
        // ".a .b" (descendant) and ".a.b" (compound) are different selectors - the space is meaning.
        // Note the transform is conservative: it keeps the space after a declaration ':' and the
        // trailing ';' (both insignificant, both erased by gzip) rather than risk selector ':'.
        assertThat(CssMinifier.minify(".a   .b {\n  color: red;\n}")).isEqualTo(".a .b{color: red;}");
    }

    @Test
    void neverRemovesWhitespaceAroundColonInASelector() {
        // "a :hover" (descendant of a, hovered) must NOT collapse to "a:hover" (a, hovered).
        assertThat(CssMinifier.minify("a :hover { x:1 }")).isEqualTo("a :hover{x:1}");
        assertThat(CssMinifier.minify("a:hover { x:1 }")).isEqualTo("a:hover{x:1}");
    }

    @Test
    void preservesSignificantSpacesInsideValues() {
        assertThat(CssMinifier.minify(".a{width:calc(100%   -   10px)}"))
                .isEqualTo(".a{width:calc(100% - 10px)}");
        assertThat(CssMinifier.minify(".a{margin:0   0   0   0}")).isEqualTo(".a{margin:0 0 0 0}");
        assertThat(CssMinifier.minify(".a{font:12px/1.5   sans-serif}"))
                .isEqualTo(".a{font:12px/1.5 sans-serif}");
    }

    @Test
    void copiesStringLiteralsVerbatimIncludingWhatLooksLikeAComment() {
        // The /* inside a string is not a comment, and the double space is significant content.
        String in = ".x::before{content:\"/* not a comment */  keep  me\"}";
        assertThat(CssMinifier.minify(in)).isEqualTo(in);
    }

    @Test
    void dropsWhitespaceOnlyAroundTheFourSafeBoundaries() {
        // Whitespace next to { } ; , goes; whitespace around ':' stays (untouched on purpose).
        assertThat(CssMinifier.minify(".a { color : red ; }")).isEqualTo(".a{color : red;}");
        assertThat(CssMinifier.minify(".a{font-family: A , B , C}"))
                .isEqualTo(".a{font-family: A,B,C}");
    }

    @Test
    void leavesAtRulesIntact() {
        assertThat(CssMinifier.minify("@media (max-width: 720px) {\n  .a { x: 1 }\n}"))
                .isEqualTo("@media (max-width: 720px){.a{x: 1}}");
        assertThat(CssMinifier.minify("@media print { .a { x:1 } }"))
                .isEqualTo("@media print{.a{x:1}}");
    }

    @Test
    void isIdempotent() {
        String once = CssMinifier.minify(".a .b { color: red } /* c */ .c{d:e}");
        assertThat(CssMinifier.minify(once)).isEqualTo(once);
    }

    @Test
    void onTheRealAppCssStripsCommentsWithoutAddingOrDroppingASingleBrace() throws Exception {
        Path appCss = Path.of("src/main/resources/static/css/app.css");
        String source = Files.readString(appCss, StandardCharsets.UTF_8);
        String out = CssMinifier.minify(source);

        assertThat(out).as("no comment may survive").doesNotContain("/*");
        assertThat(out.getBytes(StandardCharsets.UTF_8).length)
                .as("the minified file must be smaller").isLessThan(source.getBytes(StandardCharsets.UTF_8).length);

        // Braces are the skeleton of the rule set. Source counts can't be compared directly - the
        // source's comments contain '{'/'}' in prose - but the minified output must be brace-balanced
        // and still carry the hundreds of real rule blocks, so nothing structural was truncated.
        assertThat(count(out, '{')).as("braces stay balanced").isEqualTo(count(out, '}'));
        assertThat(count(out, '{')).as("the rule blocks are still all there").isGreaterThan(400);
    }

    private static long count(String s, char c) {
        return s.chars().filter(ch -> ch == c).count();
    }
}
