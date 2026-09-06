package ninja.samryecroft.returnhome.tracker.report;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.child.NameRevealService;
import ninja.samryecroft.returnhome.tracker.interview.DeadlineTracker;
import ninja.samryecroft.returnhome.tracker.interview.DueBadge;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestService;
import ninja.samryecroft.returnhome.tracker.interview.dto.ConfirmScheduleForm;
import ninja.samryecroft.returnhome.tracker.report.dto.SubmitReportForm;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
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
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@RequestMapping("/visitor")
public class VisitorController {

    private final InterviewRequestService interviewRequestService;
    private final ReportService reportService;
    private final NameRevealService nameRevealService;
    private final AuditEventPublisher auditEventPublisher;

    /**
     * Injected rather than read from {@code LocalDateTime.now()} (T241). Every deadline calculation
     * already takes its {@code now} as a parameter - {@code DeadlineTracker} was written that way -
     * so this controller is where the wall clock actually entered, and where a test previously had
     * to chase it rather than pin it. Production gets the system clock and behaves identically.
     */
    private final Clock clock;

    public VisitorController(InterviewRequestService interviewRequestService, ReportService reportService,
            NameRevealService nameRevealService, AuditEventPublisher auditEventPublisher, Clock clock) {
        this.interviewRequestService = interviewRequestService;
        this.reportService = reportService;
        this.nameRevealService = nameRevealService;
        this.auditEventPublisher = auditEventPublisher;
        this.clock = clock;
    }

    /**
     * Screen 2f. One card per state, each with only its own action.
     *
     * <p>{@code nothingOutstanding} is a separate fact from an empty list and gets separate words
     * (R-Q13): a visitor who has finished everything allocated to them has an empty-of-WORK screen,
     * not an empty screen, and telling them their coordinator will assign interviews here would
     * read as a rebuke for work they have in fact done. Outstanding means "waiting on this visitor"
     * - a submitted report is with the reviewer, so it is not outstanding for them either.
     */
    @GetMapping("/interviews")
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        List<InterviewRequest> requests = interviewRequestService.listForVisitor(principal);
        LocalDateTime now = LocalDateTime.now(clock);

