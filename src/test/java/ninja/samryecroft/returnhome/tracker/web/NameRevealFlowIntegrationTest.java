package ninja.samryecroft.returnhome.tracker.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.audit.AuditEvent;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventRepository;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventType;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T138 1c: the reveal control end to end - masked by default, one POST arms exactly the next page,
 * the audit event records who revealed what, and the flag is gone again on the page after that.
 * Uses a real {@link MockHttpSession} shared across requests, not a fresh one per call, because the
 * behaviour under test is entirely about what one session's flag does across two requests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NameRevealFlowIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private ChildRepository childRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private AuditEventRepository auditEventRepository;

    private String suffix;
    private String username;
    private String caseReference;
    private MockHttpSession session;

    @BeforeEach
    void seedData() {
        suffix = "-" + System.nanoTime();
        Organisation careProviderOrg = seededCareProvider();
        Home home = new Home();
        home.setName("Reveal Test House" + suffix);
        home.setOrganisation(careProviderOrg);
        home = homeRepository.save(home);

        caseReference = "CH-REVEAL" + suffix;
        Child child = new Child();
        child.setFirstName("Riley");
        child.setLastName("Reveal");
        child.setDateOfBirth(LocalDate.of(2012, 4, 4));
        child.setLocalCaseReference(caseReference);
        child.setHome(home);
        childRepository.save(child);

        username = "reveal-test" + suffix;
        User staff = new User();
        staff.setUsername(username);
        staff.setPassword(passwordEncoder.encode(PASSWORD));
        staff.setLastName("Reveal Tester");
        staff.setRoles(Set.of(Role.HOME_STAFF));
        staff.setHomes(new HashSet<>(Set.of(home)));
        staff.setEnabled(true);
        userRepository.save(staff);

        session = new MockHttpSession();
    }

    private RequestPostProcessor asUser() {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }

    @Test
    void aFreshPageLoadIsMaskedByDefault() throws Exception {
        String html = mockMvc.perform(get("/children").with(asUser()).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("Riley Reveal");
        assertThat(html).contains("R.R. · " + caseReference);
    }

    @Test
    void revealingArmsExactlyTheNextPageAndNoFurther() throws Exception {
        mockMvc.perform(post("/account/reveal-names").with(asUser()).with(csrf()).session(session)
                        .param("returnTo", "/children"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/children"));

        String revealed = mockMvc.perform(get("/children").with(asUser()).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(revealed).contains("Riley Reveal");

        // Same session, a second page load - the flag was consumed by the page above.
        String maskedAgain = mockMvc.perform(get("/children").with(asUser()).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(maskedAgain).doesNotContain("Riley Reveal");
        assertThat(maskedAgain).contains("R.R. · " + caseReference);
    }

    @Test
    void revealingRecordsOneAuditEventNamingThePage() throws Exception {
        mockMvc.perform(post("/account/reveal-names").with(asUser()).with(csrf()).session(session)
                        .param("returnTo", "/children"))
                .andExpect(status().is3xxRedirection());

        List<AuditEvent> events = auditEventRepository.findByEventTypeOrderByOccurredAtDesc(AuditEventType.NAMES_REVEALED);
        AuditEvent event = events.stream()
                .filter(e -> username.equals(e.getActorUsernameAtTime()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No NAMES_REVEALED event for " + username));

        assertThat(event.getMetadata()).contains("path=/children");
    }

    @Test
    void anUnsafeReturnToFallsBackToTheRootPathInstead() throws Exception {
        mockMvc.perform(post("/account/reveal-names").with(asUser()).with(csrf()).session(session)
                        .param("returnTo", "//evil.example"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/"));
    }
}
