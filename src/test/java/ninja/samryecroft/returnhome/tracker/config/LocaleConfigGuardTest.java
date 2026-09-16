package ninja.samryecroft.returnhome.tracker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Guard: the application must pin its locale to Locale.UK (en-GB) so all viewers see the same date
 * formatting regardless of browser or system locale. The LocaleConfig bean provides a
 * FixedLocaleResolver that returns Locale.UK unconditionally.
 *
 * <p>This guard has two parts: (1) verify the bean exists and is configured, (2) verify it resolves
 * to the correct locale. A removed or changed config is caught at startup.
 */
/*
 * T360 correction (Creed): this needed `extends AbstractIntegrationTest`. A bare @SpringBootTest
 * starts the whole context, which starts Flyway, which needs a database - and without the base
 * class there is no Testcontainers Postgres, so it reached for a local one and failed with
 * "password authentication failed for user tracker". All three tests errored on context load.
 *
 * Worth stating because the failure does not look like its cause: NOTHING ABOUT THE MESSAGE
 * MENTIONS THE MISSING BASE CLASS, and the same three tests would pass on a developer machine that
 * happens to have a matching local database running - which is precisely the class of defect
 * T358 just closed, arriving from the other direction.
 */
@SpringBootTest
class LocaleConfigGuardTest extends ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest {

    @Autowired
    private LocaleResolver localeResolver;

    @Test
    void localeResolverIsPresentAndConfigured() {
        assertThat(localeResolver).as("LocaleResolver bean must be present").isNotNull();
    }

    @Test
    void localeResolverReturnsUK() {
        Locale resolved = localeResolver.resolveLocale(null);
        assertThat(resolved)
                .as("LocaleResolver must return Locale.UK (en-GB) unconditionally")
                .isEqualTo(Locale.UK);
    }

    @Test
    void localeConfigSourceCodePinsToLocaleUK() throws IOException {
        // Verify that LocaleConfig.java explicitly uses Locale.UK
        Path configFile = Path.of("src/main/java/ninja/samryecroft/returnhome/tracker/config/LocaleConfig.java");
        String content = Files.readString(configFile, StandardCharsets.UTF_8);

        assertThat(content)
                .as("LocaleConfig must explicitly create FixedLocaleResolver with Locale.UK")
                .contains("new FixedLocaleResolver(Locale.UK)");
    }
}
