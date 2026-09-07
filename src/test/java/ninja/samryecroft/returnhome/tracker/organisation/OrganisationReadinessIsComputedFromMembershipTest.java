package ninja.samryecroft.returnhome.tracker.organisation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.AbstractIntegrationTest;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The half of T267 that can fail silently: WHO gets counted.
 *
 * <p>The rule itself is a pure function with its own unit test. This is the gathering, and it is an
 * integration test on purpose - every arm below is a question about a JPQL query, and a mocked
 * repository would return whatever I told it to while a broken query returned nothing. The mock
 * would agree with me and the database would not.
 *
 * <p><b>Every arm here fails in the same direction, which is why they are worth the container:</b> a
 * gathering that misses people reports an organisation as NOT operational, and reads as a slightly
 * pessimistic notice rather than as a bug. Except one - {@link #anOrganisationWhoseOnlyVisitorIsDisabledIsNotOperational}
 * - which fails the other way and is the dangerous one: it says everything is fine on the day it
 * stopped being.
 */
@SpringBootTest
class OrganisationReadinessIsComputedFromMembershipTest extends AbstractIntegrationTest {

    @Autowired
    private OrganisationReadinessService readinessService;
    @Autowired
    private OrganisationRepository organisationRepository;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private UserRepository userRepository;

    private String suffix;
    private Organisation supplier;
    private Organisation careProvider;
    private Home home;

    @BeforeEach
    void seedASupplierAndAProviderWithNobodyInThem() {
        suffix = "-" + System.nanoTime();
        supplier = saveOrg("T267 Supplier" + suffix, OrgType.SUPPLIER, null);
        careProvider = saveOrg("T267 Provider" + suffix, OrgType.CARE_PROVIDER, supplier);
        home = new Home();
        home.setName("T267 House" + suffix);
        home.setOrganisation(careProvider);
        home = homeRepository.save(home);
    }

    /** The empty case, which is the state this whole card is pointed at. */
    @Test
    void anOrganisationWithNobodyInItIsNotOperational() {
        assertThat(readinessService.readinessFor(supplier).missingRoles())
                .containsExactly(Role.COORDINATOR, Role.VISITOR, Role.REVIEWER);
        assertThat(readinessService.readinessFor(careProvider).missingRoles())
                .containsExactly(Role.HOME_STAFF);
    }

    /** The organisation route: everyone org-scoped carries {@code organisation} directly. */
    @Test
    void aSupplierWithItsThreeRolesIsOperational() {
        orgScoped("coordinator", Role.COORDINATOR);
        orgScoped("visitor", Role.VISITOR);
        orgScoped("reviewer", Role.REVIEWER);

        assertThat(readinessService.readinessFor(supplier).isOperational()).isTrue();
    }

    /**
     * THE ARM THAT MATTERS MOST, and the one a plausible single-query implementation fails.
     *
     * <p>This home staff member has <b>no organisation at all</b> - {@code needsOrganisation}
     * excludes the role, so that is the real shape of the account and not an awkward fixture. They
     * belong to the care provider only through their HOME. A readiness query written over
     * {@code u.organisation} alone - the obvious one, and the one three other queries in
     * {@code UserRepository} use - sees nobody here, and every care provider on the platform is then
     * reported as unable to do its job while being perfectly staffed. T273 found the same shape in
     * visibility; this is it in a second place.
     */
    @Test
    void homeStaffCountForTheirProviderEvenThoughTheyHaveNoOrganisation() {
        User staff = new User();
        staff.setUsername("t267-staff" + suffix);
        staff.setLastName("Staff");
        staff.setRoles(new HashSet<>(Set.of(Role.HOME_STAFF)));
        staff.setOrganisation(null);
        staff.setHomes(new HashSet<>(Set.of(home)));
        staff.setEnabled(true);
        userRepository.saveAndFlush(staff);

        assertThat(staff.getOrganisation())
                .as("the fixture is only interesting if this account really has no organisation")
                .isNull();
        assertThat(readinessService.readinessFor(careProvider).isOperational()).isTrue();
    }

    /**
     * Oscar's second cause, verbatim: an organisation loses its last visitor because somebody
     * "leaves or IS DISABLED". A disabled account cannot allocate anything, so counting the row
     * would report an organisation as operational at the exact moment it stopped being - and this is
     * the one arm here that fails towards reassurance rather than towards noise.
     */
    @Test
    void anOrganisationWhoseOnlyVisitorIsDisabledIsNotOperational() {
        orgScoped("coordinator", Role.COORDINATOR);
        orgScoped("reviewer", Role.REVIEWER);
        User visitor = orgScoped("visitor", Role.VISITOR);

        assertThat(readinessService.readinessFor(supplier).isOperational())
                .as("the positive control - with the visitor enabled this supplier IS operational, "
                        + "so the assertion below is about the disabling and nothing else")
                .isTrue();

        visitor.setEnabled(false);
        userRepository.saveAndFlush(visitor);

        assertThat(readinessService.readinessFor(supplier).missingRoles()).containsExactly(Role.VISITOR);
    }

    /**
     * The tenancy arm. Somebody else's coordinator is not a coordinator here, and a gathering that
     * lost its grouping would report every organisation on the platform as ready the moment one of
     * them was.
     */
    @Test
    void anotherOrganisationsPeopleDoNotCount() {
        Organisation otherSupplier = saveOrg("T267 Other Supplier" + suffix, OrgType.SUPPLIER, null);
        User theirs = new User();
        theirs.setUsername("t267-theirs" + suffix);
        theirs.setLastName("Theirs");
        theirs.setRoles(new HashSet<>(Set.of(Role.COORDINATOR, Role.VISITOR, Role.REVIEWER)));
        theirs.setOrganisation(otherSupplier);
        theirs.setEnabled(true);
        userRepository.saveAndFlush(theirs);

        assertThat(readinessService.readinessFor(otherSupplier).isOperational())
                .as("positive control: their own supplier is ready")
                .isTrue();
        assertThat(readinessService.readinessFor(supplier).isOperational()).isFalse();
    }

    /**
     * THE CARD'S OWN CONDITION, IN BOTH DIRECTIONS: readiness and activation are different
     * questions, and neither implies the other.
     *
     * <p>One is "cannot decrypt" and needs an operator to provision a key; the other is "has nobody
     * to do the work" and needs an administrator to add a person. A single status covering both
     * would send half its readers to the wrong remedy. Asserting only one direction would miss the
     * likelier mistake - it is the reassuring one, an ACTIVE organisation reported as ready because
     * somebody folded {@code isActive()} into the answer.
     */
    @Test
    void readinessAndActivationAreIndependentInBothDirections() {
        supplier.setStatus(OrgStatus.ACTIVE);
        organisationRepository.saveAndFlush(supplier);

        assertThat(readinessService.readinessFor(supplier).isOperational())
                .as("ACTIVE with nobody in it is not ready - the key exists, the people do not")
                .isFalse();

        Organisation pending = saveOrg("T267 Pending Supplier" + suffix, OrgType.SUPPLIER, null);
        User everybody = new User();
        everybody.setUsername("t267-everybody" + suffix);
        everybody.setLastName("Everybody");
        everybody.setRoles(new HashSet<>(Set.of(Role.COORDINATOR, Role.VISITOR, Role.REVIEWER)));
        everybody.setOrganisation(pending);
        everybody.setEnabled(true);
        userRepository.saveAndFlush(everybody);

        assertThat(pending.getStatus()).isEqualTo(OrgStatus.PENDING);
        assertThat(readinessService.readinessFor(pending).isOperational())
                .as("fully staffed and still PENDING - readiness must not read the status")
                .isTrue();
    }

    /** The bulk form and the single form answer the same question, because they are one gathering. */
    @Test
    void theTreesAnswerAndTheOneOrganisationAnswerAgree() {
        orgScoped("coordinator", Role.COORDINATOR);

        var bulk = readinessService.readinessByOrganisationId(List.of(supplier, careProvider));

        assertThat(bulk.get(supplier.getId()).missingRoles())
                .isEqualTo(readinessService.readinessFor(supplier).missingRoles());
        assertThat(bulk).as("an organisation with nobody in it still gets an entry - an absent key "
                        + "would make a caller default to 'nothing is wrong', which is the one answer "
                        + "that is never right here")
                .containsKey(careProvider.getId());
    }

    private User orgScoped(String name, Role role) {
        User user = new User();
        user.setUsername("t267-" + name + suffix);
        user.setLastName(name);
        user.setRoles(new HashSet<>(Set.of(role)));
        user.setOrganisation(supplier);
        user.setEnabled(true);
        return userRepository.saveAndFlush(user);
    }

    private Organisation saveOrg(String name, OrgType type, Organisation servedBy) {
        Organisation org = new Organisation();
        org.setName(name);
        org.setType(type);
        org.setSupplierOrganisation(servedBy);
        return organisationRepository.saveAndFlush(org);
    }
}
