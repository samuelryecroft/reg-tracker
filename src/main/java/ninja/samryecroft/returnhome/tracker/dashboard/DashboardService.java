package ninja.samryecroft.returnhome.tracker.dashboard;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.DeadlineTracker;
import ninja.samryecroft.returnhome.tracker.interview.DueState;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
import ninja.samryecroft.returnhome.tracker.interview.QueueFilter;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import ninja.samryecroft.returnhome.tracker.report.ReportStatus;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.springframework.stereotype.Service;

/**
 * Roadmap 2.3: the Safeguarding &amp; Performance Dashboard. One dashboard, two audiences - the
 * breakdown dimension changes (by home for a Care Provider, by care provider drilling to home for a
 * Supplier) but the underlying rule for every figure does not (Oscar's dashboard-build-brief.md §02).
 *
 * <p>The live "Needs attention" zone reuses {@link DeadlineTracker} verbatim - the entire reason
 * roadmap 2.1 shipped first. Everything here fetches its scope once (mirroring the org-scoping the
 * request lists already use) and computes in Java, the same "fetch scope, then compute" approach as
 * {@code DeadlineTrackingService} - deliberately not optimised into per-metric SQL yet (flagged as a
 * cost worth a backend pass once real data volume makes it matter, not before).
 */
@Service
public class DashboardService {

    private final InterviewRequestRepository interviewRequestRepository;
    private final InterviewReportRepository interviewReportRepository;
    private final HomeRepository homeRepository;
    private final ChildRepository childRepository;
    private final UserRepository userRepository;
    private final OrganisationRepository organisationRepository;
    private final OrganisationAccessService organisationAccessService;

    public DashboardService(InterviewRequestRepository interviewRequestRepository,
            InterviewReportRepository interviewReportRepository, HomeRepository homeRepository,
            ChildRepository childRepository, UserRepository userRepository,
            OrganisationRepository organisationRepository,
            OrganisationAccessService organisationAccessService) {
        this.interviewRequestRepository = interviewRequestRepository;
        this.interviewReportRepository = interviewReportRepository;
        this.homeRepository = homeRepository;
        this.childRepository = childRepository;
        this.userRepository = userRepository;
        this.organisationRepository = organisationRepository;
        this.organisationAccessService = organisationAccessService;
    }

    public CareProviderDashboard careProviderDashboard(AppUserPrincipal principal, DashboardPeriod periodOption) {
        LocalDateTime now = LocalDateTime.now();
        PeriodRange period = PeriodRange.resolve(periodOption, now);

        List<Home> homes;
        List<InterviewRequest> requests;
        List<InterviewReport> reports;
        int childCount;

        if (principal.hasRole(Role.VIEWER)) {
            List<Long> homeIds = userRepository.findHomeIds(principal.getUserId());
            homes = homeIds.isEmpty() ? List.of() : homeRepository.findAllById(homeIds);
            requests = homeIds.isEmpty() ? List.of() : interviewRequestRepository.findByHomeIdIn(homeIds);
            reports = homeIds.isEmpty() ? List.of() : interviewReportRepository.findByHomeIdIn(homeIds);
            childCount = homeIds.isEmpty() ? 0 : childRepository.findByHomeIdIn(homeIds).size();
        } else {
            Long orgId = principal.getOrganisationId();
            homes = homeRepository.findByOrganisationIdWithOrganisation(orgId);
            requests = interviewRequestRepository.findByHomeOrganisationId(orgId);
            reports = interviewReportRepository.findByHomeOrganisationId(orgId);
            childCount = childRepository.findByHomeOrganisationIdWithHome(orgId).size();
        }

        String orgName = organisationRepository.findById(principal.getOrganisationId())
                .map(Organisation::getName).orElse("Your organisation");

        List<InterviewReport> completed = completedInPeriod(reports, period);
        RateStat overallRate = combinedRate(completed);

        Map<Long, List<InterviewRequest>> liveByHome = liveRequestsByKey(requests, r -> r.getHome().getId());
        Map<Long, List<InterviewReport>> completedByHome = completedByKey(completed, r -> r.getInterviewRequest().getHome().getId());

        List<BreakdownRow> rows = homes.stream()
                .map(h -> rowFor(h.getId(), h.getName(), null, QueueFilter.QUEUE_PATH + "?homeId=" + h.getId(),
                        liveByHome.getOrDefault(h.getId(), List.of()), completedByHome.getOrDefault(h.getId(), List.of()), now))
                .toList();

        return new CareProviderDashboard(
                orgName, homes.size(), childCount,
                careProviderLiveTiles(requests, now),
                period, overallRate,
                ranked(rows), tooFew(rows),
                namedRecurrence(requests));
    }

