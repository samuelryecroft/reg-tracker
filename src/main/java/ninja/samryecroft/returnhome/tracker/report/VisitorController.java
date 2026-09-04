package ninja.samryecroft.returnhome.tracker.report;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.child.ChildIdentities;
import ninja.samryecroft.returnhome.tracker.child.ChildIdentity;
import ninja.samryecroft.returnhome.tracker.child.NameRevealService;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
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

    public VisitorController(InterviewRequestService interviewRequestService, ReportService reportService,
            NameRevealService nameRevealService, AuditEventPublisher auditEventPublisher) {
        this.interviewRequestService = interviewRequestService;
        this.reportService = reportService;
        this.nameRevealService = nameRevealService;
        this.auditEventPublisher = auditEventPublisher;
    }

    @GetMapping("/interviews")
    public String list(@AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        List<InterviewRequest> requests = interviewRequestService.listForVisitor(principal);
        model.addAttribute("requests", requests);
        model.addAttribute("childIdentities",
                ChildIdentities.mapOf(requests, InterviewRequest::getChild, nameRevealService.isRevealed()));
        return "visitor/interview-list";
    }

    @GetMapping("/interviews/{id}/schedule")
    public String scheduleForm(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        InterviewRequest request = interviewRequestService.getAuthorized(id, principal);
        model.addAttribute("request", request);
        model.addAttribute("childIdentity", ChildIdentity.of(request.getChild(), nameRevealService.isRevealed()));
        model.addAttribute("form", new ConfirmScheduleForm());
        return "visitor/schedule-form";
    }

    @PostMapping("/interviews/{id}/schedule")
    public String confirmSchedule(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal,
            @Valid @ModelAttribute("form") ConfirmScheduleForm form, BindingResult bindingResult, Model model) {
        if (bindingResult.hasErrors()) {
            InterviewRequest request = interviewRequestService.getAuthorized(id, principal);
            model.addAttribute("request", request);
            model.addAttribute("childIdentity", ChildIdentity.of(request.getChild(), nameRevealService.isRevealed()));
            return "visitor/schedule-form";
        }
        interviewRequestService.confirmSchedule(id, form.getScheduledAt(), principal);
        return "redirect:/visitor/interviews";
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
                .body(Map.of("outcome", "saved", "savedAt", LocalDateTime.now().format(SAVED_AT_FMT)));
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

    @GetMapping("/interviews/{id}/report")
    public String reportForm(@PathVariable Long id, @AuthenticationPrincipal AppUserPrincipal principal, Model model) {
        InterviewRequest request = interviewRequestService.getAuthorized(id, principal);
        model.addAttribute("request", request);
        model.addAttribute("childIdentity", ChildIdentity.of(request.getChild(), nameRevealService.isRevealed()));
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
        }
        if (bindingResult.hasErrors()) {
            InterviewRequest request = interviewRequestService.getAuthorized(id, principal);
            model.addAttribute("request", request);
            model.addAttribute("childIdentity", ChildIdentity.of(request.getChild(), nameRevealService.isRevealed()));
            return "visitor/report-form";
        }
        if ("submit".equals(action)) {
            reportService.submitForReview(id, form, principal);
        } else {
            reportService.saveDraft(id, form, principal);
        }
        return "redirect:/visitor/interviews";
    }
}
