package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * T323, and the reason it is not a permissions preference.
 *
 * <p><strong>Second-factor codes are sent to a user's email address (T322).</strong> The whole basis
 * on which emailed codes are an acceptable second factor for this product is that the address cannot
 * be redirected by somebody who works alongside you. A manager who could edit a colleague's address
 * could point their codes at an inbox they control, sign in as them, and leave that colleague's name
 * against everything done afterwards - and on a safeguarding record, attribution is what the trail
 * is for.
 *
 * <p><strong>Every assertion here is about a POST rather than about the page.</strong> Removing the
 * input from {@code user-form-edit.html} shapes the form; it does not shape the request. A test that
 * checked the markup would pass against a server that still applied a hand-crafted parameter, which
 * is the only version of this defect anyone would actually exploit.
 *
 * <p>The narrowing has to leave a route out, or it is a different defect: an address mistyped at
 * creation would be uncorrectable for the life of the account. That route is the platform admin's,
 * and it is asserted here too - a rule nobody can satisfy is what makes the next reader "fix" this.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AColleagueCannotRedirectYourSignInCodesTest extends AbstractIntegrationTest {

    private static final String THEIR_REAL_ADDRESS = "real.person@example.test";
    private static final String THE_ATTACKERS_INBOX = "redirected@attacker.test";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private HomeRepository homeRepository;
    @Autowired
    private OrganisationRepository organisationRepository;
    @Autowired
    private AuditEventRepository auditEventRepository;
    @Autowired
    private AppUserDetailsService appUserDetailsService;

    private String suffix;
    private Organisation careProvider;
    private Home home;
    private User colleague;
    private String managerUsername;
    private String platformAdminUsername;

    @BeforeEach
    void seedAManagerAndAColleagueTheyAdminister() {
        suffix = "-" + System.nanoTime();
        careProvider = new Organisation();
        careProvider.setName("T323 Provider" + suffix);
        careProvider.setType(OrgType.CARE_PROVIDER);
        careProvider = organisationRepository.saveAndFlush(careProvider);

        home = new Home();
        home.setName("T323 House" + suffix);
        home.setOrganisation(careProvider);
        home = homeRepository.save(home);

        managerUsername = "t323-manager" + suffix;
        saveUser(managerUsername, Set.of(Role.ORG_ADMIN), careProvider, null);
        platformAdminUsername = "t323-platform" + suffix;
        saveUser(platformAdminUsername, Set.of(Role.ADMIN), null, null);

        colleague = saveUser("t323-colleague" + suffix, Set.of(Role.HOME_STAFF), null, home);
    }

    /**
     * THE POSITIVE CONTROL, and the assertions below are vacuous without it: this manager really can
     * administer this colleague. The narrowing is a hole in a capability they hold, not the absence
     * of the capability.
     */
    @Test
    void theManagerCanStillEditEverythingElseAboutThisColleague() throws Exception {
        assertThat(userService.mayAdminister(colleague, principalFor(managerUsername)))
                .as("if this is false the account is simply out of reach and nothing below is tested")
                .isTrue();

        editAs(managerUsername, "Corrected", THE_ATTACKERS_INBOX)
                .andExpect(status().is3xxRedirection());

        assertThat(reload().getLastName()).isEqualTo("Corrected");
    }

    /** The defect, as a request: the parameter is submitted and the address does not move. */
    @Test
    void aManagerSubmittingAnEmailOnTheEditFormDoesNotChangeIt() throws Exception {
        editAs(managerUsername, "Corrected", THE_ATTACKERS_INBOX)
                .andExpect(status().is3xxRedirection());

        assertThat(reload().getEmail()).isEqualTo(THEIR_REAL_ADDRESS);
    }

    /**
     * And not by reaching the platform admin's screen either. A route protected only by nothing
     * linking to it is not protected - so the GET is refused as well as the POST, and both are
     * asserted rather than one being inferred from the other.
     */
    @Test
    void aManagerCannotReachOrPostToTheEmailScreen() throws Exception {
        mockMvc.perform(get("/admin/users/" + colleague.getId() + "/email").with(as(managerUsername)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/admin/users/" + colleague.getId() + "/email")
                        .with(as(managerUsername)).with(csrf())
                        .param("email", THE_ATTACKERS_INBOX))
                .andExpect(status().isForbidden());

        assertThat(reload().getEmail()).isEqualTo(THEIR_REAL_ADDRESS);
    }

    /** The service says the same thing on its own, so the rule is not a property of the routing. */
    @Test
    void theServiceRefusesAManagerDirectly() {
        assertThatThrownBy(() -> userService.changeEmail(colleague.getId(), THE_ATTACKERS_INBOX,
                principalFor(managerUsername)))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> userService.getAuthorizedToChangeEmail(colleague.getId(),
                principalFor(managerUsername)))
                .as("the screen is gated by the same rule as the save, not by a second one that "
                        + "agrees with it today")
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * THE ROUTE OUT EXISTS. A field nobody can ever change is its own defect - an address mistyped
     * at creation would otherwise be uncorrectable for the life of the account, and that pressure is
     * exactly what makes a later reader restore the field on the edit form as a usability fix.
     */
    @Test
    void aPlatformAdminCanChangeItOnItsOwnScreen() throws Exception {
        mockMvc.perform(post("/admin/users/" + colleague.getId() + "/email")
                        .with(as(platformAdminUsername)).with(csrf())
                        .param("email", "corrected.address@example.test"))
                .andExpect(status().is3xxRedirection());

        assertThat(reload().getEmail()).isEqualTo("corrected.address@example.test");
    }

    /**
     * And it is audited as its own event, never as a flag on "user updated".
     *
     * <p>"When was this address last changed, and by whom" is the first question an investigation
     * into a redirected second factor asks, and it must not require reading metadata off an event
     * about something else. <strong>Neither address is recorded</strong> - {@code audit_events}
     * refuses UPDATE and DELETE by trigger, so a value written there is permanent, and an email
     * address is personal data about a named individual.
     */
    @Test
    void theChangeIsAuditedAsItsOwnEventAndCarriesNeitherAddress() {
        userService.changeEmail(colleague.getId(), "corrected.address@example.test",
                principalFor(platformAdminUsername));

        assertThat(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("User", colleague.getId()))
                .filteredOn(event -> event.getEventType() == AuditEventType.USER_EMAIL_CHANGED)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getActorUsernameAtTime()).isEqualTo(platformAdminUsername);
                    assertThat(String.valueOf(event.getMetadata()))
                            .doesNotContain(THEIR_REAL_ADDRESS)
                            .doesNotContain("corrected.address@example.test");
                });
    }

    /** The screen offers the route only where it works - a link that 403s teaches the wrong lesson. */
    @Test
    void onlyThePlatformAdminIsOfferedTheLink() throws Exception {
        String href = "/admin/users/" + colleague.getId() + "/email";

        assertThat(editPageAs(platformAdminUsername)).contains(href);
        assertThat(editPageAs(managerUsername))
                .as("and the manager still sees the address itself - visible, not editable")
                .doesNotContain(href)
                .contains(THEIR_REAL_ADDRESS);
    }

    private String editPageAs(String username) throws Exception {
        return mockMvc.perform(get("/admin/users/" + colleague.getId() + "/edit").with(as(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private org.springframework.test.web.servlet.ResultActions editAs(String username, String lastName,
            String email) throws Exception {
        return mockMvc.perform(post("/admin/users/" + colleague.getId() + "/edit")
                .with(as(username)).with(csrf())
                .param("firstName", "Real")
                .param("lastName", lastName)
                .param("email", email)
                .param("enabled", "true")
                .param("roles", Role.HOME_STAFF.name())
                .param("homeIds", home.getId().toString()));
    }

    private User reload() {
        return userRepository.findById(colleague.getId()).orElseThrow();
    }

    private AppUserPrincipal principalFor(String username) {
        return new AppUserPrincipal(userRepository.findByUsername(username).orElseThrow(), false);
    }

    private User saveUser(String username, Set<Role> roles, Organisation organisation, Home theirHome) {
        User user = new User();
        user.setUsername(username);
        user.setFirstName("Real");
        user.setLastName("Person");
        user.setEmail(THEIR_REAL_ADDRESS);
        user.setRoles(new HashSet<>(roles));
        user.setOrganisation(organisation);
        user.setHomes(theirHome == null ? new HashSet<>() : new HashSet<>(Set.of(theirHome)));
        user.setEnabled(true);
        return userRepository.saveAndFlush(user);
    }

    private RequestPostProcessor as(String username) {
        UserDetails details = appUserDetailsService.loadUserByUsername(username);
        return securityContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())));
    }
}
