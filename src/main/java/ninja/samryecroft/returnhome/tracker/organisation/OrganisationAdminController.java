package ninja.samryecroft.returnhome.tracker.organisation;

import jakarta.validation.Valid;
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

    public OrganisationAdminController(OrganisationRepository organisationRepository, ThemeService themeService,
            KeyProvider keyProvider) {
        this.organisationRepository = organisationRepository;
        this.themeService = themeService;
        this.keyProvider = keyProvider;
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

        // T168 preflight: a CARE_PROVIDER's field data is encrypted under a per-organisation KEK that
        // the application cannot create (it is Key Vault Crypto User only - see DOCUMENT-KEYS.md). If
        // that key was not provisioned at onboarding, the organisation's first child record fails
        // closed later - historically an opaque error in front of a client (the org-2 P0). Surfacing
        // it here, to the admin who just created the org and can arrange provisioning, turns that late
        // failure into an actionable notice at the moment of onboarding. Advisory only: it never blocks
        // creation, and the encrypt path still fail-closes if the key is genuinely missing at write time.
        if (organisation.getType() == OrgType.CARE_PROVIDER && !fieldKekExists(organisation.getId())) {
            redirectAttributes.addFlashAttribute("kekWarning",
                    "This care provider’s encryption key (" + KeyProvider.keyNameFor(organisation.getId())
                            + ") is not yet provisioned. An operator must create it before any child "
                            + "records can be added for this organisation. See DOCUMENT-KEYS.md.");
        }

        return "redirect:/admin/organisations";
    }

    /**
     * Whether the organisation's field KEK can be resolved right now. A probe, not a mutation: in
     * production the provider is Crypto User with auto-create disabled, so this is a read that either
     * returns the handle or raises {@link KeyUnavailableException} (missing, or the vault is
     * unreachable) - both of which mean "not usable yet" for the purposes of the onboarding notice.
     */
    private boolean fieldKekExists(long organisationId) {
        try {
            keyProvider.currentKeyFor(organisationId);
            return true;
        } catch (KeyUnavailableException e) {
            return false;
        }
    }
}
