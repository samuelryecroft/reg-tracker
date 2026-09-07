package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * A live 500 on the user edit page, found while building T281 and fixed with it.
 *
 * <p><strong>The defect:</strong> {@code getAuthorized} loaded the target with a bare
 * {@code findById} and then read {@code user.getHomes()}. {@code homes} is LAZY and
 * {@code spring.jpa.open-in-view=false}, so on a detached entity that is a
 * {@code LazyInitializationException} - an error page, not a decision. <strong>A care-provider org
 * admin could not open the edit page for ANY user in their own organisation.</strong>
 *
 * <p><strong>Why nothing caught it, which is the part worth keeping:</strong> the check
 * short-circuits for a platform admin and returns before it ever touches the collection - and every
 * existing test of this page signs in as a platform admin. <strong>The one principal the page was
 * broken for was the one principal no test used.</strong> Coverage of the ROUTE was complete;
 * coverage of the BRANCH was not, and a route-level tally cannot tell the difference.
 *
 * <p>Confirmed against unmodified {@code origin/main} before being fixed here, so it is a
 * pre-existing defect rather than one introduced by T281.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CareProviderAdminCanOpenTheUserEditPageTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private OrganisationRepository organisationRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private String suffix;
    private Home home;
    private String orgAdminUsername;

    @BeforeEach
    void seedACareProviderAndItsAdmin() {
        suffix = "-" + System.nanoTime();
        Organisation org = new Organisation();
        org.setName("T281 Edit Org" + suffix);
        org.setType(OrgType.CARE_PROVIDER);
        org = organisationRepository.save(org);

        home = new Home();
        home.setName("T281 Edit House" + suffix);
        home.setOrganisation(org);
        home = homeRepository.save(home);

        orgAdminUsername = "t281-edit-orgadmin" + suffix;
        User admin = new User();
        admin.setUsername(orgAdminUsername);
        admin.setLastName("Org Admin");
        admin.setRoles(new HashSet<>(Set.of(Role.ORG_ADMIN)));
        admin.setOrganisation(org);
        admin.setEnabled(true);
        userRepository.saveAndFlush(admin);
    }

    /** The 500 itself. Asserting the STATUS, because the exception is what a user saw as a blank error page. */
    @Test
    void theEditPageForOneOfTheirHomeStaffRendersInsteadOfErroring() throws Exception {
        Long staffId = saveUserWithHome("t281-edit-staff" + suffix, Role.HOME_STAFF).getId();

        MvcResult result = mockMvc.perform(get("/admin/users/{id}/edit", staffId).with(asOrgAdmin()))
                .andExpect(status().isOk())
                .andReturn();

        // Named explicitly: a status assertion alone would not say WHICH failure this guards, and a
        // future lazy collection on this path would fail here for a reason nobody could read off it.
        assertThat(result.getResolvedException())
                .as("no LazyInitializationException on the authorisation path")
                .isNull();
    }

    /** And for a viewer, which T281 made visible in the first place - the two fixes meet on this page. */
    @Test
    void theEditPageForOneOfTheirViewersRendersToo() throws Exception {
        Long viewerId = saveUserWithHome("t281-edit-viewer" + suffix, Role.VIEWER).getId();

        mockMvc.perform(get("/admin/users/{id}/edit", viewerId).with(asOrgAdmin()))
                .andExpect(status().isOk());
    }

    private User saveUserWithHome(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setLastName("Target");
        user.setRoles(new HashSet<>(Set.of(role)));
        user.setHomes(new HashSet<>(Set.of(home)));
        if (role != Role.HOME_STAFF) {
            user.setOrganisation(home.getOrganisation());
        }
        user.setEnabled(true);
        return userRepository.saveAndFlush(user);
    }

    private RequestPostProcessor asOrgAdmin() {
        UserDetails details = appUserDetailsService.loadUserByUsername(orgAdminUsername);
        return securityContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())));
    }
}