    public SupplierDashboard supplierDashboard(AppUserPrincipal principal, DashboardPeriod periodOption, Long careProviderFilterId) {
        LocalDateTime now = LocalDateTime.now();
        PeriodRange period = PeriodRange.resolve(periodOption, now);
        // DashboardController routes everyone who is not a VIEWER or a care-provider org-admin
        // here, HOME_STAFF included - and they have no organisation at all, so this used to run four
        // queries with a null id and return nothing by accident. Now the scope is resolved once and
        // an absent scope renders as no rows, which is the same page for a legitimate user and a
        // stated deny rather than a coincidence for everyone else (T139).
        Optional<Long> supplierScope = organisationAccessService.supplierScopeFor(principal);

        List<Organisation> careProviders = supplierScope
                .map(organisationRepository::findBySupplierOrganisationIdOrderByName).orElseGet(List::of);
        List<Home> allHomes = supplierScope
                .map(homeRepository::findByOrganisationSupplierOrganisationId).orElseGet(List::of);
        List<InterviewRequest> allRequests = supplierScope
                .map(interviewRequestRepository::findByHomeOrganisationSupplierOrganisationId).orElseGet(List::of);
        List<InterviewReport> allReports = supplierScope
                .map(interviewReportRepository::findByHomeOrganisationSupplierOrganisationId).orElseGet(List::of);

        boolean filtered = careProviderFilterId != null;
        List<InterviewRequest> requests = !filtered ? allRequests
                : allRequests.stream().filter(r -> careProviderFilterId.equals(r.getHome().getOrganisation().getId())).toList();
        List<InterviewReport> reports = !filtered ? allReports
                : allReports.stream().filter(r -> careProviderFilterId.equals(r.getInterviewRequest().getHome().getOrganisation().getId())).toList();

        List<InterviewReport> completed = completedInPeriod(reports, period);
        RateStat overallRate = combinedRate(completed);

        List<BreakdownRow> rows = filtered
                ? homeBreakdown(allHomes.stream().filter(h -> careProviderFilterId.equals(h.getOrganisation().getId())).toList(),
                        requests, completed, now)
                : careProviderBreakdown(careProviders, allHomes, requests, completed, now);

        List<CareProviderOption> options = careProviders.stream()
                .map(o -> new CareProviderOption(o.getId(), o.getName())).toList();

        String orgName = supplierScope.flatMap(organisationRepository::findById)
                .map(Organisation::getName).orElse("Your organisation");

        return new SupplierDashboard(
                orgName, careProviders.size(), allHomes.size(),
                options, careProviderFilterId,
                supplierLiveTiles(requests, now, supplierScope.orElse(null)),
                period, overallRate,
                ranked(rows), tooFew(rows),
                homeLevelRecurrenceCounts(requests));
    }

    // ---- live tiles ----

