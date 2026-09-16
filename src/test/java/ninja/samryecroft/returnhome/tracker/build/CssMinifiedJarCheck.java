package ninja.samryecroft.returnhome.tracker.build;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Build-time backstop for the CSS minification (T361), mirroring {@link IconSpriteSubsetJarCheck}:
 * assert the PACKAGED jar carries the MINIFIED css, not the fully-commented source.
 *
 * <p><b>Why this exists as well as {@code minify-css}.</b> A step that PRODUCES the minified file
 * cannot catch its own absence - if the minify exec is skipped (a stray {@code -Dexec.skip=true},
 * which the sprite subset already proved can ship the wrong content with a clean git stamp), every
 * other check stays green and the jar quietly ships the 122 KB commented CSS. A step that INSPECTS
 * THE ARTIFACT can. This reads the jar spring-boot repackage actually built and fails the build if
 * the packaged app.css still contains a comment (i.e. minification did not run), or is not smaller
 * than the source.
 *
 * <p>Lives in test-classes on purpose (like {@link CssMinifier}): it ships no code in the app jar,
 * and a build that skips test compilation ({@code -Dmaven.test.skip=true}) makes this class absent,
 * so the exec errors loudly rather than the minification silently going unverified.
 */
public final class CssMinifiedJarCheck {

    private static final String CSS_ENTRY = "BOOT-INF/classes/static/css/app.css";

    private CssMinifiedJarCheck() {
    }

    /** args: {@code <jar> <sourceCss>}. Exits non-zero (fails the build) if the packaged CSS is not minified. */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: CssMinifiedJarCheck <jar> <sourceCss>");
            System.exit(2);
        }
        Path jarPath = Path.of(args[0]);
        Path sourceCss = Path.of(args[1]);

        if (!Files.exists(jarPath)) {
            System.err.println("[jar-css-minify-check] FAIL: jar not found: " + jarPath
                    + " (expected spring-boot repackage to have run in the package phase before this)");
            System.exit(1);
        }

        String packaged = readCssFromJar(jarPath);
        if (packaged == null) {
            System.err.println("[jar-css-minify-check] FAIL: " + CSS_ENTRY + " not present in " + jarPath);
            System.exit(1);
        }

        int sourceBytes = Files.readString(sourceCss, StandardCharsets.UTF_8)
                .getBytes(StandardCharsets.UTF_8).length;
        int packagedBytes = packaged.getBytes(StandardCharsets.UTF_8).length;

        boolean hasComment = packaged.contains("/*");
        boolean smaller = packagedBytes < sourceBytes;

        if (hasComment || !smaller) {
            System.err.println("[jar-css-minify-check] FAIL: packaged " + CSS_ENTRY + " is not minified.");
            System.err.println("  contains a /* comment : " + hasComment + " (must be false)");
            System.err.println("  packaged bytes        : " + packagedBytes);
            System.err.println("  source bytes          : " + sourceBytes + " (packaged must be smaller)");
            System.err.println("  -> the minify-css step did not run against the packaged artifact.");
            System.exit(1);
        }

        System.out.printf("[jar-css-minify-check] OK: packaged app.css is minified "
                + "(%d bytes, no comments; source %d bytes)%n", packagedBytes, sourceBytes);
    }

    private static String readCssFromJar(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(CSS_ENTRY);
            if (entry == null) {
                return null;
            }
            try (InputStream in = jar.getInputStream(entry)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
    }
}
