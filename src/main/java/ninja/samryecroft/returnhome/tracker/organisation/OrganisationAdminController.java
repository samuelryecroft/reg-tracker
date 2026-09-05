package ninja.samryecroft.returnhome.tracker.organisation;

import jakarta.validation.Valid;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.document.KeyProvider;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import ninja.samryecroft.returnhome.tracker.document.KeyUnavailableException;
import ninja.samryecroft.returnhome.tracker.organisation.dto.CreateOrganisationForm;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.theme.ThemeService;
import org.springframework.stereotype.Controller;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/organisations")
public class OrganisationAdminController {

    private final OrganisationRepository organisationRepository;
    private final ThemeService themeService;
    private final KeyProvider keyProvider;
    private final OrganisationLifecycleService lifecycleService;
    private final HomeRepository homeRepository;
    private final UserRepository userRepository;

    public OrganisationAdminController(OrganisationRepository organisationRepository, ThemeService themeService,
            KeyProvider keyProvider, OrganisationLifecycleService lifecycleService,
            HomeRepository homeRepository, UserRepository userRepository) {
        this.organisationRepository = organisationRepository;
        this.themeService = themeService;
        this.keyProvider = keyProvider;
        this.lifecycleService = lifecycleService;
        this.homeRepository = homeRepository;
        this.userRepository = userRepository;
    }

    /**
     * T119 4e: one tree, in creation order - supplier, its care providers, their homes.
     *
     * <p><b>Four queries, and the joining is done in memory.</b> Walking the tree to fetch each
     * provider's homes would be the obvious shape and an N+1 on the one screen that renders every
     * organisation on the platform. The assembly itself lives in {@link OrganisationTree#from} as a
     * pure function so it can be unit-tested without a database.
     *
     * <p>The flat list is NOT published to the model. It was, with a note saying the activation
     * banners might read it - which Dwight questioned, correctly: those banners read
     * {@code kekWarning}, {@code activationMessage} and {@code activationError}, so the reason I
     * gave was never true. The attribute itself was genuinely consumed, though, by the empty-state
     * check - which is the part the review missed, because the usage is
     * {@code ${#lists.isEmpty(organisations)}} rather than a bare {@code ${organisations}}.
     *
     * <p>So the fix is neither "keep it" nor "drop it": the empty state now tests the TREE, which
     * is what the page actually renders. A page that decides its empty state from a different
     * collection than the one it draws can say "No organisations yet" above a populated tree. With
     * that gone the attribute has no consumer at all, so it goes too.
     */
    @GetMapping
    public String list(Model model) {
        var organisations = organisationRepository.findAllWithSupplier();

        Map<Long, Integer> userCounts = new HashMap<>();
        for (Object[] row : userRepository.countByOrganisation()) {
            userCounts.put((Long) row[0], ((Number) row[1]).intValue());
        }

        // "Branding set" means someone CHOSE a colour, not that a theme row exists. My first
        // version used the row, documented the choice, and was wrong for a reason the comment
        // itself should have caught: ensureThemeExistsFor gives every supplier a default-coloured
        // row at creation, so the flag was true for all of them and the line said nothing in the
        // one slot meant to tell an admin whether a supplier is set up. The predicate lives in
        // ThemeService, next to the default it compares against.
        Set<Long> branded = themeService.organisationIdsWithChosenBranding();

        model.addAttribute("tree", OrganisationTree.from(organisations,
                homeRepository.findAllWithOrganisation(), userCounts, branded));
        return "admin/organisation-list";
    }