    private List<LiveTile> careProviderLiveTiles(List<InterviewRequest> requests, LocalDateTime now) {
        List<InterviewRequest> live = requests.stream().filter(r -> DeadlineTracker.tracksDeadline(r.getStatus())).toList();
        int overdue = countByState(live, now, DueState.OVERDUE);
        int dueSoon = countByState(live, now, DueState.DUE_SOON);
        int noClock = countByState(live, now, DueState.NO_CLOCK);
        int consentMissing = (int) requests.stream()
                .filter(r -> r.getStatus() == InterviewStatus.ALLOCATED || r.getStatus() == InterviewStatus.SCHEDULED)
                .filter(r -> r.getConsentProvided() == null || !r.getConsentProvided())
                .count();

        return List.of(
                new LiveTile("Overdue now", String.valueOf(overdue), "past the 72-hour window",
                        QueueFilter.OVERDUE.href(), "View overdue →", overdue > 0 ? "urgent" : ""),
                new LiveTile("Due in next 24h", String.valueOf(dueSoon), "interview not yet held",
                        QueueFilter.DUE_SOON.href(), "View due soon →", dueSoon > 0 ? "warn" : ""),
                new LiveTile("No return time recorded", String.valueOf(noClock), "no clock can start",
                        QueueFilter.NO_CLOCK.href(), "View requests →", noClock > 0 ? "warn" : ""),
                new LiveTile("Consent not confirmed", String.valueOf(consentMissing), "already allocated to a visitor",
                        QueueFilter.CONSENT.href(), "View requests →", consentMissing > 0 ? "warn" : ""));
    }

    private List<LiveTile> supplierLiveTiles(List<InterviewRequest> requests, LocalDateTime now, Long supplierOrgId) {
        List<InterviewRequest> live = requests.stream().filter(r -> DeadlineTracker.tracksDeadline(r.getStatus())).toList();
        int overdue = countByState(live, now, DueState.OVERDUE);

        List<InterviewRequest> unallocated = requests.stream().filter(r -> r.getStatus() == InterviewStatus.REQUESTED).toList();
        String oldestWaiting = unallocated.stream().map(InterviewRequest::getCreatedAt).min(Comparator.naturalOrder())
                .map(created -> Duration.between(created, now).toHours() + " hours")
                .map(hours -> "oldest waiting " + hours)
                .orElse("none waiting");

        List<InterviewRequest> awaitingReview = requests.stream().filter(r -> r.getStatus() == InterviewStatus.REPORT_SUBMITTED).toList();

        List<User> visitors = userRepository.findByRoleAndOrganisationId(Role.VISITOR, supplierOrgId);
        Set<InterviewStatus> openStatuses = Set.of(InterviewStatus.ALLOCATED, InterviewStatus.SCHEDULED,
                InterviewStatus.REPORT_SUBMITTED, InterviewStatus.REPORT_REJECTED);
        Set<Long> busyVisitorIds = requests.stream()
                .filter(r -> openStatuses.contains(r.getStatus()) && r.getAllocatedVisitor() != null)
                .map(r -> r.getAllocatedVisitor().getId())
                .collect(Collectors.toSet());
        int visitorsWithNoWork = (int) visitors.stream().filter(v -> !busyVisitorIds.contains(v.getId())).count();

        return List.of(
                new LiveTile("Overdue now", String.valueOf(overdue), "across every care provider we serve",
                        QueueFilter.OVERDUE.href(), "View overdue →", overdue > 0 ? "urgent" : ""),
                new LiveTile("Unallocated", String.valueOf(unallocated.size()), oldestWaiting,
                        QueueFilter.UNALLOCATED.href(), "Allocate now →", unallocated.isEmpty() ? "" : "warn"),
                new LiveTile("Awaiting review", String.valueOf(awaitingReview.size()), "reports pending a decision",
                        QueueFilter.AWAITING_REVIEW.href(), "Go to review queue →", awaitingReview.isEmpty() ? "" : "warn"),
                new LiveTile("Visitors with no work", String.valueOf(visitorsWithNoWork), "of " + visitors.size() + " active visitors",
                        "/admin/users", "View visitors →", ""));
    }

    private int countByState(List<InterviewRequest> live, LocalDateTime now, DueState state) {
        return (int) live.stream().filter(r -> DeadlineTracker.stateOf(r, now).map(s -> s == state).orElse(false)).count();
    }

    // ---- performance breakdown ----

