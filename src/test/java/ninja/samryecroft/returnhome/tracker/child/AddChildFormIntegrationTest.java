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
 * T119 spec §7g (5d, add a child). Covers D-5d-1 (the {@code homeId} field is gated on whether this
 * user needs a picker, not on being an admin - a single-home user never sees it), D-5d-2 (a birth
 * date cannot be in the future - {@code max} client-side, {@code @Past} server-side) and D-5d-4 (the
 * case reference's optional label carries a hint about what leaving it blank costs).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AddChildFormIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private String singleHomeUsername;

    @BeforeEach
    void seedASingleHomeStaffUser() {
        String suffix = "-" + System.nanoTime();
        Organisation careProvider = seededCareProvider();
        Home home = new Home();
        home.setName("T119(7g) House" + suffix);
        home.setOrganisation(careProvider);
        home = homeRepository.save(home);

        singleHomeUsername = "t5d-staff" + suffix;
        User staff = new User();
        staff.setUsername(singleHomeUsername);
        staff.setLastName("Staff");
        staff.setRoles(new HashSet<>(Set.of(Role.HOME_STAFF)));
        staff.setHomes(new HashSet<>(Set.of(home)));
        staff.setEnabled(true);
        userRepository.save(staff);
    }

    private RequestPostProcessor asStaff() {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(singleHomeUsername);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }

    @Test
    void aSingleHomeUserSeesNoHomeFieldAMaxOnTheBirthDateAndTheCaseReferenceHint() throws Exception {
        String html = mockMvc.perform(get("/children/new").with(asStaff()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // D-5d-1: the field is genuinely absent - this user's home is implied, not chosen.
        assertThat(html).doesNotContain("id=\"homeId\"");
        // D-5d-2: today, ISO-8601 - the same shape a datetime-local/date input requires.
        assertThat(html).contains("max=\"" + LocalDate.now() + "\"");
        // D-5d-4.
        assertThat(html).contains("Used to tell children apart when names are masked. Can be added later.");
    }

    @Test
    void aFutureBirthDateIsRejected() throws Exception {
        String html = mockMvc.perform(post("/children").with(asStaff()).with(csrf())
                        .param("firstName", "Future")
                        .param("lastName", "Child")
                        .param("dateOfBirth", LocalDate.now().plusDays(1).toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("#dateOfBirth");
    }
}
