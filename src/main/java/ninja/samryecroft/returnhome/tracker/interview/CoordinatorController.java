package ninja.samryecroft.returnhome.tracker.interview;

import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import ninja.samryecroft.returnhome.tracker.child.ChildIdentities;
import ninja.samryecroft.returnhome.tracker.child.ChildIdentity;
import ninja.samryecroft.returnhome.tracker.child.NameRevealService;
import ninja.samryecroft.returnhome.tracker.interview.dto.AllocateAndScheduleForm;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/coordinator")
public class CoordinatorController {

    private final InterviewRequestService interviewRequestService;
    private final UserRepository userRepository;
    private final DeadlineTrackingService deadlineTrackingService;
    private final NameRevealService nameRevealService;

    public CoordinatorController(InterviewRequestService interviewRequestService, UserRepository userRepository,
            DeadlineTrackingService deadlineTrackingService, NameRevealService nameRevealService) {
        this.interviewRequestService = interviewRequestService;
        this.userRepository = userRepository;
        this.deadlineTrackingService = deadlineTrackingService;
        this.nameRevealService = nameRevealService;
    }

    /**
     * {@code homeId} and {@code filter} exist so the roadmap 2.3 dashboard's tiles and breakdown
     * rows are real links, not dead ends - "the list it opens visibly matches the tile" (Oscar's
     * dashboard-build-brief.md). Both are pure narrowing of what {@code listVisible} already
     * authorized this principal to see, so neither widens access.
     */
    @GetMapping("/requests")
    public String list(@AuthenticationPrincipal AppUserPrincipal principal,
            @RequestParam(required = false) Long homeId, @RequestParam(required = false) String filter, Model model) {
        List<InterviewRequest> requests = interviewRequestService.listVisible(principal);
        if (homeId != null) {
            requests = requests.stream().filter(r -> r.getHome().getId().equals(homeId)).toList();
        }
        if (filter != null) {
            LocalDateTime now = LocalDateTime.now();
            requests = requests.stream().filter(r -> matchesFilter(r, filter, now)).toList();
        }
        model.addAttribute("requests", requests);
        model.addAttribute("dueGroups", deadlineTrackingService.groupByUrgency(requests));
        model.addAttribute("homeId", homeId);
        model.addAttribute("filter", filter);
        model.addAttribute("childIdentities",
                ChildIdentities.mapOf(requests, InterviewRequest::getChild, nameRevealService.isRevealed()));
        return "coordinator/requests";
    }

    private boolean matchesFilter(InterviewRequest r, String filter, LocalDateTime now) {
        return switch (filter) {
            case "overdue" -> DeadlineTracker.stateOf(r, now).map(s -> s == DueState.OVERDUE).orElse(false);
            case "dueSoon" -> DeadlineTracker.stateOf(r, now).map(s -> s == DueState.DUE_SOON).orElse(false);
            case "noClock" -> DeadlineTracker.stateOf(r, now).map(s -> s == DueState.NO_CLOCK).orElse(false);
            case "consent" -> (r.getStatus() == InterviewStatus.ALLOCATED || r.getStatus() == InterviewStatus.SCHEDULED)
                    && (r.getConsentProvided() == null || !r.getConsentProvided());
            case "unallocated" -> r.getStatus() == InterviewStatus.REQUESTED;
            case "awaitingReview" -> r.getStatus() == InterviewStatus.REPORT_SUBMITTED;
            default -> true;
        };
    }

    @GetMapping("/requests/{id}/allocate")
    public String allocateForm(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        InterviewRequest request = interviewRequestService.getAuthorized(id, principal);
        model.addAttribute("request", request);
        model.addAttribute("childIdentity", ChildIdentity.of(request.getChild(), nameRevealService.isRevealed()));
        model.addAttribute("form", new AllocateAndScheduleForm());
        model.addAttribute("visitors", visitorsFor(principal));
        return "coordinator/allocate-form";
    }

