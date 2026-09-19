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
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.user.AppUserDetailsService;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T328: a young person whose record was ARCHIVED must not be silently created a second time.
 *
 * <p>Archiving leaves the record retrievable but takes it off the lists (T170), so the picker cannot
 * show it and nothing a member of staff can see says it exists. Add them again and their history
 * splits: interviews under the old record, the new work under the new one, with no screen anywhere
 * reporting both. <b>The create is refused rather than annotated</b> - documenting a trap does not
 * disarm it, least of all at 2am under a 72-hour clock.
 *
 * <p><b>Every assertion here is about the POST.</b> The banner is what a person sees, but what makes
 * this safe is that the row is not written - a test on the markup would pass against a server that
 * rendered a warning and saved anyway.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AnArchivedYoungPersonIsNotSilentlyRecreatedTest extends AbstractIntegrationTest {

    private static final String SURNAME = "Okonkwo";
    private static final LocalDate DOB = LocalDate.of(2011, 3, 14);

    @Autowired private MockMvc mockMvc;
    @Autowired private ChildRepository childRepository;
    @Autowired private HomeRepository homeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganisationRepository organisationRepository;
    @Autowired private ChildLifecycleService childLifecycleService;
    @Autowired private ArchivedRecordMatcher archivedRecordMatcher;
    @Autowired private AppUserDetailsService appUserDetailsService;

    private String suffix;
    private Organisation careProvider;
    private Home home;
    private String staffUsername;
    private User orgAdmin;

    @BeforeEach
    void seedAHomeWithStaffAndAnOrgAdmin() {
        suffix = "-" + System.nanoTime();
        // The SEEDED care provider, because the create path refuses an organisation that is not
        // ACTIVE (T168(b)) and Organisation.setStatus is package-private to the organisation
        // package - deliberately, so every transition goes through the lifecycle service. Reaching
        // around that from a test would be reaching around the thing it exists to enforce.
        careProvider = seededCareProvider();
        home = saveHome("T328 House" + suffix, careProvider);

        staffUsername = "t328-staff" + suffix;
        saveUser(staffUsername, Role.HOME_STAFF, null, home);
        orgAdmin = saveUser("t328-orgadmin" + suffix, Role.ORG_ADMIN, careProvider, null);
    }

    /** THE POSITIVE CONTROL. Without it every refusal below could be a form that never works. */
    @Test
    void anOrdinaryCreateStillSucceeds() throws Exception {
        addChildAs(staffUsername, "Ada", "Nwosu" + suffix, DOB)
                .andExpect(status().is3xxRedirection());

        assertThat(childrenIn(home)).hasSize(1);
    }

    /** The defect: the second record is not written, and the person is told why. */
    @Test
    void addingSomebodyWhoIsArchivedHereIsRefusedAndNothingIsWritten() throws Exception {
        archiveAChildCalled(SURNAME + suffix);

        String html = addChildAs(staffUsername, "Ada", SURNAME + suffix, DOB)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("This young person already has a record here");
        assertThat(childrenIn(home))
                .as("the refusal is the row not being written - a banner over a saved duplicate "
                        + "would be the defect with an explanation attached")
                .hasSize(1);
    }

    /**
     * A DIFFERENT DATE OF BIRTH IS A DIFFERENT PERSON. Two young people can share a surname, and a
     * check that blocked on the name alone would stop legitimate work in exactly the homes that have
     * siblings in them.
     */
    @Test
    void thesameSurnameWithADifferentDateOfBirthIsNotBlocked() throws Exception {
        archiveAChildCalled(SURNAME + suffix);

        addChildAs(staffUsername, "Chidi", SURNAME + suffix, DOB.minusYears(3))
                .andExpect(status().is3xxRedirection());

        assertThat(childrenIn(home)).hasSize(2);
    }

    /**
     * AND A DIFFERENT FIRST NAME IS THE SAME PERSON, which is the whole reason first name is not
     * part of the key. Katie and Kathryn at 2am is where typing varies most, and a first-name
     * mismatch would let the duplicate straight through - the failure this card exists to close.
     */
    @Test
    void aDifferentFirstNameDoesNotGetPastTheCheck() throws Exception {
        archiveAChildCalled(SURNAME + suffix);

        addChildAs(staffUsername, "Adaeze", SURNAME + suffix, DOB)
                .andExpect(status().isOk());

        assertThat(childrenIn(home)).hasSize(1);
    }

    /** An ACTIVE record is in the picker, so it is a different problem and not this one's business. */
    @Test
    void anActiveRecordIsNotBlockedBecauseTheScreenAlreadyShowsIt() throws Exception {
        saveChild("Ada", SURNAME + suffix, DOB, home);

        addChildAs(staffUsername, "Ada", SURNAME + suffix, DOB)
                .andExpect(status().is3xxRedirection());

        assertThat(childrenIn(home)).hasSize(2);
    }

    /**
     * THE SCOPING ARM, AND IT IS THE ONE CREED CALLED LOAD-BEARING.
     *
     * <p>Unscoped, this form answers <i>"does this young person exist anywhere on the platform?"</i>
     * to anyone who may add a child - Article 9 data, on a form with no other authorisation. So an
     * archived match in a home this person cannot see must produce <b>no block and no message</b>:
     * a refusal is itself a weak oracle, because it reveals existence just as an answer does.
     *
     * <p><b>Never let a safety check answer a question the asker was not entitled to ask.</b>
     */
    @Test
    void anArchivedMatchInAHomeTheyCannotSeeIsNeitherBlockedNorMentioned() throws Exception {
        // Their organisation needs no activation: this child is written straight through the
        // repository, so it never passes the create path's ACTIVE gate. What matters is only that
        // the home is one our member of staff cannot see.
        Organisation otherProvider = saveOrg("T328 Other Provider" + suffix, OrgType.CARE_PROVIDER, null);
        Home theirHome = saveHome("T328 Their House" + suffix, otherProvider);
        Child theirs = saveChild("Ada", SURNAME + suffix, DOB, theirHome);
        childLifecycleService.archive(theirs, principalFor(adminOf(otherProvider)));

        String html = addChildAs(staffUsername, "Ada", SURNAME + suffix, DOB)
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("already has a record");
        assertThat(childrenIn(home)).as("and the create went through normally").hasSize(1);
    }

    /**
     * A CARE-PROVIDER ORG ADMIN IS SCOPED BY THEIR ORGANISATION, NOT BY HOME ATTACHMENT - and this
     * is the arm that catches the obvious wrong implementation.
     *
     * <p>{@code homeIdsFor} returns only the homes a user is directly ATTACHED to. An org admin has
     * none, so scoping the check that way leaves them with an empty candidate set and <b>no check
     * running at all</b> - silently, for the one role that can also perform the remedy. The
     * visibility rule has to be the access service's own, which admits every home in their
     * organisation.
     */
    @Test
    void anOrgAdminWithNoHomeAttachmentStillGetsTheCheck() {
        assertThat(orgAdmin.getHomes())
                .as("the fixture is only interesting while this org admin is attached to no home")
                .isEmpty();
        Child archived = saveChild("Ada", SURNAME + suffix, DOB, home);
        childLifecycleService.archive(archived, principalFor(orgAdmin));

        assertThat(archivedRecordMatcher.archivedMatchFor(SURNAME + suffix, DOB, principalFor(orgAdmin)))
                .isPresent();
    }

    private String adminOf(Organisation organisation) {
        String username = "t328-admin-" + organisation.getId() + suffix;
        return userRepository.findByUsername(username).map(User::getUsername)
                .orElseGet(() -> saveUser(username, Role.ORG_ADMIN, organisation, null).getUsername());
    }

    private void archiveAChildCalled(String lastName) {
        Child child = saveChild("Ada", lastName, DOB, home);
        childLifecycleService.archive(child, principalFor(orgAdmin));
    }

    private org.springframework.test.web.servlet.ResultActions addChildAs(String username, String firstName,
            String lastName, LocalDate dateOfBirth) throws Exception {
        return mockMvc.perform(post("/children").with(as(username)).with(csrf())
                .param("firstName", firstName)
                .param("lastName", lastName)
                .param("dateOfBirth", dateOfBirth.toString()));
    }

    private java.util.List<Child> childrenIn(Home which) {
        return childRepository.findByHomeIdIn(Set.of(which.getId()));
    }

    private Child saveChild(String firstName, String lastName, LocalDate dateOfBirth, Home which) {
        Child child = new Child();
        child.setFirstName(firstName);
        child.setLastName(lastName);
        child.setDateOfBirth(dateOfBirth);
        child.setHome(which);
        return childRepository.saveAndFlush(child);
    }

    private AppUserPrincipal principalFor(User user) {
        return new AppUserPrincipal(userRepository.findByUsername(user.getUsername()).orElseThrow(), false);
    }

    private AppUserPrincipal principalFor(String username) {
        return new AppUserPrincipal(userRepository.findByUsername(username).orElseThrow(), false);
    }

    private User saveUser(String username, Role role, Organisation organisation, Home theirHome) {
        User user = new User();
        user.setUsername(username);
        user.setLastName(username);
        user.setRoles(new HashSet<>(Set.of(role)));
        user.setOrganisation(organisation);
        user.setHomes(theirHome == null ? new HashSet<>() : new HashSet<>(Set.of(theirHome)));
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

    private Home saveHome(String name, Organisation org) {
        Home h = new Home();
        h.setName(name);
        h.setOrganisation(org);
        return homeRepository.save(h);
    }

    private RequestPostProcessor as(String username) {
        UserDetails details = appUserDetailsService.loadUserByUsername(username);
        return securityContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())));
    }
}
