package ninja.samryecroft.returnhome.tracker.theme;

import java.time.LocalDateTime;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.theme.dto.UpdateThemeForm;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import ninja.samryecroft.returnhome.tracker.user.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brand colours belong to a Supplier org (the one running the interview - e.g. STEPS with Children
 * or Greyhams Consulting), the same way each supplier's real paper forms carry their own letterhead.
 * A Care Provider org, and everyone in it, simply follows the Supplier that services them. The
 * platform ADMIN role, and any Supplier org that has no theme row of its own yet, falls back to a
 * single platform-default row (the one with {@code organisation_id IS NULL}).
 */
@Service
public class ThemeService {

    /** WCAG 1.4.3 AA for normal-weight text. Every derived/chosen colour below targets this. */
    private static final double MIN_CONTRAST = 4.5;
    private static final String WHITE = "#FFFFFF";
    private static final String INK = "#1F2328";
    private static final String DEFAULT_PRIMARY = "#F36E2A";
    private static final String DEFAULT_SECONDARY = "#FFF0DD";

    private final ThemeSettingsRepository themeSettingsRepository;
    private final OrganisationRepository organisationRepository;
    private final HomeRepository homeRepository;
    private final UserRepository userRepository;

    public ThemeService(ThemeSettingsRepository themeSettingsRepository,
            OrganisationRepository organisationRepository, HomeRepository homeRepository,
            UserRepository userRepository) {
        this.themeSettingsRepository = themeSettingsRepository;
        this.organisationRepository = organisationRepository;
        this.homeRepository = homeRepository;
        this.userRepository = userRepository;
    }

    /** The theme that should apply to everything the given principal sees: nav, buttons, tables. */
    public ThemeView getEffectiveFor(AppUserPrincipal principal) {
        return toView(resolveForViewing(resolveSupplierOrgId(principal)));
    }

    /** The theme that should apply to a report about a child in the given Care Provider org's care. */
    public ThemeView getForCareProviderOrg(Long careProviderOrgId) {
        Long supplierOrgId = organisationRepository.findSupplierOrganisationIdByCareProviderId(careProviderOrgId)
                .orElse(null);
        return toView(resolveForViewing(supplierOrgId));
    }

    public ThemeView getPlatformDefault() {
        return toView(platformDefault());
    }

    public boolean canEditOwnTheme(AppUserPrincipal principal) {
        return principal != null && (principal.hasRole(Role.ADMIN)
                || (principal.hasRole(Role.ORG_ADMIN) && principal.getOrganisationType() == OrgType.SUPPLIER));
    }

    /** The theme belonging to the org the principal is themselves allowed to edit. */
    public ThemeView getOwnFor(AppUserPrincipal principal) {
        requireCanEditOwnTheme(principal);
        return toView(resolveForViewing(editableOrganisationId(principal)));
    }

    /**
     * D-3a-5 (spec §7j): the consequence of changing this colour, as a number rather than the
     * vague plural it replaces - "and for every Care Provider org you serve" said nothing about
     * how many. Meaningless for the platform default (there is no supplier org to count care
     * providers under), so callers must gate this on {@code !platformWide} themselves; the count
     * for a platform-admin editing session ({@code editableOrganisationId} returning null) is 0.
     */
    public int careProviderCountFor(AppUserPrincipal principal) {
        requireCanEditOwnTheme(principal);
        Long organisationId = editableOrganisationId(principal);
        return organisationId == null ? 0
                : organisationRepository.findBySupplierOrganisationIdOrderByName(organisationId).size();
    }

    @Transactional
    public void updateFor(AppUserPrincipal principal, UpdateThemeForm form) {
        requireCanEditOwnTheme(principal);
        ThemeSettings settings = resolveOrCreateForEditing(editableOrganisationId(principal));
        settings.setPrimaryColor(form.getPrimaryColor());
        // T186: the secondary colour is no longer collected or read, so it is not written here.
        // This is an UPDATE of a row that already satisfies the NOT NULL column; the INSERT paths
        // (ensureThemeExistsFor below, DemoDataSeeder) still set it and must keep doing so.
        settings.setUpdatedAt(LocalDateTime.now());
        themeSettingsRepository.save(settings);
    }

    /** Called when a new Supplier org is created, so it starts with its own (default-coloured) theme. */
    @Transactional
    public void ensureThemeExistsFor(Organisation supplierOrganisation) {
        if (themeSettingsRepository.findByOrganisationId(supplierOrganisation.getId()).isPresent()) {
            return;
        }
        ThemeSettings settings = new ThemeSettings();
        settings.setOrganisation(supplierOrganisation);
        settings.setPrimaryColor(DEFAULT_PRIMARY);
        settings.setSecondaryColor(DEFAULT_SECONDARY);
        settings.setUpdatedAt(LocalDateTime.now());
        themeSettingsRepository.save(settings);
    }

