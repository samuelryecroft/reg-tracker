package ninja.samryecroft.returnhome.tracker.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The retention position, as tests: a pack is held in memory, released once, and to the account that
 * asked for it. Everything here is a property somebody could plausibly "improve" away later.
 */
class ExportLinkServiceTest {

    private static final ExportPack PACK = new ExportPack("case-file.zip",
            "zip bytes".getBytes(StandardCharsets.UTF_8), "abc123", "s3cret");

    private final ExportLinkService links = new ExportLinkService();

    @Test
    void aTokenReleasesThePackOnce() {
        String token = links.hold(PACK, 7L);

        assertThat(links.redeem(token, 7L)).contains(PACK);
        // Single use is the point: the second attempt gets nothing, so a token that leaks after the
        // download - out of a browser history, a proxy log - is already spent.
        assertThat(links.redeem(token, 7L)).isEmpty();
    }

    @Test
    void aForwardedLinkDoesNotWorkForSomeoneElse() {
        String token = links.hold(PACK, 7L);

        // Forwarding the URL to a colleague must not become a second, unaudited route to the data.
        assertThat(links.redeem(token, 8L)).isEmpty();
    }

    @Test
    void aTokenOfferedByTheWrongUserIsStillConsumed() {
        String token = links.hold(PACK, 7L);
        links.redeem(token, 8L);

        // Deliberate: the wrong-user attempt burns the token rather than leaving it live to be
        // guessed at again. Losing a legitimate download is the cheaper failure.
        assertThat(links.redeem(token, 7L)).isEmpty();
    }

    @Test
    void anUnknownTokenYieldsNothing() {
        assertThat(links.redeem("not-a-real-token", 7L)).isEmpty();
    }

    @Test
    void tokensAreUnguessableAndDistinct() {
        String first = links.hold(PACK, 7L);
        String second = links.hold(PACK, 7L);

        assertThat(first).isNotEqualTo(second);
        // 32 random bytes, url-safe base64 - long enough that enumeration is not a route in.
        assertThat(first).hasSizeGreaterThanOrEqualTo(42);
    }

    @Test
    void linksLiveFifteenMinutes() {
        // Pinned because the screen shows a countdown against it and the two must agree.
        assertThat(links.lifetime().toMinutes()).isEqualTo(15);
    }
}
