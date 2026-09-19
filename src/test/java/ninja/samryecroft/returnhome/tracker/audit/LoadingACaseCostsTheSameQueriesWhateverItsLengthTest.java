package ninja.samryecroft.returnhome.tracker.audit;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.export.CaseFileExportService;
import ninja.samryecroft.returnhome.tracker.export.ExportPeriod;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import ninja.samryecroft.returnhome.tracker.report.ReportStatus;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * T385 (CODE-REVIEW-2026-09-18 §3, "1+2N on the batched report queries"). The case history and the
 * export manifest asked for each request's report on its own - the history twice - and the report
 * finders fetched no request, so every load then fetched its request, home and organisation
 * lazily to choose a decryption key. A child with twelve episodes cost well over a hundred
 * statements. Now the cost is the same whatever the length, which is what "batched" has to mean.
 *
 * <p>Asserted as equality between two lengths rather than as a number: a number pins this
 * week's query plan, and would fail on any unrelated change; equality pins the property.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class LoadingACaseCostsTheSameQueriesWhateverItsLengthTest extends AbstractIntegrationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private OrganisationRepository organisationRepository;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ChildRepository childRepository;
    @Autowired
    private InterviewRequestRepository interviewRequestRepository;
    @Autowired
    private InterviewReportRepository interviewReportRepository;
    @Autowired
    private AuditHistoryService auditHistoryService;
    @Autowired
    private CaseFileExportService caseFileExportService;

    @Test
    void theCaseHistoryAndTheManifestCostTheSameStatementsForTwelveEpisodesAsForThree() {
        String suffix = "-" + System.nanoTime();
        Organisation org = new Organisation();
        org.setName("T385 Provider" + suffix);
        org.setType(OrgType.CARE_PROVIDER);
        org = organisationRepository.save(org);
        Home home = new Home();
        home.setName("T385 House" + suffix);
        home.setOrganisation(org);
        home = homeRepository.save(home);
        User staff = new User();
        staff.setUsername("t385-staff" + suffix);
        staff.setEmail("t385-staff" + suffix + "@example.test");
        staff.setLastName("Staff");
        staff.setRoles(new HashSet<>(Set.of(Role.ORG_ADMIN)));
        staff.setOrganisation(org);
        staff.setEnabled(true);
        staff.setCanExport(true);
        staff = userRepository.saveAndFlush(staff);
        AppUserPrincipal principal = new AppUserPrincipal(staff, false);

        Long three = childWithEpisodes(3, home, staff, suffix);
        Long twelve = childWithEpisodes(12, home, staff, suffix);

        long statementsForThree = statementsToLoad(three, principal);
        long statementsForTwelve = statementsToLoad(twelve, principal);

        assertThat(statementsForTwelve)
                .as("a case four times as long must not cost more statements to load (%d vs %d)",
                        statementsForTwelve, statementsForThree)
                .isEqualTo(statementsForThree);
    }

    private long statementsToLoad(Long childId, AppUserPrincipal principal) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        List<InterviewRequest> requests = interviewRequestRepository.findByChildIdOrderByCreatedAtDesc(childId);
        auditHistoryService.caseHistoryFor(requests, DraftSaveRuns.COLLAPSED, AuditFeedScope.WITH_ACCESS_EVENTS);
        caseFileExportService.manifestFor(childId, ExportPeriod.all(), principal);

        return statistics.getPrepareStatementCount();
    }

    private Long childWithEpisodes(int episodes, Home home, User staff, String suffix) {
        Child child = new Child();
        child.setFirstName("Case");
        child.setLastName("Length" + episodes + suffix);
        child.setDateOfBirth(LocalDate.of(2011, 1, 1));
        child.setHome(home);
        child = childRepository.save(child);
        for (int i = 0; i < episodes; i++) {
            InterviewRequest request = new InterviewRequest();
            request.setChild(child);
            request.setHome(home);
            request.setRequestedBy(staff);
            request.setReturnedAt(LocalDateTime.now().minusDays(episodes - i));
            request = interviewRequestRepository.save(request);
            InterviewReport report = new InterviewReport();
            report.setInterviewRequest(request);
            report.setVisitor(staff);
            report.setStatus(ReportStatus.DRAFT);
            report.setReviewComments("episode " + i);
            report.setRecommendations("none");
            interviewReportRepository.save(report);
        }
        return child.getId();
    }
}
