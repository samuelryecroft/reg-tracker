package ninja.samryecroft.returnhome.tracker.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.config.AppProperties;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestService;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
import ninja.samryecroft.returnhome.tracker.report.docx.DocxReportGenerator;
import ninja.samryecroft.returnhome.tracker.report.dto.SubmitReportForm;
import ninja.samryecroft.returnhome.tracker.theme.ThemeService;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");
    private static final String NOT_RECORDED = "Not recorded";

    private final InterviewReportRepository interviewReportRepository;
    private final InterviewRequestService interviewRequestService;
    private final UserRepository userRepository;
    private final DocxReportGenerator docxReportGenerator;
    private final AppProperties appProperties;
    private final ThemeService themeService;
    private final AuditEventPublisher auditEventPublisher;
    private final DefaultResourceLoader resourceLoader = new DefaultResourceLoader();

    public ReportService(InterviewReportRepository interviewReportRepository,
            InterviewRequestService interviewRequestService, UserRepository userRepository,
            DocxReportGenerator docxReportGenerator, AppProperties appProperties, ThemeService themeService,
            AuditEventPublisher auditEventPublisher) {
        this.interviewReportRepository = interviewReportRepository;
        this.interviewRequestService = interviewRequestService;
        this.userRepository = userRepository;
        this.docxReportGenerator = docxReportGenerator;
        this.appProperties = appProperties;
        this.themeService = themeService;
        this.auditEventPublisher = auditEventPublisher;
    }

    /** The Visitor's or Reviewer's form, prefilled from an existing draft/rejected/submitted report
     * if one exists, or blank defaults if this is the very first save for this request. */
    public SubmitReportForm formFor(Long requestId, AppUserPrincipal principal) {
        interviewRequestService.getAuthorized(requestId, principal);
        return interviewReportRepository.findByInterviewRequestId(requestId)
                .map(this::toForm)
                .orElseGet(() -> blankFormFor(principal));
    }

    @Transactional
    public InterviewReport saveDraft(Long requestId, SubmitReportForm form, AppUserPrincipal principal) {
        InterviewReport report = existingOrNewReport(requestId, principal);
        applyFormValues(report, form);
        report.setStatus(ReportStatus.DRAFT);
        report.setUpdatedAt(LocalDateTime.now());
        InterviewReport saved = interviewReportRepository.save(report);
        auditEventPublisher.reportDraftSaved(saved, principal);
        return saved;
    }

    @Transactional
    public InterviewReport submitForReview(Long requestId, SubmitReportForm form, AppUserPrincipal principal) {
        InterviewReport report = existingOrNewReport(requestId, principal);
        applyFormValues(report, form);
        report.setStatus(ReportStatus.SUBMITTED);
        report.setSubmittedAt(LocalDateTime.now());
        report.setUpdatedAt(LocalDateTime.now());
        // Clear the previous round's verdict - otherwise a stale rejection comment would still be
        // sitting on the report (and pre-filling the reviewer's form) for this brand new round, and
        // could get silently carried through into an APPROVED report if the reviewer doesn't notice.
        report.setReviewComments(null);
        report.setReviewedBy(null);
        report.setReviewedAt(null);
        report = interviewReportRepository.save(report);
        interviewRequestService.markStatus(report.getInterviewRequest(), InterviewStatus.REPORT_SUBMITTED);
        auditEventPublisher.reportSubmitted(report, principal);
        return report;
    }

    /** Approving may also apply the reviewer's own edits, and is the point the docx is generated -
     * not submission, since the content can still change (reviewer edit, or reject/resubmit) after. */
    @Transactional
    public InterviewReport approve(Long requestId, SubmitReportForm form, AppUserPrincipal principal) {
        InterviewReport report = getReviewable(requestId, principal);
        applyFormValues(report, form);
        report.setStatus(ReportStatus.APPROVED);
        report.setReviewedBy(userRepository.findById(principal.getUserId()).orElseThrow());
        report.setReviewedAt(LocalDateTime.now());
        report.setReviewComments(form.getReviewComments());
        report.setUpdatedAt(LocalDateTime.now());
        String generatedFilename = generateDocx(report);
        report = interviewReportRepository.save(report);
        interviewRequestService.markStatus(report.getInterviewRequest(), InterviewStatus.REPORT_APPROVED);
        auditEventPublisher.docxGenerated(report, generatedFilename, principal);
        auditEventPublisher.reportApproved(report, principal);
        return report;
    }

    @Transactional
    public InterviewReport reject(Long requestId, SubmitReportForm form, AppUserPrincipal principal) {
        if (form.getReviewComments() == null || form.getReviewComments().isBlank()) {
            throw new IllegalArgumentException("Comments are required when rejecting a report");
        }
        InterviewReport report = getReviewable(requestId, principal);
        applyFormValues(report, form);
        report.setStatus(ReportStatus.REJECTED);
        report.setReviewedBy(userRepository.findById(principal.getUserId()).orElseThrow());
        report.setReviewedAt(LocalDateTime.now());
        report.setReviewComments(form.getReviewComments());
        report.setUpdatedAt(LocalDateTime.now());
        report = interviewReportRepository.save(report);
        interviewRequestService.markStatus(report.getInterviewRequest(), InterviewStatus.REPORT_REJECTED);
        auditEventPublisher.reportRejected(report, form.getReviewComments() != null
                && !form.getReviewComments().isBlank(), principal);
        return report;
    }

    public Path resolveDocumentPath(InterviewReport report) {
        return Path.of(appProperties.getDocx().getOutputDir(), report.getGeneratedDocumentPath());
    }

    public InterviewReport getByRequestId(Long requestId) {
        return interviewReportRepository.findByInterviewRequestId(requestId)
                .orElseThrow(() -> new IllegalArgumentException("No report found for request " + requestId));
    }

    /** Loads (or starts) the report row for this request, enforcing only the allocated Visitor
     * (or an ADMIN acting on their behalf) can save/submit it. */
    private InterviewReport existingOrNewReport(Long requestId, AppUserPrincipal principal) {
        InterviewRequest request = interviewRequestService.getAuthorized(requestId, principal);
        boolean isOwner = principal.hasRole(Role.ADMIN)
                || (request.getAllocatedVisitor() != null && request.getAllocatedVisitor().getId().equals(principal.getUserId()));
        if (!isOwner) {
            throw new AccessDeniedException("Only the allocated visitor can edit this report");
        }
        return interviewReportRepository.findByInterviewRequestId(requestId)
                .orElseGet(() -> {
                    InterviewReport report = new InterviewReport();
                    report.setInterviewRequest(request);
                    report.setVisitor(userRepository.findById(principal.getUserId()).orElseThrow());
                    return report;
                });
    }

    /** Loads the report for review, enforcing the principal is a Reviewer, the report is actually
     * awaiting review, and - the conflict-of-interest rule - that they aren't its own Visitor. */
    private InterviewReport getReviewable(Long requestId, AppUserPrincipal principal) {
        interviewRequestService.getAuthorized(requestId, principal);
        if (!principal.hasRole(Role.REVIEWER) && !principal.hasRole(Role.ADMIN)) {
            throw new AccessDeniedException("Only a reviewer can approve or reject a report");
        }
        InterviewReport report = getByRequestId(requestId);
        if (report.getStatus() != ReportStatus.SUBMITTED) {
            throw new IllegalStateException("This report is not awaiting review");
        }
        if (report.getVisitor().getId().equals(principal.getUserId())) {
            throw new AccessDeniedException("You cannot review a report you submitted yourself");
        }
        return report;
    }

    /** @return the generated filename, so the caller can record it on the audit event. */
    private String generateDocx(InterviewReport report) {
        InterviewRequest request = report.getInterviewRequest();
        String filename = "rhi-report-" + request.getId() + "-" + System.currentTimeMillis() + ".docx";
        Path outputPath = Path.of(appProperties.getDocx().getOutputDir(), filename);
        try {
            Resource template = resourceLoader.getResource(appProperties.getDocx().getTemplatePath());
            ThemeService.ThemeView theme = themeService.getForCareProviderOrg(request.getHome().getOrganisation().getId());
            try (InputStream templateStream = template.getInputStream()) {
                docxReportGenerator.generate(templateStream, buildValues(request, report),
                        theme.primaryColor(), theme.primaryColorDark(), theme.secondaryColor(), outputPath);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to generate report document", e);
        }
        report.setGeneratedDocumentPath(filename);
        return filename;
    }

    private SubmitReportForm blankFormFor(AppUserPrincipal principal) {
        SubmitReportForm form = new SubmitReportForm();
        form.setInterviewDate(LocalDate.now());
        form.setConductedByStatement("This interview was conducted by, or under guidance of, "
                + principal.getUser().getFullName()
                + ", an independent, non-statutory agent with no direct involvement in the care "
                + "of the young person interviewed.");
        return form;
    }

    private SubmitReportForm toForm(InterviewReport report) {
        SubmitReportForm form = new SubmitReportForm();
        form.setInterviewDate(report.getInterviewDate());
        form.setInterviewLocation(report.getInterviewLocation());

        form.setWithin72Hours(report.getWithin72Hours());
        form.setIfNotWhyLate(report.getIfNotWhyLate());
        form.setConsultationWithHomeStaff(report.getConsultationWithHomeStaff());
        form.setPreviouslyMissing(report.getPreviouslyMissing());
        form.setMissingOccasionsLast30Days(report.getMissingOccasionsLast30Days());
        form.setConfidentialityExplained(report.getConfidentialityExplained());

        form.setInterviewAccepted(report.getInterviewAccepted());
        form.setInterviewDeclinedReason(report.getInterviewDeclinedReason());
        form.setWhereWereYouWhileMissing(report.getWhereWereYouWhileMissing());
        form.setWhoWereYouWithWhileMissing(report.getWhoWereYouWithWhileMissing());
        form.setWhatMadeYouGoMissing(report.getWhatMadeYouGoMissing());
        form.setWhatCanBeDoneToAddressReasons(report.getWhatCanBeDoneToAddressReasons());
        form.setConsideredSelfMissing(report.getConsideredSelfMissing());
        form.setWhatDidYouDoWhileMissing(report.getWhatDidYouDoWhileMissing());
        form.setWhatHappenedWhenReturned(report.getWhatHappenedWhenReturned());
        form.setPreventFutureMissingSuggestions(report.getPreventFutureMissingSuggestions());
        form.setAdditionalCommentsFromYoungPerson(report.getAdditionalCommentsFromYoungPerson());
        form.setAdditionalInfoFromParentCarer(report.getAdditionalInfoFromParentCarer());

        form.setRisksIdentifiedDuringEpisode(report.getRisksIdentifiedDuringEpisode());
        form.setRisksIncreaseFutureEpisodes(report.getRisksIncreaseFutureEpisodes());
        form.setSafeguardingConcernsToExplore(report.getSafeguardingConcernsToExplore());
        form.setInfoToHelpLocateFuture(report.getInfoToHelpLocateFuture());

        form.setInterviewerComments(report.getInterviewerComments());
        form.setRecommendations(report.getRecommendations());
        form.setConductedByStatement(report.getConductedByStatement());
        form.setDateReportShared(report.getDateReportShared());

        form.setReviewComments(report.getReviewComments());
        return form;
    }

    private void applyFormValues(InterviewReport report, SubmitReportForm form) {
        report.setInterviewDate(form.getInterviewDate());
        report.setInterviewLocation(form.getInterviewLocation());

        report.setWithin72Hours(form.getWithin72Hours());
        report.setIfNotWhyLate(form.getIfNotWhyLate());
        report.setConsultationWithHomeStaff(form.getConsultationWithHomeStaff());
        report.setPreviouslyMissing(form.getPreviouslyMissing());
        report.setMissingOccasionsLast30Days(form.getMissingOccasionsLast30Days());
        report.setConfidentialityExplained(form.getConfidentialityExplained());

        report.setInterviewAccepted(form.getInterviewAccepted());
        report.setInterviewDeclinedReason(form.getInterviewDeclinedReason());
        report.setWhereWereYouWhileMissing(form.getWhereWereYouWhileMissing());
        report.setWhoWereYouWithWhileMissing(form.getWhoWereYouWithWhileMissing());
        report.setWhatMadeYouGoMissing(form.getWhatMadeYouGoMissing());
        report.setWhatCanBeDoneToAddressReasons(form.getWhatCanBeDoneToAddressReasons());
        report.setConsideredSelfMissing(form.getConsideredSelfMissing());
        report.setWhatDidYouDoWhileMissing(form.getWhatDidYouDoWhileMissing());
        report.setWhatHappenedWhenReturned(form.getWhatHappenedWhenReturned());
        report.setPreventFutureMissingSuggestions(form.getPreventFutureMissingSuggestions());
        report.setAdditionalCommentsFromYoungPerson(form.getAdditionalCommentsFromYoungPerson());
        report.setAdditionalInfoFromParentCarer(form.getAdditionalInfoFromParentCarer());

        report.setRisksIdentifiedDuringEpisode(form.getRisksIdentifiedDuringEpisode());
        report.setRisksIncreaseFutureEpisodes(form.getRisksIncreaseFutureEpisodes());
        report.setSafeguardingConcernsToExplore(form.getSafeguardingConcernsToExplore());
        report.setInfoToHelpLocateFuture(form.getInfoToHelpLocateFuture());

        report.setInterviewerComments(form.getInterviewerComments());
        report.setRecommendations(form.getRecommendations());
        report.setConductedByStatement(form.getConductedByStatement());
        report.setDateReportShared(form.getDateReportShared());
    }

    private Map<String, String> buildValues(InterviewRequest request, InterviewReport report) {
        Map<String, String> values = new HashMap<>();

        values.put("childName", request.getChild().getFullName());
        values.put("homeName", request.getHome().getName());
        values.put("visitorName", report.getVisitor().getFullName());
        values.put("requestReceivedAt", request.getCreatedAt().format(DATETIME_FMT));
        values.put("missingEpisodeDate", formatDateTime(request.getMissingSince()));
        values.put("interviewDate", report.getInterviewDate() == null ? NOT_RECORDED : report.getInterviewDate().format(DATE_FMT));
        values.put("interviewLocation", orNotProvided(report.getInterviewLocation()));

        values.put("within72Hours", yesNo(report.getWithin72Hours()));
        values.put("ifNotWhyLate", orNotProvided(report.getIfNotWhyLate()));
        values.put("consultationWithHomeStaff", orNotProvided(report.getConsultationWithHomeStaff()));
        values.put("previouslyMissing", yesNo(report.getPreviouslyMissing()));
        values.put("missingOccasionsLast30Days", report.getMissingOccasionsLast30Days() == null
                ? NOT_RECORDED : String.valueOf(report.getMissingOccasionsLast30Days()));
        values.put("confidentialityExplained", yesNo(report.getConfidentialityExplained()));

        values.put("interviewAccepted", yesNo(report.getInterviewAccepted()));
        values.put("interviewDeclinedReason", orNotProvided(report.getInterviewDeclinedReason()));
        values.put("whereWereYouWhileMissing", orNotProvided(report.getWhereWereYouWhileMissing()));
        values.put("whoWereYouWithWhileMissing", orNotProvided(report.getWhoWereYouWithWhileMissing()));
        values.put("whatMadeYouGoMissing", orNotProvided(report.getWhatMadeYouGoMissing()));
        values.put("whatCanBeDoneToAddressReasons", orNotProvided(report.getWhatCanBeDoneToAddressReasons()));
        values.put("consideredSelfMissing", yesNo(report.getConsideredSelfMissing()));
        values.put("whatDidYouDoWhileMissing", orNotProvided(report.getWhatDidYouDoWhileMissing()));
        values.put("whatHappenedWhenReturned", orNotProvided(report.getWhatHappenedWhenReturned()));
        values.put("preventFutureMissingSuggestions", orNotProvided(report.getPreventFutureMissingSuggestions()));
        values.put("additionalCommentsFromYoungPerson", orNotProvided(report.getAdditionalCommentsFromYoungPerson()));
        values.put("additionalInfoFromParentCarer", orNotProvided(report.getAdditionalInfoFromParentCarer()));

        values.put("risksIdentifiedDuringEpisode", orNotProvided(report.getRisksIdentifiedDuringEpisode()));
        values.put("risksIncreaseFutureEpisodes", orNotProvided(report.getRisksIncreaseFutureEpisodes()));
        values.put("safeguardingConcernsToExplore", orNotProvided(report.getSafeguardingConcernsToExplore()));
        values.put("infoToHelpLocateFuture", orNotProvided(report.getInfoToHelpLocateFuture()));

        values.put("interviewerComments", orNotProvided(report.getInterviewerComments()));
        values.put("recommendations", orNotProvided(report.getRecommendations()));
        values.put("conductedByStatement", orNotProvided(report.getConductedByStatement()));
        values.put("signedLine", "Signed electronically by " + report.getVisitor().getFullName()
                + " on " + report.getSubmittedAt().format(DATETIME_FMT));
        values.put("dateReportShared", report.getDateReportShared() == null
                ? "Not yet shared" : report.getDateReportShared().format(DATE_FMT));

        values.put("generatedAt", LocalDateTime.now().format(DATETIME_FMT));
        return values;
    }

    private String yesNo(Boolean value) {
        if (value == null) {
            return NOT_RECORDED;
        }
        return value ? "Yes" : "No";
    }

    private String orNotProvided(String value) {
        return (value == null || value.isBlank()) ? "Not provided" : value;
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? NOT_RECORDED : value.format(DATETIME_FMT);
    }
}
