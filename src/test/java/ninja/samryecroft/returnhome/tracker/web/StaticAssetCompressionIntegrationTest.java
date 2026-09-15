package ninja.samryecroft.returnhome.tracker.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.zip.GZIPInputStream;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * T346: the embedded Tomcat must compress text assets. This is the evidence for the T345 lane-B
 * finding and the guard that keeps it from silently regressing - {@code server.compression.enabled}
 * was unset, and NOBODY NOTICED, because nothing measured the wire. The production comment that was
 * relied on ("Spring Boot very likely enables this...") turned out to be about
 * {@code server.forward-headers-strategy}, a different setting entirely; the only thing that ever
 * actually told us compression was off was a byte count over the wire, so that is what this asserts.
 *
 * <p>It has to run against a REAL server on a real port, not MockMvc: response compression is done
 * by the servlet container, which MockMvc does not exercise at all - a MockMvc test would pass while
 * production shipped everything uncompressed, which is precisely the gap that let this sit. The HTTP
 * client sends its own {@code Accept-Encoding} and does NOT auto-decompress (the JDK client only
 * decodes encodings it added itself), so {@code Content-Encoding} and the compressed byte length are
 * both observable here exactly as a browser would see them.
 *
 * <p>{@code /css/app.css} is the asset the finding was measured on (122,514 bytes uncompressed) and
 * is {@code permitAll} in SecurityConfig, so no login is needed - which also means this test does not
 * depend on any seeded account and is unaffected by the T344 rebuild.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaticAssetCompressionIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void cssIsGzippedWhenTheClientAcceptsItAndDecodesBackToTheOriginal() throws Exception {
        byte[] raw = fetch("identity");
        HttpResponse<byte[]> gzipped = fetchResponse("gzip");

        // The compressed response announces itself and is genuinely smaller on the wire.
        assertThat(gzipped.headers().firstValue("Content-Encoding"))
                .as("a client that accepts gzip must receive a gzip-encoded response")
                .hasValue("gzip");
        assertThat(gzipped.body().length)
                .as("the gzip-encoded body must be smaller than the raw CSS")
                .isLessThan(raw.length);

        // ...and it is the SAME bytes, not a smaller-but-wrong response: gunzip must reproduce raw.
        byte[] decoded;
        try (var in = new GZIPInputStream(new ByteArrayInputStream(gzipped.body()))) {
            decoded = in.readAllBytes();
        }
        assertThat(decoded)
                .as("gunzipping the compressed response must reproduce the original CSS byte-for-byte")
                .isEqualTo(raw);
    }

    @Test
    void cssIsServedUncompressedWhenTheClientDoesNotAcceptCompression() throws Exception {
        HttpResponse<byte[]> identity = fetchResponse("identity");

        // The baseline the finding measured: no Content-Encoding, full size - what every response
        // looked like before T346, and what an old or encoding-refusing client still gets.
        assertThat(identity.headers().firstValue("Content-Encoding"))
                .as("a client that does not accept compression must get the asset uncompressed")
                .isEmpty();
    }

    private byte[] fetch(String acceptEncoding) throws Exception {
        return fetchResponse(acceptEncoding).body();
    }

    private HttpResponse<byte[]> fetchResponse(String acceptEncoding) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/css/app.css"))
                .header("Accept-Encoding", acceptEncoding)
                .GET()
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        return response;
    }
}
