package ninja.samryecroft.returnhome.tracker.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.user.dto.EditUserForm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * T275: a role the actor cannot grant, they cannot remove.
 *
 * <p>{@code update} did {@code user.setRoles(form.getRoles())}, and {@code validateRoles} checked
 * only that the submitted roles were a SUBSET OF WHAT THE ACTOR MAY ASSIGN - it never looked at what
 * the target already held. And the edit template iterates the actor's assignable roles, so a role
 * outside that set rendered no checkbox: <strong>unrendered, unsubmitted, silently replaced away.</strong>
 *
 * <p><strong>Why the existing suite could not see it, which is the reason this class exists.</strong>
 * {@code AuditTrailIntegrationTest:339} posts the edit as an org-admin and asserts
 * rolesBefore/rolesAfter - but the target held only COORDINATOR, a role that actor CAN assign, so
 * merge and replace produce an identical result. {@code UserProfileFieldsIntegrationTest:152} posts
 * as a platform ADMIN, whose assignable set is every role, so the defect is invisible there BY
 * CONSTRUCTION. The existing assertions survive both the bug and the fix.
 *
 * <p>So every test here is built to DISCRIMINATE: the target holds a role the actor cannot assign,
 * and the actor is one who cannot assign it. Each of these fails against the code as it was.
 */
@ExtendWith(MockitoExtension.class)
class RoleMergeOnEditTest {

    private static final long TARGET_ID = 7L;
    private static final long SUPPLIER_ORG_ID = 4L;

    @Mock
    private UserRepository userRepository;
    @Mock
    private HomeRepository homeRepository;
    @Mock
    private OrganisationRepository organisationRepository;
    @Mock
    private OrganisationAccessService organisationAccessService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuditEventPublisher auditEventPublisher;

    private UserService service() {
        return new UserService(userRepository, homeRepository, organisationRepository,
                organisationAccessService, passwordEncoder, auditEventPublisher, new RoleMatrix());
    }

    /**
     * THE DISCRIMINATING CASE. A supplier org-admin may assign COORDINATOR, VISITOR and REVIEWER -
     * NOT ORG_ADMIN. The target holds ORG_ADMIN and COORDINATOR; the form submits what the screen
     * showed, which is COORDINATOR alone. ORG_ADMIN must survive.
     */
    @Test
    void aRoleTheActorCannotAssignSurvivesAnEditThatNeverMentionedIt() {
        User target = existingUser(Set.of(Role.ORG_ADMIN, Role.COORDINATOR));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        stubOrganisationLookup();

        service().update(TARGET_ID, formSubmitting(Set.of(Role.COORDINATOR)), supplierOrgAdmin(99L));

        assertThat(target.getRoles())
                .as("the actor was never shown ORG_ADMIN, so a save that omits it is not a request "
                        + "to remove it")
                .containsExactlyInAnyOrder(Role.ORG_ADMIN, Role.COORDINATOR);
    }

    /** The actor's own changes still apply - the merge adds back, it does not freeze the account. */
    @Test
    void theActorsOwnChangesStillTakeEffectAlongsideTheRetainedRole() {
        User target = existingUser(Set.of(Role.ORG_ADMIN, Role.COORDINATOR));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        stubOrganisationLookup();

        service().update(TARGET_ID, formSubmitting(Set.of(Role.VISITOR)), supplierOrgAdmin(99L));

        assertThat(target.getRoles()).containsExactlyInAnyOrder(Role.ORG_ADMIN, Role.VISITOR);
    }

    /**
     * The combination rules are checked on the RESULT, not on the submission.
     *
     * <p>The care-provider side is safe today only because HOME_STAFF may not be combined with
     * anything - an accident of an unrelated exclusivity rule. Asking the question of the merged set
     * is what stops relaxing that rule later from silently re-opening this: a submission that is
     * legal on its own must not be able to construct an illegal ACCOUNT via a retained role.
     */
    @Test
    void aSubmissionThatIsLegalAloneCannotBuildAnIllegalAccountViaARetainedRole() {
        User target = existingUser(Set.of(Role.HOME_STAFF));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));

        assertThatThrownBy(() ->
                service().update(TARGET_ID, formSubmitting(Set.of(Role.COORDINATOR)), supplierOrgAdmin(99L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Home Staff");
    }

    /** What the form must SHOW as present-and-uneditable is the same set the merge retains. */
    @Test
    void theScreenAndTheSaveAgreeAboutWhichRolesAreOffLimits() {
        User target = existingUser(Set.of(Role.ORG_ADMIN, Role.COORDINATOR));

        assertThat(service().rolesNotAssignableBy(target, supplierOrgAdmin(99L)))
                .containsExactly(Role.ORG_ADMIN);
    }

    /**
     * The visible half of the same failure: a role the actor CAN see and assign, unticked on their
     * own account. Removing it removes the ability to put it back, which no other action on this
     * screen does.
     */
    @Test
    void anActorCannotRemoveTheirOwnAdministrativeRole() {
        User target = existingUser(Set.of(Role.ADMIN));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));

        assertThatThrownBy(() ->
                service().update(TARGET_ID, formSubmitting(Set.of(Role.COORDINATOR)), platformAdmin(TARGET_ID)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("your own");
    }

    /** And it is about SELF: an admin may still change somebody else's roles freely. */
    @Test
    void anAdminMayStillChangeSomebodyElsesRoles() {
        User target = existingUser(Set.of(Role.ADMIN));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(call -> call.getArgument(0));
        stubOrganisationLookup();

        service().update(TARGET_ID, formSubmitting(Set.of(Role.COORDINATOR)), platformAdmin(1234L));

        assertThat(target.getRoles()).containsExactly(Role.COORDINATOR);
    }

    /** Only the tests that reach a successful save resolve an organisation; strict stubs keep it honest. */
    private void stubOrganisationLookup() {
        Organisation supplier = organisation(SUPPLIER_ORG_ID, OrgType.SUPPLIER);
        when(organisationRepository.findById(SUPPLIER_ORG_ID)).thenReturn(Optional.of(supplier));
    }

    // --- fixtures ---

    private static User existingUser(Set<Role> roles) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", TARGET_ID);
        user.setUsername("target");
        user.setRoles(new HashSet<>(roles));
        user.setOrganisation(organisation(SUPPLIER_ORG_ID, OrgType.SUPPLIER));
        return user;
    }

    private static EditUserForm formSubmitting(Set<Role> roles) {
        EditUserForm form = new EditUserForm();
        form.setFirstName("Pat");
        form.setLastName("Taylor");
        form.setEmail("pat.taylor@example.org");
        form.setRoles(new HashSet<>(roles));
        form.setOrganisationId(SUPPLIER_ORG_ID);
        form.setEnabled(true);
        return form;
    }

    private static AppUserPrincipal supplierOrgAdmin(long id) {
        return principal(id, Set.of(Role.ORG_ADMIN), organisation(SUPPLIER_ORG_ID, OrgType.SUPPLIER));
    }

    private static AppUserPrincipal platformAdmin(long id) {
        return principal(id, Set.of(Role.ADMIN), null);
    }

    private static AppUserPrincipal principal(long id, Set<Role> roles, Organisation organisation) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", id);
        user.setUsername("actor");
        user.setRoles(new HashSet<>(roles));
        user.setOrganisation(organisation);
        return new AppUserPrincipal(user);
    }

    private static Organisation organisation(long id, OrgType type) {
        Organisation organisation = new Organisation();
        ReflectionTestUtils.setField(organisation, "id", id);
        ReflectionTestUtils.setField(organisation, "type", type);
        organisation.setName("Beacon Return Home Services");
        return organisation;
    }
}
