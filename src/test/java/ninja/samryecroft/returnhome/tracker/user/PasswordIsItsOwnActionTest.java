package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
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
 * T277: setting a password is its own action, not a field inside the user edit form.
 *
 * <p><strong>A permission to reach a form is a permission to everything in it.</strong> While the
 * credential was a field on {@code EditUserForm}, every widening of "who may edit this user"
 * silently widened "who may set their password" - twice. A rule about WHO MAY EDIT has to be
 * re-audited against the form's CONTENTS every time either changes, and nobody will. Taking the
 * field out of the bundle makes that class of mistake unreachable rather than merely unlikely, which
 * is why this is a structural card and not a tidy-up.
 *
 * <p><strong>Nothing here narrows or widens who may do it.</strong> The new action runs through the
 * same {@code getAuthorized} the edit form uses, and the scoping test below says so - a structural
 * change that quietly moved a capability would be a worse outcome than the shape it replaced.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PasswordIsItsOwnActionTest extends AbstractIntegrationTest {

    /** 100 ASCII characters: past BCrypt's 72-BYTE ceiling, which THROWS rather than truncating. */
    private static final String OVER_THE_ENCODER_CEILING = "x".repeat(100);
    private static final String GOOD_PASSWORD = "winter kettle marble lamp";

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
    private String orgAdminUsername;
    private User target;
    private User otherProvidersUser;

    @BeforeEach
    void seedTwoProvidersAndATarget() {
        suffix = "-" + System.nanoTime();
        Organisation ours = saveOrg("T277 Ours" + suffix);
        Organisation theirs = saveOrg("T277 Theirs" + suffix);
        Home ourHome = saveHome("T277 Our House" + suffix, ours);
        Home theirHome = saveHome("T277 Their House" + suffix, theirs);

        orgAdminUsername = "t277-orgadmin" + suffix;
        User admin = new User();
        admin.setUsername(orgAdminUsername);
        admin.setLastName("Org Admin");
        admin.setRoles(new HashSet<>(Set.of(Role.ORG_ADMIN)));
        admin.setOrganisation(ours);
        admin.setEnabled(true);
        userRepository.saveAndFlush(admin);

        target = saveStaff("t277-target" + suffix, ourHome);
        otherProvidersUser = saveStaff("t277-theirs" + suffix, theirHome);
    }

    /**
     * THE STRUCTURAL PROPERTY, and the only one that makes this card worth doing: the credential is
     * not in the edit bundle any more, so submitting it there does nothing at all.
     */
    @Test
    void theEditFormNoLongerSetsAPasswordEvenIfOneIsSubmitted() throws Exception {
        String before = target.getPassword();

        mockMvc.perform(post("/admin/users/{id}/edit", target.getId()).with(asOrgAdmin()).with(csrf())
                        .param("firstName", "Renamed").param("lastName", "Target")
                        .param("email", "t277target@example.org")
                        .param("roles", Role.HOME_STAFF.name())
                        .param("homeIds", target.getHomes().iterator().next().getId().toString())
                        .param("enabled", "true")
                        .param("newPassword", GOOD_PASSWORD))
                .andExpect(status().is3xxRedirection());

        User after = userRepository.findById(target.getId()).orElseThrow();
        assertThat(after.getFirstName()).as("the edit itself still works").isEqualTo("Renamed");
        assertThat(after.getPassword())
                .as("a password submitted to the edit form must be ignored, not applied")
                .isEqualTo(before);
    }

    /** And the dedicated action does set it. Without this, the test above passes on a broken feature. */
    @Test
    void theDedicatedActionSetsThePassword() throws Exception {
        String before = target.getPassword();

        mockMvc.perform(post("/admin/users/{id}/password", target.getId()).with(asOrgAdmin()).with(csrf())
                        .param("newPassword", GOOD_PASSWORD))
                .andExpect(status().is3xxRedirection());

        assertThat(userRepository.findById(target.getId()).orElseThrow().getPassword()).isNotEqualTo(before);
    }

    /**
     * The audit becomes honest: its OWN event rather than a flag inside a user update.
     *
     * <p>Asserted positively AND with the absence of a USER_UPDATED row, because "we now write a new
     * event" and "we stopped filing it under the wrong one" are two different claims and only one of
     * them is what the card asked for.
     */
    @Test
    void settingAPasswordIsItsOwnAuditEventAndNotAUserUpdate() throws Exception {
        mockMvc.perform(post("/admin/users/{id}/password", target.getId()).with(asOrgAdmin()).with(csrf())
                        .param("newPassword", GOOD_PASSWORD))
                .andExpect(status().is3xxRedirection());

        assertThat(auditEventRepository.findByTargetTypeAndTargetIdOrderByOccurredAtDesc("User", target.getId()))
                .extracting(AuditEvent::getEventType)
                .contains(AuditEventType.USER_PASSWORD_RESET)
                .doesNotContain(AuditEventType.USER_UPDATED);
    }

    /**
     * MOVED HERE FROM {@code PasswordPolicyIsWiredToTheFormsTest} (T277), and it had to become a
     * ROUTE test to keep meaning anything.
     *
     * <p>{@code BCryptPasswordEncoder.encode()} THROWS above 72 bytes rather than truncating, so
     * without a check this is an unhandled {@code IllegalArgumentException} - <strong>the difference
     * between a field error and an error page</strong>, which is what makes the byte ceiling an
     * availability rule and not only a security one.
     *
     * <p><strong>MEASURED, because I had assumed wrongly:</strong> removing the policy check gives a
     * <em>404</em>, not a 500 - {@code IllegalArgumentException} is this codebase's not-found
     * ({@code GlobalControllerAdvice:341}). So the failure a person would actually have seen is
     * "that user does not exist" while setting their password, which is worse than a 500: it is an
     * error page that <em>says something untrue</em>.
     *
     * <p>The new form carries no bean-validation password constraint (it cannot: the policy context
     * lives on the ACCOUNT, not the submission), so the wiring runs through the controller and only
     * a request can see whether it runs at all.
     */
    @Test
    void anOverLongPasswordIsAFieldErrorRatherThanAnEncoderCrash() throws Exception {
        String before = target.getPassword();

        var result = mockMvc.perform(post("/admin/users/{id}/password", target.getId())
                        .with(asOrgAdmin()).with(csrf())
                        .param("newPassword", OVER_THE_ENCODER_CEILING))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResolvedException())
                .as("an over-long password must not reach BCryptPasswordEncoder.encode()")
                .isNull();
        assertThat(result.getResponse().getContentAsString()).contains("72 bytes");
        assertThat(userRepository.findById(target.getId()).orElseThrow().getPassword()).isEqualTo(before);
    }

    /**
     * THE SCOPING ARM. The action must be reachable by exactly the people who could already
     * administer the account - a structural change that quietly moved a capability would be worse
     * than the shape it replaced.
     */
    @Test
    void anotherProvidersUserIsNotReachableByThisAction() throws Exception {
        String before = otherProvidersUser.getPassword();

        mockMvc.perform(post("/admin/users/{id}/password", otherProvidersUser.getId())
                        .with(asOrgAdmin()).with(csrf())
                        .param("newPassword", GOOD_PASSWORD))
                .andExpect(status().isForbidden());

        assertThat(userRepository.findById(otherProvidersUser.getId()).orElseThrow().getPassword())
                .isEqualTo(before);
    }

    /**
     * THE SERVICE'S OWN GUARD, and it exists because arming found the route test could not see it.
     *
     * <p>Removing {@code getAuthorized} from {@code UserService.setPassword} left the route test
     * above GREEN - the controller loads the target through the same check to build the policy
     * context, so it refuses first and the service's own check never gets a chance to be wrong.
     * <strong>The route was guarded; the METHOD was not tested at all.</strong> A second caller
     * would have inherited an unguarded credential-setter, which is the exact class of mistake this
     * card exists to remove rather than relocate.
     */
    @Test
    void theServiceRefusesOutOfScopeEvenWhenCalledDirectly() {
        UserDetails details = appUserDetailsService.loadUserByUsername(orgAdminUsername);
        AppUserPrincipal principal = (AppUserPrincipal) details;

        assertThatThrownBy(() -> userService.setPassword(otherProvidersUser.getId(), GOOD_PASSWORD, principal))
                .isInstanceOf(AccessDeniedException.class);
    }

    private User saveStaff(String username, Home home) {
        User user = new User();
        user.setUsername(username);
        user.setLastName("Target");
        user.setRoles(new HashSet<>(Set.of(Role.HOME_STAFF)));
        user.setHomes(new HashSet<>(Set.of(home)));
        user.setEnabled(true);
        return userRepository.saveAndFlush(user);
    }

    private Organisation saveOrg(String name) {
        Organisation org = new Organisation();
        org.setName(name);
        org.setType(OrgType.CARE_PROVIDER);
        return organisationRepository.save(org);
    }

    private Home saveHome(String name, Organisation org) {
        Home home = new Home();
        home.setName(name);
        home.setOrganisation(org);
        return homeRepository.save(home);
    }

    private RequestPostProcessor asOrgAdmin() {
        UserDetails details = appUserDetailsService.loadUserByUsername(orgAdminUsername);
        return securityContext(new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())));
    }
}
