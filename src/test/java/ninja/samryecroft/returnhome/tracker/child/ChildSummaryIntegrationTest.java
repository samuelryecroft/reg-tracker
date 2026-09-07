package ninja.samryecroft.returnhome.tracker.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestTestFixtures;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T290 (spec §D-4c, {@code CREED-SPEC-child-summary.md}). {@code children/detail.html} already
 * rendered a workflow queue for one child (D-4b); this closes the gap D-4c-1 measured - not one of
 * the queue's own columns ever described the missing episode itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChildSummaryIntegrationTest extends AbstractIntegrationTest {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.UK);
    // Deliberately Locale.ENGLISH, NOT Locale.UK like DATE_FMT above: this compares against the
    // table/stack's own #temporals.format(x, 'dd MMM yyyy HH:mm') calls, which resolve their
    // locale through Spring's AcceptHeaderLocaleResolver rather than a fixed one (the same
    // already-documented, pre-existing gap ChildController.ChildListRow's own DOB_FMT comment
    // names - "Creed's T193 follow-up") - MockMvc sends no Accept-Language header, which that
    // resolver defaults to plain Locale.ENGLISH, not the JVM's own default locale (confirmed
    // empirically: on this JVM, both Locale.UK and Locale.getDefault() render September as
    // "Sept"; only Locale.ENGLISH renders "Sep", matching the template's actual output).
    // RETURNED_FMT/DATE_FMT above are different because they compare against
    // MissingEpisodesSummary's own Locale.UK-pinned formatter, not this template call.
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private ChildRepository childRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InterviewRequestRepository interviewRequestRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private String suffix;
    private String staffUsername;
    private Home home;
    private MockHttpSession session;

    @BeforeEach
    void seedAHomeAndItsStaff() {
        suffix = "-" + System.nanoTime();
        Organisation careProvider = seededCareProvider();
        home = new Home();
        home.setName("T290 House" + suffix);
        home.setOrganisation(careProvider);
        home = homeRepository.save(home);

        staffUsername = "t290-staff" + suffix;
        User staff = new User();
        staff.setUsername(staffUsername);
        staff.setLastName("Staff");
        staff.setRoles(new HashSet<>(Set.of(Role.HOME_STAFF)));
        staff.setHomes(new HashSet<>(Set.of(home)));
        staff.setEnabled(true);
        userRepository.save(staff);

        session = new MockHttpSession();
    }

    private Child savedChild() {
        Child child = new Child();
        child.setFirstName("Jordan");
        child.setLastName("T290" + suffix);
        child.setDateOfBirth(LocalDate.of(2013, 7, 22));
        child.setLocalCaseReference("CH-T290" + suffix);
        child.setHome(home);
        return childRepository.save(child);
    }

    private InterviewRequest episode(Child child, LocalDateTime returnedAt) {
        InterviewRequest request = InterviewRequestTestFixtures.requestAt(InterviewStatus.REPORT_APPROVED);
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(userRepository.findByUsername(staffUsername).orElseThrow());
        request.setReturnedAt(returnedAt);
        return interviewRequestRepository.save(request);
    }

    private RequestPostProcessor asStaff() {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(staffUsername);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }

    private String getDetail(Long childId) throws Exception {
        return mockMvc.perform(get("/children/{id}", childId).with(asStaff()).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void noEpisodesRendersTheOneDefinedEmptyStateNotAnAbsentBlock() throws Exception {
        Child child = savedChild();

        String html = getDetail(child.getId());

        assertThat(html).contains("No missing episodes recorded");
        assertThat(html).contains("Missing episodes and interviews");
        // The caption lives inside the table's own th:if (D-4b-2: an empty case file must not
        // render a table promising columns over nothing) - with zero requests the table, and its
        // caption, correctly do not render at all. Asserting the caption text here was this
        // test's own mistake, not a gap in the page.
        assertThat(html).doesNotContain("Every missing episode recorded for this young person, and the interview raised for it.");
    }

    @Test
    void oneEpisodeInLastSixMonthsUsesTheSingularCase() throws Exception {
        Child child = savedChild();
        episode(child, LocalDateTime.now().minusDays(10));

        String html = getDetail(child.getId());

        assertThat(html).contains("1 episode, in the last 6 months");
    }

    @Test
    void allEpisodesInLastSixMonthsUsesThePluralAllCase() throws Exception {
        Child child = savedChild();
        episode(child, LocalDateTime.now().minusDays(10));
        episode(child, LocalDateTime.now().minusDays(40));

        String html = getDetail(child.getId());

        assertThat(html).contains("2 episodes, all in the last 6 months");
    }

    @Test
    void noEpisodesInLastSixMonthsReadsNoneNotZero() throws Exception {
        Child child = savedChild();
        episode(child, LocalDateTime.now().minusMonths(9));
        episode(child, LocalDateTime.now().minusMonths(11));

        String html = getDetail(child.getId());

        assertThat(html).contains("2 episodes · none in the last 6 months");
        assertThat(html).doesNotContain("0 in the last 6 months");
    }

    @Test
    void aMixOfRecentAndOlderEpisodesStatesBothCounts() throws Exception {
        Child child = savedChild();
        episode(child, LocalDateTime.now().minusDays(10));
        episode(child, LocalDateTime.now().minusMonths(9));

        String html = getDetail(child.getId());

        assertThat(html).contains("2 episodes · 1 in the last 6 months");
    }

    @Test
    void theHintLineNamesTheMostRecentReturnDateAndDaysElapsedNeverMonthsOrYears() throws Exception {
        Child child = savedChild();
        LocalDateTime recent = LocalDateTime.now().minusDays(41);
        episode(child, recent);
        episode(child, LocalDateTime.now().minusMonths(8));

        String html = getDetail(child.getId());

        assertThat(html).contains("Last returned " + recent.format(DATE_FMT) + " · 41 days ago");
    }

    @Test
    void returningTodayAndYesterdayUseTheirOwnWordsNotADayCount() throws Exception {
        // Anchored to noon on each calendar day, not "N hours ago" - a fixed hour offset (e.g.
        // minusHours(2)) is only "today" if the test happens to run after 2am, and silently
        // becomes "yesterday" instead whenever it runs in the small hours. Noon is unambiguously
        // today's date and yesterday's date respectively, regardless of the wall-clock time this
        // test itself runs at.
        LocalDateTime todayNoon = LocalDateTime.now().toLocalDate().atTime(12, 0);
        Child today = savedChild();
        episode(today, todayNoon);
        assertThat(getDetail(today.getId())).contains("· today");

        LocalDateTime yesterdayNoon = todayNoon.minusDays(1);
        Child yesterday = savedChild();
        episode(yesterday, yesterdayNoon);
        assertThat(getDetail(yesterday.getId())).contains("· yesterday");
    }

    /**
     * D-4c-4, the human's own ruling (9 Sep): the derived figure wins over the recorded Boolean,
     * which can disagree with what the request history itself shows. Recording "yes, in the last 6
     * months" on an episode that actually returned 9 months ago must not make it into the derived
     * count.
     */
    @Test
    void theDerivedCountWinsOverAWronglyRecordedBoolean() throws Exception {
        Child child = savedChild();
        InterviewRequest request = InterviewRequestTestFixtures.requestAt(InterviewStatus.REPORT_APPROVED);
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(userRepository.findByUsername(staffUsername).orElseThrow());
        request.setReturnedAt(LocalDateTime.now().minusMonths(9));
        request.setMissingInLast6Months(true);
        interviewRequestRepository.save(request);

        String html = getDetail(child.getId());

        assertThat(html).contains("1 episode · none in the last 6 months");
    }

    /**
     * D-4c-5: the most recent episode's DATE only - its status is one row below in the table, and
     * a summary and a table that both state one row's status are two places that can disagree.
     */
    @Test
    void theSummaryNeverStatesTheMostRecentEpisodesStatus() throws Exception {
        Child child = savedChild();
        episode(child, LocalDateTime.now().minusDays(3));

        String html = getDetail(child.getId());
        int summaryStart = html.indexOf("Missing episodes</h3>");
        int summaryEnd = html.indexOf("</div>", summaryStart);
        String summaryBlock = html.substring(summaryStart, summaryEnd);

        assertThat(summaryBlock).doesNotContain("Report approved");
    }

    /**
     * D-4c-6, the one Creed and god both separately named as the one worth meeting early rather
     * than late: the Article 9 narrative fields must never reach this page at all, even though
     * "a summary of their information" reads as though they should.
     */
    @Test
    void article9NarrativeFieldsNeverReachThePage() throws Exception {
        Child child = savedChild();
        InterviewRequest request = InterviewRequestTestFixtures.requestAt(InterviewStatus.REPORT_APPROVED);
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(userRepository.findByUsername(staffUsername).orElseThrow());
        request.setReturnedAt(LocalDateTime.now().minusDays(3));
        request.setMissingEpisodeDetails("NARRATIVE-MARKER-EPISODE-DETAILS");
        request.setKnownRisks("NARRATIVE-MARKER-KNOWN-RISKS");
        request.setChildsComments("NARRATIVE-MARKER-CHILDS-COMMENTS");
        request.setImportantPeople("NARRATIVE-MARKER-IMPORTANT-PEOPLE");
        request.setLegalStatus("NARRATIVE-MARKER-LEGAL-STATUS");
        interviewRequestRepository.save(request);

        String html = getDetail(child.getId());

        assertThat(html).doesNotContain("NARRATIVE-MARKER");
    }

    /**
     * D-4c-3, the finding that shapes everything: a request raised LATE for an OLD episode must
     * not sort as though the episode were recent. Constructed so createdAt order and returnedAt
     * order are opposite - the old episode's request is created FIRST (createdAt earlier) but its
     * returnedAt is the more recent of the two, so a query still ordering by createdAt would put
     * the two rows in the wrong sequence and this test would fail against it.
     */
    @Test
    void episodesOrderByReturnedAtNotByWhenTheRequestWasRaised() throws Exception {
        Child child = savedChild();
        LocalDateTime recentReturn = LocalDateTime.now().minusDays(2);
        LocalDateTime oldReturn = LocalDateTime.now().minusDays(200);

        // Constructed/saved FIRST (earlier createdAt) but with the MORE RECENT returnedAt.
        episode(child, recentReturn);
        // Constructed/saved SECOND (later createdAt) but with the OLDER returnedAt.
        episode(child, oldReturn);

        String html = getDetail(child.getId());

        int recentIndex = html.indexOf(recentReturn.format(DATETIME_FMT));
        int oldIndex = html.indexOf(oldReturn.format(DATETIME_FMT));
        assertThat(recentIndex).as("returnedAt desc: the more recently returned episode leads")
                .isPositive();
        assertThat(oldIndex).isPositive();
        assertThat(recentIndex).isLessThan(oldIndex);
    }

    /**
     * D-4c-3/D-4c-7: missingSince is nullable and often absent - "Not recorded" in .unanswered,
     * never a blank or a dash, and the SAME words in both the table and the 720px stack (D-4b-1's
     * rule, now extended to these two new fields).
     */
    @Test
    void anAbsentMissingSinceReadsNotRecordedIdenticallyInTableAndStack() throws Exception {
        Child child = savedChild();
        episode(child, LocalDateTime.now().minusDays(5));

        String html = getDetail(child.getId());

        assertThat(occurrencesOf(html, "Not recorded")).isEqualTo(2);
        assertThat(occurrencesOf(html, "class=\"unanswered\">Not recorded")).isEqualTo(2);
    }

    @Test
    void aRecordedMissingSinceRendersInBothRenderingsIdentically() throws Exception {
        Child child = savedChild();
        InterviewRequest request = InterviewRequestTestFixtures.requestAt(InterviewStatus.REPORT_APPROVED);
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(userRepository.findByUsername(staffUsername).orElseThrow());
        LocalDateTime missingSince = LocalDateTime.now().minusDays(6).withSecond(0).withNano(0);
        request.setReturnedAt(LocalDateTime.now().minusDays(5));
        request.setMissingSince(missingSince);
        interviewRequestRepository.save(request);

        String html = getDetail(child.getId());

        assertThat(occurrencesOf(html, missingSince.format(DATETIME_FMT))).isEqualTo(2);
    }

    /** D-4c-7/T253: display:flex on a list strips list semantics in Safari/VoiceOver. */
    @Test
    void theMobileStackCarriesListSemantics() throws Exception {
        Child child = savedChild();
        episode(child, LocalDateTime.now().minusDays(5));

        String html = getDetail(child.getId());

        assertThat(html).contains("<ul class=\"stack\" role=\"list\"");
        assertThat(html).contains("<li class=\"srow\" role=\"listitem\"");
    }

    private static int occurrencesOf(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
