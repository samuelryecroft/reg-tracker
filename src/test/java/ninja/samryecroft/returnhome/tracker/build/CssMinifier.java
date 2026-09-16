package ninja.samryecroft.returnhome.tracker.build;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Build-time minifier for {@code static/css/app.css} (T361, Lane B perf item 5).
 *
 * <p>app.css is ~45% comments (measured, T350): the shipped file is 122 KB, of which ~55 KB is the
 * house-style explanatory comments and ~3 KB is indentation/whitespace. Those comments are English
 * prose, which gzips far worse than CSS, so removing them takes the SERVED file from ~37 KB gzipped
 * down to ~9 KB - a ~28 KB first-load saving, measured, not predicted. This writes the minified
 * result over the copy in {@code target/classes} at {@code prepare-package}, so the packaged jar
 * ships the minified CSS while <strong>the source tree keeps every comment exactly as readable as it
 * is today</strong>.
 *
 * <p><strong>Comments and whitespace ONLY - it never rewrites, merges, drops or reorders a rule</strong>
 * (T361 boundary). The transform is deliberately the most conservative one that is provably
 * behaviour-preserving:
 * <ol>
 *   <li>each {@code /* ... *}{@code /} comment becomes a single space (a space, never nothing, so
 *       {@code a}{@code /**}{@code /}{@code b} can never fuse into {@code ab});</li>
 *   <li>each run of whitespace becomes a single space - and a single space is a valid stand-in for
 *       ANY run of whitespace everywhere in CSS, so the descendant combinator ({@code .a .b}),
 *       {@code calc(100% - 10px)} and multi-value lists all keep their meaning;</li>
 *   <li>whitespace immediately adjacent to {@code &#123; &#125; ; ,} is dropped - those four are
 *       unambiguous token boundaries where surrounding whitespace is never significant.</li>
 * </ol>
 * Crucially it does NOT touch whitespace around {@code :} (removing it would fuse the selector
 * {@code a :hover} into {@code a:hover}, a different rule), and STRING CONTENTS ARE COPIED VERBATIM -
 * a {@code /*} inside {@code content:"..."} is not a comment, and the spaces inside a string are
 * significant. See {@code CssMinifierTest} for the edge cases this is asserted against.
 *
 * <p><strong>Lives in {@code src/test/java} on purpose</strong>, exactly like {@link
 * IconSpriteSubsetter}: it is build tooling, compiled into {@code test-classes} (never packaged into
 * the app jar, so it ships no dead code) and directly unit-testable. The Maven {@code exec} step runs
 * {@link #main} with {@code classpathScope=test}, the same pattern the sprite subset and the
 * Playwright install already use. {@link CssMinifiedJarCheck} is the backstop that fails the build if
 * the packaged CSS ever regains its comments (i.e. minification silently stopped running).
 */
public final class CssMinifier {

    private CssMinifier() {
    }

    /**
     * Minifies CSS by removing comments and collapsing insignificant whitespace, and nothing else.
     * String literals ({@code "..."} / {@code '...'}) are copied byte-for-byte.
     */
    public static String minify(String css) {
        StringBuilder out = new StringBuilder(css.length());
        int i = 0;
        int n = css.length();
        while (i < n) {
            char c = css.charAt(i);
            // String literal: copy verbatim, honouring backslash escapes, until the matching quote.
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < n) {
                    char d = css.charAt(i);
                    out.append(d);
                    if (d == '\\' && i + 1 < n) {
                        out.append(css.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    i++;
                    if (d == quote) {
                        break;
                    }
                }
                continue;
            }
            // A "gap" - any run mixing whitespace and comments - collapses to a single space. A
            // space (never nothing) so tokens either side can never fuse (a/**/b stays "a b"), and
            // one space is a valid stand-in for any whitespace run everywhere in CSS.
            if (isWhitespace(c) || (c == '/' && i + 1 < n && css.charAt(i + 1) == '*')) {
                while (i < n) {
                    char d = css.charAt(i);
                    if (isWhitespace(d)) {
                        i++;
                    } else if (d == '/' && i + 1 < n && css.charAt(i + 1) == '*') {
                        int end = css.indexOf("*/", i + 2);
                        i = (end == -1) ? n : end + 2;
                    } else {
                        break;
                    }
                }
                out.append(' ');
                continue;
            }
            out.append(c);
            i++;
        }
        // Drop whitespace adjacent to the four always-safe token boundaries, then trim the ends.
        // Done as a second pass over the (string-preserving) result: the only spaces present now are
        // single spaces we inserted between real tokens, never inside a string literal.
        return trimAroundBoundaries(out.toString()).trim();
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f';
    }

    /**
     * Removes a single space where it sits immediately before or after one of {@code &#123; &#125; ; ,}.
     * Re-scans string literals so a space inside {@code content:"a , b"} is never touched.
     */
    private static String trimAroundBoundaries(String css) {
        StringBuilder out = new StringBuilder(css.length());
        int n = css.length();
        for (int i = 0; i < n; i++) {
            char c = css.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < n) {
                    char d = css.charAt(i);
                    out.append(d);
                    if (d == '\\' && i + 1 < n) {
                        out.append(css.charAt(i + 1));
                        i++;
                        continue;
                    }
                    if (d == quote) {
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (c == ' ') {
                boolean prevIsBoundary = out.length() > 0 && isBoundary(out.charAt(out.length() - 1));
                boolean nextIsBoundary = i + 1 < n && isBoundary(css.charAt(i + 1));
                if (prevIsBoundary || nextIsBoundary) {
                    continue; // drop this space
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isBoundary(char c) {
        return c == '{' || c == '}' || c == ';' || c == ',';
    }

    /** args: {@code <sourceCss> <destCss>}. Reads source, writes the minified result to dest. */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            System.err.println("usage: CssMinifier <sourceCss> <destCss>");
            System.exit(2);
        }
        Path source = Path.of(args[0]);
        Path dest = Path.of(args[1]);
        String original = Files.readString(source, StandardCharsets.UTF_8);
        String minified = minify(original);
        Files.writeString(dest, minified, StandardCharsets.UTF_8);
        System.out.printf("[css-minify] %s: %d -> %d bytes (%.1f%% smaller, pre-gzip)%n",
                dest.getFileName(), original.getBytes(StandardCharsets.UTF_8).length,
                minified.getBytes(StandardCharsets.UTF_8).length,
                100.0 * (original.length() - minified.length()) / Math.max(1, original.length()));
    }
}
