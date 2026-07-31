package ninja.samryecroft.returnhome.tracker.organisation;

import jakarta.validation.Valid;
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

@Controller
@RequestMapping("/admin/organisations")
public class OrganisationAdminController {

    private final OrganisationRepository organisationRepository;
    private final ThemeService themeService;

    public OrganisationAdminController(OrganisationRepository organisationRepository, ThemeService themeService) {
        this.organisationRepository = organisationRepository;
        this.themeService = themeService;
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
            Model model) {
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

        return "redirect:/admin/organisations";
    }
}
