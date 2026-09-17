package ninja.samryecroft.returnhome.tracker.config;

import static org.assertj.core.api.Assertions.assertThat;

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
 * - HttpOnly: true (XSS defense)
 * - SameSite=Lax (allows safe cross-site requests like email-link navigation)
 * - Secure: true in production profiles only (HTTPS only)
 *
 * CRITICAL: This test runs on a RANDOM_PORT with a real Tomcat instance, not MockMvc.
 * Tomcat's cookie processor serializes SameSite only in a real HTTP response; MockMvc's
 * MockHttpServletResponse does not. The test is armed by: (1) verify red on today's config
 * (SameSite NOT SET), (2) apply the diff and verify green (SameSite=Lax).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionCookieAttributesTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate = new RestTemplate();

    @Test
    void jsessionidCookieIncludesRequiredAttributes() throws Exception {
        // POST to /login with invalid credentials to trigger session creation.
        String url = "http://localhost:" + port + "/login?username=invalid&password=invalid";
        ResponseEntity<String> response = restTemplate.postForEntity(url, null, String.class);

        HttpHeaders headers = response.getHeaders();
        java.util.List<String> setCookieHeaders = headers.get(HttpHeaders.SET_COOKIE);
        
        assertThat(setCookieHeaders).as("Response must contain Set-Cookie headers")
                .isNotEmpty();
        
        Optional<String> jsessionidCookie = setCookieHeaders.stream()
                .filter(h -> h.contains("JSESSIONID"))
                .findFirst();

        assertThat(jsessionidCookie).as("Response must contain JSESSIONID cookie")
                .isPresent();
        
        String cookie = jsessionidCookie.get();
        
        assertThat(cookie).as("JSESSIONID cookie must include HttpOnly")
                .contains("HttpOnly");
        
        // CRITICAL: SameSite=Lax is required for email-based auth flows.
        // Will FAIL on today's config (SameSite NOT SET), PASS after diff is applied.
        assertThat(cookie).as("JSESSIONID cookie must include SameSite=Lax for email-link flows")
                .contains("SameSite=Lax");
    }
}
