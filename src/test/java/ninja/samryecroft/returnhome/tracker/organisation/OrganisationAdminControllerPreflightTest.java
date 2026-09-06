package ninja.samryecroft.returnhome.tracker.organisation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.document.KeyProvider;
import ninja.samryecroft.returnhome.tracker.document.KeyUnavailableException;
import ninja.samryecroft.returnhome.tracker.organisation.dto.CreateOrganisationForm;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import ninja.samryecroft.returnhome.tracker.theme.ThemeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * T168 Part A(a): creating a CARE_PROVIDER whose per-organisation KEK cannot be confirmed surfaces an
 * actionable onboarding notice to the admin - moving discovery of the gap from a confusing failure in
 * front of a client (the org-2 P0) to the moment of onboarding. Advisory: creation still succeeds, and
 * the encrypt path still fail-closes at write time if the key is genuinely absent. The probe uses the
 * pure-read {@link KeyProvider#keyExists} (T168(b)), which never mints, so it runs in every
 * configuration - there is no auto-create guard any more.
 */
@ExtendWith(MockitoExtension.class)
class OrganisationAdminControllerPreflightTest {

    private final OrganisationRepository repository = mock(OrganisationRepository.class);
    private final ThemeService themeService = mock(ThemeService.class);
    private final KeyProvider keyProvider = mock(KeyProvider.class);
    private final OrganisationLifecycleService lifecycleService = mock(OrganisationLifecycleService.class);
    private final OrganisationAdminController controller =
            new OrganisationAdminController(repository, themeService, keyProvider, lifecycleService,
                    mock(HomeRepository.class), mock(UserRepository.class));

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
    void anAbsentKekAddsAnOnboardingNoticeNamingTheKey() {
        savedCareProviderWithId(2L);
        when(keyProvider.keyExists(2L)).thenReturn(false);

        BindingResult binding = new BeanPropertyBindingResult(careProviderForm(), "form");
        Model model = new ExtendedModelMap();
        RedirectAttributes redirect = new RedirectAttributesModelMap();

        String view = controller.create(careProviderForm(), binding, model, redirect);

        assertThat(view).isEqualTo("redirect:/admin/organisations");
        assertThat(redirect.getFlashAttributes()).containsKey("kekWarning");
        assertThat(redirect.getFlashAttributes().get("kekWarning")).asString().contains("org-2-kek");
    }

    @Test
    void aConfirmedKekAddsNoNotice() {
        savedCareProviderWithId(2L);
        when(keyProvider.keyExists(2L)).thenReturn(true);

        BindingResult binding = new BeanPropertyBindingResult(careProviderForm(), "form");
        RedirectAttributes redirect = new RedirectAttributesModelMap();

        controller.create(careProviderForm(), binding, new ExtendedModelMap(), redirect);

        assertThat(redirect.getFlashAttributes()).doesNotContainKey("kekWarning");
    }

    @Test
    void anUnreachableVaultWarnsButDoesNotFailCreation() {
        savedCareProviderWithId(2L);
        // keyExists throws when it cannot tell (vault unreachable). The advisory notice must still
        // show, and org creation must NOT fail on a vault blip - so the exception is swallowed to a warn.
        when(keyProvider.keyExists(2L)).thenThrow(new KeyUnavailableException("vault unreachable"));

        BindingResult binding = new BeanPropertyBindingResult(careProviderForm(), "form");
        RedirectAttributes redirect = new RedirectAttributesModelMap();

        String view = controller.create(careProviderForm(), binding, new ExtendedModelMap(), redirect);

        assertThat(view).isEqualTo("redirect:/admin/organisations");
        assertThat(redirect.getFlashAttributes()).containsKey("kekWarning");
        assertThat(redirect.getFlashAttributes().get("kekWarning")).asString().contains("org-2-kek");
    }
}