    private void requireCanEditOwnTheme(AppUserPrincipal principal) {
        if (!canEditOwnTheme(principal)) {
            throw new AccessDeniedException("You do not have a brand theme to edit");
        }
    }

    private Long editableOrganisationId(AppUserPrincipal principal) {
        return principal.hasRole(Role.ADMIN) ? null : principal.getOrganisationId();
    }

    /** Which Supplier org's theme applies to this principal - null means the platform default. */
    private Long resolveSupplierOrgId(AppUserPrincipal principal) {
        if (principal.hasRole(Role.ADMIN)) {
            return null;
        }
        OrgType orgType = principal.getOrganisationType();
        if (orgType == OrgType.SUPPLIER) {
            return principal.getOrganisationId();
        }
        if (orgType == OrgType.CARE_PROVIDER) {
            return organisationRepository.findSupplierOrganisationIdByCareProviderId(principal.getOrganisationId())
                    .orElse(null);
        }
        // HOME_STAFF have no organisation of their own - it's derived through their homes. Any one
        // of them answers this: UserService enforces that a user's homes all sit under the same
        // Care Provider organisation, so they cannot disagree about which Supplier to brand as.
        return userRepository.findHomeIds(principal.getUserId()).stream()
                .findFirst()
                .flatMap(homeRepository::findSupplierOrganisationIdByHomeId)
                .orElse(null);
    }

    private ThemeSettings resolveForViewing(Long organisationId) {
        if (organisationId == null) {
            return platformDefault();
        }
        return themeSettingsRepository.findByOrganisationId(organisationId).orElseGet(this::platformDefault);
    }

    /** Unlike {@link #resolveForViewing}, this creates (but doesn't yet save) a row scoped to the
     * given org if one is missing, so an edit never silently overwrites the platform default. */
    private ThemeSettings resolveOrCreateForEditing(Long organisationId) {
        if (organisationId == null) {
            return platformDefault();
        }
        return themeSettingsRepository.findByOrganisationId(organisationId)
                .orElseGet(() -> {
                    ThemeSettings settings = new ThemeSettings();
                    settings.setOrganisation(organisationRepository.getReferenceById(organisationId));
                    return settings;
                });
    }

    private ThemeSettings platformDefault() {
        return themeSettingsRepository.findByOrganisationIsNull()
                .orElseThrow(() -> new IllegalStateException("Platform default theme is missing"));
    }

    private ThemeView toView(ThemeSettings settings) {
        String primary = settings.getPrimaryColor();
        // The brand hue is derived once, here, and the integer is what travels - see
        // AccentRamp.hueFrom. Pam's CSS half reads the same primaryColor for --brand-hue, so
        // rounding in two places would let the document and the screen differ by a degree.
        //
        // T186: settings.getSecondaryColor() is deliberately not read. The column still exists and
        // is still written on insert (it is NOT NULL), but nothing derives anything from it any
        // more - see ThemeSettings.getSecondaryColor.
        int brandHue = AccentRamp.hueFrom(primary);
        return new ThemeView(primary, brandHue,
                AccentRamp.step(brandHue, AccentRamp.TINT_STEP),
                AccentRamp.step(brandHue, AccentRamp.DOC_ACCENT_STEP));
    }

    /**
     * FE-01, and the same fix the generated .docx header bar needs (Creed's docx-format-review.md
     * finding 1 - a shipped report measured 1.98:1, white text hardcoded over a supplier accent fill).
     * A background that <em>is</em> the brand colour can't be fixed by darkening a text token - the
     * foreground has to be chosen per theme. The rule: pick ink or white, whichever reads better
     * against the accent. No supplier colour can produce an unreadable result either surface.
     *
     * <p>Public and static on purpose: this is the single shared decision point for "what text colour
     * goes on this accent fill", called from {@link #toView} for the UI button and (separately, by
     * whoever wires up the docx header-bar token) from {@code DocxReportGenerator}. Don't reimplement
     * this logic a second time there - call this method.
     *
     * @param accentHex a 6-digit hex colour, with or without a leading {@code #} (e.g. {@code "#F36E2A"}
     *                   or {@code "F36E2A"})
     * @return {@code "#1F2328"} (ink) or {@code "#FFFFFF"} (white), whichever clears more contrast
     *         against {@code accentHex} - always with a leading {@code #}; strip it yourself if the
     *         caller's convention (like the docx token replacement) doesn't use one
     */
    public static String readableForegroundOn(String accentHex) {
        String accentColor = accentHex.startsWith("#") ? accentHex : "#" + accentHex;
        double accentLuminance = relativeLuminance(hexToRgb(accentColor));
        double inkContrast = contrastRatio(accentLuminance, relativeLuminance(hexToRgb(INK)));
        double whiteContrast = contrastRatio(accentLuminance, relativeLuminance(hexToRgb(WHITE)));
        return inkContrast >= whiteContrast ? INK : WHITE;
    }

