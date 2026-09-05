package ninja.samryecroft.returnhome.tracker.user;

import jakarta.validation.Valid;
import java.util.HashSet;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryService;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.user.dto.CreateUserForm;
import ninja.samryecroft.returnhome.tracker.user.dto.EditUserForm;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin/users")
public class UserAdminController {

    private final UserService userService;
    private final UserRepository userRepository;
    private final HomeRepository homeRepository;
    private final OrganisationRepository organisationRepository;
    private final AuditHistoryService auditHistoryService;
    private final AuditEventPublisher auditEventPublisher;
    private final ninja.samryecroft.returnhome.tracker.auth.ClaimCodeService claimCodeService;

    public UserAdminController(UserService userService, UserRepository userRepository, HomeRepository homeRepository,
            OrganisationRepository organisationRepository, AuditHistoryService auditHistoryService,
            AuditEventPublisher auditEventPublisher,
            ninja.samryecroft.returnhome.tracker.auth.ClaimCodeService claimCodeService) {
        this.claimCodeService = claimCodeService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.homeRepository = homeRepository;
        this.organisationRepository = organisationRepository;
        this.auditHistoryService = auditHistoryService;
        this.auditEventPublisher = auditEventPublisher;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        model.addAttribute("users", userService.listVisible(principal));
        return "admin/user-list";
    }

    @GetMapping("/new")
    public String newForm(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        model.addAttribute("form", new CreateUserForm());
        addPickerAttributes(principal, model);
        return "admin/user-form";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") CreateUserForm form, BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            addPickerAttributes(principal, model);
            return "admin/user-form";
        }
        try {
            userService.create(form, principal);
        } catch (DuplicateObjectIdException | DataIntegrityViolationException clash) {
            // Both arms are the same defect seen at different moments: the service's pre-check
            // catches the ordinary case, and uq_users_idp_subject catches two admins saving the same
            // id at once, which no pre-check can. Translating it here keeps the administrator's
            // other input on the screen instead of losing it to a 500.
            rejectDuplicateObjectId(bindingResult);
            addPickerAttributes(principal, model);
            return "admin/user-form";
        }
        return "redirect:/admin/users";
    }

    /**
     * Issues a fresh claim code and shows it to the administrator once (T197).
     *
     * <p><b>Shown once and never again.</b> Only a hash is stored, so this response is the sole
     * moment the code exists outside the administrator's hands - which is what makes "reissue, never
     * reveal" a property of the storage rather than a rule someone has to remember. Reissuing
     * invalidates the previous code, and that is the remedy when one is lost or was given to the
     * wrong person.
     *
     * <p>POST rather than GET, because it mints a credential and invalidates the previous one. A GET
     * would be pre-fetched by a browser, followed by a link checker, and replayed from history.
     */
    @PostMapping("/{id}/claim-code")
    public String issueClaimCode(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal,
            Model model) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such user: " + id));
        model.addAttribute("user", user);
        // The one place the plaintext travels. It is not logged, not audited, and not persisted.
        model.addAttribute("claimCode", claimCodeService.issue(user));
        auditEventPublisher.claimCodeIssued(user, principal);
        return "admin/claim-code-issued";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        User user = userService.getAuthorized(id, principal);
        EditUserForm form = new EditUserForm();
        form.setFirstName(user.getFirstName());
        form.setLastName(user.getLastName());
        form.setEmail(user.getEmail());
        form.setContactPhone(user.getContactPhone());
        form.setRoles(new HashSet<>(user.getRoles()));
        form.setOrganisationId(user.getOrganisation() != null ? user.getOrganisation().getId() : null);
        // One Homes field for both roles now - queried rather than read off the detached user,
        // whose homes are lazy.
        form.setHomeIds(new HashSet<>(userRepository.findHomeIds(id)));
        form.setIdpSubject(user.getIdpSubject());
        form.setEnabled(user.isEnabled());

        model.addAttribute("user", user);
        model.addAttribute("form", form);
        model.addAttribute("auditHistory", auditHistoryService.historyForUser(id));
        auditEventPublisher.auditViewOpened("User", id, principal.getOrganisationId(), null, principal);
        addPickerAttributes(principal, model);
        return "admin/user-form-edit";
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") EditUserForm form, BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("user", userService.getAuthorized(id, principal));
            addPickerAttributes(principal, model);
            return "admin/user-form-edit";
        }
        try {
            userService.update(id, form, principal);
        } catch (DuplicateObjectIdException | DataIntegrityViolationException clash) {
            rejectDuplicateObjectId(bindingResult);
            model.addAttribute("user", userService.getAuthorized(id, principal));
            addPickerAttributes(principal, model);
            return "admin/user-form-edit";
        }
        return "redirect:/admin/users";
    }

    private void rejectDuplicateObjectId(BindingResult bindingResult) {
        bindingResult.rejectValue("idpSubject", "duplicate",
                "That Directory object ID is already recorded against another account.");
    }

    private void addPickerAttributes(AppUserPrincipal principal, Model model) {
        model.addAttribute("roles", userService.allowedRolesFor(principal));
        if (principal.hasRole(Role.ADMIN)) {
            model.addAttribute("homes", homeRepository.findAllWithOrganisation());
            model.addAttribute("organisations", organisationRepository.findAllWithSupplier());
        } else if (principal.hasRole(Role.ORG_ADMIN) && principal.getOrganisationType() == OrgType.CARE_PROVIDER) {
            model.addAttribute("homes", homeRepository.findByOrganisationIdWithOrganisation(principal.getOrganisationId()));
        }
        // Supplier ORG_ADMIN needs neither picker: their new users' organisation is always their own.
    }
}
