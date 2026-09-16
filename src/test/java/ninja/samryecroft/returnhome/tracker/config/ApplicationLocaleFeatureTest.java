package ninja.samryecroft.returnhome.tracker.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * End-to-end proof that the application's FixedLocaleResolver (T360) causes the rendered HTML to
 * display dates in en-GB format (e.g., "15 Sept 2026" not "15 Sep 2026").
 *
 * <p>This test is the FEATURE proof: it verifies that actual rendered pages contain the en-GB
 * month abbreviations. If the LocaleResolver bean is removed or changed, these assertions will
 * fail, proving the feature is broken.
 *
 * <p>This supplements LocaleConfigGuardTest which verifies the bean configuration itself. Together
 * they ensure: (1) the bean is configured correctly (guard), and (2) it actually affects the
 * rendered output (feature test).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApplicationLocaleFeatureTest extends AbstractIntegrationTest {

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
        home.setName("T360(feature) House" + suffix);
        home.setOrganisation(careProvider);
        home = homeRepository.save(home);

        staffUsername = "t360-staff" + suffix;
        User staff = new User();
        staff.setUsername(staffUsername);
        // T360 correction (Creed): omitted in the original, and the row cannot be inserted without
        // it - `users_email_or_break_glass` (T344, email-as-login) requires an address on every
        // account that is not the break-glass one. The fixture this was adapted from
        // (ChildDetailIntegrationTest) sets it; the copy dropped the line.
        staff.setEmail(staffUsername + "@example.test");
        staff.setLastName("Staff");
        staff.setRoles(new HashSet<>(Set.of(Role.HOME_STAFF)));
        staff.setHomes(new HashSet<>(Set.of(home)));
        staff.setEnabled(true);
        userRepository.save(staff);

        session = new MockHttpSession();
    }

    private RequestPostProcessor asStaff() {
        // T360 correction (Creed): must go through TestLogins. T344 made sign-in resolve accounts by
        // EMAIL, and the suite's convention is <username>@example.test - so a bare username here
        // resolves to nothing ("No account for: t360-staff-..."). The fixture this was adapted from
        // uses the helper; the copy called loadUserByUsername directly.
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(
                ninja.samryecroft.returnhome.tracker.TestLogins.loginIdentifier(staffUsername));
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }

    @Test
    void renderedDateShowsSeptemberInEnGBFormat() throws Exception {
        Child child = new Child();
        child.setFirstName("Jordan");
        child.setLastName("T360" + suffix);
        child.setLocalCaseReference("CH-T360-" + suffix);
        // T360 correction (Creed): also omitted. date_of_birth_enc is NOT NULL, so the child row
        // cannot be inserted without it either.
        child.setDateOfBirth(java.time.LocalDate.of(2013, 7, 22));
        child.setHome(home);
        child = childRepository.save(child);

        // Use a date in September to trigger the locale-sensitive rendering
        LocalDateTime scheduledAt = LocalDateTime.of(2026, 9, 15, 14, 30);
        InterviewRequest request = InterviewRequestTestFixtures.requestAt(InterviewStatus.REPORT_APPROVED);
        request.setChild(child);
        request.setHome(home);
        request.setRequestedBy(userRepository.findByUsername(staffUsername).orElseThrow());
        request.setReturnedAt(LocalDateTime.now().minusHours(200));
        request.setScheduledAt(scheduledAt);
        interviewRequestRepository.save(request);

        String html = mockMvc.perform(get("/children/{id}", child.getId())
                .with(asStaff())
                .session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // PROOF: the rendered page contains the en-GB format "15 Sept 2026" not "15 Sep 2026"
        // (Locale.ENGLISH renders "Sep", Locale.UK renders "Sept")
        assertThat(html)
                .as("Rendered page must contain September date in en-GB format (Sept not Sep) - "
                        + "proves FixedLocaleResolver(Locale.UK) is active and affecting template output")
                .contains("15 Sept 2026");
        assertThat(html)
                .as("Page must NOT contain the Locale.ENGLISH format of the same date")
                .doesNotContain("15 Sep 2026");
    }
}
