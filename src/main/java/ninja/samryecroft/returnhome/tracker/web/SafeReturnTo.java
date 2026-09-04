package ninja.samryecroft.returnhome.tracker.web;

/**
 * Guards a {@code returnTo} request parameter against open redirect (T138 1b, extracted in 1c once
 * a second controller - the name-reveal toggle - needed the exact same guard). {@code returnTo} is
 * wherever a same-origin control (the appearance toggle, the reveal-names button) was clicked from
 * - not attacker-controlled in the normal case, but still a request parameter anyone can set
 * directly, and a value like {@code //evil.example} or {@code https://evil.example} would send a
 * just-authenticated POST's redirect off this app entirely.
 */
final class SafeReturnTo {

    private SafeReturnTo() {}

    /**
     * Only ever redirects somewhere that starts with a single {@code /}.
     *
     * <p>Also refuses a backslash right after that leading slash (Kevin's review, PR #29): {@code
     * /\evil.example} passes a naive "starts with one '/', not '//'" check, but browsers following
     * the WHATWG URL spec treat {@code \} as equivalent to {@code /} for http/https - so Chrome,
     * Firefox and Edge all resolve that path to {@code https://evil.example} regardless, the exact
     * protocol-relative redirect this guard exists to stop. {@code \} is legal in a query string
     * (and {@code %5C} decodes to it), so this is reachable, not theoretical.
     */
    static String of(String returnTo) {
        if (returnTo == null || returnTo.isBlank() || !returnTo.startsWith("/")) {
            return "/";
        }
        if (returnTo.length() > 1 && (returnTo.charAt(1) == '/' || returnTo.charAt(1) == '\\')) {
            return "/";
        }
        return returnTo;
    }
}
