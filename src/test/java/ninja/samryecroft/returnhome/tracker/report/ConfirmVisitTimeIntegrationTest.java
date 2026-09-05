package ninja.samryecroft.returnhome.tracker.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * D-5b-1/2/3/4/5 (T119 spec §7d): the visitor confirming a visit time is the person the 72-hour
 * statutory duty actually measures, so the form must show the clock the choice is measured
 * against, constrain the one genuinely impossible answer (before the child returned) without
 * capping the legitimate ones (after the deadline), and say once that a late visit will need a
 * reason later.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConfirmVisitTimeIntegrationTest extends AbstractIntegrationTest {

    private static final DateTimeFormatter DISPLAY_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");
    private static final DateTimeFormatter MIN_ATTR_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

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
    private String visitorUsername;
    private LocalDateTime returnedAt;
    private Long requestId;

    @BeforeEach
    void seedAnAllocatedRequest() {
        suffix = "-" + System.nanoTime();
        Organisation supplier = seededSupplier();
        Organisation careProvider = seededCareProvider();

        Home home = new Home();
        home.setName("T119(7d) House" + suffix);
        home.setOrganisation(careProvider);
        home = homeRepository.save(home);

        visitorUsername = "t5b-visitor" + suffix;
        User visitor = saveUser(visitorUsername, Role.VISITOR, supplier);
        saveUser("t5b-coordinator" + suffix, Role.COORDINATOR, supplier);

        Child child = new Child();
        child.setFirstName("Robin");
        child.setLastName("T5b" + suffix);
        child.setDateOfBirth(LocalDate.of(2013, 4, 9));
        child.setHome(home);
        Child savedChild = childRepository.save(child);

        // Truncated to the minute: a datetime-local input can never carry seconds, and the POST
        // params below round-trip through the same ISO-minute string a real picker would send.
        returnedAt = LocalDateTime.now().minusHours(10).withSecond(0).withNano(0); // ~62h remaining: ON_TRACK
        InterviewRequest request = InterviewRequestTestFixtures.requestAt(InterviewStatus.ALLOCATED);
        request.setChild(savedChild);
        request.setHome(home);
        request.setRequestedBy(userRepository.findByUsername("t5b-coordinator" + suffix).orElseThrow());
        request.setAllocatedVisitor(visitor);
        request.setReturnedAt(returnedAt);
        requestId = interviewRequestRepository.save(request).getId();
    }

    @Test
    void theClockBlockShowsReturnedDeadlineAndTimeRemainingReusingDueStateCopysWords() throws Exception {
        String html = getForm();

        assertThat(html).contains(returnedAt.format(DISPLAY_FMT));
        // D-5b-1: the deadline is returnedAt + DeadlineTracker.RETURN_WINDOW (72h), never a literal.
        assertThat(html).contains(returnedAt.plusHours(72).format(DISPLAY_FMT));
        // The exact words DueStateCopy/DeadlineTracker produce for ON_TRACK, quoted verbatim, not
        // re-worded on this screen. 61h 59m rather than a clean 62h: returnedAt is truncated to the
        // minute above, but the controller's own "now" is real wall-clock time with its own
        // (non-zero) seconds, so the computed remaining duration is always a hair under the round
        // hour, never exactly on it - confirmed against a live run rather than assumed.
        assertThat(html).contains("On track — 61h 59m left");
    }

    @Test
    void minIsSetToTheReturnDatetimeAndThereIsNoMax() throws Exception {
        String html = getForm();

        assertThat(html).contains("min=\"" + returnedAt.format(MIN_ATTR_FMT) + "\"");
        assertThat(html).doesNotContain("max=");
    }

    @Test
    void theOutsideWindowNoticeIsAlwaysShownUnconditionally() throws Exception {
        String html = getForm();

        assertThat(html).contains("the report will ask why");
    }

    @Test
    void schedulingBeforeTheChildsReturnIsRejectedAndTheClockBlockStillRendersOnRedisplay() throws Exception {
        String html = mockMvc.perform(post("/visitor/interviews/{id}/schedule", requestId)
                        .with(asUser(visitorUsername)).with(csrf())
                        .param("scheduledAt", returnedAt.minusHours(1).format(MIN_ATTR_FMT)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("cannot be before the child returned");
        // D-5b-1's block re-renders on the error redisplay, not just the fresh form.
        assertThat(html).contains("72-hour deadline");

        InterviewRequest unchanged = interviewRequestRepository.findById(requestId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(InterviewStatus.ALLOCATED);
    }

    @Test
    void aTimeAfterReturnButStillInThePastIsAccepted_noImplicitFutureCheck() throws Exception {
        // D-5b-4: deliberately no @Future - a visitor recording a visit time after the fact is
        // legitimate. returnedAt is already several hours in the past, so returnedAt+1h is too,
        // yet this must succeed since it is still after the return.
        LocalDateTime pastButAfterReturn = returnedAt.plusHours(1);

        mockMvc.perform(post("/visitor/interviews/{id}/schedule", requestId)
                        .with(asUser(visitorUsername)).with(csrf())
                        .param("scheduledAt", pastButAfterReturn.format(MIN_ATTR_FMT)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/visitor/interviews"));

        InterviewRequest updated = interviewRequestRepository.findById(requestId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(InterviewStatus.SCHEDULED);
        assertThat(updated.getScheduledAt()).isEqualTo(pastButAfterReturn);
    }

    @Test
    void aTimeAfterTheDeadlineIsAcceptedNotCapped() throws Exception {
        // D-5b-3, the load-bearing decision: outside the 72-hour window must stay recordable.
        LocalDateTime wellOutsideWindow = returnedAt.plusHours(200);

        mockMvc.perform(post("/visitor/interviews/{id}/schedule", requestId)
                        .with(asUser(visitorUsername)).with(csrf())
                        .param("scheduledAt", wellOutsideWindow.format(MIN_ATTR_FMT)))
                .andExpect(status().is3xxRedirection());

        InterviewRequest updated = interviewRequestRepository.findById(requestId).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(InterviewStatus.SCHEDULED);
        assertThat(updated.getScheduledAt()).isEqualTo(wellOutsideWindow);
    }

    private String getForm() throws Exception {
        return mockMvc.perform(get("/visitor/interviews/{id}/schedule", requestId).with(asUser(visitorUsername)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private User saveUser(String username, Role role, Organisation organisation) {
        User user = new User();
        user.setUsername(username);
        user.setLastName(username);
        user.setRoles(new HashSet<>(Set.of(role)));
        user.setOrganisation(organisation);
        user.setHomes(new HashSet<>());
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails details = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        return securityContext(context);
    }
}
