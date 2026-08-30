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
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
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
    public List<AuditHistorySection> historyFor(InterviewRequest request) {
        List<AuditEvent> events = new ArrayList<>(
                auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("InterviewRequest", request.getId()));
        interviewReportRepository.findByInterviewRequestId(request.getId()).ifPresent(report ->
                events.addAll(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("InterviewReport", report.getId())));
        return groupByDay(toEntries(events, WhenStyle.TIME));
    }

    /** Cross-request "case history": every request raised for this child, each its own section. */
    public List<AuditHistorySection> caseHistoryFor(List<InterviewRequest> requests) {
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
            sections.add(new AuditHistorySection(label, toEntries(forThisRequest, WhenStyle.SHORT_DATE)));
        }
        return sections;
    }

    /** A user account's own audit trail - role/enabled/password changes, never sign-in activity. */
    public List<AuditHistorySection> historyForUser(Long userId) {
        List<AuditEvent> events = auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("User", userId)
                .stream()
                .filter(e -> !EXCLUDED_FROM_USER_HISTORY.contains(e.getEventType()))
                .toList();
        return groupByDay(toEntries(events, WhenStyle.TIME));
    }

    private List<AuditHistoryEntry> toEntries(List<AuditEvent> events, WhenStyle whenStyle) {
        return events.stream().map(event -> toEntry(event, whenStyle)).toList();
    }

    private List<AuditHistorySection> groupByDay(List<AuditHistoryEntry> entries) {
        Map<LocalDate, List<AuditHistoryEntry>> byDay = new LinkedHashMap<>();
        for (AuditHistoryEntry entry : entries) {
            byDay.computeIfAbsent(entry.occurredAt().toLocalDate(), d -> new ArrayList<>()).add(entry);
        }
        LocalDate today = LocalDate.now();
        List<AuditHistorySection> sections = new ArrayList<>();
        for (Map.Entry<LocalDate, List<AuditHistoryEntry>> dayEntry : byDay.entrySet()) {
            sections.add(new AuditHistorySection(dayLabel(dayEntry.getKey(), today), dayEntry.getValue()));
        }
        return sections;
    }

    private String dayLabel(LocalDate day, LocalDate today) {
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
        String when = whenStyle == WhenStyle.TIME ? event.getOccurredAt().format(TIME) : event.getOccurredAt().format(SHORT_DATE);
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
        return new AuditHistoryEntry(headline, event.getOccurredAt(), when, role, detail, tone);
    }

    private String transition(Map<String, String> meta) {
        return formatted(meta, "statusBefore", "statusAfter");
    }

    private String scheduledAtDetail(Map<String, String> meta) {
        return parseAndFormatTimestamp(meta.get("scheduledAt"));
    }

    private String statusDetail(Map<String, String> meta) {
        String status = meta.get("reportStatus");
        return status == null ? null : "Status: " + titleCase(status);
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

    private String formatted(Map<String, String> meta, String beforeKey, String afterKey) {
        String before = meta.get(beforeKey);
        String after = meta.get(afterKey);
        if (before == null || after == null) {
            return null;
        }
        return titleCase(before) + " → " + titleCase(after);
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
