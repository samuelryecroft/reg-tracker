package ninja.samryecroft.returnhome.tracker.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Build-time subsetter for {@code static/icons/phosphor.svg} (T349, Lane B perf item 3).
 *
 * <p>The vendored Phosphor sprite defines 112 {@code <symbol>}s; only a fraction are referenced.
 * This keeps exactly the referenced glyphs and drops the rest, writing the result over the copy in
 * {@code target/classes} at {@code prepare-package} so the packaged jar ships the subset while the
 * source tree keeps the full library. The reference set is DISCOVERED by scanning templates and
 * scripts, so adding or removing an icon in a template is self-maintaining - no allowlist to update.
 *
 * <p><strong>This lives in {@code src/test/java} on purpose.</strong> It is build tooling, not
 * application code: compiled into {@code test-classes}, which is never packaged into the app jar, so
 * it ships no dead code - and it is directly unit-testable (see {@code IconSpriteSubsetTest}). The
 * Maven {@code exec} step runs its {@link #main} with {@code classpathScope=test}, the same pattern
 * the Playwright browser-install step already uses.
 *
 * <p><strong>The one thing a scan cannot see: a glyph named at runtime.</strong>
 * {@code fragments/layout.html} builds the theme-toggle href as {@code '#ph-' + appearanceToggle.icon()},
 * and {@code GlobalControllerAdvice.appearanceToggle} returns {@code compass}/{@code sun}/{@code moon}
 * by state. Only {@code compass} also appears as a static literal (error.html); {@code sun} and
 * {@code moon} would be dropped by a pure scan and the toggle would render blank in two of its three
 * states. They are force-kept in {@link #DYNAMIC_KEEP}, and the test asserts that set stays in step
 * with the controller - so a fourth toggle icon breaks the build rather than shipping a missing glyph.
 */
public final class IconSpriteSubsetter {

    /**
     * Glyphs referenced ONLY through a runtime-constructed name, invisible to {@link #referencedIds}.
     * Source of truth: {@code GlobalControllerAdvice.appearanceToggle} (compass/sun/moon). Guarded by
     * {@code IconSpriteSubsetTest.dynamicKeepCoversAppearanceToggle}.
     */
    public static final Set<String> DYNAMIC_KEEP = Set.of("ph-compass", "ph-sun", "ph-moon");

    /** {@code #ph-<name>} references as they appear in a {@code <use href>} / th:href literal. */
    private static final Pattern REFERENCE = Pattern.compile("#(ph-[a-z0-9-]+)");

    /** {@code id="ph-<name>"} on a {@code <symbol>}. */
    private static final Pattern SYMBOL_ID = Pattern.compile("id=\"(ph-[a-z0-9-]+)\"");

    private IconSpriteSubsetter() {
    }

    /** Every {@code #ph-*} literal referenced under the given source roots (.html/.js/.css only). */
    public static Set<String> referencedIds(List<Path> roots) throws IOException {
        Set<String> ids = new TreeSet<>();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    String name = file.getFileName().toString();
                    if (!(name.endsWith(".html") || name.endsWith(".js") || name.endsWith(".css"))) {
                        continue; // never .md - the README's examples are documentation, not usage
                    }
                    Matcher m = REFERENCE.matcher(Files.readString(file, StandardCharsets.UTF_8));
                    while (m.find()) {
                        ids.add(m.group(1));
                    }
                }
            }
        }
        return ids;
    }

    /** Every {@code <symbol>} id defined in the sprite. */
    public static Set<String> definedIds(String spriteXml) {
        Set<String> ids = new TreeSet<>();
        Matcher m = SYMBOL_ID.matcher(spriteXml);
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    /** The sprite with every {@code <symbol>} whose id is not in {@code keep} removed verbatim. */
    public static String subset(String spriteXml, Set<String> keep) {
        Pattern symbol = Pattern.compile("<symbol\\b[^>]*\\bid=\"(ph-[a-z0-9-]+)\"[^>]*>.*?</symbol>\\R?",
                Pattern.DOTALL);
        Matcher m = symbol.matcher(spriteXml);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, keep.contains(m.group(1)) ? Matcher.quoteReplacement(m.group()) : "");
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * args: {@code <sourceSprite> <outputSprite> <scanRoot>...}. Fails the build if any referenced
     * glyph is absent from the sprite - a missing glyph must break the build, never ship.
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: IconSpriteSubsetter <sourceSprite> <outputSprite> <scanRoot>...");
            System.exit(2);
        }
        Path source = Path.of(args[0]);
        Path output = Path.of(args[1]);
        List<Path> roots = Stream.of(args).skip(2).map(Path::of).toList();

        String spriteXml = Files.readString(source, StandardCharsets.UTF_8);
        Set<String> defined = definedIds(spriteXml);
        Set<String> keep = new TreeSet<>(referencedIds(roots));
        keep.addAll(DYNAMIC_KEEP);

        Set<String> missing = new TreeSet<>(keep);
        missing.removeAll(defined);
        if (!missing.isEmpty()) {
            System.err.println("[icon-subset] FAIL: referenced glyphs absent from sprite: " + missing);
            System.exit(1);
        }

        String subset = subset(spriteXml, keep);
        Files.createDirectories(output.getParent());
        long before = spriteXml.getBytes(StandardCharsets.UTF_8).length;
        Files.writeString(output, subset, StandardCharsets.UTF_8);
        long after = subset.getBytes(StandardCharsets.UTF_8).length;

        System.out.printf("[icon-subset] %d symbols -> %d kept (%d dropped); %d B -> %d B (%.0f%% smaller)%n",
                defined.size(), keep.size(), defined.size() - keep.size(), before, after,
                100.0 * (before - after) / before);
        System.out.println("[icon-subset] wrote " + output);
    }
}
