package ninja.samryecroft.returnhome.tracker.audit;

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
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T139: the audit feed's supplier scope is decided, not inherited from a coincidence.
 *
 * <p>This is the third appearance of one defect (T117, T130, now the feed) and the worst surface for
 * it: the audit feed is the broadest read in the application, and what it discloses is who looked at
 * which children's records.
 *
 * <p><b>Why the fixture is shaped the way it is.</b> An ordinary "coordinator in a care provider
 * sees an empty feed" test would have passed against the unfixed code, because the old query
 * returned nothing - not because anything denied it, but because no care provider happens to be
 * recorded as another care provider's supplier. {@code supplier_organisation_id} is a bare
 * {@code REFERENCES organisations (id)} with no type constraint (V5), so that state is permitted and
 * one wrong admin edit away. Seeding it is what turns an accidental close into a real assertion: the
 * old code hands this account another organisation's homes and case activity, and the new code
 * denies by decision.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SupplierScopeAuditFeedIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private OrganisationRepository organisationRepository;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private ChildRepository childRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private String suffix;
    private String clientHomeName;
    private String clientChildSurname;

    @BeforeEach
    void seedTheCrossProviderState() {
        suffix = "-" + System.nanoTime();
        Organisation supplier = saveOrg("T139 Supplier" + suffix, OrgType.SUPPLIER, null);
        Organisation providerA = saveOrg("T139 Provider A" + suffix, OrgType.CARE_PROVIDER, supplier);
        // The crafted row: a CARE PROVIDER recorded as another care provider's supplier. The schema
        // allows it, and it is the only thing the old code relied on not existing.
        Organisation providerB = saveOrg("T139 Provider B" + suffix, OrgType.CARE_PROVIDER, providerA);

        clientHomeName = "T139 Provider B House" + suffix;
        Home providerBHome = saveHome(clientHomeName, providerB);
        Home providerAHome = saveHome("T139 Provider A House" + suffix, providerA);

        clientChildSurname = "BravoSurname" + suffix;
        Long childId = saveChild("Casey", clientChildSurname, providerBHome);
        saveChild("Alex", "AlphaSurname" + suffix, providerAHome);

        // Coordinator sitting in a CARE PROVIDER: supplier-side by role, care-provider-side by
        // organisation. /audit/** admits COORDINATOR, so this account reaches the feed.
        userRepository.save(user("t139-coordinator" + suffix, Role.COORDINATOR, providerA, null));
        // The legitimate comparison: same role, in the supplier organisation.
        userRepository.save(user("t139-supplier-coordinator" + suffix, Role.COORDINATOR, supplier, null));
        // Home staff to raise a request, so the feed has real case activity to leak.
        userRepository.save(user("t139-staff" + suffix, Role.HOME_STAFF, null, providerBHome));

        raiseRequest("t139-staff" + suffix, childId);
    }

    @Test
    void aCoordinatorInACareProviderSeesNoneOfAnotherProvidersHomesOrActivity() throws Exception {
        // Against the unfixed code this account's own organisation id went to
        // findByHomeOrganisationSupplierOrganisationId, Provider B names Provider A as its supplier,
        // and both of these appear. Both fall-throughs - homesInScope and requestsInScope - are
        // covered: the home name comes from the first, the child's case activity from the second.
        String html = feedAs("t139-coordinator" + suffix);

        assertThat(html).doesNotContain(clientHomeName);
        assertThat(html).doesNotContain(clientChildSurname);
    }

    @Test
    void aCoordinatorInTheSupplierOrganisationStillSeesTheirClients() throws Exception {
        // The regression guard. Denying by decision must not cost the supplier side the access it
        // exists to have - Provider A is genuinely this coordinator's client.
        String html = feedAs("t139-supplier-coordinator" + suffix);

        assertThat(html).contains("T139 Provider A House" + suffix);
    }

    private String feedAs(String username) throws Exception {
        return mockMvc.perform(get("/audit").with(asUser(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void raiseRequest(String staffUsername, Long childId) {
        try {
            mockMvc.perform(post("/requests").with(asUser(staffUsername)).with(csrf())
                            .param("childId", childId.toString())
                            .param("returnedAt", "2026-07-16T20:30"))
                    .andExpect(status().is3xxRedirection());
        } catch (Exception e) {
            throw new IllegalStateException("Could not seed the interview request", e);
        }
    }

    private Organisation saveOrg(String name, OrgType type, Organisation supplier) {
        Organisation org = new Organisation();
        org.setName(name);
        org.setType(type);
        org.setSupplierOrganisation(supplier);
        return organisationRepository.save(org);
    }

    private Home saveHome(String name, Organisation org) {
        Home home = new Home();
        home.setName(name);
        home.setOrganisation(org);
        return homeRepository.save(home);
    }

    private Long saveChild(String firstName, String lastName, Home home) {
        Child child = new Child();
        child.setFirstName(firstName);
        child.setLastName(lastName);
        child.setDateOfBirth(LocalDate.of(2012, 2, 2));
        child.setHome(home);
        return childRepository.save(child).getId();
    }

    private User user(String username, Role role, Organisation organisation, Home home) {
        User user = new User();
        user.setUsername(username);
        user.setLastName(username);
        user.setRoles(new HashSet<>(Set.of(role)));
        user.setOrganisation(organisation);
        user.setHomes(home == null ? new HashSet<>() : new HashSet<>(Set.of(home)));
        user.setEnabled(true);
        user.setCanExport(true);
        return user;
    }

    private RequestPostProcessor asUser(String username) {
        UserDetails details = appUserDetailsService.loadUserByUsername(username);
        SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
        return securityContext(context);
    }
}
