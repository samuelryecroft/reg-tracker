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
 * <p>Shared deliberately. Pam's CSS half derives {@code --brand-hue} from the same
 * {@code primaryColor} this reads, and the two must agree exactly - so {@link #hueFrom} is the one
 * place the rounding happens, and an {@code int} degree is what travels from here on.
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

    /**
     * Below this chroma a colour is effectively grey and its hue is rounding noise, so deriving a
     * brand hue from it would amplify that noise into an arbitrary colour (§2.2).
     */
    private static final double GREY_CHROMA_FLOOR = 0.02;

    /** The neutral hue a grey falls back to - Nocturne's own neutral (§2.2). */
    public static final int NEUTRAL_HUE = 265;

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
     * The brand hue of a stored {@code #RRGGBB} colour, per the normative derivation in §2.2.
     *
     * <p>Rounded to a whole degree <b>here and nowhere else</b>. Two implementations that each round
     * their own way disagree by a degree on some colours, and a document whose accent is one degree
     * off the screen's is the kind of defect nobody can describe and everybody can see.
     */
    public static int hueFrom(String hexColor) {
        double[] rgb = linearFromHex(hexColor);
        double[] lab = linearRgbToOklab(rgb[0], rgb[1], rgb[2]);
        double a = lab[1];
        double b = lab[2];
        if (Math.hypot(a, b) < GREY_CHROMA_FLOOR) {
            return NEUTRAL_HUE;
        }
        return Math.floorMod(Math.round((float) Math.toDegrees(Math.atan2(b, a))), 360);
    }

    /**
     * OKLCH to sRGB hex.
     *
     * <p><b>Clamped in LINEAR space, then gamma-encoded, and in that order.</b> The OKLab matrix
     * already yields linear values, so applying the sRGB <em>decode</em> to its output - the natural
     * mistake, and one this project has made once already (§5e F3) - is a double decode that
     * silently corrupts every derived colour. Decode only when the input is a hex string, which is
     * what {@link #linearFromHex} is for.
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

    /** Hex to linear sRGB. This is the one direction that legitimately gamma-decodes. */
    private static double[] linearFromHex(String hexColor) {
        String hex = hexColor == null ? "" : hexColor.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (hex.length() != 6) {
            throw new IllegalArgumentException("Expected a #RRGGBB colour, was: " + hexColor);
        }
        double[] linear = new double[3];
        for (int channel = 0; channel < 3; channel++) {
            int value = Integer.parseInt(hex.substring(channel * 2, channel * 2 + 2), 16);
            double normalised = value / 255.0;
            linear[channel] = normalised <= 0.04045
                    ? normalised / 12.92
                    : Math.pow((normalised + 0.055) / 1.055, 2.4);
        }
        return linear;
    }

    private static double[] linearRgbToOklab(double red, double green, double blue) {
        double l = 0.4122214708 * red + 0.5363325363 * green + 0.0514459929 * blue;
        double m = 0.2119034982 * red + 0.6806995451 * green + 0.1073969566 * blue;
        double s = 0.0883024619 * red + 0.2817188376 * green + 0.6299787005 * blue;
        double lRoot = Math.cbrt(l);
        double mRoot = Math.cbrt(m);
        double sRoot = Math.cbrt(s);
        return new double[] {
                0.2104542553 * lRoot + 0.7936177850 * mRoot - 0.0040720468 * sRoot,
                1.9779984951 * lRoot - 2.4285922050 * mRoot + 0.4505937099 * sRoot,
                0.0259040371 * lRoot + 0.7827717662 * mRoot - 0.8086757660 * sRoot,
        };
    }
}
