package ninja.samryecroft.returnhome.tracker.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
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
 * T119 spec §7j (3a, D-3a-5): "and for every Care Provider org you serve" was a vague plural for a
 * countable fact. Covers all three shapes - zero (no consequence to state, so no panel at all),
 * one (singular), and more than one (plural) - plus the platform-default path, which never shows
 * the panel regardless of count (it describes a fallback, not an inheritance).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ThemeConsequenceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrganisationRepository organisationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private Organisation savedSupplier(String suffix) {
        Organisation supplier = new Organisation();
        supplier.setName("T3a Supplier" + suffix);
        supplier.setType(OrgType.SUPPLIER);
        return organisationRepository.save(supplier);
    }

    private void savedCareProviderFor(Organisation supplier, String name) {
        Organisation careProvider = new Organisation();
        careProvider.setName(name);
        careProvider.setType(OrgType.CARE_PROVIDER);
        careProvider.setSupplierOrganisation(supplier);
        organisationRepository.save(careProvider);
    }

    private String savedOrgAdmin(Organisation supplier, String suffix) {
        String username = "t3a-orgadmin" + suffix;
        User user = new User();
        user.setUsername(username);
        user.setLastName("Admin");
        user.setRoles(new HashSet<>(Set.of(Role.ORG_ADMIN)));
        user.setOrganisation(supplier);
        user.setHomes(new HashSet<>());
        user.setEnabled(true);
        userRepository.save(user);
        return username;
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails userDetails = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
        return securityContext(context);
    }

    private String getThemeForm(String username) throws Exception {
        return mockMvc.perform(get("/admin/theme").with(asUser(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void zeroCareProvidersShowsNoConsequencePanelAtAll() throws Exception {
        String suffix = "-" + System.nanoTime();
        Organisation supplier = savedSupplier(suffix);
        String username = savedOrgAdmin(supplier, suffix);

        String html = getThemeForm(username);

        assertThat(html).doesNotContain("class=\"consequence\"");
        assertThat(html).doesNotContain("other organisation");
    }

    @Test
    void oneCareProviderIsSingular() throws Exception {
        String suffix = "-" + System.nanoTime();
        Organisation supplier = savedSupplier(suffix);
        savedCareProviderFor(supplier, "T3a House A" + suffix);
        String username = savedOrgAdmin(supplier, suffix);

        String html = getThemeForm(username);

        assertThat(html).contains("Changing this colour changes what 1 other organisation see");
    }

    @Test
    void twoCareProvidersIsPlural() throws Exception {
        String suffix = "-" + System.nanoTime();
        Organisation supplier = savedSupplier(suffix);
        savedCareProviderFor(supplier, "T3a House A" + suffix);
        savedCareProviderFor(supplier, "T3a House B" + suffix);
        String username = savedOrgAdmin(supplier, suffix);

        String html = getThemeForm(username);

        assertThat(html).contains("Changing this colour changes what 2 other organisations see");
    }

    @Test
    void platformAdminNeverSeesTheConsequencePanelRegardlessOfCount() throws Exception {
        String suffix = "-" + System.nanoTime();
        String username = "t3a-platform-admin" + suffix;
        User admin = new User();
        admin.setUsername(username);
        admin.setLastName("Admin");
        admin.setRoles(new HashSet<>(Set.of(Role.ADMIN)));
        admin.setHomes(new HashSet<>());
        admin.setEnabled(true);
        userRepository.save(admin);

        String html = getThemeForm(username);

        // The platform default describes a fallback, not an inheritance - D-3a-5 keeps that split.
        assertThat(html).doesNotContain("class=\"consequence\"");
        assertThat(html).contains("platform default");
    }
}
