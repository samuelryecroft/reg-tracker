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
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T266, ruled by Oscar: an organisation with no visitor-role users gave a coordinator a REQUIRED
 * RADIO GROUP WITH NOTHING IN IT and a submit button that could never validate.
 *
 * <p><strong>The state is legitimate and permanent, not a setup wrinkle.</strong> It arises twice:
 * while an organisation is populated one person at a time, and again in normal running when a
 * visitor leaves or is disabled. The second case decides it - no onboarding order can stop an
 * organisation losing its last visitor - so the screen handles a state rather than explaining a
 * temporary condition.
 *
 * <p><strong>The control and the button ARE the defect</strong>, which is why these tests assert
 * their ABSENCE rather than checking the words around them. Replacing the copy beside an empty
 * required fieldset leaves a form that cannot be submitted and cannot say why.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AllocateWithNoVisitorsIsNotADeadEndTest extends AbstractIntegrationTest {

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
    private Organisation supplier;
    private Home home;
    private Long requestId;
    private String plainCoordinator;
    private String coordinatorWhoIsAlsoAnAdmin;

    @BeforeEach
    void seedAnOrganisationWithNoVisitorsAtAll() {
        suffix = "-" + System.nanoTime();
        supplier = seededSupplier();
        Organisation careProvider = seededCareProvider();

        home = new Home();
        home.setName("T266 House" + suffix);
        home.setOrganisation(careProvider);
        home = homeRepository.save(home);

        plainCoordinator = "t266-coordinator" + suffix;
        coordinatorWhoIsAlsoAnAdmin = "t266-coordinator-admin" + suffix;
        saveUser(plainCoordinator, Set.of(Role.COORDINATOR));
        saveUser(coordinatorWhoIsAlsoAnAdmin, Set.of(Role.COORDINATOR, Role.ORG_ADMIN));

        Child child = new Child();
        child.setFirstName("Sasha");
        child.setLastName("T266" + suffix);
        child.setDateOfBirth(LocalDate.of(2011, 6, 1));
        child.setHome(home);
        Child savedChild = childRepository.save(child);

        InterviewRequest request = InterviewRequestTestFixtures.requestAt(InterviewStatus.REQUESTED);
        request.setChild(savedChild);
        request.setHome(home);
        request.setRequestedBy(userRepository.findByUsername(plainCoordinator).orElseThrow());
        // Well past the 72-hour deadline, so the queue below has a live urgency to preserve.
        request.setReturnedAt(LocalDateTime.now().minusDays(4));
        requestId = interviewRequestRepository.save(request).getId();
    }

    /** (a) NO CONTROL, NO BUTTON. The empty required fieldset is the defect, not the copy beside it. */
    @Test
    void thereIsNoEmptyRequiredControlAndNoSubmitButton() throws Exception {
        String html = allocateFormAs(plainCoordinator);

        assertThat(html).doesNotContain("type=\"radio\"");
        assertThat(html).doesNotContain(">Allocate<");
    }

    /** (b) The missing thing is named twice, at the two precisions Oscar ruled and for his reasons. */
    @Test
    void itNamesTheMissingThingInTheCoordinatorsWordsAndInTheAdministratorsWords() throws Exception {
        String html = readableCopyOf(allocateFormAs(plainCoordinator));

        assertThat(html).contains("There are no visitors in your organisation");
        assertThat(html).contains("someone with the visitor role");
    }

    /** (c) The route out for a coordinator who cannot fix it themselves. */
    @Test
    void aCoordinatorWhoIsNotAnAdministratorIsToldWhoToAsk() throws Exception {
        String html = readableCopyOf(allocateFormAs(plainCoordinator));

        assertThat(html).contains("Ask your organisation's administrator");
        assertThat(html).doesNotContain("Add a user");
    }

    /**
     * (c) again, the other reader. The button REPLACES the middle sentence rather than joining it:
     * telling somebody to ask themselves reads as a system that has not noticed who it is talking to.
     */
    @Test
    void aCoordinatorWhoIsAlsoAnAdministratorGetsTheRouteOutInstead() throws Exception {
        String html = readableCopyOf(allocateFormAs(coordinatorWhoIsAlsoAnAdmin));

        assertThat(html).contains("Add a user");
        assertThat(html).doesNotContain("Ask your organisation's administrator");
    }

    /**
     * THE REGRESSION HALF. A template that hid the form unconditionally would satisfy every
     * assertion above, and would be a far worse defect than the one being fixed.
     */
    @Test
    void addAVisitorAndTheFormComesBack() throws Exception {
        saveUser("t266-visitor" + suffix, Set.of(Role.VISITOR));

        String html = readableCopyOf(allocateFormAs(plainCoordinator));

        assertThat(html).contains("type=\"radio\"");
        assertThat(html).contains(">Allocate<");
        assertThat(html).doesNotContain("There are no visitors in your organisation");
    }

    /**
     * THE SAFEGUARDING HALF, and Oscar asked for it to be CHECKED rather than assumed.
     *
     * <p>A coordinator arrives, cannot allocate, and leaves - and a child's return home interview is
     * now not happening while the 72-hour clock runs. <strong>Nothing here may mark, defer or quieten
     * the request: the failure to allocate is our problem to SHOW, not the request's status to
     * change.</strong> So after the dead end has been visited, the request is still on the queue,
     * still REQUESTED, and still carrying its urgency.
     */
    @Test
    void visitingTheDeadEndLeavesTheRequestOutstandingAndUrgentOnTheQueue() throws Exception {
        allocateFormAs(plainCoordinator);

        assertThat(interviewRequestRepository.findById(requestId).orElseThrow().getStatus())
                .as("the failure to allocate is ours to show, not the request's status to change")
                .isEqualTo(InterviewStatus.REQUESTED);

        String queue = mockMvc.perform(get("/coordinator/requests").with(asUser(plainCoordinator)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(queue).contains("/coordinator/requests/" + requestId + "/allocate");
        assertThat(queue).as("its urgency must survive the visit intact").contains("class=\"due");
    }

    private String allocateFormAs(String username) throws Exception {
        return mockMvc.perform(get("/coordinator/requests/{id}/allocate", requestId).with(asUser(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /**
     * The page with runs of whitespace collapsed, because <strong>these assertions are about the
     * sentence a person reads and a person does not see source line breaks.</strong>
     *
     * <p>Caught by this test failing on "someone with the visitor role" while the copy was perfectly
     * correct - the template wraps that sentence across two lines, so the substring was not
     * contiguous in the markup. Asserting on raw HTML would have made every one of these copy checks
     * a hostage to how the template happens to be indented, and the fix for that is not to reflow
     * the template to suit the test.
     */
    private String readableCopyOf(String html) {
        return html.replaceAll("\\s+", " ");
    }

    private User saveUser(String username, Set<Role> roles) {
        User user = new User();
        user.setUsername(username);
        user.setLastName(username);
        user.setRoles(new HashSet<>(roles));
        user.setOrganisation(supplier);
        user.setHomes(new HashSet<>());
        user.setEnabled(true);
        return userRepository.save(user);
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails details = appUserDetailsService.loadUserByUsername(username);
        return securityContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())));
    }
}
