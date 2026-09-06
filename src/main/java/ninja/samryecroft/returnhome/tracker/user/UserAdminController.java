package ninja.samryecroft.returnhome.tracker.user;

import jakarta.validation.Valid;
import java.util.HashSet;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryService;
import ninja.samryecroft.returnhome.tracker.audit.DraftSaveRuns;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.user.dto.CreateUserForm;
import ninja.samryecroft.returnhome.tracker.user.dto.EditUserForm;
import ninja.samryecroft.returnhome.tracker.user.password.PasswordContext;
import ninja.samryecroft.returnhome.tracker.user.password.PasswordPolicy;
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
    private final OrganisationAccessService organisationAccessService;
    private final AuditHistoryService auditHistoryService;
    private final AuditEventPublisher auditEventPublisher;
    private final PasswordPolicy passwordPolicy;

    public UserAdminController(UserService userService, UserRepository userRepository, HomeRepository homeRepository,
            OrganisationRepository organisationRepository, OrganisationAccessService organisationAccessService,
            AuditHistoryService auditHistoryService, AuditEventPublisher auditEventPublisher,
            PasswordPolicy passwordPolicy) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.homeRepository = homeRepository;
        this.organisationRepository = organisationRepository;
        this.organisationAccessService = organisationAccessService;
        this.auditHistoryService = auditHistoryService;
        this.auditEventPublisher = auditEventPublisher;
        this.passwordPolicy = passwordPolicy;
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
        } catch (DataIntegrityViolationException clash) {
            // Kept, and re-pointed, when the Entra object id was removed. The arm existed for
            // uq_users_idp_subject, but it was ALSO the only thing catching uq_users_username -
            // there is no pre-check or validator for a duplicate username anywhere - so deleting it
            // outright would have turned a graceful form error into a 500 on an ordinary admin
            // mistake. It also fixes a mislabel: a duplicate username used to be reported as a
            // duplicate Directory object ID.
            rejectDuplicateUsername(bindingResult);
            addPickerAttributes(principal, model);
            return "admin/user-form";
        }
        return "redirect:/admin/users";
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
        form.setEnabled(user.isEnabled());

        model.addAttribute("user", user);
        model.addAttribute("form", form);
        model.addAttribute("rolesYouCannotChange", userService.rolesNotAssignableBy(user, principal));
        model.addAttribute("auditHistory", auditHistoryService.historyForUser(id, DraftSaveRuns.COLLAPSED));
        auditEventPublisher.auditViewOpened("User", id, principal.getOrganisationId(), null, principal);
        addPickerAttributes(principal, model);
        return "admin/user-form-edit";
    }

    @PostMapping("/{id}/edit")
    public String update(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") EditUserForm form, BindingResult bindingResult, Model model) {
        rejectAPasswordBuiltFromTheUsername(id, principal, form, bindingResult);
        if (bindingResult.hasErrors()) {
            User target = userService.getAuthorized(id, principal);
            model.addAttribute("user", target);
            model.addAttribute("rolesYouCannotChange", userService.rolesNotAssignableBy(target, principal));
            addPickerAttributes(principal, model);
            return "admin/user-form-edit";
        }
        try {
            userService.update(id, form, principal);
        } catch (DataIntegrityViolationException clash) {
            User target = userService.getAuthorized(id, principal);
            rejectDuplicateUsername(bindingResult);
            model.addAttribute("user", target);
            model.addAttribute("rolesYouCannotChange", userService.rolesNotAssignableBy(target, principal));
            addPickerAttributes(principal, model);
            return "admin/user-form-edit";
        }
        return "redirect:/admin/users";
    }

    /**
     * The one context value {@code EditUserForm} cannot supply (T272 R2).
     *
     * <p>The form does not edit the username and does not carry it, so the class-level constraint
     * checks the email, organisation and application values but not this one. Carrying the username
     * in a hidden field would close the gap by making a validation input user-controllable, which
     * trades a small hole for a worse shape. So the real username is read from the loaded account
     * here - the SAME {@link PasswordPolicy} object, not a second copy of the rule, and only the
     * username context is supplied because everything else has already been checked.
     */
    private void rejectAPasswordBuiltFromTheUsername(Long id, AppUserPrincipal principal,
            EditUserForm form, BindingResult bindingResult) {
        if (form.getNewPassword() == null || form.getNewPassword().isBlank()) {
            return;
        }
        String username = userService.getAuthorized(id, principal).getUsername();
        passwordPolicy.rejectionFor(form.getNewPassword(), new PasswordContext(username, null, null))
                .ifPresent(message -> bindingResult.rejectValue("newPassword", "password.policy", message));
    }

    private void rejectDuplicateUsername(BindingResult bindingResult) {
        bindingResult.rejectValue("username", "duplicate",
                "That username is already taken.");
    }

    private void addPickerAttributes(AppUserPrincipal principal, Model model) {
        model.addAttribute("roles", userService.allowedRolesFor(principal));
        if (principal.hasRole(Role.ADMIN)) {
            model.addAttribute("homes", homeRepository.findAllWithOrganisation());
            model.addAttribute("organisations", organisationRepository.findAllWithSupplier());
        } else if (principal.hasRole(Role.ORG_ADMIN) && principal.getOrganisationType() == OrgType.CARE_PROVIDER) {
            model.addAttribute("homes", homeRepository.findByOrganisationIdWithOrganisation(principal.getOrganisationId()));
        } else {
            // T249. A supplier org-admin could not previously create a user for one of their care
            // providers at all - they were pinned to their own organisation - which was a functional
            // gap rather than a safeguard. The picker offers their own organisation plus the care
            // providers they serve, built from the SAME rule the service enforces.
            //
            // Offering the right options is not what makes this safe. A filtered dropdown is not a
            // constraint: it shapes the form, not the POST. The constraint is
            // UserService.resolveOrganisation, which now reads the submitted id and refuses one
            // outside scope - and it had to arrive in the same commit as this list, because reading
            // that id at all is what creates the escalation path.
            List<Organisation> permitted =
                    organisationAccessService.organisationsUserMayBePlacedIn(principal);
            if (permitted.size() > 1) {
                model.addAttribute("organisations", permitted);
            }
        }
    }
}
