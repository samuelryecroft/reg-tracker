package ninja.samryecroft.returnhome.tracker.theme;

/**
 * The Nocturne accent ramp, computed on the server: one hue in, nine fixed steps out as sRGB hex.
 *
 * <p>Nine <em>fixed</em> lightness/chroma pairs with a single injected hue is the whole of
 * per-supplier branding (design spec §2.2). Because only the hue moves, contrast holds whatever
 * colour a supplier picks - which is a guarantee a stored per-supplier hex could not make, since a
 * supplier choosing a pale tint would put the ink on it below 4.5:1 with nothing to stop them.
 *
 * <p><b>Why this exists in Java at all.</b> CSS can derive the ramp itself with {@code oklch()}, so
 * §R-Q8 treated a server-side ramp as a fallback for old browsers. For the generated {@code .docx}
 * it is not a fallback: there is no browser anywhere in the document path, so the server must
 * produce hex regardless of how the browser-baseline question resolves (§5e, R-Q8 amended).
 *
 * <p><b>This class does not derive hues.</b> One direction each:
 * {@link ThemeService#brandHueOf} owns hex to hue, this owns hue to hex. The sRGB to linear to
 * OKLab path deliberately does not exist here - an unused copy of it sitting beside the encode path
 * is exactly where a double gamma decode hides, and the next person needing a hue would reach for
 * the local helper instead of the shared one, leaving two derivations that nobody chose to have.
 */
public final class AccentRamp {

    /**
     * Lightness and chroma per step, signed off in §5e. Fixed - only the hue is per-supplier.
     *
     * <p>Indexed by step/100 - 1, so index 0 is step 100 and index 8 is step 900.
     */
    private static final double[][] STEPS = {
            {0.975, 0.020}, // 100
            {0.925, 0.045}, // 200
            {0.860, 0.090}, // 300
            {0.775, 0.115}, // 400
            {0.660, 0.125}, // 500
            {0.565, 0.110}, // 600
            {0.460, 0.090}, // 700
            {0.360, 0.070}, // 800
            {0.280, 0.055}, // 900
    };

    /** Ramp step 100: the document's band tint, replacing {@code secondaryColor} (R-Q7). */
    public static final int TINT_STEP = 100;

    /** Ramp step 700: {@code --doc-accent}, replacing {@code primaryColorDark} (D-Q5). */
    public static final int DOC_ACCENT_STEP = 700;

    private AccentRamp() {
    }

    /**
     * One ramp step as {@code #RRGGBB}.
     *
     * <p>These are the <b>unmirrored</b> values, and for the document that is not incidental. Light
     * appearance mirrors the ramp's lightness axis, so in the light block step 700 is L 0.860 -
     * which on paper measures 1.43:1, a near-invisible heading colour in the one artefact that goes
     * into a case file. Paper has no appearance: it is light in both themes, so the document takes
     * these values unmirrored and byte-identical either way (§2.4).
     *
     * @param hue  degrees, any integer; normalised into {@code [0, 360)}
     * @param step one of 100..900 in hundreds
     */
    public static String step(int hue, int step) {
        if (step < 100 || step > 900 || step % 100 != 0) {
            throw new IllegalArgumentException("Ramp step must be 100..900 in hundreds, was " + step);
        }
        double[] lc = STEPS[step / 100 - 1];
        return oklchToHex(lc[0], lc[1], Math.floorMod(hue, 360));
    }

    /**
     * OKLCH to sRGB hex.
     *
     * <p><b>Clamped in LINEAR space, then gamma-encoded, and in that order.</b> The OKLab matrix
     * already yields linear values, so applying the sRGB <em>decode</em> to its output - the natural
     * mistake, and one this project has made once already (§5e F3) - is a double decode that
     * silently corrupts every derived colour. Decode only when the input is a hex string, which is
     * what {@link ThemeService#brandHueOf}'s own decode is for.
     *
     * <p>Clipping is expected here, not a symptom: 797 of the 3240 hue x step combinations fall
     * outside sRGB, almost all in the pale steps. A pale step clips toward white, which only raises
     * its contrast against dark ink, so the clamp is safe as well as required (§5e).
     */
    private static String oklchToHex(double lightness, double chroma, int hue) {
        double hueRadians = Math.toRadians(hue);
        double a = chroma * Math.cos(hueRadians);
        double b = chroma * Math.sin(hueRadians);

        double lRoot = lightness + 0.3963377774 * a + 0.2158037573 * b;
        double mRoot = lightness - 0.1055613458 * a - 0.0638541728 * b;
        double sRoot = lightness - 0.0894841775 * a - 1.2914855480 * b;
        double l = lRoot * lRoot * lRoot;
        double m = mRoot * mRoot * mRoot;
        double s = sRoot * sRoot * sRoot;

        double red = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
        double green = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
        double blue = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s;

        return String.format("#%02X%02X%02X", encode(red), encode(green), encode(blue));
    }

    /** Clamp in linear space first, then gamma-encode - never the other way round. */
    private static int encode(double linearChannel) {
        double clamped = Math.min(1.0, Math.max(0.0, linearChannel));
        double encoded = clamped <= 0.0031308
                ? 12.92 * clamped
                : 1.055 * Math.pow(clamped, 1.0 / 2.4) - 0.055;
        return (int) Math.round(encoded * 255.0);
    }

}
