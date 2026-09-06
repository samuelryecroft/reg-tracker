package ninja.samryecroft.returnhome.tracker.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T237: the two halves of the invariant, tested at the two layers that now hold it.
 *
 * <p><b>The second test is the whole point of the card.</b> The rule was already enforced, correctly
 * and with a good message, in {@code HomeAdminController} - one of two write paths, in the layer that
 * cannot be the only one. Moving it onto the entity is only worth anything if it holds for a write
 * that never goes near that endpoint, so that is what is asserted: build a home the way
 * {@code DemoDataSeeder} does and hand it a supplier, with no controller, no form, no binding result
 * anywhere in the stack.
 *
 * <p>Arm it by reverting: take the guard out of {@code Home.setOrganisation} and that test must go
 * red. <b>If it stays green it is reaching the controller and measuring the guard that was already
 * there</b> - the specific way this change could look proven while being unproven.
 *
 * <p>The first test exists because the entity now throws underneath the controller. An admin who
 * picks the wrong organisation must still get a form message; a 500 would be a regression introduced
 * by hardening.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HomeOrganisationInvariantIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private OrganisationRepository organisationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private String suffix;

    @BeforeEach
    void seedAdmin() {
        suffix = "-" + System.nanoTime();
        User admin = new User();
        admin.setUsername("t237-admin" + suffix);
        admin.setPassword(passwordEncoder.encode("password123"));
        admin.setFirstName("Invariant");
        admin.setLastName("Admin");
        admin.setEmail("t237" + suffix + "@example.test");
        admin.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        userRepository.save(admin);
    }

    @Test
    void theAdminWhoPicksASupplierStillGetsAFormErrorAndNotA500() throws Exception {
        Organisation supplier = seededSupplier();

        mockMvc.perform(post("/admin/homes")
                        .with(asUser("t237-admin" + suffix)).with(csrf())
                        .param("name", "Wrongly Parented House" + suffix)
                        .param("organisationId", String.valueOf(supplier.getId())))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/home-form"))
                .andExpect(model().attributeHasFieldErrors("form", "organisationId"));

        assertThat(homeRepository.findAll())
                .as("the rejected home must not have been written")
                .noneMatch(home -> home.getName().endsWith(suffix));
    }

    /**
     * The seeder-shaped write: entity, repository, no endpoint. This is the door the controller
     * check never covered, and the one a future importer or fixture will resemble.
     */
    @Test
    void theGuardFiresOnAWriteThatNeverTouchesTheController() {
        Organisation supplier = seededSupplier();

        Home home = new Home();
        home.setName("Seeder Shaped House" + suffix);

        assertThatThrownBy(() -> home.setOrganisation(supplier))
                .isInstanceOf(IllegalArgumentException.class)
                .as("the message names the organisation, its type and the rule - whoever reads it is "
                        + "debugging a write they believed was legal")
                .hasMessageContaining(String.valueOf(supplier.getId()))
                .hasMessageContaining(supplier.getName())
                .hasMessageContaining("SUPPLIER")
                .hasMessageContaining("CARE_PROVIDER");

        assertThat(homeRepository.findAll())
                .as("nothing reached the database, and it failed at the assignment rather than as a "
                        + "constraint violation several frames later")
                .noneMatch(candidate -> candidate.getName().endsWith(suffix));
    }

    /** A care provider still works, so the guard is a constraint and not a wall. */
    @Test
    void aCareProviderHomeStillSaves() {
        Home home = new Home();
        home.setName("Correctly Parented House" + suffix);
        home.setOrganisation(seededCareProvider());

        assertThat(homeRepository.save(home).getId()).isNotNull();
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }
}
