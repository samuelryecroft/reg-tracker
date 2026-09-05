package ninja.samryecroft.returnhome.tracker.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
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
 * T119 spec §7b (6a, children list): a masked list of people, not an aggregate, so R-Q12 rules the
 * table out - one card rendering, not two. Covers the structural change itself (no {@code <table>}
 * left at all) and the R-Q13 empty-state copy, which changed shape (a CTA link, not the old bare
 * "No children yet.").
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChildListLayoutIntegrationTest extends AbstractIntegrationTest {

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

    private RequestPostProcessor asUser(String username) {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }

    private Home savedHome(String suffix) {
        Home home = new Home();
        home.setName("T119(6a) House" + suffix);
        home.setOrganisation(seededCareProvider());
        return homeRepository.save(home);
    }

    private User savedHomeStaff(String username, Home home) {
        User staff = new User();
        staff.setUsername(username);
        staff.setLastName("Staff");
        staff.setRoles(new HashSet<>(Set.of(Role.HOME_STAFF)));
        staff.setHomes(new HashSet<>(Set.of(home)));
        staff.setEnabled(true);
        return userRepository.save(staff);
    }

    @Test
    void aNonEmptyListRendersNoTableAtAllOnlyTheCardStack() throws Exception {
        String suffix = "-" + System.nanoTime();
        Home home = savedHome(suffix);
        String username = "t6a-staff" + suffix;
        savedHomeStaff(username, home);

        Child child = new Child();
        child.setFirstName("Sam");
        child.setLastName("T6a" + suffix);
        child.setDateOfBirth(LocalDate.of(2014, 3, 3));
        child.setLocalCaseReference("CH-T6A" + suffix);
        child.setHome(home);
        childRepository.save(child);

        String html = mockMvc.perform(get("/children").with(asUser(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // R-Q12 (spec §7b): children are people, not an aggregate - the table this page used to
        // render alongside the card stack is gone, not merely hidden at this viewport.
        assertThat(html).doesNotContain("<table");
        assertThat(html).contains(child.getLocalCaseReference());
    }

    @Test
    void anEmptyListShowsTheFinalRQ13CopyAndAnAddChildLink() throws Exception {
        String suffix = "-" + System.nanoTime();
        Home home = savedHome(suffix);
        String username = "t6a-empty-staff" + suffix;
        savedHomeStaff(username, home);

        String html = mockMvc.perform(get("/children").with(asUser(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // R-Q13 (spec §5d), final copy - do not reword.
        assertThat(html).contains("No children added yet. Add a child before you can raise an "
                + "interview request.");
        assertThat(html).contains("href=\"/children/new\"");
    }
}
