package ninja.samryecroft.returnhome.tracker.home;

import jakarta.validation.Valid;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.home.dto.CreateHomeForm;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.RoleMatrix;
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
    private final RoleMatrix roleMatrix;
    private final OrganisationAccessService organisationAccessService;

    public HomeAdminController(HomeRepository homeRepository, OrganisationRepository organisationRepository,
            RoleMatrix roleMatrix,
            OrganisationAccessService organisationAccessService) {
        this.homeRepository = homeRepository;
        this.organisationRepository = organisationRepository;
        this.roleMatrix = roleMatrix;
        this.organisationAccessService = organisationAccessService;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        List<Home> homes;
        if (principal.hasRole(Role.ADMIN)) {
            homes = homeRepository.findAllWithOrganisation();
        } else if (roleMatrix.isCareProviderOrgAdmin(principal)) {
            homes = homeRepository.findByOrganisationIdWithOrganisation(principal.getOrganisationId());
        } else {
            // Supplier ORG_ADMIN: read-only view across their client Care Provider orgs' homes.
            // Scoped through the access service rather than from the principal directly, so this is
            // not a fourth place that decides who counts as supplier-side (T139).
            homes = organisationAccessService.supplierScopeFor(principal)
                    .map(homeRepository::findByOrganisationSupplierOrganisationId)
                    .orElseGet(List::of);
        }
        model.addAttribute("homes", homes);
        return "admin/home-list";
    }

    @GetMapping("/new")
    public String newForm(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        if (!roleMatrix.canCreateHome(principal)) {
            throw new AccessDeniedException("Only a platform admin or a Care Provider's own admin can add homes");
        }
        model.addAttribute("form", new CreateHomeForm());
        if (principal.hasRole(Role.ADMIN)) {
            // ...WithSupplier: 6d's option labels name each provider's supplier, and
            // open-in-view is false, so the association is fetched here or the render throws.
            // BOTH call sites matter - this one and the validation re-render below - or the form
            // works until the first time someone gets it wrong.
            model.addAttribute("organisations", organisationRepository.findByTypeWithSupplier(OrgType.CARE_PROVIDER));
        }
        return "admin/home-form";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") CreateHomeForm form, BindingResult bindingResult, Model model) {
        if (!roleMatrix.canCreateHome(principal)) {
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
                // T168(b): the dropdown above is already filtered to care providers, but A FILTERED
                // DROPDOWN IS NOT A CONSTRAINT - it shapes the form, not the POST, and a platform
                // admin can post any organisation id. Nothing else stopped a home being hung off a
                // SUPPLIER: V6's foreign key does not care about the type either.
                //
                // It is enforced here because OTHER CODE NOW RELIES ON IT. Every encrypted entity
                // resolves its owning organisation through home.getOrganisation(), so
                // OrganisationLifecycleService only requires a KEK for CARE_PROVIDERs - correct
                // precisely because homes belong to care providers. A home under a supplier would
                // make that narrowing wrong, and the write would fail closed against a key that does
                // not exist and never should. This is the floor under that assumption.
                if (organisation.getType() != OrgType.CARE_PROVIDER) {
                    bindingResult.addError(new FieldError("form", "organisationId",
                            "Homes belong to care provider organisations. Please select a care provider."));
                    organisation = null;
                }
            }
        } else {
            // Care Provider ORG_ADMIN: home is always pinned to their own organisation.
            organisation = organisationRepository.findById(principal.getOrganisationId()).orElseThrow();
        }

        if (bindingResult.hasErrors()) {
            if (principal.hasRole(Role.ADMIN)) {
                model.addAttribute("organisations", organisationRepository.findByTypeWithSupplier(OrgType.CARE_PROVIDER));
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


}
