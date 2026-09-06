package ninja.samryecroft.returnhome.tracker.audit;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import ninja.samryecroft.returnhome.tracker.report.ReportStatus;
import org.springframework.stereotype.Service;

/**
 * Builds the V1 per-record "History" timeline (audit-mockups.html §01) for the three places it's
 * shown: an interview request/report, a child (across all their requests), and a user account.
 *
 * <p>This is the one place raw {@link AuditEvent} rows are translated into something a template may
 * render. The translation is deliberately an allow-list, not a filter: each {@link AuditEventType}
 * is handled explicitly below, and only ids/statuses/timestamps/roles ever reach an
 * {@link AuditHistoryEntry} - metadata keys that hold free text (a filename, an access-denied
 * reason, whether review comments exist beyond a yes/no flag) are never read. Callers are
 * responsible for authorizing the caller against the record itself before calling in here (the
 * three controllers that use this already do, via the same checks their pages have always used);
 * this service does not re-check organisation scope on its own.
 */
@Service
public class AuditHistoryService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMMM yyyy");
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMM yyyy");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");

    /** Sign-in monitoring is explicitly out of scope for V1 (gated on an unresolved GDPR policy call). */
    private static final Set<AuditEventType> EXCLUDED_FROM_USER_HISTORY =
            Set.of(AuditEventType.LOGIN_SUCCESS, AuditEventType.LOGIN_FAILURE);

    /**
     * Roadmap 2.5's audit feed is deliberately "case activity" only (Oscar's T35 split) - interview
     * request/report lifecycle, never sign-in, account-admin or access-denied events. This is what
     * keeps the ADMIN sign-in-events export out of MVP for free: those types simply never appear
     * here, rather than needing a separate check.
     */
    private static final Set<AuditEventType> CASE_ACTIVITY_TYPES = Set.of(
            AuditEventType.INTERVIEW_REQUEST_CREATED, AuditEventType.INTERVIEW_REQUEST_ALLOCATED,
            AuditEventType.INTERVIEW_REQUEST_SCHEDULED, AuditEventType.INTERVIEW_REQUEST_RETURN_TIME_RECORDED,
            AuditEventType.REPORT_DRAFT_SAVED, AuditEventType.REPORT_SUBMITTED,
            AuditEventType.REPORT_APPROVED, AuditEventType.REPORT_REJECTED,
            AuditEventType.DOCX_GENERATED, AuditEventType.DOCX_DOWNLOADED);

    /**
     * How the WHEN column reads. TIME is used inside a day-grouped section (the day is already the
     * heading); SHORT_DATE is used inside a request-grouped section (the child page's case history
     * spans months, so every row needs its own date - audit-mockups.html §01).
     */
    private enum WhenStyle { TIME, SHORT_DATE }

    private final AuditEventRepository auditEventRepository;
    private final InterviewReportRepository interviewReportRepository;

    public AuditHistoryService(AuditEventRepository auditEventRepository,
            InterviewReportRepository interviewReportRepository) {
        this.auditEventRepository = auditEventRepository;
        this.interviewReportRepository = interviewReportRepository;
    }

    /** The request's own lifecycle, plus its report's lifecycle once one exists - one combined story. */
    public List<AuditHistorySection> historyFor(InterviewRequest request, DraftSaveRuns draftSaveRuns) {
        List<AuditEvent> events = new ArrayList<>(
                auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("InterviewRequest", request.getId()));
        interviewReportRepository.findByInterviewRequestId(request.getId()).ifPresent(report ->
                events.addAll(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("InterviewReport", report.getId())));
        // Each finder returns its own rows newest-first, but CONCATENATING two sorted lists does not
        // produce a sorted one: every request event lands ahead of every report event whatever the
        // clock said. caseHistoryFor has always re-sorted after the same concatenation and this
        // method never did. T177 is what makes that load-bearing rather than cosmetic - "consecutive
        // draft saves" is a claim about time order, so a run computed over an unsorted list is not
        // the run the reader is looking at.
        events.sort(Comparator.comparing(AuditEvent::getOccurredAt).reversed());
        return groupByDay(events, WhenStyle.TIME, draftSaveRuns);
    }

    /** Cross-request "case history": every request raised for this child, each its own section. */
    public List<AuditHistorySection> caseHistoryFor(List<InterviewRequest> requests, DraftSaveRuns draftSaveRuns) {
        if (requests.isEmpty()) {
            return List.of();
        }
        List<Long> requestIds = requests.stream().map(InterviewRequest::getId).toList();
        List<Long> reportIds = requests.stream()
                .map(r -> interviewReportRepository.findByInterviewRequestId(r.getId()).map(InterviewReport::getId).orElse(null))
                .filter(Objects::nonNull)
                .toList();

        List<AuditEvent> all = new ArrayList<>(
                auditEventRepository.findByTargetTypeAndTargetIdInOrderByOccurredAtDesc("InterviewRequest", requestIds));
        if (!reportIds.isEmpty()) {
            all.addAll(auditEventRepository.findByTargetTypeAndTargetIdInOrderByOccurredAtDesc("InterviewReport", reportIds));
        }

        Map<Long, InterviewReport> reportByRequestId = new LinkedHashMap<>();
        for (InterviewRequest r : requests) {
            interviewReportRepository.findByInterviewRequestId(r.getId()).ifPresent(report -> reportByRequestId.put(r.getId(), report));
        }

        List<AuditHistorySection> sections = new ArrayList<>();
        for (InterviewRequest request : requests.stream()
                .sorted(Comparator.comparing(InterviewRequest::getCreatedAt).reversed()).toList()) {
            InterviewReport report = reportByRequestId.get(request.getId());
            List<AuditEvent> forThisRequest = all.stream()
                    .filter(e -> ("InterviewRequest".equals(e.getTargetType()) && request.getId().equals(e.getTargetId()))
                            || (report != null && "InterviewReport".equals(e.getTargetType()) && report.getId().equals(e.getTargetId())))
                    .sorted(Comparator.comparing(AuditEvent::getOccurredAt).reversed())
                    .toList();
            if (forThisRequest.isEmpty()) {
                continue;
            }
            String label = "Request #" + request.getId() + " — " + request.getCreatedAt().format(MONTH_YEAR);
            sections.add(new AuditHistorySection(label, toEntries(forThisRequest, WhenStyle.SHORT_DATE, draftSaveRuns)));
        }
        return sections;
    }

    /**
     * Roadmap 2.5's org-wide case-activity feed: every request in {@code requestsInScope} (already
     * org-scoped by the caller, the same "fetch scope, then compute" pattern the dashboard uses),
     * filtered by home and date range, resolved back to its home/child so a multi-child feed can
     * show and link them. Sign-in/account/access-denied events never appear (see
     * {@link #CASE_ACTIVITY_TYPES}), which is what keeps the platform-ADMIN sign-in-export question
     * out of scope here rather than needing a separate exclusion.
     *
     * <p><strong>Same two-consumer shape as {@link #caseHistoryFor}, and it takes no
     * {@link DraftSaveRuns} because it collapses nothing: one row per event, always.</strong>
     * {@code AuditFeedController} builds the org-wide feed SCREEN from this, and its
     * {@code exportCsv} builds the audit-trail CSV - a disclosure, taken under a purpose and a
     * reference and recorded as its own audit event. That the CSV is complete is therefore true
     * <em>by construction</em> rather than by decision, which is exactly the position
     * {@code caseHistoryFor} was in before T177 and where it went wrong. Anyone collapsing this for
     * the screen has to split the two callers first, and {@code AuditFeedNeverCollapsesTest} is
     * what will stop them from not noticing.
     */
    public List<AuditFeedRow> caseActivityFeed(List<InterviewRequest> requestsInScope, Long homeIdFilter,
            LocalDate from, LocalDate to) {
        if (requestsInScope.isEmpty()) {
            return List.of();
        }
        Set<Long> organisationIds = requestsInScope.stream()
                .map(r -> r.getHome().getOrganisation().getId())
                .collect(Collectors.toSet());
        Map<Long, InterviewRequest> requestById = requestsInScope.stream()
                .collect(Collectors.toMap(InterviewRequest::getId, r -> r, (a, b) -> a));
        List<Long> requestIds = requestsInScope.stream().map(InterviewRequest::getId).toList();
        Map<Long, Long> requestIdByReportId = interviewReportRepository.findByInterviewRequestIdIn(requestIds).stream()
                .collect(Collectors.toMap(InterviewReport::getId, r -> r.getInterviewRequest().getId()));

        List<AuditFeedRow> rows = new ArrayList<>();
        for (AuditEvent event : auditEventRepository.findByOrganisationIdIn(organisationIds)) {
            if (!CASE_ACTIVITY_TYPES.contains(event.getEventType())) {
                continue;
            }
            Long requestId = "InterviewRequest".equals(event.getTargetType()) ? event.getTargetId()
                    : "InterviewReport".equals(event.getTargetType()) ? requestIdByReportId.get(event.getTargetId())
                    : null;
            InterviewRequest request = requestId == null ? null : requestById.get(requestId);
            if (request == null) {
                continue;
            }
            if (homeIdFilter != null && !homeIdFilter.equals(request.getHome().getId())) {
                continue;
            }
            LocalDate day = event.getOccurredAt().toLocalDate();
            if ((from != null && day.isBefore(from)) || (to != null && day.isAfter(to))) {
                continue;
            }
            rows.add(new AuditFeedRow(toEntry(event, WhenStyle.SHORT_DATE), request.getHome().getName(),
                    request.getChild().getFullName(), request.getId()));
        }
        rows.sort(Comparator.comparing((AuditFeedRow row) -> row.entry().occurredAt()).reversed());
        return rows;
    }

    /** A user account's own audit trail - role/enabled/password changes, never sign-in activity. */
    public List<AuditHistorySection> historyForUser(Long userId, DraftSaveRuns draftSaveRuns) {
        List<AuditEvent> events = auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("User", userId)
                .stream()
                .filter(e -> !EXCLUDED_FROM_USER_HISTORY.contains(e.getEventType()))
                .toList();
        return groupByDay(events, WhenStyle.TIME, draftSaveRuns);
    }

    /**
     * Maps one section's events to rows, collapsing each run of consecutive ordinary draft saves
     * into a single row (T177).
     *
     * <p><strong>The rule is DRAFT &rarr; DRAFT only; a transition is never collapsed.</strong>
     * With T174's per-step autosave there is a REPORT_DRAFT_SAVED for every step of every report,
     * so the naive rule - collapse consecutive REPORT_DRAFT_SAVED - would fold the
     * REJECTED &rarr; DRAFT save into the run above it. That save is the moment a visitor began
     * reworking a report a reviewer sent back: the one draft save anybody ever goes looking for,
     * and the reason #67 put {@code statusBefore} on the event in the first place.
     *
     * <p>A run also breaks on a change of actor role. Folding a home-staff save into an admin's
     * would silently restate <em>who</em> did something, and the only thing this projection is for
     * is that a row's facts are its event's facts.
     *
     * <p>{@code draftSaveRuns} is what keeps the case-file export out of this: see
     * {@link DraftSaveRuns}. The export reaches the timeline through the same builder, which is
     * what made this change need no template edit and is also what would have collapsed a
     * disclosure as a side effect of tidying a screen.
     *
     * <p><strong>Display-only and reversible.</strong> Nothing is dropped and nothing is filtered:
     * the rows underneath are untouched and still answer "how many times was this revised, and
     * when" for a DPO or a court - and so does the collapsed row itself, which carries the count
     * and the span rather than hiding them behind an affordance.
     */
    private List<AuditHistoryEntry> toEntries(List<AuditEvent> events, WhenStyle whenStyle,
            DraftSaveRuns draftSaveRuns) {
        List<AuditHistoryEntry> entries = new ArrayList<>();
        int i = 0;
        while (i < events.size()) {
            int end = draftSaveRuns == DraftSaveRuns.COLLAPSED ? endOfDraftSaveRun(events, i) : i + 1;
            entries.add(end - i > 1 ? collapsedDraftSaves(events.subList(i, end), whenStyle)
                    : toEntry(events.get(i), whenStyle));
            i = end;
        }
        return entries;
    }

    /** Exclusive end of the run starting at {@code from}, or {@code from + 1} if none starts there. */
    private int endOfDraftSaveRun(List<AuditEvent> events, int from) {
        AuditEvent first = events.get(from);
        if (!isOrdinaryDraftSave(first)) {
            return from + 1;
        }
        int end = from + 1;
        while (end < events.size() && isOrdinaryDraftSave(events.get(end))
                && Objects.equals(first.getActorRolesAtTime(), events.get(end).getActorRolesAtTime())) {
            end++;
        }
        return end;
    }

    /**
     * A draft save that overwrote a draft - the only kind that may be collapsed.
     *
     * <p>Everything else is a beginning and stands on its own row: REJECTED means the rework of a
     * sent-back report started here, and an absent {@code statusBefore} (the builder writes
     * {@code "none"} for a null) means this save CREATED the report. Written as "is it DRAFT"
     * rather than "is it not REJECTED" on purpose - a status added to ReportStatus later is then
     * excluded until somebody decides it should not be, which is the direction that fails safe.
     */
    private boolean isOrdinaryDraftSave(AuditEvent event) {
        return event.getEventType() == AuditEventType.REPORT_DRAFT_SAVED
                && ReportStatus.DRAFT.name().equals(parseMetadata(event.getMetadata()).get("statusBefore"));
    }

    /**
     * One row standing for a whole run. {@code run} is newest-first, like everything else here.
     *
     * <p>It takes the newest save's id and instant, so it sits where the run's most recent event
     * sat and a reader following the id lands on that event rather than on an arbitrary member.
     */
    private AuditHistoryEntry collapsedDraftSaves(List<AuditEvent> run, WhenStyle whenStyle) {
        AuditEvent latest = run.get(0);
        String from = display(run.get(run.size() - 1), whenStyle);
        String to = display(latest, whenStyle);
        // Null when both ends render the same - several saves inside one minute, or (on the child
        // page, whose column is date-only) inside one day. The WHEN column already says it, and a
        // detail reading "09:14 - 09:14" is noise wearing the clothes of information.
        String span = from.equals(to) ? null : from + " – " + to;
        return new AuditHistoryEntry(latest.getId(), "Draft saved (" + run.size() + " times)",
                latest.getOccurredAt(), to, formatRoles(latest.getActorRolesAtTime()), span, "");
    }

    private String display(AuditEvent event, WhenStyle whenStyle) {
        return whenStyle == WhenStyle.TIME ? event.getOccurredAt().format(TIME)
                : event.getOccurredAt().format(SHORT_DATE);
    }

    /**
     * Days are cut from the EVENTS, before {@link #toEntries} collapses anything, so a run that
     * spans midnight collapses into one row per day rather than one row filed under whichever day
     * happened to be first. A day heading that has rows under it the day did not contain is a
     * worse defect than the noise this card exists to remove.
     */
    private List<AuditHistorySection> groupByDay(List<AuditEvent> events, WhenStyle whenStyle,
            DraftSaveRuns draftSaveRuns) {
        Map<LocalDate, List<AuditEvent>> byDay = new LinkedHashMap<>();
        for (AuditEvent event : events) {
            byDay.computeIfAbsent(event.getOccurredAt().toLocalDate(), d -> new ArrayList<>()).add(event);
        }
        LocalDate today = LocalDate.now();
        List<AuditHistorySection> sections = new ArrayList<>();
        for (Map.Entry<LocalDate, List<AuditEvent>> dayEntry : byDay.entrySet()) {
            sections.add(new AuditHistorySection(dayLabel(dayEntry.getKey(), today),
                    toEntries(dayEntry.getValue(), whenStyle, draftSaveRuns)));
        }
        return sections;
    }

    /**
     * Groups the org-wide feed (2g) into days, using the SAME {@link #dayLabel} the single record's
     * timeline uses. Two implementations of "Today" is how one screen comes to disagree with
     * another about which day an event happened on - and both would look right in isolation.
     */
    public List<AuditFeedDay> groupFeedByDay(List<AuditFeedRow> rows) {
        Map<LocalDate, List<AuditFeedRow>> byDay = new LinkedHashMap<>();
        for (AuditFeedRow row : rows) {
            byDay.computeIfAbsent(row.entry().occurredAt().toLocalDate(), d -> new ArrayList<>()).add(row);
        }
        LocalDate today = LocalDate.now();
        List<AuditFeedDay> days = new ArrayList<>();
        byDay.forEach((day, dayRows) -> days.add(new AuditFeedDay(dayLabel(day, today), dayRows)));
        return days;
    }

    static String dayLabel(LocalDate day, LocalDate today) {
        if (day.equals(today)) {
            return "Today — " + day.format(DATE);
        }
        if (day.equals(today.minusDays(1))) {
            return "Yesterday — " + day.format(DATE);
        }
        return day.format(DATE);
    }

    private AuditHistoryEntry toEntry(AuditEvent event, WhenStyle whenStyle) {
        Map<String, String> meta = parseMetadata(event.getMetadata());
        String role = formatRoles(event.getActorRolesAtTime());
        String when = display(event, whenStyle);
        return switch (event.getEventType()) {
            case INTERVIEW_REQUEST_CREATED -> entry("Interview requested", event, when, role, null, "info");
            case INTERVIEW_REQUEST_ALLOCATED -> entry("Visitor allocated", event, when, role, transition(meta), "info");
            case INTERVIEW_REQUEST_SCHEDULED -> entry("Interview scheduled", event, when, role, scheduledAtDetail(meta), "info");
            case REPORT_DRAFT_SAVED -> entry("Draft saved", event, when, role, null, "");
            case REPORT_SUBMITTED -> entry("Report submitted for review", event, when, role, statusDetail(meta), "info");
            case REPORT_APPROVED -> entry("Report approved", event, when, role, statusDetail(meta), "ok");
            case REPORT_REJECTED -> entry("Report sent back for revision", event, when, role, rejectionDetail(meta), "back");
            case DOCX_GENERATED -> entry("Report document produced", event, when, role, null, "");
            case DOCX_DOWNLOADED -> entry("Report downloaded", event, when, role, null, "");
            case USER_CREATED -> entry("User account created", event, when, role, rolesDetail(meta.get("rolesAssigned")), "info");
            case USER_UPDATED -> entry("User account updated", event, when, role, userUpdateDetail(meta), "info");
            // ACCESS_DENIED has no meaningful target linkage for a per-record view, and its metadata
            // is free text throughout; LOGIN_SUCCESS/FAILURE are excluded upstream for the user page
            // and never match a request/report/child target in the first place.
            default -> entry(event.getEventType().name(), event, when, role, null, "");
        };
    }

    private AuditHistoryEntry entry(String headline, AuditEvent event, String when, String role, String detail, String tone) {
        return new AuditHistoryEntry(event.getId(), headline, event.getOccurredAt(), when, role, detail, tone);
    }

    private String transition(Map<String, String> meta) {
        return formatted(meta, "statusBefore", "statusAfter");
    }

    private String scheduledAtDetail(Map<String, String> meta) {
        return parseAndFormatTimestamp(meta.get("scheduledAt"));
    }

    /**
     * The report's state, in the word the system itself uses for it.
     *
     * <p>This went through {@link #titleCase} - the generic formatter shared with role names - and
     * so rendered REJECTED as "Rejected", the exact word Creed's #45 follow-up had renamed to "Sent
     * back" because <em>"Rejected" reads as a verdict where the reality is a request for more
     * detail</em>. The result was one row saying the event twice in two vocabularies three words
     * apart: the headline already reads "Report sent back for revision".
     *
     * <p>Worth being precise about what was wrong, because it decides the shape of the fix.
     * "Rejected" was never a decision competing with Creed's - it was titleCase over an enum
     * constant, i.e. <strong>the absence of a vocabulary decision rather than a rival one</strong>.
     * So the fix is to ask {@link ReportStatus} what it calls the state, not to special-case one
     * string inside a helper that also formats roles: that would make the word right by coincidence
     * of the formatter, and leave the next status wrong in the same way.
     */
    private String statusDetail(Map<String, String> meta) {
        String status = meta.get("reportStatus");
        // "none" is AuditEventRecord.Builder's rendering of a null, not a state - "Status: None"
        // would be an absence rendered as a value.
        if (status == null || "none".equals(status)) {
            return null;
        }
        return "Status: " + reportStatusName(status);
    }

    /**
     * Audit rows are permanent and this one may name a constant a future {@link ReportStatus} no
     * longer has. That row still has to render, so an unrecognised value falls back to the generic
     * formatter rather than throwing and taking the whole timeline with it. This is the historic
     * path, not the normal one: every constant the enum currently declares goes through
     * {@code getDisplayName}, and {@code AuditStatusVocabularyTest} asserts that for all of them.
     */
    private String reportStatusName(String constant) {
        try {
            return ReportStatus.valueOf(constant).getDisplayName();
        } catch (IllegalArgumentException unknownToThisVersion) {
            return titleCase(constant);
        }
    }

    private String rejectionDetail(Map<String, String> meta) {
        String status = statusDetail(meta);
        boolean commentsProvided = "true".equals(meta.get("commentsProvided"));
        if (status != null && commentsProvided) {
            return status + " · Comments provided";
        }
        return status != null ? status : (commentsProvided ? "Comments provided" : null);
    }

    private String rolesDetail(String roles) {
        return roles == null ? null : "Roles: " + formatRoles(roles);
    }

    private String userUpdateDetail(Map<String, String> meta) {
        String rolesBefore = meta.get("rolesBefore");
        String rolesAfter = meta.get("rolesAfter");
        if (rolesBefore != null && rolesAfter != null && !rolesBefore.equals(rolesAfter)) {
            return formatRoles(rolesBefore) + " → " + formatRoles(rolesAfter);
        }
        String enabledBefore = meta.get("enabledBefore");
        String enabledAfter = meta.get("enabledAfter");
        if (enabledBefore != null && enabledAfter != null && !enabledBefore.equals(enabledAfter)) {
            return enabledLabel(enabledBefore) + " → " + enabledLabel(enabledAfter);
        }
        if ("true".equals(meta.get("passwordChanged"))) {
            return "Password changed";
        }
        return null;
    }

    private String enabledLabel(String value) {
        return "true".equals(value) ? "Enabled" : "Disabled";
    }

    /**
     * A status transition, with BOTH ends said in the words the system uses for those states (T262).
     *
     * <p>This titleCased both operands, so a re-allocated sent-back interview rendered
     * <em>"Report Rejected &rarr; Allocated"</em> - the exact pre-rename string D-1a-2 removed,
     * reconstructed by the formatter from the constant, on a screen the visitor whose work it
     * describes can see.
     *
     * <p><strong>The reason for the change is not that those particular words are loaded.</strong>
     * That made REPORT_REJECTED urgent; it is not what makes the route correct. The route is correct
     * because <strong>a generic formatter is not authorised to speak for the enum</strong>, and that
     * reason does not depend on which enum it is. "Allocated" reads correctly through titleCase
     * <em>by coincidence</em> - its display name happens to equal titleCase of its constant. That
     * coincidence covers four of seven constants, is invisible at the call site, and holds only
     * until somebody renames one. A coincidence that covers most cases and is invisible where it
     * fails is not a reason to keep asking the formatter; it is why this went unseen for so long.
     *
     * <p>So the CALL is converted, not the words. Three of seven constants differ today, and that is
     * a fact about today's names rather than a licence to convert three constants: a per-constant
     * list is the one-string patch wearing a bigger number, right by coincidence of the current
     * names and silently wrong after the next rename.
     *
     * <p>Why this matters more than {@link #statusDetail} did, even though that one was found first:
     * {@code statusDetail} restated its own headline, and this does not. {@code statusBefore} is
     * genuinely new information and the only place the reader learns it. <strong>A wrong vocabulary
     * on a detail the reader uses is worse than on one that echoes.</strong>
     */
    private String formatted(Map<String, String> meta, String beforeKey, String afterKey) {
        String before = meta.get(beforeKey);
        String after = meta.get(afterKey);
        if (before == null || after == null) {
            return null;
        }
        return interviewStatusName(before) + " → " + interviewStatusName(after);
    }

    /**
     * The same shape as {@link #reportStatusName}, and for the same reason: an audit row is permanent
     * and may name a constant a later {@link InterviewStatus} no longer has, and that row still has
     * to render rather than throwing and taking the whole timeline with it. The fallback is the
     * historic path; every constant the enum currently declares goes through {@code getDisplayName},
     * and {@code InterviewStatusVocabularyTest} asserts that for all of them - including the ones no
     * transition can currently produce.
     */
    private String interviewStatusName(String constant) {
        try {
            return InterviewStatus.valueOf(constant).getDisplayName();
        } catch (IllegalArgumentException unknownToThisVersion) {
            return titleCase(constant);
        }
    }

    private String parseAndFormatTimestamp(String value) {
        if (value == null || "none".equals(value)) {
            return null;
        }
        try {
            return LocalDateTime.parse(value).format(TIMESTAMP);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** {@code "REVIEWER"} -&gt; {@code "Reviewer"}; {@code "HOME_STAFF,VIEWER"} -&gt; {@code "Home Staff, Viewer"}. */
    private String formatRoles(String roles) {
        if (roles == null || roles.isBlank()) {
            return null;
        }
        return Arrays.stream(roles.split(","))
                .map(this::titleCase)
                .reduce((a, b) -> a + ", " + b)
                .orElse(null);
    }

    private String titleCase(String enumLikeValue) {
        String[] words = enumLikeValue.split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return result.toString();
    }

    /** Undoes {@code AuditEventRecord.Builder}'s {@code "k=v; k=v"} rendering. */
    private Map<String, String> parseMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : metadata.split("; ")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                result.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return result;
    }
}