    static int[] hexToRgb(String hexColor) {
        return new int[] {
                Integer.parseInt(hexColor.substring(1, 3), 16),
                Integer.parseInt(hexColor.substring(3, 5), 16),
                Integer.parseInt(hexColor.substring(5, 7), 16)
        };
    }

    /** WCAG relative luminance (sRGB), the basis of the 1.4.3 contrast-ratio formula. */
    static double relativeLuminance(int[] rgb) {
        double r = channelLuminance(rgb[0]);
        double g = channelLuminance(rgb[1]);
        double b = channelLuminance(rgb[2]);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channelLuminance(int channel) {
        double normalised = channel / 255.0;
        return normalised <= 0.03928 ? normalised / 12.92 : Math.pow((normalised + 0.055) / 1.055, 2.4);
    }

    static double contrastRatio(double luminanceA, double luminanceB) {
        double lighter = Math.max(luminanceA, luminanceB);
        double darker = Math.min(luminanceA, luminanceB);
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double[] toHsl(String hexColor) {
        int[] rgb = hexToRgb(hexColor);
        double r = rgb[0] / 255.0, g = rgb[1] / 255.0, b = rgb[2] / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double lightness = (max + min) / 2.0;
        double hue;
        double saturation;
        if (max == min) {
            hue = 0;
            saturation = 0;
        } else {
            double delta = max - min;
            saturation = lightness > 0.5 ? delta / (2.0 - max - min) : delta / (max + min);
            if (max == r) {
                hue = ((g - b) / delta) + (g < b ? 6 : 0);
            } else if (max == g) {
                hue = ((b - r) / delta) + 2;
            } else {
                hue = ((r - g) / delta) + 4;
            }
            hue *= 60;
        }
        return new double[] { hue, saturation * 100, lightness * 100 };
    }

    private static String fromHsl(double hue, double saturationPct, double lightnessPct) {
        double s = saturationPct / 100.0;
        double l = lightnessPct / 100.0;
        if (s == 0) {
            int gray = (int) Math.round(l * 255);
            return String.format("#%02x%02x%02x", gray, gray, gray);
        }
        double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
        double p = 2 * l - q;
        double h = hue / 360.0;
        int r = (int) Math.round(hueToRgb(p, q, h + 1.0 / 3.0) * 255);
        int g = (int) Math.round(hueToRgb(p, q, h) * 255);
        int b = (int) Math.round(hueToRgb(p, q, h - 1.0 / 3.0) * 255);
        return String.format("#%02x%02x%02x", clamp(r), clamp(g), clamp(b));
    }

    private static double hueToRgb(double p, double q, double t) {
        double tt = t;
        if (tt < 0) tt += 1;
        if (tt > 1) tt -= 1;
        if (tt < 1.0 / 6.0) return p + (q - p) * 6 * tt;
        if (tt < 1.0 / 2.0) return q;
        if (tt < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - tt) * 6;
        return p;
    }

    private static int clamp(int channel) {
        return Math.max(0, Math.min(255, channel));
    }

    /**
     * T186 removed three components - {@code primaryColorDark}, {@code secondaryColor} and
     * {@code primaryColorInk}. All three existed to feed the per-org inline {@code <style>} block in
     * {@code fragments/layout.html}, which was their only consumer; deleting that block left them
     * with none. {@code readableForegroundOn}, which produced {@code primaryColorInk}, is
     * <b>kept</b>: {@code DocxReportGenerator} calls it statically and the generated report's header
     * contrast depends on it.
     *
     * @param primaryColor the supplier's stored brand colour, and the source of {@link #brandHue}
     * @param brandHue     the one number per-supplier branding actually stores, in degrees
     * @param accentTint   ramp step 100 - the document's band fill, replacing secondaryColor
     * @param docAccent    ramp step 700 - the document's heading colour, replacing primaryColorDark
     */
    public record ThemeView(String primaryColor, int brandHue, String accentTint, String docAccent) {
    }
}
