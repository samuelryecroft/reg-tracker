package ninja.samryecroft.returnhome.tracker.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestTestFixtures;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
import ninja.samryecroft.returnhome.tracker.interview.QueueFilter;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T319: the supplier dashboard knows what is LATE and now also what is STUCK.
 *
 * <p><b>A deadline metric reports; a stage-duration metric assigns.</b> Every tile on this screen
 * used to derive from the 72-hour clock, and a request can sit unallocated for six days while
 * appearing on none of them - because its clock has not started, or its deadline is still ahead.
 * "7 overdue" says they are late; it names nobody to chase.
 *
 * <p><b>Oscar's premise was false for one of the three stages, and this test is written around the
 * corrected version.</b> The Unallocated tile ALREADY carried an age before this card - as "oldest
 * waiting 144 hours". So the work is one genuinely missing stage (allocated, no visit time) plus a
 * format nobody converts in their head, applied to all three so the screen does not carry two
 * spellings of one concept.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TheSupplierDashboardKnowsWhatIsStuckTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganisationRepository organisationRepository;
    @Autowired private HomeRepository homeRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private InterviewRequestRepository interviewRequestRepository;
    @Autowired private AppUserDetailsService appUserDetailsService;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String suffix;
    private Organisation supplierOrg;
    private Home home;
    private Child child;
    private User visitor;
    private User homeStaff;
    private String coordinatorUsername;

    @BeforeEach
    void seedASupplierServingOneHome() {
        suffix = "-" + System.nanoTime();
        supplierOrg = saveOrg("T319 Supplier" + suffix, OrgType.SUPPLIER, null);
        Organisation careProvider = saveOrg("T319 Provider" + suffix, OrgType.CARE_PROVIDER, supplierOrg);
        home = saveHome("T319 House" + suffix, careProvider);

        child = new Child();
        child.setFirstName("Sam");
        child.setLastName("T319" + suffix);
        child.setDateOfBirth(LocalDate.of(2011, 4, 3));
        child.setHome(home);
        child = childRepository.save(child);

        visitor = saveUser("t319-visitor" + suffix, Role.VISITOR, supplierOrg);
        homeStaff = saveUser("t319-staff" + suffix, Role.HOME_STAFF, null);
        coordinatorUsername = "t319-coordinator" + suffix;
        saveUser(coordinatorUsername, Role.COORDINATOR, supplierOrg);
    }

    /**
     * THE MISSING STAGE. Allocated six days ago and still with no visit time: on main this request
     * appears on no tile at all, because its deadline says nothing about how long a visitor has been
     * sitting on it.
     */
    @Test
    void anInterviewAllocatedSixDaysAgoWithNoVisitTimeIsNamedAndAged() throws Exception {
        allocatedWithNoVisitTime(LocalDateTime.now().minusDays(6));

        String html = dashboardHtml();

        assertThat(html).contains("Awaiting a visit time");
        assertThat(html).as("the count says how much work there is; the age says whether something "
                        + "has been abandoned, and only the second is actionable at a glance")
                .contains("oldest 6 days");
    }

    /**
     * THE TILE AND THE LIST IT OPENS MUST AGREE, which is why this stage needed its own filter.
     *
     * <p>{@code AWAITING_REPORT} was the existing filter closest to it and covers ALLOCATED,
     * SCHEDULED and REPORT_REJECTED together. Pointing the tile there would have shown a count of
     * one and opened a list of two - and {@code QueueFilter}'s own comment says that a tile and its
     * list going out of step is the thing to avoid.
     */
    @Test
    void theTilesLinkOpensExactlyWhatItCounted() throws Exception {
        InterviewRequest stuck = allocatedWithNoVisitTime(LocalDateTime.now().minusDays(6));
        InterviewRequest scheduled = seedRequest(InterviewStatus.SCHEDULED);
        scheduled.setAllocatedVisitor(visitor);
        scheduled.setAllocatedAt(LocalDateTime.now().minusDays(6));
        scheduled.setScheduledAt(LocalDateTime.now().plusDays(1));
        interviewRequestRepository.saveAndFlush(scheduled);

        assertThat(dashboardHtml()).contains(QueueFilter.AWAITING_SCHEDULE.href());

        assertThat(QueueFilter.AWAITING_SCHEDULE.matches(stuck, LocalDateTime.now())).isTrue();
        assertThat(QueueFilter.AWAITING_SCHEDULE.matches(scheduled, LocalDateTime.now()))
                .as("a scheduled interview is somebody's work but it is not stuck - the broader "
                        + "AWAITING_REPORT filter cannot tell the two apart")
                .isFalse();
        assertThat(QueueFilter.AWAITING_REPORT.matches(scheduled, LocalDateTime.now()))
                .as("and it is still on the broader one, so nothing was taken away")
                .isTrue();
    }

    /**
     * THE FORMAT IS NORMALISED ACROSS ALL THREE STAGE TILES (god's ruling).
     *
     * <p>The Unallocated tile already carried an age before this card, as "oldest waiting 144
     * hours". Shipping the new tile in days beside that would have put two spellings of one concept
     * on a single screen - a smaller copy of the defect being fixed, created inside the fix.
     */
    @Test
    void theOldFormatIsGoneFromTheScreenEntirely() throws Exception {
        InterviewRequest waiting = seedRequest(InterviewStatus.REQUESTED);
        raisedDaysAgo(waiting, 6);

        String html = dashboardHtml();

        assertThat(html).as("the unallocated tile now speaks the same language as the new one")
                .contains("oldest 6 days");
        assertThat(html).as("and the raw-hours form it replaced is gone rather than merely joined")
                .doesNotContain("oldest waiting");
    }

    /**
     * A stage with nothing in it says so, rather than reporting an age of nothing.
     *
     * <p>The negative control: without it every assertion above is satisfied by a tile that always
     * shows a number.
     */
    @Test
    void aStageWithNothingInItSaysNoneWaiting() throws Exception {
        String html = dashboardHtml();

        assertThat(html).contains("Awaiting a visit time").contains("none waiting");
        assertThat(html).doesNotContain("oldest");
    }

    /**
     * A REQUEST ALLOCATED BEFORE V15 HAS NO {@code allocated_at}, AND THE TILE SAYS SO.
     *
     * <p>V15 added that column with no backfill, deliberately - there is no honest answer to what
     * time to invent for a record that never had one. Falling back to the request's creation time
     * would report an age this system cannot measure, as a fact, on the one tile whose whole point
     * is that the age can be trusted; dropping the row would print a reassuring "oldest" beside
     * something that might have been sitting far longer.
     */
    @Test
    void aRequestWithNoRecordedAllocationTimeIsCountedAndNamedRatherThanGuessedAt() throws Exception {
        InterviewRequest undated = seedRequest(InterviewStatus.ALLOCATED);
        undated.setAllocatedVisitor(visitor);
        undated.setAllocatedAt(null);
        interviewRequestRepository.saveAndFlush(undated);
        // Raised long ago, so a creation-time fallback would be loudly visible if one existed.
        raisedDaysAgo(undated, 30);

        String html = dashboardHtml();

        assertThat(html).contains("waiting time not recorded");
        assertThat(html).as("and it did NOT quietly date the stage from the request's creation")
                .doesNotContain("oldest 30 days");
    }

    /**
     * Backdates {@code created_at} in SQL, because {@code InterviewRequest} deliberately has no
     * setter for it - the column is {@code updatable = false} and initialised on construction, so
     * the only honest way to test an old request is to age the row rather than to add a setter that
     * exists for tests.
     */
    private void raisedDaysAgo(InterviewRequest request, int days) {
        jdbc.update("update interview_requests set created_at = ? where id = ?",
                LocalDateTime.now().minusDays(days), request.getId());
    }

    private InterviewRequest allocatedWithNoVisitTime(LocalDateTime allocatedAt) {
        InterviewRequest request = seedRequest(InterviewStatus.ALLOCATED);
        request.setAllocatedVisitor(visitor);
        request.setAllocatedAt(allocatedAt);
        request.setScheduledAt(null);
        return interviewRequestRepository.saveAndFlush(request);
    }

    private InterviewRequest seedRequest(InterviewStatus status) {
        InterviewRequest request = InterviewRequestTestFixtures.requestAt(status);
        request.setChild(child);
        request.setHome(home);
        request.setReturnedAt(LocalDateTime.now().minusHours(4));
        request.setRequestedBy(homeStaff);
        return interviewRequestRepository.saveAndFlush(request);
    }

    private String dashboardHtml() throws Exception {
        return mockMvc.perform(get("/dashboard").with(asUser(coordinatorUsername)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private User saveUser(String username, Role role, Organisation organisation) {
        User user = new User();
        user.setUsername(username);
        user.setLastName(username);
        user.setRoles(new HashSet<>(Set.of(role)));
        user.setOrganisation(organisation);
        user.setEnabled(true);
        return userRepository.saveAndFlush(user);
    }

    private Organisation saveOrg(String name, OrgType type, Organisation servedBy) {
        Organisation org = new Organisation();
        org.setName(name);
        org.setType(type);
        org.setSupplierOrganisation(servedBy);
        return organisationRepository.saveAndFlush(org);
    }

    private Home saveHome(String name, Organisation org) {
        Home home = new Home();
        home.setName(name);
        home.setOrganisation(org);
        return homeRepository.save(home);
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails details = appUserDetailsService.loadUserByUsername(username);
        return securityContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())));
    }
}
