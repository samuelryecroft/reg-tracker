package ninja.samryecroft.returnhome.tracker.report;

import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
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

    public VisitorController(InterviewRequestService interviewRequestService, ReportService reportService,
            NameRevealService nameRevealService) {
        this.interviewRequestService = interviewRequestService;
        this.reportService = reportService;
        this.nameRevealService = nameRevealService;
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
            @ModelAttribute("form") SubmitReportForm form) {
        try {
            reportService.saveDraft(id, form, principal);
        } catch (ReportNotEditableException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("outcome", "terminal", "message", ex.getMessage()));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("outcome", "saved", "savedAt", LocalDateTime.now().format(SAVED_AT_FMT)));
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
