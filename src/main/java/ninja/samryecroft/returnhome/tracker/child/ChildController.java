package ninja.samryecroft.returnhome.tracker.child;

import jakarta.validation.Valid;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryService;
import ninja.samryecroft.returnhome.tracker.child.dto.CreateChildForm;
import ninja.samryecroft.returnhome.tracker.export.ExportCapability;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.RoleMatrix;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/children")
public class ChildController {

    private final ChildRepository childRepository;
    private final HomeRepository homeRepository;
    private final InterviewRequestRepository interviewRequestRepository;
    private final OrganisationAccessService organisationAccessService;
    private final AuditHistoryService auditHistoryService;
    private final AuditEventPublisher auditEventPublisher;
    private final RoleMatrix roleMatrix;
    private final NameRevealService nameRevealService;

    public ChildController(ChildRepository childRepository, HomeRepository homeRepository,
            InterviewRequestRepository interviewRequestRepository, OrganisationAccessService organisationAccessService,
            AuditHistoryService auditHistoryService, AuditEventPublisher auditEventPublisher,
            RoleMatrix roleMatrix, NameRevealService nameRevealService) {
        this.childRepository = childRepository;
        this.homeRepository = homeRepository;
        this.interviewRequestRepository = interviewRequestRepository;
        this.organisationAccessService = organisationAccessService;
        this.auditHistoryService = auditHistoryService;
        this.auditEventPublisher = auditEventPublisher;
        this.roleMatrix = roleMatrix;
        this.nameRevealService = nameRevealService;
    }

    @GetMapping
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        List<Child> children;
        boolean showHomeColumn;
        if (principal.hasRole(Role.ADMIN)) {
            children = childRepository.findAllWithHome();
            showHomeColumn = true;
        } else if (roleMatrix.isCareProviderOrgAdmin(principal)) {
            children = childRepository.findByHomeOrganisationIdWithHome(principal.getOrganisationId());
            showHomeColumn = true;
        } else if (principal.hasRole(Role.VIEWER)) {
            children = childRepository.findByHomeIdIn(organisationAccessService.homeIdsFor(principal));
            showHomeColumn = true;
        } else {
            // Home staff may hold more than one home since V16, so this is the same query the
            // viewer above runs - and the home column now earns its place whenever it is ambiguous.
            List<Long> homeIds = organisationAccessService.homeIdsFor(principal);
            children = childRepository.findByHomeIdIn(homeIds);
            showHomeColumn = homeIds.size() > 1;
        }
        // Sorted here rather than by the database: the names are encrypted columns now, so an
        // ORDER BY on them would sort ciphertext. Home-grouped lists keep the home order they had.
        List<Child> sorted = children.stream()
                .sorted(showHomeColumn ? ChildRepository.BY_HOME_THEN_NAME : ChildRepository.BY_NAME)
                .toList();
        model.addAttribute("children", sorted);
        model.addAttribute("isAdmin", showHomeColumn);
        model.addAttribute("childIdentities",
                ChildIdentities.mapOf(sorted, c -> c, nameRevealService.isRevealed()));
        return "children/list";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        Child child = childRepository.findDetailedById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such child: " + id));
        if (!principal.hasRole(Role.ADMIN) && !organisationAccessService.canViewHome(principal, child.getHome())) {
            throw new AccessDeniedException("Not authorized to view this child");
        }

        List<InterviewRequest> requests = interviewRequestRepository.findByChildIdOrderByCreatedAtDesc(id);
        long approvedReportCount = requests.stream()
                .filter(r -> r.getStatus().name().equals("REPORT_APPROVED"))
                .count();
        model.addAttribute("child", child);
        model.addAttribute("childIdentity", ChildIdentity.of(child, nameRevealService.isRevealed()));
        model.addAttribute("requests", requests);
        model.addAttribute("caseHistory", auditHistoryService.caseHistoryFor(requests));
        model.addAttribute("canExport", ExportCapability.canExport(principal));
        model.addAttribute("approvedReportCount", approvedReportCount);
        // Opening a child's case history is professional access to a safeguarding record, and is
        // recorded as such. A cover sheet that invites a reader to verify an export against the
        // trail reads oddly if consulting the trail is the one thing the trail does not record.
        auditEventPublisher.auditViewOpened("Child", id,
                child.getHome() == null || child.getHome().getOrganisation() == null
                        ? null : child.getHome().getOrganisation().getId(),
                child.getHome() == null ? null : child.getHome().getId(), principal);
        return "children/detail";
    }

    @GetMapping("/new")
    public String newForm(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        if (!roleMatrix.canCreateChild(principal)) {
            throw new AccessDeniedException("You do not have permission to add a child");
        }
        model.addAttribute("form", new CreateChildForm());
        List<Home> homeOptions = homePickerOptionsFor(principal);
        model.addAttribute("isAdmin", homeOptions != null);
        if (homeOptions != null) {
            model.addAttribute("homes", homeOptions);
        }
        return "children/form";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") CreateChildForm form, BindingResult bindingResult, Model model) {
        if (!roleMatrix.canCreateChild(principal)) {
            throw new AccessDeniedException("You do not have permission to add a child");
        }
        List<Home> homeOptions = homePickerOptionsFor(principal);
        boolean needsHomePicker = homeOptions != null;

        Home home;
        if (needsHomePicker) {
            if (form.getHomeId() == null) {
                bindingResult.addError(new FieldError("form", "homeId", "Please select a home"));
                home = null;
            } else {
                home = homeRepository.findById(form.getHomeId())
                        .orElseThrow(() -> new IllegalArgumentException("No such home: " + form.getHomeId()));
                if (!organisationAccessService.canViewHome(principal, home)) {
                    throw new AccessDeniedException("Home does not belong to your organisation");
                }
            }
        } else {
            // Not reachable: homePickerOptionsFor returns a picker for anyone who could land here
            // with more than one home, so the only remaining case is a single implicit home.
            home = homeRepository.findById(organisationAccessService.homeIdsFor(principal).get(0)).orElseThrow();
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute("isAdmin", needsHomePicker);
            if (needsHomePicker) {
                model.addAttribute("homes", homeOptions);
            }
            return "children/form";
        }

        Child child = new Child();
        child.setFirstName(form.getFirstName());
        child.setLastName(form.getLastName());
        child.setDateOfBirth(form.getDateOfBirth());
        child.setLocalCaseReference(form.getLocalCaseReference());
        child.setHome(home);
        childRepository.save(child);

        return needsHomePicker ? "redirect:/children" : "redirect:/requests/new";
    }


    /** VIEWER is read-only; a Supplier-side ORG_ADMIN has no home to imply and no picker either. */

    /** Null means no picker needed - the home is implied (HOME_STAFF's own home). */
    private List<Home> homePickerOptionsFor(AppUserPrincipal principal) {
        if (principal.hasRole(Role.ADMIN)) {
            return homeRepository.findAllWithOrganisation();
        }
        if (roleMatrix.isCareProviderOrgAdmin(principal)) {
            return homeRepository.findByOrganisationIdWithOrganisation(principal.getOrganisationId());
        }
        // Home staff attached to several homes have to say which one the child belongs to; there is
        // no defensible way to choose for them, and putting a child in the wrong home would put
        // them in front of the wrong staff. One home stays implicit, as it was before V16.
        List<Long> homeIds = organisationAccessService.homeIdsFor(principal);
        if (homeIds.size() > 1) {
            return homeRepository.findAllById(homeIds);
        }
        return null;
    }
}
