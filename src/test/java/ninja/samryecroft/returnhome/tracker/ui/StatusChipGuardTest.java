package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import ninja.samryecroft.returnhome.tracker.organisation.OrgStatus;
import org.junit.jupiter.api.Test;

/**
 * The {@code .status} chip convention, and the CSS the chip needs to mean anything (T265).
 *
 * <p>The admin organisation tree rendered {@code th:text="${...status}"} - the CONSTANT - so the
 * screen showed "PENDING" in shouting caps. Four sibling chips already followed the convention
 * ({@code children/detail.html:67,:94}, {@code interview/detail.html:12},
 * {@code reviewer/review-form.html:18}): <strong>two expressions doing two jobs</strong>,
 * {@code th:classappend} on the constant because {@code app.css} keys {@code .status.*} on constant
 * names, {@code th:text} on the display name.
 *
 * <p><strong>Why a source guard rather than a rendering assertion.</strong> The failure Creed named
 * is a HALF-FIX: {@code organisation-list.html} is the only template that broke the convention and
 * it broke it TWICE, in different branches of the tree, so fixing one leaves a screen that looks
 * correct until an org type nobody tested renders. That is invisible to every rendering test whose
 * fixture happens to exercise one branch - and a fixture's branch coverage is itself the thing
 * nobody checks. Reading the templates has no fixture to be incomplete. Same argument as
 * {@link FrontendSourceGuardTest}: the server starts, every template renders, nothing throws.
 *
 * <p><strong>What this does NOT catch, stated rather than implied.</strong> It reads the chip's own
 * {@code th:text}. A chip whose words come from a NESTED element is invisible to it -
 * {@code fragments/case-card.html:88} is exactly that shape, and it is correct today (its inner span
 * asks for {@code displayName}), but this guard is not what keeps it correct.
 */
class StatusChipGuardTest {

    private static final Path TEMPLATES_DIR = Path.of("src/main/resources/templates");
    private static final Path APP_CSS = Path.of("src/main/resources/static/css/app.css");

    /** Any opening tag with a static class attribute. Attributes may wrap lines. */
    private static final Pattern ELEMENT_WITH_CLASS = Pattern.compile(
            "<\\w+[^>]*\\sclass=\"([^\"]*)\"[^>]*>", Pattern.DOTALL);
    private static final Pattern TH_TEXT = Pattern.compile("th:text=\"([^\"]*)\"");
    private static final Pattern TH_CLASSAPPEND = Pattern.compile("th:classappend=\"([^\"]*)\"");
    /** An expression whose last property is a status - i.e. the object, not a name for it. */
    private static final Pattern RENDERS_A_STATUS_OBJECT = Pattern.compile("[Ss]tatus\\(?\\)?\\s*\\}");

    @Test
    void noStatusChipRendersTheConstantWhereTheDisplayNameBelongs() throws IOException {
        List<String> violations = new ArrayList<>();
        forEachStatusChip((file, chip) -> {
            String text = attribute(TH_TEXT, chip);
            if (text != null && RENDERS_A_STATUS_OBJECT.matcher(text).find()) {
                violations.add(file + ": th:text=\"" + text + "\" renders the constant; "
                        + "the chip's words come from the display name");
            }
        });

        assertThat(violations)
                .as("a .status chip showing an enum constant - PENDING, REPORT_REJECTED - is the "
                        + "screen asking the constant instead of asking the system what it calls "
                        + "the state, and it is invisible to every test that only renders the page")
                .isEmpty();
    }

    /**
     * The other half of "two expressions, two jobs". Moving the display name into
     * {@code th:classappend} yields {@code class="status Awaiting activation"} - <strong>two junk
     * classes, silently</strong>, and a chip with no styling at all. That failure is invisible in
     * exactly the same way as the one above, and it is the obvious mistake for someone tidying the
     * duplication.
     */
    @Test
    void noStatusChipPutsTheDisplayNameInTheClassAttribute() throws IOException {
        List<String> violations = new ArrayList<>();
        forEachStatusChip((file, chip) -> {
            String classes = attribute(TH_CLASSAPPEND, chip);
            if (classes != null && classes.contains("displayName")) {
                violations.add(file + ": th:classappend=\"" + classes + "\"");
            }
        });

        assertThat(violations)
                .as("app.css keys .status.* modifiers on CONSTANT names, so a display name here is "
                        + "not a wrong class, it is two junk classes and no styling")
                .isEmpty();
    }

    /**
     * Every {@link OrgStatus} has its own chip colour, read from the enum rather than listed here.
     *
     * <p>All three states used to fall through to base {@code .status}, so <strong>the colour
     * carried no information and the shouting-caps constant was the entire signal</strong>. Giving
     * them display names without giving them modifiers would have made the screen quieter without
     * making it clearer, which is why Creed ruled the CSS part of this change rather than a
     * follow-up. A fourth state added later inherits the same defect silently: neutral on neutral,
     * indistinguishable from every other org on the screen a platform admin audits the estate from.
     *
     * <p>Scoped to OrgStatus deliberately. InterviewStatus has a modifier for all seven of its
     * constants today, but nobody has ruled that it must, and asserting it here would be inventing
     * a constraint for an enum this card was told not to touch.
     */
    @Test
    void everyOrgStatusHasItsOwnChipColour() throws IOException {
        String css = Files.readString(APP_CSS, StandardCharsets.UTF_8);

        assertThat(Stream.of(OrgStatus.values())
                .filter(status -> !css.contains(".status." + status.name() + " "))
                .map(OrgStatus::name)
                .toList())
                .as("OrgStatus constants with no .status.<CONSTANT> rule in app.css - they render "
                        + "neutral on neutral, which is indistinguishable from every other state")
                .isEmpty();
    }

    // --- plumbing ---

    private interface ChipVisitor {
        void visit(Path file, String openingTag);
    }

    private void forEachStatusChip(ChipVisitor visitor) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(TEMPLATES_DIR)) {
            files = walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".html"))
                    .sorted()
                    .toList();
        }
        int chips = 0;
        for (Path file : files) {
            Matcher matcher = ELEMENT_WITH_CLASS.matcher(Files.readString(file, StandardCharsets.UTF_8));
            while (matcher.find()) {
                // A CSS class token is delimited by WHITESPACE, so the match has to be on a token
                // and not on a regex word boundary: \\bstatus\\b also matches inside "error-status",
                // whose ${status} is an HTTP status integer and has no display name to ask for.
                // Narrowing the class rather than excluding that file keeps the guard free of an
                // exception list, which is the part of a guard that goes stale unwatched.
                if (!List.of(matcher.group(1).trim().split("\\s+")).contains("status")) {
                    continue;
                }
                chips++;
                visitor.visit(file, matcher.group());
            }
        }
        // The guard checks its own reach. A regex that silently stops matching finds no violations
        // and reports success - the shape that let six tests stay green over a null in T177, and the
        // one failure mode a source scanner cannot notice about itself.
        assertThat(chips)
                .as("status chips found across %d templates - if this is 0 the pattern has stopped "
                        + "matching and both guards above are vacuously green", files.size())
                .isGreaterThanOrEqualTo(6);
    }

    private static String attribute(Pattern pattern, String openingTag) {
        Matcher matcher = pattern.matcher(openingTag);
        return matcher.find() ? matcher.group(1) : null;
    }
}
