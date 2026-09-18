package ninja.samryecroft.returnhome.tracker.security.trusteddevice;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The one place that knows the trusted-device cookie's name and attributes (design §4). Every issue,
 * clear and read goes through here so the attributes cannot drift between the code that sets the
 * cookie and the code that reads it - the same discipline as {@code TokenHashing} for the hash.
 *
 * <p><b>Attributes, and why each is what it is (design §4):</b>
 * <ul>
 *   <li>{@code HttpOnly} - the token is a bearer credential; script must never read it (XSS defence).</li>
 *   <li>{@code SameSite=Lax}, not {@code Strict} - the product sends people in by emailed link, and
 *       {@code Strict} would drop the cookie on exactly that top-level navigation, producing a code
 *       demand that looks like the feature being broken.</li>
 *   <li>{@code Path=/} - the waiver is account-wide, not scoped to one path.</li>
 *   <li>{@code Max-Age} = the token's absolute expiry, so the browser forgets it on the same date the
 *       server does. Never a rolling max-age - expiry is absolute (design §4).</li>
 *   <li>{@code Secure} - sent only over HTTPS. Defaults on (fail-secure); a plain-HTTP local
 *       environment sets {@code app.security.trusted-device.cookie-secure=false}.</li>
 * </ul>
 *
 * <p>Never put the user id in the cookie - the row holds {@code user_id}, the cookie holds only the
 * opaque token, so the cookie is neither an enumeration surface nor a thing to tamper with (§4).
 */
@Component
public class TrustedDeviceCookie {

    /** The cookie name. One constant, referenced by build, clear and read alike. */
    public static final String NAME = "RHT_TRUSTED_DEVICE";

    private final boolean secure;

    public TrustedDeviceCookie(
            @Value("${app.security.trusted-device.cookie-secure:true}") boolean secure) {
        this.secure = secure;
    }

    /**
     * The cookie carrying a freshly minted or rotated token. {@code maxAge} is the time remaining to
     * the token's absolute expiry, so the browser and the server forget it together.
     */
    public ResponseCookie build(String token, Duration maxAge) {
        return base(token).maxAge(maxAge).build();
    }

    /**
     * The cookie that clears the trust from the browser: same name and path, empty value, {@code
     * Max-Age=0}. The server-side row is revoked separately; this only removes the browser's copy.
     */
    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    /** The token presented by the browser, if this cookie is present. */
    public Optional<String> readToken(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> NAME.equals(cookie.getName()))
                .map(jakarta.servlet.http.Cookie::getValue)
                .filter(value -> value != null && !value.isEmpty())
                .findFirst();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/");
    }
}