        model.addAttribute("requests", requests);
        model.addAttribute("childIdentities",
                nameRevealService.identitiesFor(requests, InterviewRequest::getChild));
        model.addAttribute("reports", reportService.reportsByRequestId(requests));
        model.addAttribute("dueBadges", requests.stream()
                .filter(r -> DeadlineTracker.badgeFor(r, now).isPresent())
                .collect(Collectors.toMap(InterviewRequest::getId, r -> DeadlineTracker.badgeFor(r, now).orElseThrow())));
        model.addAttribute("nothingOutstanding",
                !requests.isEmpty() && requests.stream().noneMatch(VisitorController::isOutstandingForVisitor));
        return "visitor/interview-list";
    }

    /** HTML5 {@code datetime-local} min/value/max attributes require this exact ISO shape, not the display format. */
    private static final DateTimeFormatter DATETIME_LOCAL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private static final DateTimeFormatter SCHEDULE_ERROR_DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    /**
     * D-5b-6 (spec §7d follow-up, found reviewing #87): {@code getAuthorized} enforces
     * AUTHORIZATION and says nothing about STATUS - {@link InterviewRequestService#confirmSchedule}
     * carries its own precondition, so the POST was always safe, but the GET offered this form at
     * every status, including ones {@code confirmSchedule} would refuse. Two consequences that made
     * this worth fixing rather than leaving as a documented assumption: a status past SCHEDULED (or
     * CANCELLED) makes {@code DeadlineTracker.badgeFor} return empty, rendering a labelled clock row
     * with nothing in it; and the page offered a Confirm button the server would reject outright -
     * the same shape already fixed once on {@code children/list.html} ("a supplier org-admin... was
     * offered an action the server then refused"). Gating here means {@code populateScheduleModel}'s
     * own claim that {@code badgeFor} never returns empty is now a property of the code, not an
     * assumption about every caller, present and future.
     */
    @GetMapping("/interviews/{id}/schedule")
    public String scheduleForm(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        InterviewRequest request = interviewRequestService.getAuthorized(id, principal);
        if (!InterviewRequestService.isAwaitingSchedule(request)) {
            return "redirect:/interview-requests/" + id;
        }
        populateScheduleModel(model, request);
        model.addAttribute("form", new ConfirmScheduleForm());
        return "visitor/schedule-form";
    }

    @PostMapping("/interviews/{id}/schedule")
    public String confirmSchedule(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") ConfirmScheduleForm form, BindingResult bindingResult, Model model) {
        InterviewRequest request = interviewRequestService.getAuthorized(id, principal);
        // D-5b-4 (spec §7d): the sibling of D-187-7 at the other end of the flow - the same
        // impossible-sequence class that reached the compliance rate through heldAt. Caught here,
        // the visitor is present and can fix a mistyped date in seconds; caught nowhere, a document
        // reader meets it months later in a council's copy. Deliberately NOT @Future on the form
        // field itself: a visit time in the past is legitimate (recording after the fact) - only
        // *before the child's return* is impossible, so the check compares against returnedAt, not
        // against now.
        if (form.getScheduledAt() != null && form.getScheduledAt().isBefore(request.getReturnedAt())) {
            bindingResult.rejectValue("scheduledAt", "beforeReturn", "Visit time cannot be before the "
                    + "child returned, " + request.getReturnedAt().format(SCHEDULE_ERROR_DATE_FMT));
        }
        if (bindingResult.hasErrors()) {
            populateScheduleModel(model, request);
            return "visitor/schedule-form";
        }
        interviewRequestService.confirmSchedule(id, form.getScheduledAt(), principal);
        return "redirect:/visitor/interviews";
    }

    /**
     * D-5b-1 (spec §7d): the clock the visitor's choice is measured against, shown above the field
     * on both the fresh form and an error redisplay. {@code deadline} reuses
     * {@link DeadlineTracker#RETURN_WINDOW} rather than restating 72 hours as a literal;
     * {@code timeRemaining} is taken through the same {@link DeadlineTracker#badgeFor} path the
     * queue uses, so the two screens say the same words about the same request - never re-worded
     * here. Always present: both callers reach this only after {@link InterviewRequestService#isAwaitingSchedule}
     * has confirmed ALLOCATED (D-5b-6), which always tracks the deadline, so {@code badgeFor} never
     * returns empty here - a property the GET handler enforces, not an assumption about its caller.
     */
    private void populateScheduleModel(Model model, InterviewRequest request) {
        model.addAttribute("request", request);
        model.addAttribute("childIdentity", nameRevealService.identityFor(request.getChild()));
        model.addAttribute("scheduledAtMin", request.getReturnedAt().format(DATETIME_LOCAL_FMT));
        model.addAttribute("deadline", request.getReturnedAt().plus(DeadlineTracker.RETURN_WINDOW));
        model.addAttribute("timeRemaining", DeadlineTracker.badgeFor(request, LocalDateTime.now(clock))
                .map(DueBadge::text).orElse(null));
    }

    private static final DateTimeFormatter SAVED_AT_FMT = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * Per-step autosave for the report wizard (T174): the same save the "Save draft" button performs,
     * without navigating away from the form.
     *
     * <p><b>It takes the whole form, not the step the visitor just finished, and that is a
     * correctness requirement rather than a convenience.</b> {@code applyFormValues} is a full
     * replacement - every field is written from the form, nulls included - and Spring's form binding
     * cannot distinguish "this field was absent from the request" from "this field was cleared". So a
     * post carrying only step four's fields is not a smaller payload, it is a destructive one: it
     * would blank steps one to three. The stepper's fieldsets are {@code hidden}, not
     * {@code disabled}, so the whole form serialises from the browser anyway - the correct choice is
     * also the simpler one, and it carries the CSRF token along with it.
     *
     * <p><b>The response exists to let the client tell three outcomes apart</b>, because two of them
     * are failures with opposite remedies:
     * <ul>
     *   <li><b>Saved</b> - 200 with a JSON body carrying {@code savedAt}.</li>
     *   <li><b>Terminal</b> - 409 with a JSON body. The report was submitted or approved while the
     *       visitor was typing; retrying will never succeed, so the client must stop and tell them
     *       what happened to the text on their screen.</li>
     *   <li><b>Transient</b> - anything else, and in practice that means an expired session. Retrying
     *       after signing in is exactly right.</li>
     * </ul>
     *
     * <p><b>Why "not a 200" is the wrong test for failure, and why this method answers in JSON.</b>
     * {@code fetch} follows redirects by default, so an expired session does not reach the client as
     * a 302 or a 401 - it reaches it as <em>200 carrying the login page's HTML</em>, with
     * {@code response.ok === true}. A client that only checks the status would report "Saved" at the
     * exact moment the visitor's work was thrown away, which is worse than not autosaving at all.
     * The test that actually holds is <em>200 and a JSON content type</em>, which is why this method
     * returns JSON on the refusal too rather than letting {@code ReportNotEditableException} reach
     * {@code GlobalControllerAdvice} and come back as a 409 whose body is an HTML error page.
     *
     * <p>No new data reaches the database that a submission would not: the same fields, the same
     * encrypted columns, the same key, written earlier and partially. If the organisation has no key
     * the field-encryption listener refuses at flush, exactly as it does for a final submit.
     */
    @PostMapping("/interviews/{id}/report/draft")
    @ResponseBody
    public ResponseEntity<Map<String, String>> autosaveDraft(@PathVariable Long id,
            @AuthenticationPrincipal AppUserPrincipal principal,
            @ModelAttribute("form") SubmitReportForm form, HttpServletRequest request) {
        try {
            reportService.saveDraft(id, form, principal);
        } catch (ReportNotEditableException ex) {
            return terminal(HttpStatus.CONFLICT, ex.getMessage());
        } catch (AccessDeniedException ex) {
            // The second terminal case (Kevin, T174 review): a visitor de-allocated from the
            // interview while they were typing. Left to the advice this is a 403 whose body is HTML,
            // which the client reads as transient and retries forever against a door that will never
            // reopen - the same right-message-wrong-duration failure the 409 case exists to prevent.
            //
            // Answered here rather than by a client-side "403 means terminal" rule, and MEASURING
            // the expired-session case is what settles that: a request with no session is rejected
            // by the CSRF filter with a 403 BEFORE it reaches this method, so a rule keyed on the
            // status would classify an expired session - the most transient failure there is - as
            // terminal and stop autosaving on someone who only needed to sign in again. Only a 403
            // this method itself produced is terminal, and only the server can tell them apart.
            //
            // The audit event is published here because the advice is no longer the one answering.
            // A denial that stops appearing in the trail because a handler got more specific is a
            // silent loss, and this path will produce far more denials than the form ever did once
            // autosave is running.
            auditEventPublisher.accessDenied(principal, request.getMethod(), request.getRequestURI(),
                    ex.getMessage());
            return terminal(HttpStatus.FORBIDDEN, "You are no longer the visitor allocated to this "
                    + "interview, so this report can no longer be saved. Copy anything you still "
                    + "need before leaving this page.");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("outcome", "saved", "savedAt", LocalDateTime.now(clock).format(SAVED_AT_FMT)));
    }

    /**
     * The status stays honest at the HTTP layer - 409 for a report that has moved on, 403 for a
     * visitor who no longer has it - but the <em>decision</em> travels in {@code outcome}, and the
     * client reads only that. It then needs no table of which statuses mean "stop", and could not
     * maintain a correct one anyway: an expired session is also a 403, produced by the CSRF filter
     * before this method runs, and it is the most transient failure there is.
     */
    private ResponseEntity<Map<String, String>> terminal(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("outcome", "terminal", "message", message));
    }

    /**
     * T192: an interview cannot have been held before the child came back.
     *
     * <p>The T187 predicate change stopped such a record corrupting the published compliance rate -
     * it reads as "not measurable" rather than as a pass - but it did not stop the record existing.
     * <b>A state you have to write display language for is usually a state nobody prevented</b>
     * (Creed), and rendering it well is the floor rather than the fix.
     *
     * <p><b>On submit only, never on a draft save.</b> The same reasoning the required-field checks
     * above already follow: a draft is work in progress and a visitor may be typing a date before
     * they have typed the year. Refusing to save half-entered work would lose it, which is the
     * opposite of what save-as-you-go is for. Submission is the point at which the record becomes a
     * claim.
     *
     * <p>The message names both times rather than saying "invalid", because the visitor cannot act
     * on a refusal that will not say what it is comparing - and one of the two is on a different
     * screen, so they cannot simply look.
     */
    private void rejectInterviewBeforeReturn(Long id, AppUserPrincipal principal,
            SubmitReportForm form, BindingResult bindingResult) {
        if (form.getHeldAt() == null) {
            return;
        }
        LocalDateTime returnedAt = interviewRequestService.getAuthorized(id, principal).getReturnedAt();
        if (returnedAt == null || !form.getHeldAt().isBefore(returnedAt)) {
            return;
        }
        bindingResult.rejectValue("heldAt", "beforeReturn",
                "The interview cannot have been held before the child returned. This says the "
                        + "interview was " + form.getHeldAt().format(HELD_AT_FMT) + " and the return "
                        + "was " + returnedAt.format(HELD_AT_FMT) + " - please check which is wrong.");
    }

    private static final java.time.format.DateTimeFormatter HELD_AT_FMT =
            java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", java.util.Locale.UK);

    @GetMapping("/interviews/{id}/report")
    public String reportForm(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        InterviewRequest request = interviewRequestService.getAuthorized(id, principal);
        model.addAttribute("request", request);
        model.addAttribute("childIdentity", nameRevealService.identityFor(request.getChild()));
        model.addAttribute("form", reportService.formFor(id, principal));
        return "visitor/report-form";
    }

    @PostMapping("/interviews/{id}/report")
    public String submit(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal,
            @ModelAttribute("form") SubmitReportForm form, BindingResult bindingResult,
            @RequestParam("action") String action, Model model) {
        if ("submit".equals(action)) {
            if (form.getHeldAt() == null) {
                bindingResult.rejectValue("heldAt", "required", "Date and time the interview was held is required to submit for review");
            }
            if (form.getInterviewLocation() == null || form.getInterviewLocation().isBlank()) {
                bindingResult.rejectValue("interviewLocation", "required", "Location is required to submit for review");
            }
            rejectInterviewBeforeReturn(id, principal, form, bindingResult);
        }
        if (bindingResult.hasErrors()) {
            InterviewRequest request = interviewRequestService.getAuthorized(id, principal);
            model.addAttribute("request", request);
            model.addAttribute("childIdentity", nameRevealService.identityFor(request.getChild()));
            return "visitor/report-form";
        }
        if ("submit".equals(action)) {
            reportService.submitForReview(id, form, principal);
        } else {
            reportService.saveDraft(id, form, principal);
        }
        return "redirect:/visitor/interviews";
    }

    /** Waiting on the VISITOR specifically - a submitted report is with the reviewer, not them. */
    private static boolean isOutstandingForVisitor(InterviewRequest request) {
        return request.getStatus() == InterviewStatus.ALLOCATED
                || request.getStatus() == InterviewStatus.SCHEDULED
                || request.getStatus() == InterviewStatus.REPORT_REJECTED;
    }
}
