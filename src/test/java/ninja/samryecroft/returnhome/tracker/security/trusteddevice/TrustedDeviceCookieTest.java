package ninja.samryecroft.returnhome.tracker.security.trusteddevice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

/**
 * T365 stage 1 guard: the trusted-device cookie carries the attributes the design fixes (§4). A pure
 * unit test - the cookie's attributes are decided in code, not by the container, so this needs no
 * Spring context and no database.
 */
class TrustedDeviceCookieTest {

    @Test
    void theIssuedCookieIsHttpOnlyLaxRootScopedAndBoundedToTheTokensExpiry() {
        TrustedDeviceCookie cookie = new TrustedDeviceCookie(true);

        ResponseCookie issued = cookie.build("a-token", Duration.ofDays(30));

        assertThat(issued.getName())
                .as("one place owns the cookie name")
                .isEqualTo(TrustedDeviceCookie.NAME);
        assertThat(issued.isHttpOnly())
                .as("the token is a bearer credential - script must never read it (XSS defence)")
                .isTrue();
        assertThat(issued.getSameSite())
                .as("SameSite=Lax, not Strict: the product sends people in by emailed link and Strict "
                        + "would drop the cookie on that navigation")
                .isEqualTo("Lax");
        assertThat(issued.getPath())
                .as("the waiver is account-wide, so the cookie is root-scoped")
                .isEqualTo("/");
        assertThat(issued.getMaxAge())
                .as("Max-Age tracks the token's absolute expiry so browser and server forget it together")
                .isEqualTo(Duration.ofDays(30));
    }

    @Test
    void theCookieIsSecureByDefaultAndOnlyPlainHttpOptsOut() {
        assertThat(new TrustedDeviceCookie(true).build("t", Duration.ofDays(1)).isSecure())
                .as("Secure defaults on (fail-secure) - the token travels only over HTTPS")
                .isTrue();
        assertThat(new TrustedDeviceCookie(false).build("t", Duration.ofDays(1)).isSecure())
                .as("only a plain-HTTP local environment turns Secure off")
                .isFalse();
    }

    @Test
    void theClearingCookieExpiresImmediatelyWithTheSameNameAndPath() {
        ResponseCookie cleared = new TrustedDeviceCookie(true).clear();

        assertThat(cleared.getName()).isEqualTo(TrustedDeviceCookie.NAME);
        assertThat(cleared.getPath()).isEqualTo("/");
        assertThat(cleared.getMaxAge())
                .as("Max-Age=0 removes the browser's copy")
                .isEqualTo(Duration.ZERO);
    }
}
