package ninja.samryecroft.returnhome.tracker.interview;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The shared case card (§6b S-1) as it actually reaches a browser, on 2a.
 *
 * <p>Everything here is a check that would pass equally well if the thing it is about had been
 * left out - which is why it is a rendered-HTML test rather than a source one. A card that has
 * quietly lost its axis labels, or a screen that still carries the second copy of its own data,
 * renders perfectly and looks right.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CaseCardIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "CorrectHorse123!";

    @Autowired private MockMvc mockMvc;
    @Autowired private HomeRepository homeRepository;
    @Autowired private ChildRepository childRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private InterviewRequestRepository interviewRequestRepository;
    @Autowired private AppUserDetailsService appUserDetailsService;
    @Autowired private PasswordEncoder passwordEncoder;

    private Organisation careProviderOrg;
    private Home home;
    private String suffix;

    @BeforeEach
    void seedData() {
        suffix = "-" + System.nanoTime();
        careProviderOrg = seededCareProvider();
        Organisation supplierOrg = seededSupplier();
        home = saveHome("Card House" + suffix);
        userRepository.save(newUser("card-home" + suffix, Role.HOME_STAFF, home, null));
        userRepository.save(newUser("card-coordinator" + suffix, Role.COORDINATOR, null, supplierOrg));
    }

    /**
     * R-Q12 and §6b's finding: all four case lists rendered their rows twice - a responsive table
     * AND a duplicated .stack card list - and the two copies had already drifted about what a row
     * contains. The fix was to delete one, so the guard is that the deleted one has not come back:
     * the duplication is invisible on any single screen, because each copy renders correctly at
     * its own width and only one is ever on screen at a time.
     */
    @Test
    void theQueueRendersCaseCardsAndNoSecondCopyOfTheSameRows() throws Exception {
        saveRequest("CARD-0001", InterviewStatus.REQUESTED, LocalDateTime.now().minusHours(80));

        String html = queueHtml("");

        assertThat(html).contains("class=\"case\"").contains("case-list");
        assertThat(html)
                .as("the table and the .stack card list were two renderings of one dataset; "
                        + "deleting one is the fix, so neither may reappear on this screen")
                .doesNotContain("table-wrap").doesNotContain("class=\"stack\"");
    }

    /**
     * D-2a-2: a card carries two coloured tags on two different axes, and nothing non-visual says
     * which is which - "Pending review" and "3h 20m overdue" arrive as two bare phrases. The
     * hidden axis word is access rather than duplication precisely because the axis name appears
     * in no visible text, which is the distinction D-1a-2b's converse turns on.
     */
    @Test
    void eachOfTheTwoTagsNamesItsOwnAxisToAScreenReader() throws Exception {
        saveRequest("CARD-0002", InterviewStatus.REQUESTED, LocalDateTime.now().minusHours(80));

        String html = queueHtml("");

        assertThat(html).contains("<span class=\"visually-hidden\">Status: </span>");
        assertThat(html).contains("<span class=\"visually-hidden\">Deadline: </span>");
    }

    /**
     * D-2a-1 REVISED: the due tag keeps its state WORD inside a tier group. The group heading is
     * announced once per group and the card is scanned and deep-linked on its own, so the word is
     * what makes a card complete wherever it appears. The copy is DueStateCopy's, human-signed-off
     * and pinned character-for-character elsewhere - this pins that the CARD still carries it.
     */
    @Test
    void theDueTagKeepsItsStateWordEvenInsideATierGroupThatAlreadySaysIt() throws Exception {
        saveRequest("CARD-0003", InterviewStatus.REQUESTED, LocalDateTime.now().minusHours(50));

        String html = queueHtml("");

        assertThat(html).contains(DueStateCopy.stateWord(DueState.DUE_SOON) + " —");
    }

    /**
     * S-1: masked, the canvas's own label beside an initials disc would read the initials twice.
     * The disc takes them and the label takes the case reference - but the disc is real text
     * inside the same link, so a screen reader still hears both. Both halves matter: dropping the
     * repetition is the visible half, and keeping the initials reachable is the half that a purely
     * visual review would never notice was gone.
     */
    @Test
    void theCardShowsTheInitialsOnceAndStillNamesBothInsideOneLink() throws Exception {
        saveRequest("CARD-0004", InterviewStatus.REQUESTED, LocalDateTime.now().minusHours(80));

        String html = queueHtml("");
        String link = html.substring(html.indexOf("case-link"), html.indexOf("</a>", html.indexOf("case-link")));

        assertThat(link).contains(">C.D<");            // the disc: punctuated initials, once
        assertThat(link).contains(">CARD-0004<");      // the label: the reference, not the initials again
        assertThat(link).doesNotContain("C.D. · CARD-0004");
    }

    /**
     * D-2a-7: the whole-card target is rejected (S-1), so its visual signature goes with it. A
     * caret that promises a click the card does not honour is worse than a missing decoration.
     */
    @Test
    void theCardHasOneNamedLinkAndOneActionAndNoWholeRowTargetSignature() throws Exception {
        saveRequest("CARD-0005", InterviewStatus.REQUESTED, LocalDateTime.now().minusHours(80));

        String html = queueHtml("");
        String card = html.substring(html.indexOf("class=\"case\""));
        // T253: the card's root is <li>, not <div> - it is one item in a real list now.
        card = card.substring(0, card.indexOf("</li>", card.indexOf("case-action")));

        assertThat(card).contains("case-action").contains("Allocate");
        assertThat(card).doesNotContain("caret-right");
    }

    /**
     * R-Q13, and Oscar's first principle: in a safeguarding queue an empty list is ambiguous
     * between "nothing to do" and "the system is not showing me everything". A filtered-empty
     * queue reading as an all-clear is the dangerous confusion, so it must say which it is and
     * offer the way out.
     */
    @Test
    void anEmptyQueueSaysWhetherItIsEmptyBecauseOfAFilter() throws Exception {
        saveRequest("CARD-0006", InterviewStatus.REQUESTED, LocalDateTime.now().minusHours(80));

        assertThat(queueHtml("?filter=awaitingReview"))
                .contains("No interviews match these filters").contains("Clear filters");
        assertThat(queueHtml("?filter=awaitingReview")).doesNotContain("No interviews are waiting.");
    }

    /**
     * D-2a-6: a filter the dashboard deep-links to but the menu does not offer still has to show a
     * selected chip, or the tile and the list stop visibly matching with nothing to say they have.
     */
    @Test
    void aDeepLinkedOffMenuFilterStillShowsASelectedChip() throws Exception {
        saveRequest("CARD-0007", InterviewStatus.REQUESTED, LocalDateTime.now().minusHours(80));

        String html = queueHtml("?filter=overdue");

        assertThat(html).contains("aria-current=\"true\"").contains(QueueFilter.OVERDUE.label());
        assertThat(html).as("S-3: the banner is what puts the filter state in ordinary running text")
                .contains("Showing a filtered view");
    }

    private String queueHtml(String query) throws Exception {
        return mockMvc.perform(get("/coordinator/requests" + query).with(asUser("card-coordinator" + suffix)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        return securityContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities())));
    }

    private Home saveHome(String name) {
        Home h = new Home();
        h.setName(name);
        h.setOrganisation(careProviderOrg);
        return homeRepository.save(h);
    }

    /** First/last names give the disc "C.D"; the case reference is what the masked label shows. */
    private InterviewRequest saveRequest(String reference, InterviewStatus status, LocalDateTime returnedAt) {
        Child child = new Child();
        child.setFirstName("Casey");
        child.setLastName("Doyle");
        child.setLocalCaseReference(reference);
        child.setDateOfBirth(LocalDate.of(2011, 3, 4));
        child.setHome(home);
        child = childRepository.save(child);

        InterviewRequest request = new InterviewRequest();
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(userRepository.findByUsername("card-home" + suffix).orElseThrow());
        request.setStatus(status);
        request.setReturnedAt(returnedAt);
        return interviewRequestRepository.save(request);
    }

    private User newUser(String username, Role role, Home userHome, Organisation organisation) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setLastName(username);
        user.setRoles(Set.of(role));
        user.setHomes(userHome == null ? new HashSet<>() : new HashSet<>(Set.of(userHome)));
        user.setOrganisation(organisation);
        user.setEnabled(true);
        return user;
    }
}
