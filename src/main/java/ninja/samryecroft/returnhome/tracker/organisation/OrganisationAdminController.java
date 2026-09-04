package ninja.samryecroft.returnhome.tracker.organisation;

import jakarta.validation.Valid;
import ninja.samryecroft.returnhome.tracker.document.DocumentStorageProperties;
import ninja.samryecroft.returnhome.tracker.document.KeyProvider;
import ninja.samryecroft.returnhome.tracker.document.KeyUnavailableException;
import ninja.samryecroft.returnhome.tracker.organisation.dto.CreateOrganisationForm;
import ninja.samryecroft.returnhome.tracker.theme.ThemeService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/organisations")
public class OrganisationAdminController {

    private final OrganisationRepository organisationRepository;
    private final ThemeService themeService;
    private final KeyProvider keyProvider;
    private final DocumentStorageProperties documentStorageProperties;

    public OrganisationAdminController(OrganisationRepository organisationRepository, ThemeService themeService,
            KeyProvider keyProvider, DocumentStorageProperties documentStorageProperties) {
        this.organisationRepository = organisationRepository;
        this.themeService = themeService;
        this.keyProvider = keyProvider;
        this.documentStorageProperties = documentStorageProperties;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("organisations", organisationRepository.findAllWithSupplier());
        return "admin/organisation-list";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("form", new CreateOrganisationForm());
        model.addAttribute("types", OrgType.values());
        model.addAttribute("suppliers", organisationRepository.findByTypeOrderByName(OrgType.SUPPLIER));
        return "admin/organisation-form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") CreateOrganisationForm form, BindingResult bindingResult,
            Model model, RedirectAttributes redirectAttributes) {
        Organisation supplier = null;
        if (form.getType() == OrgType.CARE_PROVIDER) {
            if (form.getSupplierOrganisationId() == null) {
                bindingResult.addError(new FieldError("form", "supplierOrganisationId", "Please select a supplier"));
            } else {
                supplier = organisationRepository.findById(form.getSupplierOrganisationId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "No such organisation: " + form.getSupplierOrganisationId()));
            }
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute("types", OrgType.values());
            model.addAttribute("suppliers", organisationRepository.findByTypeOrderByName(OrgType.SUPPLIER));
            return "admin/organisation-form";
        }

        Organisation organisation = new Organisation();
        organisation.setName(form.getName());
        organisation.setType(form.getType());
        organisation.setSupplierOrganisation(supplier);
        organisation = organisationRepository.save(organisation);
        if (organisation.getType() == OrgType.SUPPLIER) {
            themeService.ensureThemeExistsFor(organisation);
        }

        // T168 preflight: a CARE_PROVIDER's field data is encrypted under a per-organisation KEK. Where
        // the application cannot create keys (Key Vault Crypto User only, auto-create disabled - the
        // production shape, see DOCUMENT-KEYS.md), a KEK not provisioned at onboarding makes the
        // organisation's first child record fail closed later - a confusing error in front of a client
        // (the org-2 P0). Surfacing it here, to the admin who can arrange provisioning, turns that late
        // failure into an actionable notice at onboarding. Advisory only: never blocks creation, and the
        // encrypt path still fail-closes at write time if the key is genuinely missing.
        //
        // Guarded on !auto-create precisely because currentKeyFor is NOT non-mutating when auto-create
        // is on: there the provider creates the key on first reference, so probing would mint it as a
        // side effect and the notice could never fire. Where auto-create is on the write path provisions
        // the key anyway, so no notice is needed - which is exactly the branch we skip.
        if (organisation.getType() == OrgType.CARE_PROVIDER
                && !documentStorageProperties.getKeyVault().isAutoCreateKeys()
                && !fieldKekConfirmed(organisation.getId())) {
            redirectAttributes.addFlashAttribute("kekWarning",
                    "This care provider’s encryption key (" + KeyProvider.keyNameFor(organisation.getId())
                            + ") could not be confirmed. It must exist before any child records can be "
                            + "added for this organisation; if it has not been provisioned, an operator "
                            + "needs to create it. See DOCUMENT-KEYS.md.");
        }

        return "redirect:/admin/organisations";
    }

    /**
     * Whether the organisation's field KEK could be confirmed available right now. Called only when
     * auto-create is disabled (see the caller), where {@code currentKeyFor} is a pure read: it either
     * returns the handle or raises {@link KeyUnavailableException}. That exception collapses four causes
     * - missing key, vault unreachable, RBAC denied, network partition - so a {@code false} here means
     * "not confirmed", not specifically "missing"; the notice is worded accordingly.
     */
    private boolean fieldKekConfirmed(long organisationId) {
        try {
            keyProvider.currentKeyFor(organisationId);
            return true;
        } catch (KeyUnavailableException e) {
            return false;
        }
    }
}
