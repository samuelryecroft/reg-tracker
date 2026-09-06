package ninja.samryecroft.returnhome.tracker.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
@Import(ConfirmVisitTimeIntegrationTest.PinnedClock.class)
@AutoConfigureMockMvc
class ConfirmVisitTimeIntegrationTest extends AbstractIntegrationTest {

    /**
     * T241. A fixed instant, so "how much time is left" is arithmetic rather than a race with the
     * runner. Chosen mid-minute and mid-hour on an ordinary weekday: a value that is round in any
     * component invites a passing assertion for the wrong reason.
     *
     * <p><b>Zero seconds, deliberately.</b> A {@code datetime-local} input cannot carry them, so the
     * POSTs below round-trip through an ISO-minute string and come back truncated. A fixed instant
     * with seconds in it would make the expected and actual values differ by the seconds the browser
     * was never able to send - a fixture artefact wearing the costume of a real defect.
     *
     * <p>The clock is replaced for the whole context rather than stubbed per call, because the
     * defect was never in one calculation - {@code DeadlineTracker} already takes its {@code now} as
     * a parameter and is entirely clock-agnostic. The wall clock entered at the controller, which is
     * the boundary this pins.
     */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-09-09T11:37:00Z"), ZoneOffset.UTC);

    /**
     * {@code @Primary} rather than a same-named override: Spring Boot disables bean-definition
     * overriding by default, and turning it on for one test would quietly license it everywhere.
     * Two clocks exist in this context and injection prefers this one.
     */
    @TestConfiguration
    static class PinnedClock {
        @Bean
        @Primary
        Clock pinnedClock() {
            return FIXED_CLOCK;
        }
    }

    /**
     * T241, the second half. This formatter used to take the JVM's default locale, and the screen
     * renders through Thymeleaf's {@code #temporals.format}, which takes the <b>request</b> locale.
     * Two undetermined inputs that happen to agree on most days - and disagree in exactly one month,
     * because English CLDR abbreviates September as "Sept" under {@code en-GB} and "Sep" under
     * {@code en-US}. Every other month is three letters in both.
     *
     * <p>So the test was red on unmodified main at the moment it was picked up, for a reason nobody
     * had diagnosed: it was reported as a wall-clock flake, and the wall-clock anchoring is real, but
     * the failure actually on screen was this. <b>A test can be flaky for two independent reasons and
     * the first explanation that fits will stop the search.</b>
     *
     * <p>Both sides are now pinned to {@code en-GB} - the request below sends it explicitly - so the
     * comparison no longer depends on the machine, the month, or which locale a runner defaults to.
     * The screen keeping the request locale is deliberate and ruled (spec §7x): a SCREEN follows its
     * reader, while the exported statutory DOCUMENT pins {@code Locale.UK} because it must not print
     * month names in whatever language a container happens to default to. Different rules, on
     * purpose - what was missing was the test declaring which one it was testing.
     */
    private static final Locale DISPLAY_LOCALE = Locale.UK;
    private static final DateTimeFormatter DISPLAY_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", DISPLAY_LOCALE);
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

        // T241: derived from the PINNED clock, not from the wall clock. This used to be
        // LocalDateTime.now().minusHours(10), which made the "time left" assertion below depend on
        // where now() happened to fall - correct only while the seconds discarded by truncation plus
        // the test's own runtime stayed inside one minute. It passed reliably on a fast machine and
        // would drift on a slow runner, in the BLOCKING lane, which is the worst place for it: a
        // flaky test there trains people to re-run CI on red, and a lane whose red is routinely
        // re-run has stopped being a gate while still looking like one.
        //
        // The offset is deliberately not a round number of hours. A fixture 10 hours back would
        // assert "62h 0m left", which a stub returning a round default could also produce; 10h23m
        // asserts a figure only the real arithmetic yields.
        returnedAt = LocalDateTime.now(FIXED_CLOCK).minusHours(10).minusMinutes(23)
                .withSecond(0).withNano(0);
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
        // re-worded on this screen. 61h 37m is 72h minus the fixture's 10h23m, and it is now an
        // arithmetic fact rather than an observation: with the clock pinned, the figure cannot move
        // whatever the machine or the day.
        //
        // The previous value was 61h 59m, and the comment here explained it as "always a hair under
        // the round hour ... confirmed against a live run rather than assumed". That reasoning was
        // sound and the number was right - it was a correct description of an artefact, which is a
        // harder thing to spot than a wrong one, because everything about it reads as diligence.
        assertThat(html).contains("On track — 61h 37m left");
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

    @Test
    void aRequestThatIsNotAwaitingAScheduleRedirectsToTheDetailPageInsteadOfAFormThatCannotSucceed() throws Exception {
        // D-5b-6: getAuthorized enforces authorization, not status - a bookmark or stale link must
        // not reach a form offering a Confirm the server would refuse (confirmSchedule's own
        // precondition is ALLOCATED only). Seeded already SCHEDULED here; CANCELLED would fail the
        // same way, for the same reason - one status past the precondition is enough to prove the
        // gate, not an exhaustive sweep of every non-ALLOCATED value.
        InterviewRequest alreadyScheduled = InterviewRequestTestFixtures.requestAt(InterviewStatus.SCHEDULED);
        Child child = new Child();
        child.setFirstName("Casey");
        child.setLastName("T5b6" + suffix);
        child.setDateOfBirth(LocalDate.of(2014, 1, 1));
        child.setHome(homeOf(requestId));
        Child savedChild = childRepository.save(child);
        alreadyScheduled.setChild(savedChild);
        alreadyScheduled.setHome(homeOf(requestId));
        alreadyScheduled.setRequestedBy(userRepository.findByUsername("t5b-coordinator" + suffix).orElseThrow());
        alreadyScheduled.setAllocatedVisitor(userRepository.findByUsername(visitorUsername).orElseThrow());
        alreadyScheduled.setReturnedAt(returnedAt);
        alreadyScheduled.setScheduledAt(returnedAt.plusHours(20));
        Long scheduledId = interviewRequestRepository.save(alreadyScheduled).getId();

        mockMvc.perform(get("/visitor/interviews/{id}/schedule", scheduledId).with(asUser(visitorUsername)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/interview-requests/" + scheduledId));
    }

    private Home homeOf(Long requestId) {
        return interviewRequestRepository.findById(requestId).orElseThrow().getHome();
    }

    private String getForm() throws Exception {
        return mockMvc.perform(get("/visitor/interviews/{id}/schedule", requestId)
                        .locale(DISPLAY_LOCALE)
                        .with(asUser(visitorUsername)))
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
