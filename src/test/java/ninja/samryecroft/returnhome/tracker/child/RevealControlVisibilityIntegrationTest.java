package ninja.samryecroft.returnhome.tracker.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
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
 * T236, Kevin's ruling: the header's Reveal/Hide control must only appear on a page that actually
 * resolved at least one {@link ChildIdentity} - not on every page, which is what it did before.
 *
 * <p><b>The test that matters, per Kevin's own framing of the risk.</b> Asserting the control is
 * ABSENT where there is nothing to reveal is the natural test to write, because the change reads
 * as "stop showing it where it does nothing" - but that assertion passes on a permanently broken
 * button just as readily as on a correctly-wired one. <b>The load-bearing half is asserting the
 * control APPEARS where there is something to reveal</b>, in both its states (offering "Reveal"
 * when masked, offering "Hide" when revealed) - a missing affordance with nothing red is exactly
 * the shape this floor has spent the week catching elsewhere (a locked-out user told to retry, an
 * alert nobody saw fire). Both directions are asserted here; the appearing ones are not optional.
 *
 * <p>Uses the SAME route ({@code /children}) for every case, varying only the data - proof the
 * control is data-driven ({@link NameRevealService#hasMaskedNames()}, set as a side effect of
 * {@link NameRevealService#identitiesFor}) rather than keyed to which page it is.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RevealControlVisibilityIntegrationTest extends AbstractIntegrationTest {

    /** Present on both the "Reveal" form and the "Hide" link - see fragments/layout.html. */
    private static final String CONTROL_MARKER = "shell-reveal-toggle";

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

    private Home savedHome(String suffix) {
        Home home = new Home();
        home.setName("T236 House" + suffix);
        home.setOrganisation(seededCareProvider());
        return homeRepository.save(home);
    }

    private String savedHomeStaff(Home home, String suffix) {
        String username = "t236-staff" + suffix;
        User staff = new User();
        staff.setUsername(username);
        staff.setLastName("Staff");
        staff.setRoles(new HashSet<>(Set.of(Role.HOME_STAFF)));
        staff.setHomes(new HashSet<>(Set.of(home)));
        staff.setEnabled(true);
        userRepository.save(staff);
        return username;
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }

    @Test
    void theRevealControlAppearsOnAPageWithAMaskedChildIdentity() throws Exception {
        String suffix = "-" + System.nanoTime();
        Home home = savedHome(suffix);
        String username = savedHomeStaff(home, suffix);

        Child child = new Child();
        child.setFirstName("Sam");
        child.setLastName("T236" + suffix);
        child.setDateOfBirth(LocalDate.of(2014, 3, 3));
        child.setHome(home);
        childRepository.save(child);

        String html = mockMvc.perform(get("/children").with(asUser(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .as("a page that resolved a real ChildIdentity must offer the Reveal control - "
                        + "absence here is the actual defect T236 exists to fix, and it is silent "
                        + "unless something asserts presence, not just absence elsewhere")
                .contains(CONTROL_MARKER);
    }

    @Test
    void theHideVariantAppearsOnceRevealed() throws Exception {
        String suffix = "-" + System.nanoTime();
        Home home = savedHome(suffix);
        String username = savedHomeStaff(home, suffix);

        Child child = new Child();
        child.setFirstName("Robin");
        child.setLastName("T236" + suffix);
        child.setDateOfBirth(LocalDate.of(2015, 6, 1));
        child.setHome(home);
        childRepository.save(child);

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/account/reveal-names").with(asUser(username)).with(csrf()).session(session)
                        .param("returnTo", "/children"))
                .andExpect(status().is3xxRedirection());

        String html = mockMvc.perform(get("/children").with(asUser(username)).session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .as("revealed still needs the control - it is how the reader re-masks the screen, "
                        + "and its own presence must not depend on which of the two states is live")
                .contains(CONTROL_MARKER);
    }

    @Test
    void theControlIsAbsentOnTheSameRouteWhenThereAreNoChildrenToShow() throws Exception {
        String suffix = "-" + System.nanoTime();
        Home home = savedHome(suffix);
        String username = savedHomeStaff(home, suffix);

        String html = mockMvc.perform(get("/children").with(asUser(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html)
                .as("the SAME route with zero children resolved must not offer a control for "
                        + "something that isn't there - proof this is driven by what the page "
                        + "actually rendered, not by which URL it is")
                .doesNotContain(CONTROL_MARKER);
    }
}