    private List<InterviewReport> completedInPeriod(List<InterviewReport> reports, PeriodRange period) {
        return reports.stream()
                .filter(r -> r.getStatus() == ReportStatus.APPROVED)
                .filter(r -> r.getReviewedAt() != null && !r.getReviewedAt().isBefore(period.start()) && r.getReviewedAt().isBefore(period.endExclusive()))
                .toList();
    }

    private RateStat combinedRate(List<InterviewReport> completed) {
        return rateOf(completed);
    }

    /**
     * The compliance rate, measured rather than declared.
     *
     * <p>It used to count {@code Boolean.TRUE.equals(getWithin72Hours())} against every completed
     * report with a return time. That quietly scored an unanswered question as a breach: a null
     * ("Unknown") failed the TRUE test but still sat in the denominator, so a report nobody had
     * finished cost an organisation exactly what a genuine late interview did. Now the answer is
     * computed from the two timestamps, and a report that cannot be measured is excluded from both
     * sides instead of counted against one.
     */
    private RateStat rateOf(List<InterviewReport> reports) {
        int measurable = 0;
        int within72 = 0;
        for (InterviewReport report : reports) {
            Boolean met = report.getWithin72Hours();
            if (met == null) {
                continue;
            }
            measurable++;
            if (met) {
                within72++;
            }
        }
        return new RateStat(within72, measurable, reports.size() - measurable);
    }

    private <K> Map<K, List<InterviewRequest>> liveRequestsByKey(List<InterviewRequest> requests, java.util.function.Function<InterviewRequest, K> key) {
        return requests.stream()
                .filter(r -> DeadlineTracker.tracksDeadline(r.getStatus()))
                .collect(Collectors.groupingBy(key));
    }

    private <K> Map<K, List<InterviewReport>> completedByKey(List<InterviewReport> completed, java.util.function.Function<InterviewReport, K> key) {
        return completed.stream().collect(Collectors.groupingBy(key));
    }

    private BreakdownRow rowFor(Long id, String name, String subLabel, String href,
            List<InterviewRequest> liveRequests, List<InterviewReport> completedReports, LocalDateTime now) {
        int overdueNow = countByState(liveRequests, now, DueState.OVERDUE);
        return new BreakdownRow(id, name, subLabel, href, overdueNow, rateOf(completedReports));
    }

    private List<BreakdownRow> homeBreakdown(List<Home> homes, List<InterviewRequest> requests,
            List<InterviewReport> completed, LocalDateTime now) {
        Map<Long, List<InterviewRequest>> liveByHome = liveRequestsByKey(requests, r -> r.getHome().getId());
        Map<Long, List<InterviewReport>> completedByHome = completedByKey(completed, r -> r.getInterviewRequest().getHome().getId());
        return homes.stream()
                .map(h -> rowFor(h.getId(), h.getName(), null, QueueFilter.QUEUE_PATH + "?homeId=" + h.getId(),
                        liveByHome.getOrDefault(h.getId(), List.of()), completedByHome.getOrDefault(h.getId(), List.of()), now))
                .toList();
    }

    private List<BreakdownRow> careProviderBreakdown(List<Organisation> careProviders, List<Home> allHomes,
            List<InterviewRequest> requests, List<InterviewReport> completed, LocalDateTime now) {
        Map<Long, List<InterviewRequest>> liveByProvider = liveRequestsByKey(requests, r -> r.getHome().getOrganisation().getId());
        Map<Long, List<InterviewReport>> completedByProvider = completedByKey(completed,
                r -> r.getInterviewRequest().getHome().getOrganisation().getId());
        Map<Long, Long> homeCountByProvider = allHomes.stream()
                .collect(Collectors.groupingBy(h -> h.getOrganisation().getId(), Collectors.counting()));

        return careProviders.stream()
                .map(org -> {
                    long homeCount = homeCountByProvider.getOrDefault(org.getId(), 0L);
                    String subLabel = homeCount + (homeCount == 1 ? " home" : " homes");
                    return rowFor(org.getId(), org.getName(), subLabel, "/dashboard?careProviderId=" + org.getId(),
                            liveByProvider.getOrDefault(org.getId(), List.of()), completedByProvider.getOrDefault(org.getId(), List.of()), now);
                })
                .toList();
    }

