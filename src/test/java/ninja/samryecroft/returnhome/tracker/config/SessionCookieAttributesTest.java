package ninja.samryecroft.returnhome.tracker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * T373: guard the session cookie policy. The JSESSIONID cookie must include:
 * <ul>
 *   <li>HttpOnly: true (XSS defence)</li>
 *   <li>SameSite=Lax (allows safe cross-site navigation like email-link auth flows)</li>
 *   <li>Secure: true in production profiles only (HTTPS) - not asserted here, since the test
 *       runs on plain HTTP; that attribute is proven by the azure profile config, not this probe.</li>
 * </ul>
 *
 * <p>CRITICAL: this runs on a RANDOM_PORT with a real Tomcat, not MockMvc. Tomcat serialises
 * SameSite only in a real HTTP response; MockMvc's MockHttpServletResponse does not.
 *
 * <p><b>The probe is a GET of the login page, not a POST.</b> POST /login is CSRF-protected, so a
 * tokenless POST is a 403 that never reaches the cookie - a wrong-reason red that proves nothing.
 * A GET is CSRF-free and is how a browser first meets the app; Spring Security renders a CSRF token
 * on that page, which forces session creation, so the JSESSIONID cookie is set.
 *
 * <p><b>The two possible reds are kept apart on purpose</b>, because they mean opposite things:
 * no JSESSIONID at all means the probe failed to create a session (a probe bug, stop and fix the
 * probe); a JSESSIONID without SameSite is the real finding this guard exists for. Each has its own
 * assertion and message so the CI log says which happened without anyone guessing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionCookieAttributesTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate = new RestTemplate();

    @Test
    void jsessionidCookieIncludesRequiredAttributes() {
        // GET the login page: CSRF-free, and rendering it generates a CSRF token which forces
        // session creation, so the JSESSIONID cookie is set. getForEntity does not throw on 2xx.
        String url = "http://localhost:" + port + "/login";
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        List<String> setCookieHeaders = response.getHeaders().get(HttpHeaders.SET_COOKIE);

        // WRONG-REASON RED: no Set-Cookie at all means GET /login did not create a session, so the
        // guard cannot say anything about SameSite. This is a probe bug, not a policy finding - fix
        // the probe, do not touch the cookie config.
        assertThat(setCookieHeaders)
                .as("probe did not create a session: GET /login returned no Set-Cookie - fix the probe, not the policy")
                .isNotEmpty();

        // Pick the JSESSIONID header specifically, not whichever Set-Cookie happens to be first.
        Optional<String> jsessionidCookie = setCookieHeaders.stream()
                .filter(header -> header.contains("JSESSIONID"))
                .findFirst();

        // WRONG-REASON RED (same family): a Set-Cookie without a JSESSIONID is still "no session
        // was created" as far as this guard is concerned.
        assertThat(jsessionidCookie)
                .as("probe did not create a session: no JSESSIONID in the Set-Cookie headers - fix the probe, not the policy")
                .isPresent();

        String cookie = jsessionidCookie.get();

        assertThat(cookie)
                .as("JSESSIONID must be HttpOnly (XSS defence)")
                .contains("HttpOnly");

        // THE REAL GUARD: the session cookie exists but carries no SameSite. This is the finding -
        // today's config does not set it, so browsers fall back to their default, which can change
        // under us and is not explicit for the email-link auth flows (password reset, second-factor).
        assertThat(cookie)
                .as("JSESSIONID must include SameSite=Lax for email-link auth flows (password reset, second-factor)")
                .contains("SameSite=Lax");
    }
}
