package ninja.samryecroft.returnhome.tracker.user;

import jakarta.validation.Valid;
import java.util.HashSet;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryService;
import ninja.samryecroft.returnhome.tracker.auth.ClaimCodeService;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.user.dto.CreateUserForm;
import ninja.samryecroft.returnhome.tracker.user.dto.EditUserForm;
import org.springframework.security.access.AccessDeniedException;
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
    private final ClaimCodeService claimCodeService;

    /**
     * The same flag {@code application-entra.properties} sets - one source, not a second that merely
     * looks like it. (T189's lesson, checked rather than assumed.)
     */
    @org.springframework.beans.factory.annotation.Value("${app.auth.entra.enabled:false}")
    private boolean entraEnabled;

    public UserAdminController(UserService userService, UserRepository userRepository, HomeRepository homeRepository,
            OrganisationRepository organisationRepository, AuditHistoryService auditHistoryService,
            AuditEventPublisher auditEventPublisher,
            ClaimCodeService claimCodeService) {
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
        User created;
        try {
            created = userService.create(form, principal);
        } catch (DuplicateObjectIdException | DataIntegrityViolationException clash) {
            // Both arms are the same defect seen at different moments: the service's pre-check
            // catches the ordinary case, and uq_users_idp_subject catches two admins saving the same
            // id at once, which no pre-check can. Translating it here keeps the administrator's
            // other input on the screen instead of losing it to a 500.
            rejectDuplicateObjectId(bindingResult);
            addPickerAttributes(principal, model);
            return "admin/user-form";
        }
        // T197 §6f: the code is issued ON CREATE and the confirmation shows it once. Issuing here
        // rather than as a later step is what makes it one action for the admin - and a seven-day
        // expiry is only generous if renewing it is trivial, otherwise admins ask for a longer
        // window and the bound stops meaning anything.
        //
        // GATED ON ENTRA BEING LIVE, which the design does not say and I am flagging rather than
        // deciding silently. §6f was written for the world after cutover; before it, minting a code
        // on every create would put a standing claim on an account in a deployment where nothing can
        // ever redeem it - a credential that exists for no reason, which is the kind of thing that
        // is later found and wondered about. Where Entra is off, creation redirects as it always did.
        if (entraEnabled) {
            model.addAttribute("user", created);
            model.addAttribute("claimCode", claimCodeService.issue(created));
            auditEventPublisher.claimCodeIssued(created, principal);
            return "admin/claim-code-issued";
        }
        return "redirect:/admin/users";
    }

    /**
     * Reissues a claim code and shows it to the administrator once (T197 §6f).
     *
     * <p><b>Shown once and never again.</b> Only a hash of the secret half is stored, so this
     * response is the sole moment the code exists outside the administrator's hands - which makes
     * "reissue, never reveal" a property of the storage rather than a rule someone remembers. The
     * design says plainly that a future build must not "fix" this: making a code retrievable means
     * storing it recoverably, which is a security regression wearing a usability costume.
     *
     * <p><b>Reissue is the whole of revocation.</b> There is no cancel verb: a new code invalidates
     * the previous one, and disabling the user - already a field on the same edit form - invalidates
     * any outstanding one. Those two cover both real cases, wrong person and person never joined,
     * using admin concepts that already exist.
     *
     * <p>POST, because it mints a credential and invalidates the previous one. A GET would be
     * pre-fetched by a browser, followed by a link checker and replayed from history.
     */
    @PostMapping("/{id}/reissue-code")
    public String reissueClaimCode(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal,
            Model model) {
        // Gated exactly as creation is, and for the same reason - Kevin caught that I had applied my
        // own argument to only one of its two paths. With Entra off, this minted precisely the
        // unredeemable credential the create gate exists to avoid: stored, in the audit trail, able
        // to leak, and doing nothing.
        //
        // The button is also not rendered in that state, because a button that 403s is worse than a
        // button that is not there: it tells an admin the feature exists and then refuses, with no
        // way to tell a permissions problem from a configuration one.
        if (!entraEnabled) {
            throw new AccessDeniedException(
                    "Claim codes are only issued when Entra sign-in is enabled");
        }
        User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such user: " + id));
        model.addAttribute("user", user);
        // The one place the plaintext travels. Not logged, not audited, not persisted.
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
        // T197. The claim-code section renders only under Entra. Kevin's point, and it is the right
        // one: a button that 403s is worse than a button that is not there - it tells the admin the
        // action exists and that they are not permitted it, when in fact nobody is. The server-side
        // gate on the reissue handler stays regardless; this is the second half of that pair, not a
        // substitute for it.
        model.addAttribute("entraEnabled", entraEnabled);
        // Bound from the constant rather than typed into the template, because the template already
        // drifted once: it still said "of 5" after the cap moved to 10.
        model.addAttribute("claimCodeMaxAttempts", ClaimCodeService.MAX_ATTEMPTS);
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