    /** Oscar's D-5: rank only rows at or above the minimum base; sub-base rows go last, unranked. */
    private List<BreakdownRow> ranked(List<BreakdownRow> rows) {
        return rows.stream()
                .filter(r -> !r.stat().tooFewToReport())
                .sorted(Comparator.comparingInt(r -> r.stat().percent().orElse(Integer.MAX_VALUE)))
                .toList();
    }

    private List<BreakdownRow> tooFew(List<BreakdownRow> rows) {
        return rows.stream()
                .filter(r -> r.stat().tooFewToReport())
                .sorted(Comparator.comparing(BreakdownRow::name))
                .toList();
    }

    // ---- recurrence ----

    private boolean isFlagged(InterviewRequest r) {
        return Boolean.TRUE.equals(r.getMissingFiveTimesIn30Days())
                || Boolean.TRUE.equals(r.getMissingInLast6Months())
                || Boolean.TRUE.equals(r.getStrategyMeetingRequested());
    }

    private List<String> flagsFor(InterviewRequest r) {
        List<String> flags = new ArrayList<>();
        if (Boolean.TRUE.equals(r.getMissingFiveTimesIn30Days())) {
            flags.add("5+ in 30 days");
        }
        if (Boolean.TRUE.equals(r.getMissingInLast6Months())) {
            flags.add("Repeat in 6 months");
        }
        if (Boolean.TRUE.equals(r.getStrategyMeetingRequested())) {
            flags.add("Strategy meeting requested");
        }
        return flags;
    }

    /** Care Provider panel: named, sorted 5-in-30-days first then most recent - Oscar's D-1 default for this audience. */
    private List<RecurrenceEntry> namedRecurrence(List<InterviewRequest> requests) {
        Map<Long, InterviewRequest> latestFlaggedByChild = new LinkedHashMap<>();
        requests.stream()
                .filter(this::isFlagged)
                .sorted(Comparator.comparing(InterviewRequest::getCreatedAt).reversed())
                .forEach(r -> latestFlaggedByChild.putIfAbsent(r.getChild().getId(), r));

        return latestFlaggedByChild.values().stream()
                .sorted(Comparator.comparing((InterviewRequest r) -> Boolean.TRUE.equals(r.getMissingFiveTimesIn30Days())).reversed()
                        .thenComparing(InterviewRequest::getCreatedAt, Comparator.reverseOrder()))
                .map(r -> new RecurrenceEntry(r.getChild().getFullName(), r.getHome().getName(), flagsFor(r),
                        r.getStatus().getDisplayName(), "/interview-requests/" + r.getId()))
                .toList();
    }

    /** Supplier panel: home-level counts only, no names (Oscar's D-1 safe default pending human sign-off). */
    private List<RecurrenceCount> homeLevelRecurrenceCounts(List<InterviewRequest> requests) {
        Map<Long, Home> homeById = new LinkedHashMap<>();
        Map<Long, Set<Long>> flaggedChildIdsByHome = new LinkedHashMap<>();
        for (InterviewRequest r : requests) {
            if (!isFlagged(r)) {
                continue;
            }
            homeById.putIfAbsent(r.getHome().getId(), r.getHome());
            flaggedChildIdsByHome.computeIfAbsent(r.getHome().getId(), k -> new HashSet<>()).add(r.getChild().getId());
        }
        return homeById.values().stream()
                .map(h -> new RecurrenceCount(h.getName(), h.getOrganisation().getName(),
                        flaggedChildIdsByHome.get(h.getId()).size(), QueueFilter.QUEUE_PATH + "?homeId=" + h.getId()))
                .sorted(Comparator.comparingInt(RecurrenceCount::childCount).reversed())
                .toList();
    }
}
