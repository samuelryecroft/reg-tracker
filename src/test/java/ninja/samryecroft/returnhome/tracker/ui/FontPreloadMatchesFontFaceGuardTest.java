package ninja.samryecroft.returnhome.tracker.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * T348: the layout head preloads the web font. A preload only helps if the URL it names is
 * byte-identical to the one the browser actually requests - otherwise it fetches the file a SECOND
 * time and the "optimisation" doubles the download instead of removing a round trip.
 *
 * <p>The real font request comes from {@code app.css}'s {@code @font-face src: url(...)}, which is a
 * plain static path Thymeleaf never fingerprints - so the preload is deliberately a plain href too,
 * NOT a {@code @{...}} expression (which would resolve to the content-hashed URL and never match).
 * That is invisible to every other test: the page renders, the font loads, nothing throws - it just
 * downloads twice. The only way to catch a drift between the two is to compare the source files,
 * which is what this does. If someone later fingerprints one url, this fails loudly instead of
 * silently regressing the very thing the preload exists to fix.
 */
class FontPreloadMatchesFontFaceGuardTest {

    private static final Path LAYOUT = Path.of("src/main/resources/templates/fragments/layout.html");
    private static final Path APP_CSS = Path.of("src/main/resources/static/css/app.css");

    @Test
    void thePreloadHrefIsByteIdenticalToTheFontFaceUrl() throws IOException {
        String layout = Files.readString(LAYOUT, StandardCharsets.UTF_8);
        String css = Files.readString(APP_CSS, StandardCharsets.UTF_8);

        Matcher preload = Pattern.compile(
                "<link[^>]*rel=\"preload\"[^>]*href=\"([^\"]+\\.woff2)\"[^>]*>").matcher(layout);
        assertThat(preload.find())
                .as("layout.html must carry a <link rel=\"preload\"> for the woff2 font")
                .isTrue();
        String preloadHref = preload.group(1);

        Matcher fontFace = Pattern.compile("src:\\s*url\\(\"([^\"]+\\.woff2)\"").matcher(css);
        assertThat(fontFace.find())
                .as("app.css must declare the font via @font-face src: url(...)")
                .isTrue();
        String fontFaceUrl = fontFace.group(1);

        assertThat(preloadHref)
                .as("the preload href must exactly match the @font-face url, or the font downloads "
                        + "twice - do NOT change one without the other, and do NOT fingerprint either")
                .isEqualTo(fontFaceUrl);

        // The two attributes a font preload cannot work without: fonts are fetched in CORS mode, so
        // a preload missing crossorigin is treated as a separate request and primes nothing.
        String preloadTag = preload.group(0);
        assertThat(preloadTag)
                .as("a font preload must declare as=\"font\" and crossorigin")
                .contains("as=\"font\"")
                .contains("crossorigin");
    }
}
