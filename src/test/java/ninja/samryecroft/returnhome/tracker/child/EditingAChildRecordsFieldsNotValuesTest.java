package ninja.samryecroft.returnhome.tracker.child;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.audit.AuditEvent;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventRepository;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventType;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T170, the edit half: correcting a young person's details is ORDINARY AND NECESSARY - typos, legal
 * name changes, a case reference issued after the record was created.
 *
 * <p><strong>Audited as WHICH FIELD CHANGED, BY WHOM, WHEN - NEVER THE VALUES.</strong> That stays
 * inside the existing allow-list rather than carving an exception into it, and the reason is
 * concrete: a child's name and date of birth are encrypted at rest precisely so they are not lying
 * around in readable tables. Copying them into an append-only trail that exists to be READ during a
 * review would put them somewhere they can never be removed from - {@code audit_events} refuses
 * UPDATE and DELETE by database trigger.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EditingAChildRecordsFieldsNotValuesTest extends AbstractIntegrationTest {

    /** Distinctive enough that a substring assertion cannot pass by luck against boilerplate. */
    private static final String NEW_SURNAME = "Featherstonehaugh";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService appUserDetailsService;
    @Autowired
    private ChildLifecycleService childLifecycleService;
    @Autowired
    private ChildRepository childRepository;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private OrganisationRepository organisationRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AuditEventRepository auditEventRepository;

    private String suffix;
    private Child child;
    private AppUserPrincipal manager;
    private String supplierUsername;

    @BeforeEach
    void seedAChild() {
        suffix = "-" + System.nanoTime();
        Organisation org = new Organisation();
        org.setName("T170e Org" + suffix);
        org.setType(OrgType.CARE_PROVIDER);
        org = organisationRepository.save(org);

        Organisation supplier = new Organisation();
        supplier.setName("T170e Supplier" + suffix);
        supplier.setType(OrgType.SUPPLIER);
        supplier = organisationRepository.save(supplier);

        Home home = new Home();
        home.setName("T170e House" + suffix);
        home.setOrganisation(org);
        home = homeRepository.save(home);

        manager = principal("t170e-manager" + suffix, Role.ORG_ADMIN, org);
        supplierUsername = "t170e-supplier" + suffix;
        principal(supplierUsername, Role.COORDINATOR, supplier);

        child = new Child();
        child.setFirstName("Sasha");
        child.setLastName("Original" + suffix);
        child.setDateOfBirth(LocalDate.of(2011, 6, 1));
        child.setLocalCaseReference("CH-T170E" + suffix);
        child.setHome(home);
        child = childRepository.save(child);
    }

    /** The edit itself works - without this, everything below passes on a feature that does nothing. */
    @Test
    void theCorrectionIsApplied() {
        childLifecycleService.update(child.getId(), "Sasha", NEW_SURNAME, child.getDateOfBirth(),
                child.getLocalCaseReference(), manager);

        assertThat(childRepository.findDetailedById(child.getId()).orElseThrow().getLastName())
                .isEqualTo(NEW_SURNAME);
    }

    /**
     * THE PROPERTY THAT MATTERS. The trail names the field and never the value - and the assertion
     * uses a distinctive surname so it cannot pass by luck against ordinary boilerplate.
     */
    @Test
    void theAuditNamesTheFieldAndNeverTheValue() {
        childLifecycleService.update(child.getId(), "Sasha", NEW_SURNAME, LocalDate.of(2011, 6, 2),
                child.getLocalCaseReference(), manager);

        AuditEvent event = auditEventRepository
                .findByTargetTypeAndTargetIdOrderByOccurredAtDesc("Child", child.getId()).stream()
                .filter(row -> row.getEventType() == AuditEventType.CHILD_UPDATED)
                .findFirst().orElseThrow();

        assertThat(event.getMetadata()).contains("lastName").contains("dateOfBirth");
        assertThat(event.getMetadata())
                .as("a name copied into an append-only table can never be removed from it")
                .doesNotContain(NEW_SURNAME)
                .doesNotContain("2011-06-02");
    }

    /** Only what actually moved is named, or "which field changed" stops being an answer. */
    @Test
    void anUnchangedFieldIsNotNamed() {
        childLifecycleService.update(child.getId(), "Sasha", NEW_SURNAME, child.getDateOfBirth(),
                child.getLocalCaseReference(), manager);

        assertThat(latestUpdate().getMetadata()).contains("lastName").doesNotContain("dateOfBirth");
    }

    /**
     * A submission that changes nothing writes NO row. An event saying "these fields changed" when
     * none did is not a harmless extra line - it is a false statement in a record that cannot be
     * corrected.
     */
    @Test
    void aSubmissionThatChangesNothingIsNotAudited() {
        childLifecycleService.update(child.getId(), child.getFirstName(), child.getLastName(),
                child.getDateOfBirth(), child.getLocalCaseReference(), manager);

        assertThat(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("Child", child.getId()))
                .extracting(AuditEvent::getEventType)
                .doesNotContain(AuditEventType.CHILD_UPDATED);
    }

    /**
     * A SUPPLIER MAY NOT - asserted AT THE CONTROLLER, because that is where the rule lives.
     *
     * <p>Written this way after arming: calling the service directly as a supplier SUCCEEDS, because
     * {@code mineToManage} holds the WHO rule and the service does not. That is the T277 shape again -
     * a test through the outer layer cannot see whether the inner one is guarded - so rather than
     * assert a property this service does not have, the assertion goes where the property is. The
     * service is reachable only from that controller today; a second caller would need the same check
     * and there is nothing here that would tell them so, which is recorded rather than fixed because
     * widening the service's dependencies is a change Oscar's ruling does not ask for.
     */
    @Test
    void aSupplierMayNotReachTheChildManagementRoutes() throws Exception {
        UserDetails details = appUserDetailsService.loadUserByUsername(supplierUsername);
        mockMvc.perform(post("/children/{id}/archive", child.getId()).with(csrf())
                        .with(securityContext(new SecurityContextImpl(
                                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())))))
                .andExpect(status().isForbidden());

        assertThat(childRepository.findDetailedById(child.getId()).orElseThrow().isArchived())
                .as("and nothing changed by the attempt")
                .isFalse();
    }

    private AuditEvent latestUpdate() {
        return auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("Child", child.getId())
                .stream().filter(row -> row.getEventType() == AuditEventType.CHILD_UPDATED)
                .findFirst().orElseThrow();
    }

    private AppUserPrincipal principal(String username, Role role, Organisation organisation) {
        User user = new User();
        user.setUsername(username);
        user.setLastName(username);
        user.setRoles(new HashSet<>(Set.of(role)));
        user.setOrganisation(organisation);
        user.setEnabled(true);
        return new AppUserPrincipal(userRepository.saveAndFlush(user), false);
    }
}
