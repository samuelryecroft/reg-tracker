package ninja.samryecroft.returnhome.tracker.organisation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.document.DocumentStorageProperties;
import ninja.samryecroft.returnhome.tracker.document.KeyHandle;
import ninja.samryecroft.returnhome.tracker.document.KeyProvider;
import ninja.samryecroft.returnhome.tracker.document.KeyUnavailableException;
import ninja.samryecroft.returnhome.tracker.organisation.dto.CreateOrganisationForm;
import ninja.samryecroft.returnhome.tracker.theme.ThemeService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * T168 Part A(a): creating a CARE_PROVIDER whose per-organisation KEK is not yet provisioned surfaces
 * an actionable onboarding notice to the admin - moving discovery of the gap from a confusing failure
 * in front of a client (the org-2 P0) to the moment of onboarding. It is advisory: creation still
 * succeeds, and the encrypt path still fail-closes at write time if the key is genuinely absent. The
 * probe runs only where the provider cannot create keys (auto-create disabled); where it can, the
 * write path provisions the key anyway, so there is nothing to warn about.
 */
class OrganisationAdminControllerPreflightTest {

    private final OrganisationRepository repository = mock(OrganisationRepository.class);
    private final ThemeService themeService = mock(ThemeService.class);
    private final KeyProvider keyProvider = mock(KeyProvider.class);

    /** The production shape: the app cannot create keys, so the preflight probe is meaningful. */
    private static DocumentStorageProperties propsWithAutoCreate(boolean autoCreate) {
        DocumentStorageProperties props = new DocumentStorageProperties();
        props.getKeyVault().setAutoCreateKeys(autoCreate);
        return props;
    }

    private OrganisationAdminController controllerWithAutoCreate(boolean autoCreate) {
        return new OrganisationAdminController(repository, themeService, keyProvider,
                propsWithAutoCreate(autoCreate));
    }

    private static CreateOrganisationForm careProviderForm() {
        CreateOrganisationForm form = new CreateOrganisationForm();
        form.setName("Acme Care");
        form.setType(OrgType.CARE_PROVIDER);
        form.setSupplierOrganisationId(5L);
        return form;
    }

    private Organisation savedCareProviderWithId(long id) {
        Organisation saved = mock(Organisation.class);
        when(saved.getType()).thenReturn(OrgType.CARE_PROVIDER);
        when(saved.getId()).thenReturn(id);
        when(repository.findById(5L)).thenReturn(Optional.of(new Organisation()));
        when(repository.save(any(Organisation.class))).thenReturn(saved);
        return saved;
    }

    @Test
    void unconfirmedKekAddsAnOnboardingNoticeNamingTheKey() {
        savedCareProviderWithId(2L);
        when(keyProvider.currentKeyFor(2L)).thenThrow(new KeyUnavailableException("no key for org 2"));

        BindingResult binding = new BeanPropertyBindingResult(careProviderForm(), "form");
        Model model = new ExtendedModelMap();
        RedirectAttributes redirect = new RedirectAttributesModelMap();

        String view = controllerWithAutoCreate(false).create(careProviderForm(), binding, model, redirect);

        assertThat(view).isEqualTo("redirect:/admin/organisations");
        assertThat(redirect.getFlashAttributes()).containsKey("kekWarning");
        assertThat(redirect.getFlashAttributes().get("kekWarning")).asString().contains("org-2-kek");
    }

    @Test
    void aConfirmedKekAddsNoNotice() {
        savedCareProviderWithId(2L);
        when(keyProvider.currentKeyFor(2L))
                .thenReturn(new KeyHandle(2L, "org-2-kek", "v1", "RSA-OAEP-256"));

        BindingResult binding = new BeanPropertyBindingResult(careProviderForm(), "form");
        RedirectAttributes redirect = new RedirectAttributesModelMap();

        controllerWithAutoCreate(false).create(careProviderForm(), binding, new ExtendedModelMap(), redirect);

        assertThat(redirect.getFlashAttributes()).doesNotContainKey("kekWarning");
    }

    @Test
    void withAutoCreateOnTheProbeIsSkippedEntirely() {
        savedCareProviderWithId(2L);
        // With auto-create on, currentKeyFor would MINT the key as a side effect, so the guard must
        // skip the probe altogether - both no notice and no call to the provider.
        BindingResult binding = new BeanPropertyBindingResult(careProviderForm(), "form");
        RedirectAttributes redirect = new RedirectAttributesModelMap();

        controllerWithAutoCreate(true).create(careProviderForm(), binding, new ExtendedModelMap(), redirect);

        assertThat(redirect.getFlashAttributes()).doesNotContainKey("kekWarning");
        Mockito.verify(keyProvider, Mockito.never()).currentKeyFor(Mockito.anyLong());
    }
}