    @PostMapping("/requests/{id}/allocate")
    public String allocate(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") AllocateAndScheduleForm form, BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            InterviewRequest request = interviewRequestService.getAuthorized(id, principal);
            model.addAttribute("request", request);
            model.addAttribute("childIdentity", ChildIdentity.of(request.getChild(), nameRevealService.isRevealed()));
            model.addAttribute("visitors", visitorsFor(principal));
            return "coordinator/allocate-form";
        }
        interviewRequestService.allocateAndSchedule(id, form, principal);
        return "redirect:/coordinator/requests";
    }

    /**
     * D-4a-2 (spec §7b): "a coordinator allocating blind cannot load-balance, and an overloaded
     * visitor is how a 72-hour deadline gets missed" - so the visitor list carries each one's
     * CURRENT LOAD, sorted least-loaded first, rather than a bare name list. Platform ADMIN sees
     * every visitor; a coordinator/org-admin only their own organisation's (unchanged from before).
     *
     * <p>D-4a-4 (spec §7c): a bare count is a blunt instrument on a 72-hour clock, so each option
     * also carries the worst due-state tier among its open work. Primary sort stays count-ascending
     * (so #75's original test still holds); the tiebreak at equal counts is least-urgent-first -
     * deliberately not a compound tier-then-count sort, which would be more "correct" and less
     * legible ("a sort the reader cannot predict is worse than a slightly cruder one").
     */
    private List<VisitorOption> visitorsFor(AppUserPrincipal principal) {
        List<User> visitors = principal.hasRole(Role.ADMIN)
                ? userRepository.findByRoleOrderByFullName(Role.VISITOR)
                : userRepository.findByRoleAndOrganisationId(Role.VISITOR, principal.getOrganisationId());
        LocalDateTime now = LocalDateTime.now();
        return visitors.stream()
                .map(v -> visitorOption(v, now))
                .sorted(Comparator.comparingLong(VisitorOption::openAllocations)
                        .thenComparingInt(VisitorOption::urgencyRank))
                .toList();
    }

    /**
     * "Current load" means work still on this visitor's plate: allocated or scheduled but not yet
     * visited-and-written-up, or sent back and awaiting a rewrite. Once a report is submitted the
     * ball is in the reviewer's court, and once it's approved/cancelled the record is closed - so
     * neither counts against the visitor a coordinator is trying to load-balance for a NEW request.
     *
     * <p>Of that open work, only ALLOCATED/SCHEDULED rows are still subject to the pre-interview
     * 72-hour clock ({@link DeadlineTracker#tracksDeadline}) - a REPORT_REJECTED row's interview
     * already happened, so {@link DeadlineTracker#stateOf} returns empty for it. D-4a-4b: it still
     * contributes its OWN rung ("sent back") rather than falling out of the tier entirely - the
     * status itself, not a due-state, is what makes a rejected row count as pressure.
     */
    private VisitorOption visitorOption(User visitor, LocalDateTime now) {
        List<InterviewRequest> open = interviewRequestService.listAllocatedTo(visitor.getId()).stream()
                .filter(r -> r.getStatus() == InterviewStatus.ALLOCATED
                        || r.getStatus() == InterviewStatus.SCHEDULED
                        || r.getStatus() == InterviewStatus.REPORT_REJECTED)
                .toList();
        long overdueCount = open.stream()
                .filter(r -> DeadlineTracker.stateOf(r, now).filter(s -> s == DueState.OVERDUE).isPresent())
                .count();
        long dueSoonCount = open.stream()
                .filter(r -> DeadlineTracker.stateOf(r, now).filter(s -> s == DueState.DUE_SOON).isPresent())
                .count();
        long sentBackCount = open.stream().filter(r -> r.getStatus() == InterviewStatus.REPORT_REJECTED).count();
        return new VisitorOption(visitor.getId(), visitor.getFullName(), open.size(), overdueCount, dueSoonCount, sentBackCount);
    }

    /**
     * One row of the D-4a-2/D-4a-4/D-4a-4b visitor list: a stable id/name/load quintuple, sorted
     * before rendering. {@code overdueCount}/{@code dueSoonCount}/{@code sentBackCount} are counts
     * WITHIN {@code openAllocations}, not additional totals.
     */
    public record VisitorOption(Long id, String fullName, long openAllocations, long overdueCount, long dueSoonCount,
            long sentBackCount) {

        /**
         * D-4a-4/D-4a-4b: the ladder, most to least constraining - overdue -> due soon -> sent
         * back -> nothing - as ONE ordering, not two. {@link #urgencyRank()} (the sort tiebreak)
         * and {@link #urgencyNote()} (the displayed suffix) both derive from this single method
         * rather than each re-deriving the precedence in its own if-chain: two independent
         * if-ladders encoding the same decision agree only because they were written together, and
         * a later rung added to one but not the other would let the shown suffix and the sort
         * order silently disagree (a visitor reading "1 overdue" while sorting as though on
         * track) - Creed's review of #76 (spec §7c), the same one-dataset-one-rendering shape
         * behind the table/card duplication and the 1a/1b markup split.
         */
        private Rung rung() {
            if (overdueCount > 0) {
                return Rung.OVERDUE;
            }
            if (dueSoonCount > 0) {
                return Rung.DUE_SOON;
            }
            if (sentBackCount > 0) {
                return Rung.SENT_BACK;
            }
            return Rung.NOTHING;
        }

        /** Ascending = least urgent first, used only as the equal-count tiebreak in {@link #visitorsFor}. */
        private int urgencyRank() {
            return rung().ordinal();
        }

        /**
         * The single most constraining fact only, with its own count - not a breakdown, because a
         * list row isn't a table and the tier that constrains a visitor is the one that decides
         * whether they can take another case. {@code null} when there's nothing to name.
         *
         * <p>Reuses {@link DueStateCopy}'s bare OVERDUE word and {@link InterviewStatus#REPORT_REJECTED}'s
         * own display word (both lower-cased into the count phrase, never a full statutory-surface
         * sentence - this is a workload figure, not a compliance one) and
         * {@link DeadlineTracker#DUE_SOON_THRESHOLD} rather than restating any of them.
         */
        public String urgencyNote() {
            return switch (rung()) {
                case OVERDUE -> overdueCount + " " + DueStateCopy.stateWord(DueState.OVERDUE).toLowerCase(Locale.ROOT);
                case DUE_SOON -> dueSoonCount + " due within " + DeadlineTracker.DUE_SOON_THRESHOLD.toHours() + " hours";
                case SENT_BACK -> sentBackCount + " " + InterviewStatus.REPORT_REJECTED.getDisplayName().toLowerCase(Locale.ROOT);
                case NOTHING -> null;
            };
        }

        /**
         * The whole load figure for the radio row, e.g. "3 open allocations · 1 overdue". Zero
         * reads "No open allocations" - the state a coordinator is hunting for, so it should read
         * like an answer rather than a measurement - never "0 open allocations".
         *
         * <p>D-4a-4a: no semantic colour here - the figure is rendered in one plain-muted span
         * regardless of tier, because on a person's row the same fact means something different
         * than it does on a case card ("this visitor is already carrying pressure", not "this
         * visitor is failing").
         */
        public String loadSummary() {
            if (openAllocations == 0) {
                return "No open allocations";
            }
            String base = openAllocations + (openAllocations == 1 ? " open allocation" : " open allocations");
            String note = urgencyNote();
            return note == null ? base : base + " · " + note;
        }

        /** Ordinal order IS the ladder - least constraining first, so {@link #urgencyRank} is free. */
        private enum Rung {
            NOTHING, SENT_BACK, DUE_SOON, OVERDUE
        }
    }
}
