package ninja.samryecroft.returnhome.tracker.home;

import jakarta.validation.Valid;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.home.dto.CreateHomeForm;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/homes")
public class HomeAdminController {

    private final HomeRepository homeRepository;
    private final OrganisationRepository organisationRepository;

    public HomeAdminController(HomeRepository homeRepository, OrganisationRepository organisationRepository) {
        this.homeRepository = homeRepository;
        this.organisationRepository = organisationRepository;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        List<Home> homes;
        if (principal.hasRole(Role.ADMIN)) {
            homes = homeRepository.findAllWithOrganisation();
        } else if (isCareProviderOrgAdmin(principal)) {
            homes = homeRepository.findByOrganisationIdWithOrganisation(principal.getOrganisationId());
        } else {
            // Supplier ORG_ADMIN: read-only view across their client Care Provider orgs' homes.
            homes = homeRepository.findByOrganisationSupplierOrganisationId(principal.getOrganisationId());
        }
        model.addAttribute("homes", homes);
        model.addAttribute("canCreate", canCreateHomes(principal));
        return "admin/home-list";
    }

    @GetMapping("/new")
    public String newForm(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        if (!canCreateHomes(principal)) {
            throw new AccessDeniedException("Only a platform admin or a Care Provider's own admin can add homes");
        }
        model.addAttribute("form", new CreateHomeForm());
        if (principal.hasRole(Role.ADMIN)) {
            model.addAttribute("organisations", organisationRepository.findByTypeOrderByName(OrgType.CARE_PROVIDER));
        }
        return "admin/home-form";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") CreateHomeForm form, BindingResult bindingResult, Model model) {
        if (!canCreateHomes(principal)) {
            throw new AccessDeniedException("Only a platform admin or a Care Provider's own admin can add homes");
        }

        Organisation organisation;
        if (principal.hasRole(Role.ADMIN)) {
            if (form.getOrganisationId() == null) {
                bindingResult.addError(new FieldError("form", "organisationId", "Please select a care provider organisation"));
                organisation = null;
            } else {
                organisation = organisationRepository.findById(form.getOrganisationId())
                        .orElseThrow(() -> new IllegalArgumentException("No such organisation: " + form.getOrganisationId()));
            }
        } else {
            // Care Provider ORG_ADMIN: home is always pinned to their own organisation.
            organisation = organisationRepository.findById(principal.getOrganisationId()).orElseThrow();
        }

        if (bindingResult.hasErrors()) {
            if (principal.hasRole(Role.ADMIN)) {
                model.addAttribute("organisations", organisationRepository.findByTypeOrderByName(OrgType.CARE_PROVIDER));
            }
            return "admin/home-form";
        }

        Home home = new Home();
        home.setName(form.getName());
        home.setOrganisation(organisation);
        home.setAddressLine1(form.getAddressLine1());
        home.setAddressLine2(form.getAddressLine2());
        home.setAddressLine3(form.getAddressLine3());
        home.setPostcode(form.getPostcode());
        home.setWhat3words(form.getWhat3words());
        home.setLocalAuthority(form.getLocalAuthority());
        homeRepository.save(home);
        return "redirect:/admin/homes";
    }

    private boolean isCareProviderOrgAdmin(AppUserPrincipal principal) {
        return principal.hasRole(Role.ORG_ADMIN) && principal.getOrganisationType() == OrgType.CARE_PROVIDER;
    }

    private boolean canCreateHomes(AppUserPrincipal principal) {
        return principal.hasRole(Role.ADMIN) || isCareProviderOrgAdmin(principal);
    }
}
