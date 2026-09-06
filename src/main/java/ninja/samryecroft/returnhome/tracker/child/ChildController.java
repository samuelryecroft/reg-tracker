package ninja.samryecroft.returnhome.tracker.child;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.audit.AuditHistoryService;
import ninja.samryecroft.returnhome.tracker.child.dto.CreateChildForm;
import ninja.samryecroft.returnhome.tracker.export.ExportCapability;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.DeadlineTracker;
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
        boolean revealed = nameRevealService.isRevealed();
        model.addAttribute("children", sorted);
        model.addAttribute("showHomeColumn", showHomeColumn);
        model.addAttribute("childIdentities", ChildIdentities.mapOf(sorted, c -> c, revealed));
        model.addAttribute("childRows",
                sorted.stream().collect(Collectors.toMap(Child::getId, c -> ChildListRow.of(c, revealed))));
        return "children/list";
    }

    /**
     * T193 (PILOT-GATE, spec §7f D-4b-11): {@code children/list.html} took names through
     * {@link ChildIdentities} but read {@code Child}'s date of birth straight off the entity - an
     * {@code @Encrypted} (Article 9) field - so a masked row showed initials alongside an exact
     * birth date in the clear. Gated here the same way {@link ChildIdentity} resolves its own
     * fields: the caller decides {@code revealed} once, and the template receives exactly one
     * already-resolved string - never a hidden value sitting unrendered in the DOM, and never both
     * strings present at once. The original T193 fix also gated the case reference; see below for
     * why that part was reverted.
     *
     * <p><strong>The case reference is never gated - it is not a disclosure to begin with.</strong>
     * Kevin's masked label already carries it on every row ("A.B. · CH-0041" - see
     * {@link ChildIdentity}'s own javadoc); masking defeats a stranger's glance, not a colleague's,
     * by design. An earlier version of this fix masked the column too, "for consistency" - that was
     * wrong: a masked row then read <em>Case reference: Hidden</em> two columns from a name that
     * openly displayed it, so the only value the column withheld was one already on screen. A false
     * "Hidden" doesn't just mislead, it makes the reader act - the one control on offer to see a
     * value that's already visible is the reveal toggle, so a user chasing the reference reveals
     * every child's full name on the page to get back something they could already read. Deciding a
     * value MAY be withheld is a different decision from deciding what the withheld state SAYS, and
     * the column's text is the one a reader actually acts on (Creed, T193 follow-up).
     *
     * <p>A private, page-scoped record rather than a change to {@link ChildIdentity} itself: that
     * type is a separate, already-ruled ticket (making reveal strictly additive), and this page
     * needs to be correct both before and after that change lands.
     *
     * <p>Named {@code dob()}/{@code caseReference()} rather than mirroring {@code Child}'s own
     * accessor names on purpose: T194's guard scans template source for a bare property-read of
     * either encrypted field name, by design with no receiver-type check (documented in its own
     * javadoc as the accepted trade-off against a hand-maintained allow-list) - so a differently-
     * named accessor here can never register as a fresh read of the entity, distinguishing it by
     * construction rather than relying on a reviewer to keep re-deriving that this record's own
     * two fields are already-resolved strings, not the encrypted values themselves.
     */
    private record ChildListRow(String dob, String caseReference) {
        // Pinned explicitly rather than left to inherit the JVM default: the #temporals.format
        // call this replaced resolved its locale through Thymeleaf/Spring's own LocaleResolver
        // (no custom bean configured, so it falls back to the request's Accept-Language, not a
        // fixed one), so leaving this formatter unpinned would be a real behaviour change, not a
        // like-for-like swap - the same class of gap Jim's report-date formatters have (Creed's
        // T193 follow-up).
        private static final DateTimeFormatter DOB_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.UK);
        private static final String HIDDEN = "Hidden";

        static ChildListRow of(Child child, boolean revealed) {
            String dob = revealed
                    ? (child.getDateOfBirth() == null ? "Not recorded" : child.getDateOfBirth().format(DOB_FMT))
                    : HIDDEN;
            String reference = child.getLocalCaseReference() == null || child.getLocalCaseReference().isBlank()
                    ? "—" : child.getLocalCaseReference();
            return new ChildListRow(dob, reference);
        }
    }

    /**
     * D-4b-8 (spec §7e, closed via D-4b-9/T195): the identity block under the {@code <h1>} - date of
     * birth and case reference, in the shipped {@code dl.detail} shape. {@code dateOfBirth} is
     * {@code @Encrypted} Article-9 data, so - the same rule and the same guard (T194) as
     * {@link ChildListRow} - the masked branch resolves to the words themselves and never contains a
     * date of birth at all, not a hidden one: there is nothing here a template could leak by reaching
     * past this record. Naming what is withheld (D-4b-8) beats dropping the row (D-1a-1) - the layout
     * stays stable across the toggle and a masked viewer is told the control exists.
     *
     * <p>{@code caseReference()} is never gated, for the same reason it isn't on
     * {@code children/list.html} (D-4b-12/D-4b-14): {@code childIdentity.label()} already carries it
     * on this same page whenever one is recorded, so a "Hidden" here would misreport a value that is
     * two lines above it.
     *
     * <p><strong>No age, in either state (T195, human, 6 Sep).</strong> Age was considered as a
     * coarsening of the date of birth that might sit outside the reveal (D-4b-9's first draft), but
     * Kevin's axis - <em>who can read it</em>, not how much information it carries - ruled it out: an
     * integer age is legible to a far wider population than the case reference is, which makes it
     * identifying to precisely the population the mask exists to defeat. The human then closed it
     * without a bed-count-dependent escape hatch: there is no home size at which age returns to the
     * screen. There is no age concept anywhere in this system to add one from here.
     *
     * <p>Named {@code dob()}/{@code caseReference()} rather than mirroring {@code Child}'s own
     * accessors, for the same reason as {@link ChildListRow}: T194's guard scans for a bare
     * property-read of either encrypted field name, by design with no receiver-type check, so a
     * differently-named accessor here can never register as a fresh read of the entity.
     */
    private record ChildIdentityDetail(String dob, String caseReference) {
        private static final DateTimeFormatter DOB_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.UK);
        private static final String DOB_HIDDEN = "Hidden — reveal names to show";

        static ChildIdentityDetail of(Child child, boolean revealed) {
            String dob = revealed
                    ? (child.getDateOfBirth() == null ? "Not recorded" : child.getDateOfBirth().format(DOB_FMT))
                    : DOB_HIDDEN;
            String reference = child.getLocalCaseReference() == null || child.getLocalCaseReference().isBlank()
                    ? "—" : child.getLocalCaseReference();
            return new ChildIdentityDetail(dob, reference);
        }
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
        boolean revealed = nameRevealService.isRevealed();
        LocalDateTime now = LocalDateTime.now();
        model.addAttribute("child", child);
        model.addAttribute("childIdentity", ChildIdentity.of(child, revealed));
        model.addAttribute("identityDetail", ChildIdentityDetail.of(child, revealed));
        model.addAttribute("requests", requests);
        // D-4b-7 (spec §7e): a due badge only where DeadlineTracker.badgeFor actually returns one -
        // a completed or cancelled request has no live clock and must show no urgency (D-4a-4's
        // NO_CLOCK rule), so its absence here is meaningful rather than a gap. Same shape as
        // VisitorController's own dueBadges map (screen 2f).
        model.addAttribute("dueBadges", requests.stream()
                .filter(r -> DeadlineTracker.badgeFor(r, now).isPresent())
                .collect(Collectors.toMap(InterviewRequest::getId, r -> DeadlineTracker.badgeFor(r, now).orElseThrow())));
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
        // D-5d-1 (spec §7g): this decides "does this user need to be asked which home", not
        // "is this user an administrator" - a multi-home HOME_STAFF gets the picker too. Named for
        // what it decides, matching what homePickerOptionsFor already returns.
        model.addAttribute("needsHomePicker", homeOptions != null);
        if (homeOptions != null) {
            model.addAttribute("homes", homeOptions);
        }
        model.addAttribute("dobMax", LocalDate.now());
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
                // With the organisation loaded: the T168(b) guard below reads it, and
                // Home.organisation is LAZY under open-in-view=false.
                home = homeRepository.findByIdWithOrganisation(form.getHomeId())
                        .orElseThrow(() -> new IllegalArgumentException("No such home: " + form.getHomeId()));
                if (!organisationAccessService.canViewHome(principal, home)) {
                    throw new AccessDeniedException("Home does not belong to your organisation");
                }
            }
        } else {
            // Not reachable: homePickerOptionsFor returns a picker for anyone who could land here
            // with more than one home, so the only remaining case is a single implicit home.
            home = homeRepository.findByIdWithOrganisation(
                    organisationAccessService.homeIdsFor(principal).get(0)).orElseThrow();
        }

        // T168(b): the organisation must be ACTIVE before it can hold a child's record.
        //
        // WHY HERE AND NOT DEEPER. Three entities carry encrypted fields - Child, InterviewRequest
        // and InterviewReport - so one gate looks partial. It is not: a request requires a child and
        // a report hangs off a request, so for an organisation with no confirmed KEK the child
        // record is the only door into the encrypted class, by structure rather than by luck.
        // EncryptedEntityChokepointTest fails the day that stops being true, which is what makes
        // relying on it safe.
        //
        // WHY NOT AT THE FIELDCRYPTO LAYER, which someone will reasonably propose as defence in
        // depth: fail-closed ALREADY refuses there, and it refuses mid-flush with an error the
        // person cannot act on. That late, opaque refusal is the failure this ticket exists to move
        // earlier. The guard's job is to refuse EARLY with something actionable, not to refuse
        // twice.
        //
        // D-5d-3 (spec §7g): NOT a FieldError on homeId - for a single-home user that field is not
        // even rendered (th:if="${needsHomePicker}"), so the banner's own link pointed at nothing
        // and there was no control to correct anyway (the message says an administrator must act,
        // not this user). This is a page-level condition, so it gets a page-level banner instead,
        // with no anchor offered.
        String orgInactiveError = home != null && !home.getOrganisation().isActive()
                ? "This organisation is not yet active, so records cannot be added for it. "
                        + "An administrator needs to activate it first."
                : null;

        if (bindingResult.hasErrors() || orgInactiveError != null) {
            model.addAttribute("needsHomePicker", needsHomePicker);
            if (needsHomePicker) {
                model.addAttribute("homes", homeOptions);
            }
            model.addAttribute("dobMax", LocalDate.now());
            model.addAttribute("orgInactiveError", orgInactiveError);
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
