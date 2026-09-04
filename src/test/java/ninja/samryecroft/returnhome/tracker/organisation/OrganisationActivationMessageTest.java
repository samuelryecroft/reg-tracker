package ninja.samryecroft.returnhome.tracker.organisation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import ninja.samryecroft.returnhome.tracker.document.KeyProvider;
import ninja.samryecroft.returnhome.tracker.document.KeyUnavailableException;
import ninja.samryecroft.returnhome.tracker.theme.ThemeService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * T168(b): "the key is absent" and "we could not tell whether the key is there" are different
 * answers, and an admin gets different words for them.
 *
 * <p>The organisation stays PENDING either way - failing closed on "could not tell" is the whole
 * reason {@code keyExists} keeps the two apart - but the REMEDY differs completely. One needs an
 * operator to create a key; the other needs a retry in five minutes. Telling an admin to provision a
 * key that may already exist is the T168 mistake inverted, which is the point Pam raised on the
 * activation policy.
 *
 * <p>The unreachable case is caught in the controller rather than left to the advice, and this test
 * is what pins that. Uncaught, {@link KeyUnavailableException} is a {@code DocumentSecurityException},
 * so {@code handleDocumentSecurity} matches it by cause and answers <em>"this REPORT cannot be opened
 * right now"</em> - to an admin who just clicked Activate on an organisation. That is precisely the
 * wrong-noun defect T168 exists to fix, reappearing on the screen built to fix it, which is why the
 * assertion is on the NOUN and not merely on something being shown.
 */
class OrganisationActivationMessageTest {

    private final OrganisationRepository repository = mock(OrganisationRepository.class);
    private final OrganisationLifecycleService lifecycle = mock(OrganisationLifecycleService.class);
    private final OrganisationAdminController controller =
            new OrganisationAdminController(repository, mock(ThemeService.class), mock(KeyProvider.class), lifecycle);
    private final AppUserPrincipal principal = mock(AppUserPrincipal.class);

    private Organisation pendingCareProvider() {
        Organisation organisation = new Organisation();
        organisation.setName("Harbourside Care");
        organisation.setType(OrgType.CARE_PROVIDER);
        ReflectionTestUtils.setField(organisation, "id", 2L);
        when(repository.findById(2L)).thenReturn(Optional.of(organisation));
        return organisation;
    }

    @Test
    void anUnreachableVaultSaysTryAgainAndNeverMentionsAReport() {
        pendingCareProvider();
        when(lifecycle.activate(any(Organisation.class), any()))
                .thenThrow(new KeyUnavailableException("Key Vault is unreachable"));
        RedirectAttributes redirect = new RedirectAttributesModelMap();

        String view = controller.activate(2L, principal, redirect);

        assertThat(view).isEqualTo("redirect:/admin/organisations");
        String message = String.valueOf(redirect.getFlashAttributes().get("activationError"));

        // The defect this guards: the document handler's wording, on an organisation screen.
        assertThat(message).doesNotContain("report");
        assertThat(message).contains("try again");
        assertThat(message).contains("has not been activated");

        // And it must NOT tell the admin to go and create a key that may already exist.
        assertThat(message).doesNotContain("does not exist");
    }

    /** The other answer: definitely absent, so name the key the operator has to create. */
    @Test
    void anAbsentKeyNamesTheKeyToProvision() {
        pendingCareProvider();
        when(lifecycle.activate(any(Organisation.class), any()))
                .thenThrow(new OrganisationNotActivatableException("org-2-kek"));
        RedirectAttributes redirect = new RedirectAttributesModelMap();

        controller.activate(2L, principal, redirect);

        String message = String.valueOf(redirect.getFlashAttributes().get("activationError"));
        assertThat(message).contains("org-2-kek").contains("does not exist");
        assertThat(message).doesNotContain("report");
    }

    /** The paired positive, so "always shows an error" cannot pass the two above. */
    @Test
    void aSuccessfulActivationSaysSo() {
        Organisation organisation = pendingCareProvider();
        when(lifecycle.activate(any(Organisation.class), any())).thenReturn(organisation);
        RedirectAttributes redirect = new RedirectAttributesModelMap();

        controller.activate(2L, principal, redirect);

        assertThat(redirect.getFlashAttributes()).doesNotContainKey("activationError");
        assertThat(String.valueOf(redirect.getFlashAttributes().get("activationMessage")))
                .contains("Harbourside Care").contains("now active");
    }
}
