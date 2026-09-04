package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * T93 hotfix guard: this whole class of bug (a bare git conflict divider left in a committed CSS
 * file, and the responsive .table-wrap/.stack pair coming apart) is invisible to every other test
 * in the suite - the server starts fine, every Thymeleaf template renders fine, nothing throws.
 * The only way to catch it is to look at the source files themselves.
 *
 * <p>This is the second time (per Creed's review) the table-stack pair has broken, so the check
 * is a plain count-per-file match rather than a spot check on one or two templates.
 */
class FrontendSourceGuardTest {

    private static final Path CSS_DIR = Path.of("src/main/resources/static/css");
    private static final Path TEMPLATES_DIR = Path.of("src/main/resources/templates");

    /** A real conflict divider is exactly seven characters on its own line - decorative comment
     * banners such as {@code /* ======... header ======... *}{@code /} use far more than seven
     * and always carry surrounding text, so they never match. */
    private static final Pattern CONFLICT_START = Pattern.compile("^\\s*<{7}(\\s|$)");
    private static final Pattern CONFLICT_MID = Pattern.compile("^\\s*={7}\\s*$");
    private static final Pattern CONFLICT_END = Pattern.compile("^\\s*>{7}(\\s|$)");

    @Test
    void noCommittedGitConflictMarkersInCssOrTemplates() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path dir : List.of(CSS_DIR, TEMPLATES_DIR)) {
            for (Path file : sourceFilesUnder(dir)) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (CONFLICT_START.matcher(line).find() || CONFLICT_MID.matcher(line).find()
                            || CONFLICT_END.matcher(line).find()) {
                        violations.add(file + ":" + (i + 1) + ": " + line.trim());
                    }
                }
            }
        }

        assertThat(violations)
                .as("committed git conflict marker(s) found - a bad merge resolution silently "
                        + "disables every CSS rule (or template line) after it")
                .isEmpty();
    }

    /**
     * Every screen below 720px hides {@code .table-wrap.responsive} and shows {@code .stack}
     * instead (see app.css's 720px breakpoint) - a table with no {@code .stack} sibling simply
     * vanishes on a phone, it does not fall back to a scrollable table. So the two must always
     * appear in the same template in equal numbers: one {@code .stack} for every
     * {@code .table-wrap.responsive}.
     */
    @Test
    void everyResponsiveTableHasAStackFallback() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : sourceFilesUnder(TEMPLATES_DIR)) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            long tableWraps = countOccurrences(content, "table-wrap responsive");
            long stacks = countOccurrences(content, "class=\"stack\"");
            if (tableWraps != stacks) {
                violations.add(file + ": " + tableWraps + " table-wrap.responsive vs " + stacks + " .stack");
            }
        }

        assertThat(violations)
                .as("a .table-wrap.responsive without a matching .stack fallback disappears "
                        + "entirely below the 720px breakpoint - it does not become scrollable")
                .isEmpty();
    }

    /**
     * T119: an explicit {@code data-appearance="light"} choice and "auto" on a light OS must
     * resolve identically (Creed's own words: "if you change one, change the other") - the two
     * blocks in app.css are hand-duplicated (the second is nested inside a
     * {@code @media (prefers-color-scheme: light)} block, which can't share a selector list with
     * a non-media rule), which is exactly the kind of duplication that drifts silently: a token
     * added to one and not the other looks correct in whichever appearance someone happens to
     * test. This diffs the two blocks' declarations after stripping indentation, so the check
     * survives reformatting but not an actual value or token-name mismatch.
     */
    @Test
    void lightAndAutoAppearanceBlocksStayDeclarationIdentical() throws IOException {
        String css = Files.readString(CSS_DIR.resolve("app.css"), StandardCharsets.UTF_8);

        List<String> light = declarationsOf(css, "\\[data-appearance=\"light\"\\]\\s*\\{");
        List<String> auto = declarationsOf(css, "\\[data-appearance=\"auto\"\\]\\s*\\{");

        assertThat(light).as("light appearance block").isNotEmpty();
        assertThat(auto)
                .as("[data-appearance=\"light\"] and [data-appearance=\"auto\"] must declare the "
                        + "exact same custom properties in the exact same order - an explicit "
                        + "choice and auto-on-a-light-OS have to resolve to the same thing")
                .containsExactlyElementsOf(light);
    }

    /**
     * T119 F1 (Creed's fidelity review): a {@code var(--x)} referencing a custom property that is
     * declared NOWHERE in the file is invalid at computed-value time - not "keeps the old value",
     * it resolves to {@code unset}/{@code initial}. That is exactly how the legacy bridge bug
     * happened: the wholesale {@code :root} replacement deleted ~20 tokens that 33 not-yet-migrated
     * screens' rules still referenced, and every one of those rules silently lost its border,
     * padding, or background with nothing to throw and nothing else in the suite able to notice -
     * only a rendered-in-Chrome check (or this) catches a deleted custom property. Comments are
     * stripped first so a {@code var(--x)} mentioned only in prose (as several are, in the banner
     * above {@code :root}) is never mistaken for a real reference.
     */
    @Test
    void everyCustomPropertyReferenceResolvesToADeclaration() throws IOException {
        String rawCss = Files.readString(CSS_DIR.resolve("app.css"), StandardCharsets.UTF_8);
        String css = rawCss.replaceAll("(?s)/\\*.*?\\*/", "");

        Set<String> declared = new HashSet<>();
        Matcher declaration = Pattern.compile("(--[a-zA-Z0-9-]+)\\s*:").matcher(css);
        while (declaration.find()) {
            declared.add(declaration.group(1));
        }

        Set<String> referenced = new TreeSet<>();
        Matcher reference = Pattern.compile("var\\((--[a-zA-Z0-9-]+)[,)]").matcher(css);
        while (reference.find()) {
            referenced.add(reference.group(1));
        }

        List<String> undeclared = referenced.stream().filter(name -> !declared.contains(name)).toList();

        assertThat(undeclared)
                .as("var(--x) referencing a custom property declared nowhere in app.css - it resolves "
                        + "to unset/initial wherever it's used, not to whatever the property used to be")
                .isEmpty();
    }

    /** Every actual {@code --token: value;} declaration between the matched selector's {@code {}
     * and its closing {@code }} - comment-only lines and trailing {@code /* ratio *}{@code /}
     * annotations are stripped first, since the light block carries explanatory prose the
     * hand-duplicated auto block deliberately doesn't repeat (matching the design reference
     * sheet's own convention); it's the tokens and values that must match, not the commentary.
     * Deliberately naive about nested braces, which is fine since neither block nests any. */
    private static List<String> declarationsOf(String css, String selectorPattern) {
        Pattern selector = Pattern.compile(selectorPattern);
        var matcher = selector.matcher(css);
        if (!matcher.find()) {
            return List.of();
        }
        int start = matcher.end();
        int end = css.indexOf('}', start);
        String body = css.substring(start, end);
        // Strip /* ... */ comments (including ones spanning multiple lines) before splitting,
        // so a standalone comment paragraph disappears entirely rather than leaving blank lines.
        String withoutComments = body.replaceAll("(?s)/\\*.*?\\*/", "");
        List<String> declarations = new ArrayList<>();
        for (String line : withoutComments.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                declarations.add(trimmed);
            }
        }
        return declarations;
    }

    /**
     * Creed's review (spec 12d10e8, following T132/T150): regular-versus-fill is Nocturne's own
     * idiom for an active state (it's how the reveal toggle distinguishes masked from revealed), so
     * a vendored icon missing one weight isn't just an inconsistency - it's the one glyph that can't
     * express {@code aria-current} with a filled variant if a future nav item wants to, and it'll
     * read as a puzzling one-off rather than an obvious gap. {@code ph-user-list} shipped without
     * {@code ph-fill-user-list} for exactly one PR before this caught it (the icon-resolves test
     * added alongside it only proved the regular one worked, since that's the one actually used).
     *
     * <p>Pins the RULE rather than that one instance, same shape as the achromatic chroma-floor test
     * cases (a specific failure becomes a general invariant, not a regression test for the exact
     * bug): R-Q11 vendors both weights for every icon with no exceptions - confirmed by checking the
     * sprite before writing this, rather than assuming a plausible-sounding exception exists - so
     * the check is plain symmetry (every {@code ph-X} has a {@code ph-fill-X} and vice versa), not a
     * hand-maintained allow-list that would silently stop checking whatever it didn't list.
     */
    @Test
    void everyVendoredIconHasBothARegularAndAFillVariant() throws IOException {
        String svg = Files.readString(
                Path.of("src/main/resources/static/icons/phosphor.svg"), StandardCharsets.UTF_8);

        Set<String> allIds = new TreeSet<>();
        Matcher symbol = Pattern.compile("<symbol id=\"(ph-[a-zA-Z0-9-]+)\"").matcher(svg);
        while (symbol.find()) {
            allIds.add(symbol.group(1));
        }

        Set<String> regular = new TreeSet<>();
        Set<String> fill = new TreeSet<>();
        for (String id : allIds) {
            if (id.startsWith("ph-fill-")) {
                fill.add(id.substring("ph-fill-".length()));
            } else {
                regular.add(id.substring("ph-".length()));
            }
        }

        List<String> missingFill = regular.stream().filter(name -> !fill.contains(name)).toList();
        List<String> missingRegular = fill.stream().filter(name -> !regular.contains(name)).toList();

        assertThat(missingFill)
                .as("ph-%s vendored without its ph-fill-%s counterpart - R-Q11 vendors both weights "
                        + "for every icon", missingFill, missingFill)
                .isEmpty();
        assertThat(missingRegular)
                .as("ph-fill-%s vendored without its plain ph-%s counterpart", missingRegular, missingRegular)
                .isEmpty();
    }

    /**
     * Creed's review, spec cc3574c: {@code .banner.err} hard-coded {@code color: #991B1B} - a
     * light-page red - while its own siblings ({@code .warn}/{@code .ok}/{@code .info}) correctly
     * read {@code var(--warn)}/{@code var(--ok)}/{@code var(--info)}. Measured: 1.73:1 against dark
     * mode's {@code --error-bg}, against WCAG 1.4.3's 4.5:1 - and it was LIVE, not latent, the
     * moment T138 1b shipped per-user appearance and someone could actually reach dark mode. Found
     * by inspection two more instances of the identical pattern next to the one Creed flagged
     * ({@code .due.overdue}, {@code .pill.flag-high}) - same hard-coded literal, same live failure,
     * both on genuinely safety-relevant content (an overdue-interview badge, a "missing 5+ times in
     * 30 days" risk flag).
     *
     * <p>Pins the RULE rather than re-testing those three instances: any rule pairing a themed
     * {@code var(--X-bg)} background with a literal hex {@code color} is exactly this bug shape,
     * whether or not anyone has measured that specific pair's contrast yet - a themed background
     * with a fixed ink is the tell, because the ink was clearly meant to track the same semantic
     * token family and doesn't.
     */
    @Test
    void everyThemedBackgroundPairsWithAThemedInkNeverAHardcodedColour() throws IOException {
        List<String> offendingRules = themedBackgroundsWithHardcodedInk(
                Files.readString(CSS_DIR.resolve("app.css"), StandardCharsets.UTF_8));

        assertThat(offendingRules)
                .as("a themed background paired with a hard-coded ink instead of that same family's "
                        + "var(--x) token - correct in one theme by coincidence, wrong the moment "
                        + "anyone reaches the other one")
                .isEmpty();
    }

    /**
     * The {@code color} property itself, never the tail of a longhand that merely ends in it.
     *
     * <p>{@code border-color}, {@code background-color}, {@code outline-color},
     * {@code caret-color} and {@code text-decoration-color} all <em>contain</em> the substring
     * {@code color:}, so an unanchored pattern matches whichever of them comes first in the rule
     * and reports its value as the ink. That made the check above depend on declaration order:
     * {@code .banner.err} passed only because its {@code color:} happened to precede its
     * {@code border-color:}. A guard whose verdict depends on the order someone wrote two
     * unrelated declarations in is not one you can trust the green from (T159).
     */
    private static final Pattern THEMED_INK = Pattern.compile("(?<![-\\w])color:\\s*([^;]+);");

    /** Keyword values that never fix a specific colour, so pairing one with a themed background is
     * never this bug - {@code inherit}/{@code currentColor}/{@code unset} read whatever the
     * parent/element's own (already theme-aware) colour already is, and {@code transparent} fixes
     * no colour at all. Widening the background match beyond the {@code -bg} suffix (Creed's #59
     * review) surfaced {@code .tile { background: var(--surface); color: inherit; }} as a false
     * positive on the first run - this list is what excludes it correctly, rather than narrowing
     * the background match back down to dodge one case.
     *
     * <p>{@code initial} is the one entry here that ISN'T self-evidently safe (Creed's follow-up):
     * it computes to {@code CanvasText}, which is only theme-aware because app.css sets
     * {@code color-scheme} per appearance (dark at :root, light in both appearance overrides) - a
     * declared dependency, not a property of the keyword itself. If those {@code color-scheme}
     * declarations are ever removed, this exclusion silently becomes a hole. */
    private static final Set<String> SAFE_INK_KEYWORDS = Set.of("inherit", "currentcolor", "unset", "initial", "transparent");

    /**
     * Rules in {@code css} pairing a themed {@code var(--X)} background with a literal ink.
     *
     * <p>Not anchored to a {@code -bg} suffix: Creed's #59 review found this guard structurally
     * blind to the accent family's own fills (--accent, --accent-dark, --tint - none end in -bg,
     * unlike every semantic family's --error-bg/--warn-bg/etc), which is exactly where two
     * further live 1.4.3 failures were hiding, missed by both this guard and the manual sweep
     * that produced #48. A naming convention had quietly become this check's definition of
     * "themed" - any {@code var(--...)} background is theme-aware by construction (that is the
     * entire point of a custom-property token), so the match is not narrowed to a suffix.
     */
    /**
     * The CONVERSE of the check above, and the reason it is a separate one: that check finds a
     * themed background under a hard-coded ink, and this finds a hard-coded background under an
     * ink that is free to move. Both produce the same failure - a pair where one half follows the
     * appearance and the other does not - but only this one catches a rule that sets NO colour at
     * all, which is exactly how the live 2c defect survived two clean sweeps.
     *
     * <p>{@code .tile.urgent} and {@code .tile.warn} fixed a light fill ({@code #FFFAFA},
     * {@code #FFFDF6}) and left the ink inherited. On dark that put near-white text on near-white:
     * the 30px count on the "needs attention" tiles measured 1.17:1, so the overdue number was the
     * one that had gone invisible. The other check could not see it, because there was no
     * {@code color} declaration in the rule for it to read - the bug is the ABSENCE of one.
     *
     * <p>Searching the shape rather than those two found four more live instances, including
     * {@code input.is-invalid}, which is every invalid field on every form in the app.
     *
     * <p>So the rule this pins is the general one: <b>a background is half of a contrast pair
     * whose other half follows the appearance, so it must come from a token.</b> Print is the one
     * exemption and it is a real one rather than a carve-out for awkward cases - paper is white in
     * both appearances, so a fixed white there is a statement of fact.
     */
    @Test
    void noBackgroundIsAHardcodedColourExceptWherePrintMakesItAFact() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path file : sourceFilesUnder(CSS_DIR)) {
            violations.addAll(hardcodedBackgroundsOutsidePrint(
                    Files.readString(file, StandardCharsets.UTF_8), file.toString()));
        }

        assertThat(violations)
                .as("a hard-coded background is half of a contrast pair whose other half follows "
                        + "the appearance - take the fill from a token, and where the ink is "
                        + "inherited, let it stay inherited")
                .isEmpty();
    }

    /**
     * Backgrounds set to a colour literal, ignoring anything inside a {@code @media print} block.
     *
     * <p>The print block is found by brace depth rather than by a line range, so it keeps working
     * when rules are added to it - a range would silently stop covering the block's tail.
     */
    private static List<String> hardcodedBackgroundsOutsidePrint(String css, String name) {
        String stripped = css.replaceAll("(?s)/\\*.*?\\*/", "");
        Pattern literalBackground =
                Pattern.compile("background(-color)?:\\s*(#[0-9A-Fa-f]{3,8}|rgba?\\(|hsla?\\()");
        List<String> violations = new ArrayList<>();
        int depth = 0;
        int printBlockDepth = -1;
        for (String line : stripped.split("\n")) {
            if (line.contains("@media") && line.contains("print")) {
                printBlockDepth = depth;
            }
            if (printBlockDepth < 0 && literalBackground.matcher(line).find()) {
                violations.add(name + ": " + line.trim());
            }
            depth += count(line, '{') - count(line, '}');
            if (printBlockDepth >= 0 && depth <= printBlockDepth) {
                printBlockDepth = -1;
            }
        }
        return violations;
    }

    private static int count(String line, char c) {
        return (int) line.chars().filter(ch -> ch == c).count();
    }

    private static List<String> themedBackgroundsWithHardcodedInk(String css) {
        String stripped = css.replaceAll("(?s)/\\*.*?\\*/", "");
        List<String> offendingRules = new ArrayList<>();
        Matcher rule = Pattern.compile("([^{}]+)\\{([^{}]*)\\}").matcher(stripped);
        while (rule.find()) {
            String selector = rule.group(1).trim();
            String body = rule.group(2);
            if (!body.contains("background:")) {
                continue;
            }
            Matcher background = Pattern.compile("background:\\s*var\\(--([a-zA-Z0-9-]+)\\)").matcher(body);
            if (!background.find()) {
                continue;
            }
            Matcher ink = THEMED_INK.matcher(body);
            if (ink.find() && !ink.group(1).trim().startsWith("var(")
                    && !SAFE_INK_KEYWORDS.contains(ink.group(1).trim().toLowerCase(Locale.ROOT))) {
                offendingRules.add(selector + " { background: var(--" + background.group(1) + "); color: "
                        + ink.group(1).trim() + "; }");
            }
        }
        return offendingRules;
    }

    /**
     * Pins the reading of the ink against the two false positives the unanchored pattern produced,
     * with a genuine violation alongside them so a check that simply stopped finding anything could
     * not pass this by going quiet.
     */
    @Test
    void theThemedInkCheckReadsTheInkAndNotEveryPropertyWhoseNameEndsInColour() {
        assertThat(themedBackgroundsWithHardcodedInk(
                ".genuine { background: var(--error-bg); color: #991B1B; }"))
                .as("a themed background with a literal ink is still the bug this guard exists for")
                .hasSize(1);

        assertThat(themedBackgroundsWithHardcodedInk(".ordered { background: var(--error-bg); "
                + "border-color: color-mix(in srgb, var(--error) 25%, transparent); color: var(--error); }"))
                .as("the ink is themed; a border-color declared before it must not be read as the ink")
                .isEmpty();

        assertThat(themedBackgroundsWithHardcodedInk(
                ".borderOnly { background: var(--error-bg); border-color: #F3C0C0; }"))
                .as("a literal BORDER colour is not an ink, and this rule declares no ink at all")
                .isEmpty();
    }

    /** Every value {@code token} is given in {@code css}, across all the appearance blocks. */
    private static Set<String> valuesOf(String css, String token) {
        Matcher m = Pattern.compile(Pattern.quote(token) + "\\s*:\\s*([^;]+);").matcher(css);
        Set<String> values = new HashSet<>();
        while (m.find()) {
            values.add(m.group(1).trim());
        }
        return values;
    }

    /**
     * An ink named for a FILL must not resolve to the page ink.
     *
     * <p>T163. {@code .btn} paints {@code background: var(--accent)} and takes its ink from
     * {@code --accent-ink}, so that ink has to contrast with the ACCENT. T119 (#22) redefined it
     * from a fixed {@code #1F2328} to {@code var(--color-text)} at the point {@code --accent}
     * stopped being a pale fill, and {@code --color-text} is the PAGE ink - it moves between
     * appearances the same way the accent does, so the two travel together and never separate.
     * The resting button measured 2.39:1 dark / 2.08:1 light, failing 1.4.3 at all 360 hues in
     * BOTH appearances, for as long as that definition stood.
     *
     * <p>Deliberately not part of {@link #everyThemedBackgroundPairsWithAThemedInkNeverAHardcodedColour}:
     * that guard's tell is a LITERAL ink under a themed fill, and this failure is two perfectly
     * themed tokens that happen to move in the same direction. No pattern over the source can see
     * it, so what is pinned here is the relationship the tokens must keep, not a measurement -
     * an ink minted for a fill and the page ink being the same value is the mistake itself, and it
     * is the exact form the T119 regression took.
     */
    @Test
    void theInkMintedForAnAccentFillIsNotAlsoThePageInk() throws IOException {
        String css = Files.readString(CSS_DIR.resolve("app.css"), StandardCharsets.UTF_8);

        Set<String> accentInk = valuesOf(css, "--accent-ink");
        assertThat(accentInk).as("--accent-ink is declared").isNotEmpty();
        assertThat(accentInk)
                .as("--accent-ink sits on an --accent fill, so it must not be the page ink "
                        + "(%s) - those two move together between appearances and never separate",
                        valuesOf(css, "--ink"))
                .doesNotContainAnyElementsOf(valuesOf(css, "--ink"));
    }

    /**
     * Creed's #59 review: widening the background match beyond the {@code -bg} suffix (needed to
     * catch the accent family's own fills - --accent, --accent-dark, --tint) surfaced a real false
     * positive on its first run, {@code .tile { background: var(--surface); color: inherit; }} -
     * {@code inherit} never fixes a colour, so it can never be the bug this guard exists for.
     */
    @Test
    void theWidenedBackgroundMatchCatchesANonBgSuffixedTokenButNotAnInheritedInk() {
        assertThat(themedBackgroundsWithHardcodedInk(
                ".genuine { background: var(--accent-dark); color: #fff; }"))
                .as("a non -bg-suffixed themed background (the accent family's own fills) is still this bug")
                .hasSize(1);

        assertThat(themedBackgroundsWithHardcodedInk(
                ".tile { background: var(--surface); color: inherit; }"))
                .as("inherit is never a hard-coded literal - it reads whatever the parent's own colour is")
                .isEmpty();

        assertThat(themedBackgroundsWithHardcodedInk(
                ".icon { background: var(--surface); color: currentColor; }"))
                .as("currentColor is the same non-literal case, differently cased")
                .isEmpty();
    }

    private static List<Path> sourceFilesUnder(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".css") || p.toString().endsWith(".html"))
                    .toList();
        }
    }

    private static long countOccurrences(String content, String needle) {
        long count = 0;
        int index = 0;
        while ((index = content.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
