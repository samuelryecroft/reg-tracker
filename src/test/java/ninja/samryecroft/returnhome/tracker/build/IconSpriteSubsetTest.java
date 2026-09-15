package ninja.samryecroft.returnhome.tracker.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the icon-sprite subset (T349). These run against the real sprite and the real templates,
 * so they fail if a template references a glyph the sprite lacks, if the subset would drop a glyph
 * that is used, or if a new runtime-named glyph is added without being force-kept.
 *
 * <p>The subsetter itself is build tooling in {@code src/test/java}; this exercises its public
 * methods directly rather than shelling out, so a break points at a line rather than an exit code.
 */
class IconSpriteSubsetTest {

    private static final Path SPRITE = Path.of("src/main/resources/static/icons/phosphor.svg");
    private static final List<Path> SCAN_ROOTS = List.of(
            Path.of("src/main/resources/templates"), Path.of("src/main/resources/static"));

    private Set<String> keepSet() throws IOException {
        Set<String> keep = new TreeSet<>(IconSpriteSubsetter.referencedIds(SCAN_ROOTS));
        keep.addAll(IconSpriteSubsetter.DYNAMIC_KEEP);
        return keep;
    }

    @Test
    void everyReferencedGlyphExistsInTheSprite() throws IOException {
        String xml = Files.readString(SPRITE, StandardCharsets.UTF_8);
        Set<String> defined = IconSpriteSubsetter.definedIds(xml);
        // A missing glyph is worse than a large file: a reference the sprite cannot satisfy must
        // fail here, not render blank in production.
        assertThat(defined).containsAll(keepSet());
    }

    @Test
    void subsetKeepsExactlyTheUsedGlyphsAndDropsTheRest() throws IOException {
        String xml = Files.readString(SPRITE, StandardCharsets.UTF_8);
        Set<String> defined = IconSpriteSubsetter.definedIds(xml);
        Set<String> keep = keepSet();

        String subset = IconSpriteSubsetter.subset(xml, keep);
        Set<String> survived = IconSpriteSubsetter.definedIds(subset);

        assertThat(survived).isEqualTo(keep);
        assertThat(subset.getBytes(StandardCharsets.UTF_8).length)
                .as("subset must be smaller than the full sprite")
                .isLessThan(xml.getBytes(StandardCharsets.UTF_8).length);
        assertThat(defined.size() - keep.size())
                .as("most glyphs are unused and should be dropped")
                .isGreaterThan(defined.size() / 2);
    }

    @Test
    void everySurvivingSymbolIsStillWellFormed() throws IOException {
        String xml = Files.readString(SPRITE, StandardCharsets.UTF_8);
        String subset = IconSpriteSubsetter.subset(xml, keepSet());
        // Balanced tags and an intact wrapper - the subset is still one usable sprite.
        assertThat(subset).startsWith("<!-- ").contains("<svg ").endsWith("</svg>\n");
        long opens = subset.split("<symbol\\b", -1).length - 1;
        long closes = subset.split("</symbol>", -1).length - 1;
        assertThat(opens).isEqualTo(closes).isEqualTo(keepSet().size());
    }

    @Test
    void dynamicKeepCoversTheAppearanceToggle() throws IOException {
        // The theme toggle names its glyph at runtime: '#ph-' + appearanceToggle.icon(). Whatever
        // GlobalControllerAdvice can return MUST be kept, or the toggle renders blank in that state.
        Path advice = Path.of(
                "src/main/java/ninja/samryecroft/returnhome/tracker/web/GlobalControllerAdvice.java");
        String source = Files.readString(advice, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("new AppearanceToggle\\(\"([a-z0-9-]+)\"").matcher(source);
        Set<String> keep = keepSet();
        int found = 0;
        while (m.find()) {
            found++;
            String glyph = "ph-" + m.group(1);
            assertThat(keep)
                    .as("appearance-toggle glyph %s must be kept (add it to DYNAMIC_KEEP)", glyph)
                    .contains(glyph);
        }
        assertThat(found).as("expected to find the AppearanceToggle states").isGreaterThanOrEqualTo(3);
    }
}
