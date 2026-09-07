package ninja.samryecroft.returnhome.tracker.audit;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
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

    /**
     * Opening a record. One type today; a set so a second one is an addition rather than a rewrite.
     *
     * <p><strong>Declared here, above its first use, because three places now had their own copy of
     * "what an access event is"</strong> and one of them would eventually have stopped agreeing: the
     * request page excludes them (T246), the child page excludes them (T274 A5) and the feed's
     * WITH_ACCESS_EVENTS scope includes them (T274 A1). They are the same question asked three times,
     * so they read one answer - add a second access type and all three move together rather than two
     * of them moving and the third being found later.
     */
    private static final Set<AuditEventType> ACCESS_TYPES = Set.of(AuditEventType.AUDIT_VIEW_OPENED);

    /**
     * Opening a record is not something that happened TO the record, so the record's own history
     * does not list it (T246).
     *
     * <p>The human's words: <em>"When a user views a request it adds a audit view opened event - I
     * don't think we need an audit view opened event because that is not sensible."</em> Opening the
     * interview request page emits AUDIT_VIEW_OPENED against that very request
     * ({@code InterviewRequestDetailController}), so the panel showing the request's own story filled
     * up with rows about people reading it - and, having no case in {@link #toEntry}'s switch, they
     * rendered through the default branch as the literal string {@code "AUDIT_VIEW_OPENED"}.
     *
     * <p><strong>DISPLAY ONLY, AND REVERSIBLE. The events are still written and still queryable.</strong>
     * That distinction is the whole reason this is a filter and not a change to
     * {@code AuditEventPublisher}: a panel you filtered can be unfiltered, but a record you stopped
     * writing cannot be recovered - and "who looked at this child's data" is a question a council or
     * a DPO asks, which these rows are the only place to answer.
     *
     * <p>Same shape as {@link #EXCLUDED_FROM_USER_HISTORY} below, and deliberately a SEPARATE set:
     * the two exclusions answer different questions and merging them would make one screen's policy
     * silently govern the other's.
     *
     * <p><strong>It IS however the same question as {@link #ACCESS_TYPES}, so it is that set rather
     * than a second literal.</strong> The name is kept because the POLICY - "a record's own history
     * excludes these" - is a different statement from the DEFINITION of an access event, and only the
     * definition is shared. That distinction is what makes this safe where merging it with the
     * user-history exclusion would not be.
     */
    private static final Set<AuditEventType> EXCLUDED_FROM_RECORD_HISTORY = ACCESS_TYPES;

    /**
     * Sign-in monitoring is explicitly out of scope for V1 (gated on an unresolved GDPR policy call).
     *
     * <p><b>T322 added four members, and adding them was the point rather than an afterthought.</b>
     * The second factor's events are sign-in events: MFA_CHALLENGE_ISSUED, MFA_SUCCESS, MFA_FAILURE
     * and MFA_LOCKED say when a named person tried to sign in, how often, and how often they failed.
     * Leaving them out of this set would not have raised an error anywhere - it would simply have
     * delivered per-user sign-in monitoring to the user page through a new door, and the policy call
     * that deliberately parked it would have been answered by omission.
     */
    // Package-private, not private, so EveryAuditEventTypeIsClassifiedTest can read the REAL set
    // rather than a copy of it. A guard that restated this list would be a second answer to the same
    // question - the defect this class already carries a warning about - and it would drift.
    static final Set<AuditEventType> EXCLUDED_FROM_USER_HISTORY =
            Set.of(AuditEventType.LOGIN_SUCCESS, AuditEventType.LOGIN_FAILURE,
                    AuditEventType.MFA_CHALLENGE_ISSUED, AuditEventType.MFA_SUCCESS,
                    AuditEventType.MFA_FAILURE, AuditEventType.MFA_LOCKED);

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
        // FILTER FIRST, COLLAPSE AFTER, and the order is required rather than incidental. The
        // draft-save collapse lives downstream inside toEntries, so removing a view event can leave
        // two runs of draft saves ADJACENT - and they must then collapse into ONE row, because what
        // the reader sees has to be the collapse of what the reader sees. Filtering after the
        // collapse would leave two "Draft saved" rows with nothing between them, which is the same
        // wall of noise T177 removed, reintroduced by the fix for a different kind of noise.
        events.removeIf(event -> EXCLUDED_FROM_RECORD_HISTORY.contains(event.getEventType()));
        return groupByDay(events, WhenStyle.TIME, draftSaveRuns);
    }

    /**
     * Cross-request "case history": every request raised for this child, each its own section.
     *
     * <p><strong>The scope is a parameter with no default because this method has TWO consumers and
     * they now want different things</strong> (T274 A5). {@code ChildController} builds the child
     * page, which stops listing who opened a request; {@code CaseFileExportService} builds the
     * case-file pack for a DPO, a local authority or a court, which does not. That is the same
     * two-consumer shape as {@link #caseActivityFeed}, and the same trap: filtering here without a
     * scope would have tidied a screen and silently rewritten every disclosure pack produced from
     * this day on. <strong>The export therefore names {@code WITH_ACCESS_EVENTS} explicitly, and it
     * means "unchanged", not "extended".</strong>
     *
     * <p><strong>Why the pack keeps them, weighed rather than defaulted:</strong> Kevin's A3 objection
     * to access rows in a disclosure is that it acquires a SECOND DATA SUBJECT - an org-wide export
     * containing access rows is an employee-monitoring dataset leaving the building under a purpose
     * about a child. Two things make this pack the other case: it is scoped to ONE child rather than
     * an organisation and a date range, and {@code CaseFileNarrativeWriter} renders <em>roles rather
     * than individual names</em>. "Who accessed this child's record" is a question a DPO or a court
     * asks OF a child's case file, and it is answered here without naming staff.
     */
    public List<AuditHistorySection> caseHistoryFor(List<InterviewRequest> requests, DraftSaveRuns draftSaveRuns,
            AuditFeedScope scope) {
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
        // FILTER FIRST, COLLAPSE AFTER - the same ordering historyFor documents, and load-bearing for
        // the same reason. The draft-save collapse runs downstream in toEntries, so removing a view
        // event can leave two runs of draft saves ADJACENT, and they must then collapse into ONE row:
        // what the reader sees has to be the collapse of what the reader sees. Filtering after the
        // collapse leaves two "Draft saved" rows with nothing between them - T177's wall of noise,
        // reintroduced by the fix for a different kind of noise.
        if (scope != AuditFeedScope.WITH_ACCESS_EVENTS) {
            all.removeIf(event -> ACCESS_TYPES.contains(event.getEventType()));
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
            LocalDate from, LocalDate to, AuditFeedScope scope) {
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

        // T292's scope, and this line IS the access control. A child is reachable here ONLY through a
        // request that is already in the principal's bounded set, so the child path cannot see a
        // child the request path could not. BOUND FROM THE PRINCIPAL FIRST, FILTER WITHIN - never a
        // fresh query by home or organisation, which would be a SECOND implementation of
        // requestsInScope, free to drift from it, silently, across organisations.
        Map<Long, List<InterviewRequest>> inScopeRequestsByChildId = requestsInScope.stream()
                .collect(Collectors.groupingBy(request -> request.getChild().getId()));

        List<AuditFeedRow> rows = new ArrayList<>();
        for (AuditEvent event : auditEventRepository.findByOrganisationIdIn(organisationIds)) {
            if (!typesIn(scope).contains(event.getEventType())) {
                continue;
            }
            LocalDate day = event.getOccurredAt().toLocalDate();
            if ((from != null && day.isBefore(from)) || (to != null && day.isAfter(to))) {
                continue;
            }
            AuditFeedRow row = "Child".equals(event.getTargetType())
                    ? childRow(event, inScopeRequestsByChildId, homeIdFilter)
                    : requestRow(event, requestById, requestIdByReportId, homeIdFilter);
            if (row != null) {
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparing((AuditFeedRow row) -> row.entry().occurredAt()).reversed());
        return rows;
    }

    /** An event about a request or its report, resolved back to the request. {@code null} if out of scope. */
    private AuditFeedRow requestRow(AuditEvent event, Map<Long, InterviewRequest> requestById,
            Map<Long, Long> requestIdByReportId, Long homeIdFilter) {
        Long requestId = "InterviewRequest".equals(event.getTargetType()) ? event.getTargetId()
                : "InterviewReport".equals(event.getTargetType()) ? requestIdByReportId.get(event.getTargetId())
                : null;
        InterviewRequest request = requestId == null ? null : requestById.get(requestId);
        if (request == null || (homeIdFilter != null && !homeIdFilter.equals(request.getHome().getId()))) {
            return null;
        }
        return AuditFeedRow.forRequest(toEntry(event, WhenStyle.SHORT_DATE), request.getHome().getName(),
                request.getChild().getFullName(), request.getId());
    }

    /**
     * An event about a child - today only record access (T292). <strong>Before this the feed dropped
     * every one of them</strong>, because a row it could not resolve to an interview request was
     * skipped: we had not lost the data, we had lost the ROUTE, and a row reachable only by writing
     * SQL against production is hoarded rather than kept.
     *
     * <p><strong>The event is NOT retargeted to a request to make it resolve.</strong> That was
     * considered and refused: it would falsify what happened, and you do not close a retrieval gap by
     * mislabelling the record. The row keeps saying it was about a child, and the feed learns to
     * carry that.
     *
     * <p><strong>Scope comes from the anchor request, not from the child.</strong> The child's own
     * {@code getHome()} is deliberately not read: a child who moved homes could carry one the
     * principal cannot see, and the feed would then print a home name that its own access rule would
     * have withheld. Every field here comes from a request already in the principal's set.
     */
    private AuditFeedRow childRow(AuditEvent event, Map<Long, List<InterviewRequest>> inScopeRequestsByChildId,
            Long homeIdFilter) {
        List<InterviewRequest> anchors = inScopeRequestsByChildId.get(event.getTargetId());
        if (anchors == null) {
            return null;
        }
        // Any in-scope request in the filtered home will do, and looking at ALL of them is the point:
        // a child with requests in two homes must still appear when the feed is filtered to the
        // second one. Picking one anchor up front would have hidden the row instead.
        InterviewRequest anchor = anchors.stream()
                .filter(request -> homeIdFilter == null || homeIdFilter.equals(request.getHome().getId()))
                .findFirst()
                .orElse(null);
        if (anchor == null) {
            return null;
        }
        return AuditFeedRow.forChild(toEntry(event, WhenStyle.SHORT_DATE), anchor.getHome().getName(),
                anchor.getChild().getFullName(), event.getTargetId());
    }

    /**
     * The event types one scope asks for (T274).
     *
     * <p>ACCESS_TYPES is added to the base set rather than replacing any of it, and it holds exactly
     * one type. <strong>Checked rather than assumed, because the base set is load-bearing for
     * something else:</strong> the javadoc above says this filter is what keeps the platform-ADMIN
     * sign-in-export question out of scope "rather than needing a separate exclusion". Sign-in is
     * LOGIN_SUCCESS and LOGIN_FAILURE; neither is added here by either scope, so that question stays
     * answered. Anyone adding a third type must re-check that sentence - it is answered today as a
     * side effect of this filter, not by a rule of its own.
     *
     * <p>NAMES_REVEALED is deliberately NOT included. It is access-shaped and arguably belongs, but
     * the ruling names access events and that type is a reveal ACTION rather than a record opening.
     * Reported rather than decided.
     */
    private static Set<AuditEventType> typesIn(AuditFeedScope scope) {
        if (scope != AuditFeedScope.WITH_ACCESS_EVENTS) {
            return CASE_ACTIVITY_TYPES;
        }
        Set<AuditEventType> types = EnumSet.copyOf(CASE_ACTIVITY_TYPES);
        types.addAll(ACCESS_TYPES);
        return types;
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
            // T295, ruled by Creed. "Viewed", NOT "opened": in children's services OPENING A CASE IS A
            // TERM OF ART MEANING STARTING ONE, so a row reading "Record opened" beside a date and a
            // name can be read by a local authority or a court as THE CASE having been opened. That
            // misreading is invisible outside the domain and consequential inside it.
            //
            // TONE IS NEUTRAL AND THAT IS A CATEGORY DECISION, NOT AN AESTHETIC ONE. Every other tone
            // here marks something that happened TO the record; an access row is the one row type
            // where NOTHING HAPPENED TO IT. A reader scanning a chronology for what CHANGED will
            // count a coloured row as a change.
            //
            // Detail stays null: actor, timestamp and target already say who, when and which record,
            // and a sentence here would restate the columns either side of it.
            case AUDIT_VIEW_OPENED -> entry("Record viewed", event, when, role, null, "");
            // T277. Its own row because it is its own action - and "Password set" rather than
            // "Password changed", because the person whose account it is did not change anything.
            case USER_PASSWORD_RESET -> entry("Password set by an administrator", event, when, role, null, "info");
            // T322. Ruled copy even though all four are excluded from the user page above, because
            // titleCase is a safety net and not a substitute for ruled copy (T295 §5): the exclusion
            // is a POLICY decision that could be reversed by a later ruling, and the day it is
            // reversed these rows must not surface to a reader as "Mfa Challenge Issued".
            //
            // "Security code" rather than "MFA" or "second factor" throughout - the reader of this
            // trail may be an IRO or a court, and the phrase has to mean something without a glossary.
            case MFA_CHALLENGE_ISSUED -> entry("Security code sent", event, when, role, null, "");
            case MFA_SUCCESS -> entry("Security code accepted", event, when, role, null, "");
            case MFA_FAILURE -> entry("Security code rejected", event, when, role, null, "info");
            // Says what actually happened - the code stopped being usable - rather than "locked",
            // which would read as the ACCOUNT being locked. It is not; the person can start again.
            case MFA_LOCKED -> entry("Security code refused too many times", event, when, role, null, "info");
            // T320 (scope ruled in T250). These three arrived with T170 and went straight to the
            // default, so this timeline has been rendering "Child Updated" - retired vocabulary,
            // reinstated by a formatter, exactly the way "Rejected" came back after the product had
            // renamed it. Not a competing decision: the ABSENCE of one.
            //
            // THE STORED CONSTANT IS NOT TOUCHED. eventType is @Enumerated(STRING) and
            // @Column(updatable = false) on an append-only table; renaming CHILD_UPDATED would not
            // rename history, it would SPLIT it - old rows keep the old name, new rows get the new
            // one, and a query across the boundary answers half a question while looking like it
            // answered all of it. Display mapping only, which is the whole point of this switch.
            //
            // AND THE DEFAULT IS WHY THESE NEED CASES AT ALL. The default is ruled (T295 §5) and it
            // is a good rule - titleCase is plain enough that "Names Revealed" reads as something
            // nobody chose. That property is what fails here: "Child Updated" does NOT read as
            // undesigned. It reads as ruled copy, on a document a DPO or a court may read, and there
            // is nothing about it that would make a reader doubt it. A safety net is safe for a
            // constant whose plain rendering is merely unchosen; it is not safe for one whose plain
            // rendering is a word the product has retired. AuditEventVocabularyTest holds that line
            // for every constant this enum declares, including the ones added after today.
            case CHILD_UPDATED -> entry("Young person's details updated", event, when, role,
                    fieldsChangedDetail(meta), "info");
            case CHILD_ARCHIVED -> entry("Young person archived", event, when, role, null, "info");
            case CHILD_RESTORED -> entry("Young person restored", event, when, role, null, "info");
            // ACCESS_DENIED has no meaningful target linkage for a per-record view, and its metadata
            // is free text throughout; LOGIN_SUCCESS/FAILURE are excluded upstream for the user page
            // and never match a request/report/child target in the first place.
            // THE DEFAULT IS RULED, NOT JUST THE CONSTANT (T295 §5), and that is the whole structural
            // half of the card. ACCESS_TYPES is a one-element set TODAY and an extension point BY
            // DESIGN - NAMES_REVEALED is the obvious next member - so giving one constant a case
            // would leave the next one to arrive HERE and render as a raw SHOUTING literal, on a
            // court document, with nothing failing.
            //
            // titleCase is a SAFETY NET AND NOT A SUBSTITUTE FOR RULED COPY: it guarantees no
            // unhandled type can reach a reader as a constant, and it is deliberately plain enough
            // that "Names Revealed" reads as something nobody chose. The same fallback this class
            // already uses for historic status constants.
            default -> entry(titleCase(event.getEventType().name()), event, when, role, null, "");
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

    /**
     * T170 records WHICH fields changed and deliberately never what they changed to - "was this young
     * person's date of birth changed, and by whom" is a question this trail answers; "what was it
     * before" is one it is built not to answer from here. So the detail is the field names, verbatim.
     */
    private String fieldsChangedDetail(Map<String, String> meta) {
        String changed = meta.get("fieldsChanged");
        return changed == null || changed.isBlank() ? null : changed;
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
