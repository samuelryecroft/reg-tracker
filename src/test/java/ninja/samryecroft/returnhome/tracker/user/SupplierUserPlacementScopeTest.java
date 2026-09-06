package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.user.dto.CreateUserForm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * T249: a supplier may place a user in a care provider it serves, and <b>only</b> in one it serves.
 *
 * <p><b>The check is a precondition of the feature, not a hardening of it</b>, and that is why these
 * live together. Before this change {@code resolveOrganisation} <em>ignored</em> the submitted
 * organisation id for a non-ADMIN and returned the principal's own - so a supplier could not escalate
 * because <b>you cannot get a check wrong on an input you never read</b>. Letting a supplier choose
 * one of their care providers requires the code to start reading it, and the escalation path becomes
 * real at that moment.
 *
 * <p><b>Asserted at the SERVICE, never through the form.</b> A test that posted to the controller and
 * watched the option list would pass with the check deleted, because the picker would still offer the
 * right options - and a filtered dropdown is not a constraint. Remove the check from
 * {@code resolveOrganisation} and the refusal below must go red while the picker stays exactly as it
 * is.
 */
@SpringBootTest
class SupplierUserPlacementScopeTest extends AbstractIntegrationTest {

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganisationRepository organisationRepository;
    @Autowired private AppUserDetailsService appUserDetailsService;
    @Autowired private PasswordEncoder passwordEncoder;

    private String suffix;
    private Organisation servedProvider;
    private Organisation strangerProvider;

    @BeforeEach
    void seed() {
        suffix = "-" + System.nanoTime();

        servedProvider = organisationRepository.save(
                careProviderUnder(seededSupplier(), "Served Provider" + suffix));

        Organisation otherSupplier = new Organisation();
        otherSupplier.setName("Rival Supplier" + suffix);
        otherSupplier.setType(OrgType.SUPPLIER);
        otherSupplier = organisationRepository.save(otherSupplier);
        strangerProvider = organisationRepository.save(
                careProviderUnder(otherSupplier, "Stranger Provider" + suffix));

        User supplierAdmin = new User();
        supplierAdmin.setUsername("t249-supplier-admin" + suffix);
        supplierAdmin.setPassword(passwordEncoder.encode("password123"));
        supplierAdmin.setFirstName("Supplier");
        supplierAdmin.setLastName("Admin");
        supplierAdmin.setEmail("t249" + suffix + "@example.test");
        supplierAdmin.setRoles(new HashSet<>(Set.of(Role.ORG_ADMIN)));
        supplierAdmin.setOrganisation(seededSupplier());
        userRepository.save(supplierAdmin);
    }

    @Test
    void aSupplierMayCreateAUserForACareProviderItServes() {
        User created = userService.create(formFor("served" + suffix, servedProvider.getId()), principal());

        assertThat(created.getOrganisation().getId())
                .as("THE FEATURE: a supplier org-admin could not do this at all before - they were "
                        + "pinned to their own organisation, which was a functional gap rather than "
                        + "a safeguard")
                .isEqualTo(servedProvider.getId());
    }

    @Test
    void aSupplierMayNotCreateAUserForACareProviderItDoesNotServe() {
        assertThatThrownBy(() ->
                userService.create(formFor("stranger" + suffix, strangerProvider.getId()), principal()))
                .as("THE ARMING TARGET. This is refused in the service, on the submitted value, and "
                        + "it must go red if the check is removed while the picker stays - the "
                        + "picker being correct is not evidence, because it shapes the form and not "
                        + "the POST")
                .isInstanceOf(AccessDeniedException.class);

        assertThat(userRepository.findByUsername("stranger" + suffix))
                .as("and nothing was written")
                .isEmpty();
    }

    @Test
    void aSupplierMayStillCreateAUserInItsOwnOrganisation() {
        User created = userService.create(
                formFor("own" + suffix, seededSupplier().getId()), principal());

        assertThat(created.getOrganisation().getId())
                .as("the behaviour that existed before this change must survive it")
                .isEqualTo(seededSupplier().getId());
    }

    @Test
    void anAbsentOrganisationStillMeansTheirOwn() {
        User created = userService.create(formFor("absent" + suffix, null), principal());

        assertThat(created.getOrganisation().getId())
                .as("a care-provider org-admin has no picker and posts nothing, so null must keep "
                        + "meaning 'my own organisation' rather than becoming an error")
                .isEqualTo(seededSupplier().getId());
    }

    private Organisation careProviderUnder(Organisation supplier, String name) {
        Organisation provider = new Organisation();
        provider.setName(name);
        provider.setType(OrgType.CARE_PROVIDER);
        provider.setSupplierOrganisation(supplier);
        return provider;
    }

    private CreateUserForm formFor(String username, Long organisationId) {
        CreateUserForm form = new CreateUserForm();
        form.setUsername(username);
        form.setPassword("password123");
        form.setFirstName("New");
        form.setLastName("User");
        form.setEmail(username + "@example.test");
        form.setRoles(new HashSet<>(Set.of(Role.COORDINATOR)));
        form.setOrganisationId(organisationId);
        return form;
    }

    private AppUserPrincipal principal() {
        UserDetails details =
                appUserDetailsService.loadUserByUsername("t249-supplier-admin" + suffix);
        return (AppUserPrincipal) details;
    }
}
