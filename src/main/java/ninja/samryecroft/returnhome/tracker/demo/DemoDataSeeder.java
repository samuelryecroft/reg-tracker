package ninja.samryecroft.returnhome.tracker.demo;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import ninja.samryecroft.returnhome.tracker.report.ReportService;
import ninja.samryecroft.returnhome.tracker.report.ReportStatus;
import ninja.samryecroft.returnhome.tracker.report.dto.SubmitReportForm;
import ninja.samryecroft.returnhome.tracker.theme.ThemeSettings;
import ninja.samryecroft.returnhome.tracker.theme.ThemeSettingsRepository;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Populates a throwaway database with a complete, realistic-looking demo tenancy so the app can be
 * shown to a client end to end without hand-building data first.
 *
 * <p><strong>This never runs in production.</strong> It is gated on the {@code demo} Spring profile,
 * which is opt-in and set nowhere in the committed default configuration - so a normal boot (and
 * every deployed environment) simply does not create this bean. It is deliberately <em>not</em> a
 * Flyway {@code Vnn__} migration, because a migration would be applied automatically to every
 * database the app ever connects to, including a real one.
 *
 * <p><strong>Everyone and everything here is invented.</strong> No real child, member of staff,
 * home, or organisation is represented; names are drawn from a fixed fictional cast so the same
 * seed is reproducible run after run.
 *
 * <p><strong>Idempotency.</strong> The seeder claims the database once: if the marker supplier
 * organisation already exists it logs and returns, so repeated boots of a seeded database are
 * no-ops rather than duplicating the tenancy. It cannot "reset" in place, because
 * {@code audit_events} is append-only at the database level (V11 installs a trigger that rejects
 * UPDATE and DELETE) and its rows reference the seeded users. To start over, throw the database
 * away and recreate it - see DEMO.md, which is one command.
 *
 * <p><strong>Never reassign from {@code save()} here.</strong> For an entity that already has an
 * id, {@code save()} is a {@code merge()}: it returns a <em>different</em>, managed copy whose lazy
 * associations are proxies, and this class runs outside any transaction, so those proxies are
 * detached the moment the call returns. The audit publisher resolves organisation and home scope by
 * walking those associations, so handing it a merged copy fails with a
 * {@code LazyInitializationException}. The locally-built instance is already fully populated - keep
 * using it and discard the return value.
 *
 * <p><strong>How the lifecycle states are produced.</strong> Base records are written straight
 * through the repositories, but every state transition is announced through the real
 * {@link AuditEventPublisher}, and the two review outcomes are driven through the real
 * {@link ReportService}. That means the demo's audit trail and its generated {@code .docx} are
 * produced by the same production code paths a user would exercise, not by fixtures that merely
 * imitate them.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    /** Presence of this organisation is what marks a database as already seeded. */
    static final String MARKER_ORGANISATION = "Beacon Return Home Services";

    private final OrganisationRepository organisationRepository;
    private final ThemeSettingsRepository themeSettingsRepository;
    private final HomeRepository homeRepository;
    private final ChildRepository childRepository;
    private final UserRepository userRepository;
    private final InterviewRequestRepository interviewRequestRepository;
    private final InterviewReportRepository interviewReportRepository;
    private final ReportService reportService;
    private final AuditEventPublisher audit;
    private final PasswordEncoder passwordEncoder;
    private final DemoProperties demoProperties;

    public DemoDataSeeder(OrganisationRepository organisationRepository,
            ThemeSettingsRepository themeSettingsRepository, HomeRepository homeRepository,
            ChildRepository childRepository, UserRepository userRepository,
            InterviewRequestRepository interviewRequestRepository,
            InterviewReportRepository interviewReportRepository, ReportService reportService,
            AuditEventPublisher audit, PasswordEncoder passwordEncoder,
            DemoProperties demoProperties) {
        this.organisationRepository = organisationRepository;
        this.themeSettingsRepository = themeSettingsRepository;
        this.homeRepository = homeRepository;
        this.childRepository = childRepository;
        this.userRepository = userRepository;
        this.interviewRequestRepository = interviewRequestRepository;
        this.interviewReportRepository = interviewReportRepository;
        this.reportService = reportService;
        this.audit = audit;
        this.passwordEncoder = passwordEncoder;
        this.demoProperties = demoProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.warn("=====================================================================");
        log.warn(" DEMO PROFILE ACTIVE - this instance seeds fictional demo data and");
        log.warn(" uses well-known demo passwords. Never point it at a real database.");
        log.warn("=====================================================================");

        if (alreadySeeded()) {
            log.info("Demo data already present ('{}' exists) - nothing to do.", MARKER_ORGANISATION);
            return;
        }

        Seed seed = new Seed();
        seedOrganisations(seed);
        seedHomesAndChildren(seed);
        seedUsers(seed);
        seedInterviewLifecycle(seed);

        log.info("Seeded demo tenancy: {} organisations, {} homes, {} children, {} users, "
                        + "{} interview requests covering every lifecycle state.",
                seed.organisations, seed.homes, seed.children, seed.users, seed.requests);
        log.info("Sign in with any of: {} (all use the demo password).", seed.usernames);
    }

    /**
     * Deliberately answered with an existing production query rather than a new {@code findByName}
     * on the shared repository: the demo feature should not widen the application's persistence API.
     */
    boolean alreadySeeded() {
        return organisationRepository.findByTypeOrderByName(OrgType.SUPPLIER).stream()
                .anyMatch(org -> MARKER_ORGANISATION.equals(org.getName()));
    }

    // ------------------------------------------------------------------ organisations

    /**
     * Two Supplier organisations, each with Care Providers beneath them, so the demo can show that
     * a Supplier's staff see only their own clients' data. Themes differ per Supplier, which also
     * exercises the per-organisation branding in the UI and in the generated document.
     */
    private void seedOrganisations(Seed seed) {
        seed.beacon = organisation(MARKER_ORGANISATION, OrgType.SUPPLIER, null);
        seed.northgate = organisation("Northgate Safeguarding Partners", OrgType.SUPPLIER, null);

        seed.harbourside = organisation("Harbourside Children's Care", OrgType.CARE_PROVIDER, seed.beacon);
        seed.willowfield = organisation("Willowfield Residential Group", OrgType.CARE_PROVIDER, seed.beacon);
        seed.stanmore = organisation("Stanmore Care Homes", OrgType.CARE_PROVIDER, seed.northgate);

        theme(seed.beacon, "#1F5C7A", "#E8F0F4");
        theme(seed.northgate, "#6B3FA0", "#F1ECF7");
        theme(seed.harbourside, "#0F766E", "#E6F4F2");
        theme(seed.willowfield, "#B45309", "#FDF3E3");
        theme(seed.stanmore, "#334155", "#EDF1F6");
        seed.organisations = 5;
    }

    private Organisation organisation(String name, OrgType type, Organisation supplier) {
        Organisation org = new Organisation();
        org.setName(name);
        org.setType(type);
        org.setSupplierOrganisation(supplier);
        return organisationRepository.save(org);
    }

    private void theme(Organisation org, String primary, String secondary) {
        ThemeSettings settings = new ThemeSettings();
        settings.setOrganisation(org);
        settings.setPrimaryColor(primary);
        settings.setSecondaryColor(secondary);
        themeSettingsRepository.save(settings);
    }

    // ------------------------------------------------------------------ homes and children

    private void seedHomesAndChildren(Seed seed) {
        seed.oakwood = home(seed.harbourside, "Oakwood House", "14 Oakwood Rise", "Fairhaven",
                "FH2 4QL", "Fairhaven Borough Council", "petals.rocket.lantern");
        seed.marisco = home(seed.harbourside, "Marisco Lodge", "3 Harbour Walk", "Fairhaven",
                "FH1 8PN", "Fairhaven Borough Council", "cabin.trend.puzzle");
        seed.willowbank = home(seed.willowfield, "Willowbank", "88 Mill Lane", "Ashdon",
                "AS7 2RW", "Ashdon County Council", "shelf.mural.badge");
        seed.stanmoreHouse = home(seed.stanmore, "Stanmore House", "5 Priory Gardens", "Kelbridge",
                "KB3 9TT", "Kelbridge Metropolitan Borough", "orbit.gravel.season");
        seed.homes = 4;

        seed.alex = child("Alex", "Brennan", LocalDate.of(2009, 4, 12), seed.oakwood, "FH-CASE-1042");
        seed.priya = child("Priya", "Nandra", LocalDate.of(2010, 11, 3), seed.oakwood, "FH-CASE-1108");
        seed.jordan = child("Jordan", "Okafor", LocalDate.of(2008, 2, 27), seed.marisco, "FH-CASE-0977");
        seed.megan = child("Megan", "Lyall", LocalDate.of(2011, 7, 19), seed.willowbank, "AS-CASE-2210");
        seed.tomas = child("Tomas", "Vidal", LocalDate.of(2009, 9, 8), seed.stanmoreHouse, "KB-CASE-3391");
        seed.children = 5;
    }

    private Home home(Organisation org, String name, String line1, String line2, String postcode,
            String localAuthority, String what3words) {
        Home home = new Home();
        home.setOrganisation(org);
        home.setName(name);
        home.setAddressLine1(line1);
        home.setAddressLine2(line2);
        home.setPostcode(postcode);
        home.setLocalAuthority(localAuthority);
        home.setWhat3words(what3words);
        return homeRepository.save(home);
    }

    private Child child(String first, String last, LocalDate dob, Home home, String caseRef) {
        Child child = new Child();
        child.setFirstName(first);
        child.setLastName(last);
        child.setDateOfBirth(dob);
        child.setHome(home);
        child.setLocalCaseReference(caseRef);
        return childRepository.save(child);
    }

    // ------------------------------------------------------------------ users

    /**
     * One demo account per role, plus a second Visitor and a second Home Staff so allocation and
     * home-scoping are visibly not trivial. Every account shares {@code app.demo.password}: that is
     * safe only because this whole class is unreachable outside the demo profile.
     */
    private void seedUsers(Seed seed) {
        seed.orgAdmin = user("orgadmin", "Rachel Idowu", Set.of(Role.ORG_ADMIN), seed.beacon, null);
        seed.coordinator = user("coordinator", "Daniel Fitzhugh", Set.of(Role.COORDINATOR), seed.beacon, null);
        seed.visitor = user("visitor", "Naomi Clarke", Set.of(Role.VISITOR), seed.beacon, null);
        seed.visitor2 = user("visitor2", "Ade Balogun", Set.of(Role.VISITOR), seed.beacon, null);
        seed.reviewer = user("reviewer", "Helen Mowbray", Set.of(Role.REVIEWER), seed.beacon, null);
        seed.homeStaff = user("homestaff", "Sian Roberts", Set.of(Role.HOME_STAFF), seed.harbourside, seed.oakwood);
        seed.homeStaff2 = user("homestaff2", "Marcus Ellery", Set.of(Role.HOME_STAFF), seed.harbourside, seed.marisco);

        // A Viewer has no org of its own: it is granted read access to a named set of homes.
        seed.viewer = user("viewer", "Local Authority Liaison", Set.of(Role.VIEWER), null, null);
        seed.viewer.setHomes(new LinkedHashSet<>(List.of(seed.oakwood, seed.marisco)));
        userRepository.save(seed.viewer);

        // Northgate's own coordinator, to show cross-Supplier separation in the demo.
        seed.northgateCoordinator =
                user("coordinator.ng", "Owen Prescott", Set.of(Role.COORDINATOR), seed.northgate, null);

        seed.users = 9;
        seed.usernames = "admin, orgadmin, coordinator, visitor, visitor2, reviewer, homestaff, "
                + "homestaff2, viewer, coordinator.ng";

        // A handful of sign-ins so the audit trail is not empty on the first screen.
        audit.loginSuccess(new AppUserPrincipal(seed.coordinator));
        audit.loginSuccess(new AppUserPrincipal(seed.homeStaff));
        audit.loginFailure("coordinator", "Bad credentials");
    }

    private User user(String username, String fullName, Set<Role> roles, Organisation org, Home home) {
        User user = new User();
        user.setUsername(username);
        user.setFullName(fullName);
        user.setPassword(passwordEncoder.encode(demoProperties.getPassword()));
        user.setRoles(new LinkedHashSet<>(roles));
        user.setOrganisation(org);
        user.setHomes(home == null ? new LinkedHashSet<>() : new LinkedHashSet<>(List.of(home)));
        user.setEnabled(true);
        return userRepository.save(user);
    }

    // ------------------------------------------------------------------ interview lifecycle

    /**
     * One interview request per lifecycle state, so a demo can jump straight to any screen:
     * REQUESTED, ALLOCATED, SCHEDULED (bare), SCHEDULED with a draft report, REPORT_SUBMITTED
     * awaiting review, REPORT_APPROVED, REPORT_REJECTED, and CANCELLED.
     */
    private void seedInterviewLifecycle(Seed seed) {
        LocalDateTime now = LocalDateTime.now();

        // 1. REQUESTED - raised by home staff this morning, not yet picked up.
        InterviewRequest requested = request(seed, seed.alex, seed.oakwood, seed.homeStaff,
                now.minusHours(6), "Returned overnight; keen to be seen quickly.");

        // 2. ALLOCATED - a visitor is named but no date is agreed yet.
        InterviewRequest allocated = request(seed, seed.priya, seed.oakwood, seed.homeStaff,
                now.minusDays(1), "Second episode this month; escalation discussed.");
        allocated.setAllocatedVisitor(seed.visitor);
        allocated.setStatus(InterviewStatus.ALLOCATED);
        interviewRequestRepository.save(allocated);
        audit.interviewRequestAllocated(allocated, seed.visitor.getId(), InterviewStatus.REQUESTED,
                new AppUserPrincipal(seed.coordinator));

        // 3. SCHEDULED - visit booked, nothing written yet.
        InterviewRequest scheduled = schedule(seed, request(seed, seed.jordan, seed.marisco,
                seed.homeStaff2, now.minusDays(2), "Found safe at a friend's address."),
                seed.visitor2, now.plusDays(1).withHour(15).withMinute(0));

        // 4. SCHEDULED with a DRAFT report - the visitor has started writing.
        InterviewRequest drafting = schedule(seed, request(seed, seed.megan, seed.willowbank,
                seed.homeStaff, now.minusDays(3), "Third episode; strategy meeting requested."),
                seed.visitor, now.minusDays(1).withHour(11).withMinute(30));
        InterviewReport draft = report(drafting, seed.visitor, ReportStatus.DRAFT,
                now.minusDays(1).withHour(11).withMinute(30));
        draft.setInterviewerComments("Notes captured at the visit; to be written up before submission.");
        draft.setRecommendations(null);
        interviewReportRepository.save(draft);
        audit.reportDraftSaved(draft, new AppUserPrincipal(seed.visitor));

        // 5. REPORT_SUBMITTED - waiting on the reviewer; this is the reviewer's demo screen.
        InterviewRequest submitted = schedule(seed, request(seed, seed.alex, seed.oakwood,
                seed.homeStaff, now.minusDays(5), "Missing for 14 hours; returned by police."),
                seed.visitor, now.minusDays(4).withHour(14).withMinute(0));
        InterviewReport awaitingReview = report(submitted, seed.visitor, ReportStatus.SUBMITTED,
                now.minusDays(4).withHour(14).withMinute(0));
        awaitingReview.setSubmittedAt(now.minusDays(4).withHour(17).withMinute(20));
        interviewReportRepository.save(awaitingReview);
        seed.markStatus(interviewRequestRepository, submitted, InterviewStatus.REPORT_SUBMITTED);
        audit.reportSubmitted(awaitingReview, new AppUserPrincipal(seed.visitor));

        // 6. REPORT_APPROVED - driven through the real service so a real .docx is generated.
        InterviewRequest approved = schedule(seed, request(seed, seed.priya, seed.oakwood,
                seed.homeStaff, now.minusDays(12), "Returned voluntarily after 6 hours."),
                seed.visitor2, now.minusDays(11).withHour(10).withMinute(0));
        InterviewReport toApprove = report(approved, seed.visitor2, ReportStatus.SUBMITTED,
                now.minusDays(11).withHour(14).withMinute(0));
        toApprove.setSubmittedAt(now.minusDays(11).withHour(16).withMinute(45));
        interviewReportRepository.save(toApprove);
        seed.markStatus(interviewRequestRepository, approved, InterviewStatus.REPORT_SUBMITTED);
        audit.reportSubmitted(toApprove, new AppUserPrincipal(seed.visitor2));
        reportService.approve(approved.getId(), reviewForm("Thorough and timely. Approved for sharing "
                + "with the placing authority."), new AppUserPrincipal(seed.reviewer));

        // 7. REPORT_REJECTED - sent back for amendment, again through the real service.
        InterviewRequest rejected = schedule(seed, request(seed, seed.jordan, seed.marisco,
                seed.homeStaff2, now.minusDays(9), "Returned after two nights away."),
                seed.visitor, now.minusDays(8).withHour(9).withMinute(30));
        InterviewReport toReject = report(rejected, seed.visitor, ReportStatus.SUBMITTED,
                now.minusDays(8).withHour(14).withMinute(0));
        toReject.setSubmittedAt(now.minusDays(8).withHour(13).withMinute(5));
        interviewReportRepository.save(toReject);
        seed.markStatus(interviewRequestRepository, rejected, InterviewStatus.REPORT_SUBMITTED);
        audit.reportSubmitted(toReject, new AppUserPrincipal(seed.visitor));
        reportService.reject(rejected.getId(), reviewForm("Please expand the risk section and confirm "
                + "whether the police MFH coordinator was consulted."), new AppUserPrincipal(seed.reviewer));

        // 8. CANCELLED - the escape hatch state, so the demo shows it exists.
        InterviewRequest cancelled = request(seed, seed.tomas, seed.stanmoreHouse, seed.homeStaff,
                now.minusDays(15), "Young person moved placement before the visit could take place.");
        cancelled.setStatus(InterviewStatus.CANCELLED);
        interviewRequestRepository.save(cancelled);

        seed.requests = 8;
    }

    private SubmitReportForm reviewForm(String comments) {
        SubmitReportForm form = new SubmitReportForm();
        form.setReviewComments(comments);
        return form;
    }

    private InterviewRequest request(Seed seed, Child child, Home home, User raisedBy,
            LocalDateTime raisedAt, String notes) {
        InterviewRequest request = new InterviewRequest();
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(raisedBy);
        request.setStatus(InterviewStatus.REQUESTED);
        request.setNotes(notes);
        request.setMissingSince(raisedAt.minusHours(18));
        request.setReturnedAt(raisedAt.minusHours(2));
        request.setLegalStatus("Section 20 (voluntary accommodation)");
        request.setPlacingLocalAuthority(home.getLocalAuthority());
        request.setKnownRisks("Associates with a peer group known to the local authority; "
                + "history of returning late from unsupervised time out.");
        request.setChildsComments("Says they were \"just with mates\" and did not want to come back.");
        request.setMissingEpisodeDetails("Left the home after the evening meal and did not return "
                + "at the agreed time. Reported missing to police at 22:15.");
        request.setMissingInLast6Months(true);
        request.setMissingFiveTimesIn30Days(false);
        request.setStrategyMeetingRequested(false);
        request.setImportantPeople("Grandmother (regular weekend contact); best friend at school.");
        request.setAboutYoungPerson("Enjoys football and music production. Responds best to a calm, "
                + "unhurried conversation and dislikes being asked the same question twice.");
        request.setSocialWorkerDetails("Fictional Social Worker, " + home.getLocalAuthority()
                + ", 01234 000000");
        request.setPoliceMfhCoordinatorDetails("Fictional MFH Coordinator, local force, 01234 000001");
        request.setParentsDetails("Mother, contact via the placing authority.");
        request.setOtherProfessionals("School designated safeguarding lead.");
        request.setConsentProvided(true);
        request.setSubmitterOrganisation(home.getOrganisation().getName());
        request.setSubmitterNameAndRole(raisedBy.getFullName() + ", Residential Support Worker");
        request.setRelationshipToYoungPerson("Key worker");
        request.setSubmitterAddress(home.getFullAddress());
        request.setSubmitterContactDetails("01234 000002");
        request.setBestTimesToVisit("Weekday afternoons after 15:30 (school hours excluded)");
        request.setUpdatedAt(raisedAt);
        interviewRequestRepository.save(request);
        audit.interviewRequestCreated(request, new AppUserPrincipal(raisedBy));
        return request;
    }

    private InterviewRequest schedule(Seed seed, InterviewRequest request, User visitor,
            LocalDateTime at) {
        InterviewStatus before = request.getStatus();
        request.setAllocatedVisitor(visitor);
        request.setScheduledAt(at);
        request.setStatus(InterviewStatus.SCHEDULED);
        interviewRequestRepository.save(request);
        audit.interviewRequestAllocated(request, visitor.getId(), before,
                new AppUserPrincipal(seed.coordinator));
        audit.interviewRequestScheduled(request, before, new AppUserPrincipal(seed.coordinator));
        return request;
    }

    /** A fully-answered report body, so the generated .docx has content in every section. */
    private InterviewReport report(InterviewRequest request, User visitor, ReportStatus status,
            LocalDateTime heldAt) {
        InterviewReport report = new InterviewReport();
        report.setInterviewRequest(request);
        report.setVisitor(visitor);
        report.setStatus(status);
        report.setHeldAt(heldAt);
        report.setInterviewLocation(request.getHome().getName() + " - quiet room");
        report.setConsultationWithHomeStaff("Spoke with the key worker on arrival; no new concerns "
                + "raised beyond those in the request.");
        report.setPreviouslyMissing(true);
        report.setMissingOccasionsLast30Days(2);
        report.setConfidentialityExplained(true);
        report.setInterviewAccepted(true);
        report.setWhereWereYouWhileMissing("At a friend's flat in the town centre, then a late-night "
                + "fast food place nearby.");
        report.setWhoWereYouWithWhileMissing("Two friends from school and one older person they knew "
                + "only by first name.");
        report.setWhatMadeYouGoMissing("Argument about phone use at the home; wanted space rather "
                + "than to leave permanently.");
        report.setWhatCanBeDoneToAddressReasons("Agree a clearer phone arrangement in the placement "
                + "plan and a named person to go to when frustrated.");
        report.setConsideredSelfMissing(false);
        report.setWhatDidYouDoWhileMissing("Watched films, ate, and slept on the sofa. Denies any "
                + "substance use or offending.");
        report.setWhatHappenedWhenReturned("Returned in the morning, was offered food and a shower, "
                + "and slept before any discussion took place.");
        report.setPreventFutureMissingSuggestions("Being able to phone the key worker directly rather "
                + "than waiting for a handover.");
        report.setAdditionalCommentsFromYoungPerson("Would like more notice before placement meetings.");
        report.setAdditionalInfoFromParentCarer("None offered at this stage.");
        report.setRisksIdentifiedDuringEpisode("Unknown adult present at the address; no independent "
                + "confirmation of who they are.");
        report.setRisksIncreaseFutureEpisodes("Continued unsupervised contact with the older individual.");
        report.setSafeguardingConcernsToExplore("Identify the older individual with the police MFH "
                + "coordinator and consider a strategy discussion if contact continues.");
        report.setInfoToHelpLocateFuture("Known to spend time at the friend's flat and the shopping "
                + "precinct near the bus station.");
        report.setInterviewerComments("Engaged well and answered openly. No immediate risk of a "
                + "further episode identified on the day.");
        report.setRecommendations("Review the phone arrangement; confirm the identity of the adult "
                + "at the address; repeat this interview if a further episode occurs within 30 days.");
        report.setConductedByStatement(visitor.getFullName() + ", Independent Return Home Interviewer");
        report.setDateReportShared(heldAt.toLocalDate().plusDays(1));
        return report;
    }

    /** Small mutable carrier so the seeding steps read as a sequence rather than a parameter pile. */
    private static final class Seed {
        Organisation beacon;
        Organisation northgate;
        Organisation harbourside;
        Organisation willowfield;
        Organisation stanmore;
        Home oakwood;
        Home marisco;
        Home willowbank;
        Home stanmoreHouse;
        Child alex;
        Child priya;
        Child jordan;
        Child megan;
        Child tomas;
        User orgAdmin;
        User coordinator;
        User northgateCoordinator;
        User visitor;
        User visitor2;
        User reviewer;
        User homeStaff;
        User homeStaff2;
        User viewer;
        int organisations;
        int homes;
        int children;
        int users;
        int requests;
        String usernames;

        void markStatus(InterviewRequestRepository repository, InterviewRequest request,
                InterviewStatus status) {
            request.setStatus(status);
            repository.save(request);
        }
    }
}
