package ninja.samryecroft.returnhome.tracker.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;

/**
 * T119 4e: "branding set" must mean someone CHOSE a colour, not that a theme row exists.
 *
 * <p><b>The defect this pins is a flag that was true in every state.</b> The tree's first version
 * asked whether the supplier had a {@link ThemeSettings} row - and
 * {@link ThemeService#ensureThemeExistsFor} gives every supplier a default-coloured one the moment
 * it is created, so the answer was always yes. The line rendered "branding set" for everybody, in
 * the one slot on that row meant to tell a platform admin whether a supplier has actually been set
 * up. <b>A label that cannot be false is not a weak signal; it is not a signal.</b>
 *
 * <p>It is also the failure mode a test is worst at catching by accident: the screen looked
 * finished, nothing threw, and the only way to notice was to ask what the sentence would say for a
 * supplier that had never been touched. So the case that matters here is the <em>negative</em> one,
 * and it is first.
 */
class ChosenBrandingPredicateTest {

    private final ThemeSettingsRepository themeSettingsRepository = mock(ThemeSettingsRepository.class);

    private final ThemeService themeService = new ThemeService(themeSettingsRepository,
            mock(OrganisationRepository.class), mock(HomeRepository.class), mock(UserRepository.class));

    /** Organisation has no id setter - the identity column is assigned on persist. */
    private static Organisation org(long id) {
        Organisation organisation = new Organisation();
        try {
            Field idField = Organisation.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(organisation, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Organisation.id moved or was renamed", e);
        }
        return organisation;
    }

    private static ThemeSettings theme(Organisation organisation, String primaryColor) {
        ThemeSettings settings = new ThemeSettings();
        settings.setOrganisation(organisation);
        settings.setPrimaryColor(primaryColor);
        return settings;
    }

    @Test
    void aSupplierLeftOnTheDefaultColourDoesNotCountAsBranded() {
        // Exactly what ensureThemeExistsFor writes at creation, so this is the state EVERY
        // untouched supplier is in - and the state the first version reported as "branding set".
        when(themeSettingsRepository.findAll()).thenReturn(List.of(theme(org(1L), "#F36E2A")));

        assertThat(themeService.organisationIdsWithChosenBranding())
                .as("every supplier gets a default-coloured theme row at creation, so a predicate "
                        + "that asks whether a row exists is true for all of them and tells a "
                        + "platform admin nothing about whether anyone has set the supplier up")
                .isEmpty();
    }

    @Test
    void aSupplierWhoseColourWasChangedCountsAsBranded() {
        when(themeSettingsRepository.findAll()).thenReturn(List.of(
                theme(org(1L), "#F36E2A"),
                theme(org(2L), "#1B5E8C")));

        assertThat(themeService.organisationIdsWithChosenBranding()).containsExactly(2L);
    }

    @Test
    void caseAndNullsDoNotChangeTheAnswer() {
        // Hex is case-insensitive and the picker's casing is not a design decision, so "#f36e2a"
        // is still the default. A null colour is not a chosen one either - it is a missing one.
        when(themeSettingsRepository.findAll()).thenReturn(List.of(
                theme(org(1L), "#f36e2a"),
                theme(org(2L), null),
                theme(null, "#1B5E8C")));

        assertThat(themeService.organisationIdsWithChosenBranding())
                .as("a lower-case default is still the default; a null colour is missing, not "
                        + "chosen; and the platform-default theme row has no organisation at all")
                .isEqualTo(Set.of());
    }
}
