package ninja.samryecroft.returnhome.tracker.build;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Build-time backstop for the icon-sprite subset (T356): assert the PACKAGED jar carries the SUBSET,
 * not the full source sprite.
 *
 * <p><b>Why this exists as well as {@code subset-icon-sprite}.</b> R17 shipped the full 112-symbol
 * sprite with a clean git stamp because the deploy build passed {@code -Dexec.skip=true}, which
 * skipped the subset step while every other check stayed green - correct commit, wrong content, the
 * nastiest release shape because nothing failed. A step that PRODUCES the subset cannot catch its own
 * absence; a step that INSPECTS THE ARTIFACT can. This reads the jar spring-boot repackage actually
 * built and fails the build if the packaged sprite still has the source symbol count.
 *
 * <p><b>The expected count is derived, not hard-coded.</b> It is the referenced-glyph set the
 * subsetter itself computes (scan of templates/scripts, plus the runtime-named toggle glyphs), so
 * this guard stays correct when icons are added or removed and never drifts to a stale magic number.
 * It also asserts the packaged count is strictly LESS than the source count, so a build that somehow
 * packaged the full sprite while the referenced set happened to equal 112 would still be caught.
 *
 * <p>Lives in test-classes on purpose (like {@code IconSpriteSubsetter}): it ships no code in the app
 * jar, and a build that skips test compilation ({@code -Dmaven.test.skip=true}) makes this class
 * absent, so the exec errors loudly rather than the subset silently going unverified.
 */
public final class IconSpriteSubsetJarCheck {

    private static final String SPRITE_ENTRY = "BOOT-INF/classes/static/icons/phosphor.svg";

    private IconSpriteSubsetJarCheck() {
    }

    /** args: {@code <jar> <sourceSprite> <scanRoot>...}. Exits non-zero (fails the build) on mismatch. */
    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: IconSpriteSubsetJarCheck <jar> <sourceSprite> <scanRoot>...");
            System.exit(2);
        }
        Path jarPath = Path.of(args[0]);
        Path sourceSprite = Path.of(args[1]);
        List<Path> roots = java.util.Arrays.stream(args).skip(2).map(Path::of).toList();

        if (!Files.exists(jarPath)) {
            System.err.println("[jar-subset-check] FAIL: jar not found: " + jarPath
                    + " (expected spring-boot repackage to have run in the package phase before this)");
            System.exit(1);
        }

        Set<String> expected = new TreeSet<>(IconSpriteSubsetter.referencedIds(roots));
        expected.addAll(IconSpriteSubsetter.DYNAMIC_KEEP);
        int sourceCount = IconSpriteSubsetter.definedIds(
                Files.readString(sourceSprite, StandardCharsets.UTF_8)).size();

        String packagedXml = readSpriteFromJar(jarPath);
        if (packagedXml == null) {
            System.err.println("[jar-subset-check] FAIL: " + SPRITE_ENTRY + " not present in " + jarPath);
            System.exit(1);
        }
        Set<String> packaged = IconSpriteSubsetter.definedIds(packagedXml);

        if (!packaged.equals(expected) || packaged.size() >= sourceCount) {
            System.err.printf("[jar-subset-check] FAIL: packaged sprite carries %d symbols; expected the "
                    + "subset of %d referenced glyphs (source has %d). The jar was built without the "
                    + "subset step - a -Dexec.skip build is the usual cause. Do not ship it.%n",
                    packaged.size(), expected.size(), sourceCount);
            TreeSet<String> unexpected = new TreeSet<>(packaged);
            unexpected.removeAll(expected);
            if (!unexpected.isEmpty()) {
                System.err.println("[jar-subset-check] packaged-but-unreferenced (first 5): "
                        + unexpected.stream().limit(5).toList());
            }
            System.exit(1);
        }

        System.out.printf("[jar-subset-check] OK: packaged sprite carries the %d-glyph subset "
                + "(source has %d).%n", packaged.size(), sourceCount);
    }

    private static String readSpriteFromJar(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(SPRITE_ENTRY);
            if (entry == null) {
                return null;
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