    /**
     * T168(b): activation is a POST because it changes state, and it goes through
     * {@link OrganisationLifecycleService} rather than setting the field here - that service is
     * where the KEK is actually verified and the transition audited. A controller that flipped the
     * status itself would make ACTIVE mean "an admin clicked a button", which is the assertion this
     * whole guard exists to replace with a check.
     */
    @PostMapping("/{id}/activate")
    public String activate(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal,
            RedirectAttributes redirectAttributes) {
        Organisation organisation = organisationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such organisation: " + id));
        try {
            lifecycleService.activate(organisation, principal);
            redirectAttributes.addFlashAttribute("activationMessage",
                    organisation.getName() + " is now active.");
        } catch (OrganisationNotActivatableException notYet) {
            // The key name is named because this is the privileged admin screen and the admin needs
            // it to have the key provisioned - the same audience split the onboarding notice draws.
            redirectAttributes.addFlashAttribute("activationError",
                    organisation.getName() + " cannot be activated yet: its encryption key ("
                            + notYet.getKeyName() + ") does not exist. An operator needs to create it "
                            + "before any records can be added for this organisation.");
        } catch (KeyUnavailableException cannotVerify) {
            // ABSENT and UNREACHABLE are different answers and get different words. The organisation
            // stays PENDING either way - failing closed on "we could not tell" is the whole point of
            // not conflating them - but the remedy differs completely: one needs an operator to
            // create a key, the other needs a retry in five minutes. Telling an admin to provision a
            // key that may already exist is the T168 mistake inverted.
            //
            // Caught HERE rather than left to the advice, and that is not tidiness: uncaught, this
            // is a DocumentSecurityException, so handleDocumentSecurity matches it by cause and
            // answers "this REPORT cannot be opened right now" - to an admin who just clicked
            // Activate on an organisation. That is exactly the wrong-noun defect T168 was raised to
            // fix, reappearing on the screen built to fix it.
            redirectAttributes.addFlashAttribute("activationError",
                    "Could not confirm " + organisation.getName() + "'s encryption key: the key "
                            + "service is unavailable. " + organisation.getName() + " has not been "
                            + "activated. This is usually temporary - please try again shortly.");
        }
        return "redirect:/admin/organisations";
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

        // T168 preflight: a CARE_PROVIDER's field data is encrypted under a per-organisation KEK. If
        // that key was not provisioned at onboarding, the organisation's first child record fails
        // closed later - a confusing error in front of a client (the org-2 P0). Surfacing it here, to
        // the admin who can arrange provisioning, turns that late failure into an actionable notice at
        // onboarding. Advisory only: never blocks creation, and the encrypt path still fail-closes at
        // write time if the key is genuinely missing.
        //
        // Uses keyExists (T168(b)), a pure read with NO create path, so it is safe in EVERY
        // configuration - unlike currentKeyFor, which mints the key when auto-create is on and so could
        // not be used to probe. That is what lets this drop the old !auto-create guard and fire the
        // notice everywhere rather than only in the least-privilege shape.
        if (organisation.getType() == OrgType.CARE_PROVIDER && !fieldKekConfirmed(organisation.getId())) {
            redirectAttributes.addFlashAttribute("kekWarning",
                    "This care provider’s encryption key (" + KeyProvider.keyNameFor(organisation.getId())
                            + ") could not be confirmed. It must exist before any child records can be "
                            + "added for this organisation; if it has not been provisioned, an operator "
                            + "needs to create it. See DOCUMENT-KEYS.md.");
        }

        return "redirect:/admin/organisations";
    }

    /**
     * Whether the organisation's field KEK could be confirmed present right now, via the pure-read
     * {@link KeyProvider#keyExists}. {@code false} means definitely absent; a
     * {@link KeyUnavailableException} means the vault could not be reached to tell - and for this
     * advisory notice both resolve to "not confirmed" (so the notice is worded "could not be
     * confirmed"). Deliberately swallows the exception rather than propagating it: a vault blip must
     * not fail an admin's organisation-create, only leave the notice showing.
     */
    private boolean fieldKekConfirmed(long organisationId) {
        try {
            return keyProvider.keyExists(organisationId);
        } catch (KeyUnavailableException e) {
            return false;
        }
    }
}
