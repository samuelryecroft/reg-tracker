package ninja.samryecroft.returnhome.tracker.theme;

import java.time.LocalDateTime;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.theme.dto.UpdateThemeForm;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
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

    private static final double DARKEN_FACTOR = 0.8;
    private static final String DEFAULT_PRIMARY = "#F36E2A";
    private static final String DEFAULT_SECONDARY = "#FFF0DD";

    private final ThemeSettingsRepository themeSettingsRepository;
    private final OrganisationRepository organisationRepository;
    private final HomeRepository homeRepository;

    public ThemeService(ThemeSettingsRepository themeSettingsRepository,
            OrganisationRepository organisationRepository, HomeRepository homeRepository) {
        this.themeSettingsRepository = themeSettingsRepository;
        this.organisationRepository = organisationRepository;
        this.homeRepository = homeRepository;
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

    @Transactional
    public void updateFor(AppUserPrincipal principal, UpdateThemeForm form) {
        requireCanEditOwnTheme(principal);
        ThemeSettings settings = resolveOrCreateForEditing(editableOrganisationId(principal));
        settings.setPrimaryColor(form.getPrimaryColor());
        settings.setSecondaryColor(form.getSecondaryColor());
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
        // HOME_STAFF have no organisation of their own - it's derived through their home.
        if (principal.getHomeId() != null) {
            return homeRepository.findSupplierOrganisationIdByHomeId(principal.getHomeId()).orElse(null);
        }
        return null;
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
        return new ThemeView(settings.getPrimaryColor(), darken(settings.getPrimaryColor()), settings.getSecondaryColor());
    }

    private String darken(String hexColor) {
        int r = Integer.parseInt(hexColor.substring(1, 3), 16);
        int g = Integer.parseInt(hexColor.substring(3, 5), 16);
        int b = Integer.parseInt(hexColor.substring(5, 7), 16);
        return String.format("#%02x%02x%02x",
                scale(r), scale(g), scale(b));
    }

    private int scale(int channel) {
        int scaled = (int) Math.round(channel * DARKEN_FACTOR);
        return Math.max(0, Math.min(255, scaled));
    }

    public record ThemeView(String primaryColor, String primaryColorDark, String secondaryColor) {
    }
}
