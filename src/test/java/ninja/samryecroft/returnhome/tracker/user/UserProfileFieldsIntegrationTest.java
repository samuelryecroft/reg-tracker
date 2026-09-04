package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
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
 * T127: first name, last name, email and contact phone are captured, persisted and shown.
 *
 * <p>These go through the HTTP form rather than the service, because the templates are the half
 * most likely to break silently: a {@code th:field} naming a property that no longer exists throws
 * at render time, not at compile time, and T116 shipped exactly that bug through a gate that never
 * rendered the page. Binding the real form is what catches it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class UserProfileFieldsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private String suffix;
    private Organisation supplier;

    @BeforeEach
    void seedAdmin() {
        suffix = "-" + System.nanoTime();
        supplier = seededSupplier();
        User admin = new User();
        admin.setUsername("profile-admin" + suffix);
        admin.setFirstName("Profile");
        admin.setLastName("Admin");
        admin.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        admin.setEnabled(true);
        userRepository.save(admin);
    }

    @Test
    void creatingAUserCapturesAllFourProfileFields() throws Exception {
        mockMvc.perform(post("/admin/users").with(admin()).with(csrf())
                        .param("username", "new-staffer" + suffix)
                        .param("password", "a-long-enough-password")
                        .param("firstName", "Ada")
                        .param("lastName", "Lovelace")
                        .param("email", "ada.lovelace@example.test")
                        .param("contactPhone", "07700 900123")
                        .param("roles", Role.COORDINATOR.name())
                        .param("organisationId", supplier.getId().toString()))
                .andExpect(status().is3xxRedirection());

        User saved = userRepository.findByUsername("new-staffer" + suffix).orElseThrow();
        assertThat(saved.getFirstName()).isEqualTo("Ada");
        assertThat(saved.getLastName()).isEqualTo("Lovelace");
        assertThat(saved.getEmail()).isEqualTo("ada.lovelace@example.test");
        assertThat(saved.getContactPhone()).isEqualTo("07700 900123");
        // Derived, not stored - the one place the two halves are joined for display.
        assertThat(saved.getFullName()).isEqualTo("Ada Lovelace");
    }

    @Test
    void aMalformedEmailIsRejectedAndNothingIsPersisted() throws Exception {
        mockMvc.perform(post("/admin/users").with(admin()).with(csrf())
                        .param("username", "bad-email" + suffix)
                        .param("password", "a-long-enough-password")
                        .param("firstName", "Grace")
                        .param("lastName", "Hopper")
                        .param("email", "not-an-address")
                        .param("roles", Role.COORDINATOR.name())
                        .param("organisationId", supplier.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "email"));

        assertThat(userRepository.findByUsername("bad-email" + suffix)).isEmpty();
    }

    @Test
    void aMissingLastNameIsRejected() throws Exception {
        mockMvc.perform(post("/admin/users").with(admin()).with(csrf())
                        .param("username", "no-surname" + suffix)
                        .param("password", "a-long-enough-password")
                        .param("firstName", "Grace")
                        .param("lastName", "  ")
                        .param("email", "grace@example.test")
                        .param("roles", Role.COORDINATOR.name())
                        .param("organisationId", supplier.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("form", "lastName"));

        assertThat(userRepository.findByUsername("no-surname" + suffix)).isEmpty();
    }

    @Test
    void theContactPhoneIsOptionalAndAnUntouchedFieldIsStoredAsNullNotEmpty() throws Exception {
        // An untouched HTML input submits "", which would otherwise persist as an empty string that
        // reads as "we hold a number for this person" everywhere it is displayed.
        mockMvc.perform(post("/admin/users").with(admin()).with(csrf())
                        .param("username", "no-phone" + suffix)
                        .param("password", "a-long-enough-password")
                        .param("firstName", "Alan")
                        .param("lastName", "Turing")
                        .param("email", "alan.turing@example.test")
                        .param("contactPhone", "")
                        .param("roles", Role.COORDINATOR.name())
                        .param("organisationId", supplier.getId().toString()))
                .andExpect(status().is3xxRedirection());

        assertThat(userRepository.findByUsername("no-phone" + suffix).orElseThrow().getContactPhone()).isNull();
    }

    @Test
    void theEditFormIsPrefilledWithTheStoredProfileAndSavesChangesToIt() throws Exception {
        User existing = new User();
        existing.setUsername("editable" + suffix);
        existing.setFirstName("Edith");
        existing.setLastName("Clarke");
        existing.setEmail("edith.clarke@example.test");
        existing.setContactPhone("01234 567890");
        existing.setRoles(new HashSet<>(Set.of(Role.COORDINATOR)));
        existing.setOrganisation(supplier);
        existing.setEnabled(true);
        existing = userRepository.save(existing);

        String html = mockMvc.perform(get("/admin/users/" + existing.getId() + "/edit").with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("edith.clarke@example.test").contains("01234 567890").contains("Clarke");

        mockMvc.perform(post("/admin/users/" + existing.getId() + "/edit").with(admin()).with(csrf())
                        .param("firstName", "Edith")
                        .param("lastName", "Clarke-Smith")
                        .param("email", "edith.clarke-smith@example.test")
                        .param("contactPhone", "01234 000111")
                        .param("enabled", "true")
                        .param("roles", Role.COORDINATOR.name())
                        .param("organisationId", supplier.getId().toString()))
                .andExpect(status().is3xxRedirection());

        User updated = userRepository.findById(existing.getId()).orElseThrow();
        assertThat(updated.getLastName()).isEqualTo("Clarke-Smith");
        assertThat(updated.getEmail()).isEqualTo("edith.clarke-smith@example.test");
        assertThat(updated.getContactPhone()).isEqualTo("01234 000111");
    }

    @Test
    void theUserListShowsTheDerivedNameAndTheNewContactDetails() throws Exception {
        User listed = new User();
        listed.setUsername("listed" + suffix);
        listed.setFirstName("Mary");
        listed.setLastName("Seacole");
        listed.setEmail("mary.seacole@example.test");
        listed.setRoles(new HashSet<>(Set.of(Role.COORDINATOR)));
        listed.setOrganisation(supplier);
        listed.setEnabled(true);
        userRepository.save(listed);

        String html = mockMvc.perform(get("/admin/users").with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("Mary Seacole").contains("mary.seacole@example.test");
        // No phone was supplied, and the list says so rather than showing a blank cell.
        assertThat(html).contains("Not recorded");
    }

    private RequestPostProcessor admin() {
        UserDetails details = appUserDetailsService.loadUserByUsername("profile-admin" + suffix);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        return securityContext(context);
    }
}
