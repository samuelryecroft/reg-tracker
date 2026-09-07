package ninja.samryecroft.returnhome.tracker.user;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.Organisation;
import ninja.samryecroft.returnhome.tracker.organisation.HomeScope;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrgType;
import ninja.samryecroft.returnhome.tracker.user.dto.CreateUserForm;
import ninja.samryecroft.returnhome.tracker.user.dto.EditUserForm;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final Set<Role> CARE_PROVIDER_ONLY = EnumSet.of(Role.VIEWER);
    private static final Set<Role> SUPPLIER_ONLY = EnumSet.of(Role.COORDINATOR, Role.VISITOR, Role.REVIEWER);

    private final UserRepository userRepository;
    private final HomeRepository homeRepository;
    private final OrganisationRepository organisationRepository;
    private final OrganisationAccessService organisationAccessService;
    private final PasswordEncoder passwordEncoder;
    private final AuditEventPublisher auditEventPublisher;
    private final RoleMatrix roleMatrix;

    public UserService(UserRepository userRepository, HomeRepository homeRepository,
            OrganisationRepository organisationRepository, OrganisationAccessService organisationAccessService,
            PasswordEncoder passwordEncoder, AuditEventPublisher auditEventPublisher, RoleMatrix roleMatrix) {
        this.userRepository = userRepository;
        this.homeRepository = homeRepository;
        this.organisationRepository = organisationRepository;
        this.organisationAccessService = organisationAccessService;
        this.passwordEncoder = passwordEncoder;
        this.auditEventPublisher = auditEventPublisher;
        this.roleMatrix = roleMatrix;
    }

    /**
     * Platform ADMIN sees everyone; an org-admin sees only users belonging to their own
     * organisation; anyone else sees nothing.
     *
     * <p>The last branch is a <em>positive</em> test for a supplier org-admin (T130). It used to
     * fall through - whoever was neither a platform admin nor a care-provider org-admin was handed
     * {@code findByOrganisationId(principal.getOrganisationId())}, which is the same default-allow
     * shape T117 removed from {@link RoleMatrix#assignableRoles}, ten lines below.
     *
     * <p>It did fail closed, but <b>only by accident</b>: the one state that reaches here and is
     * neither side is an ORG_ADMIN with no organisation, whose id is therefore null, and the JPQL
     * {@code u.organisation.id = :organisationId} matches no rows because SQL equality against NULL
     * is never true. Nothing positively decided that account should see nothing - a database quirk
     * did. That is not a property to rest a tenancy boundary on: it changes if the query gains an
     * {@code OR :organisationId IS NULL}, if a third {@link OrgType} is added, or if any future path
     * reaches this method with a role that is neither. So the deny is now stated, and the
     * repository is not asked at all.
     *
     * <p>The account is not hypothetical: an ORG_ADMIN with no organisation is what a half-applied
     * data repair leaves behind. (It was also the shape of a half-provisioned Entra account, which
     * is gone - but the data repair case never depended on that, so the deny still earns its keep.)
     */
    public List<User> listVisible(AppUserPrincipal principal) {
        if (principal == null) {
            return List.of();
        }
        if (principal.hasRole(Role.ADMIN)) {
            return userRepository.findAllWithHome();
        }
        if (roleMatrix.isCareProviderOrgAdmin(principal)) {
            return userRepository.findByAnyRoleAndHomeOrganisationId(
                    rolesVisibleTo(principal), principal.getOrganisationId());
        }
        if (roleMatrix.isSupplierOrgAdmin(principal)) {
            return userRepository.findByOrganisationId(principal.getOrganisationId());
        }
        return List.of();
    }

    /**
     * Which roles this principal is allowed to assign when creating/editing a user.
     *
     * <p>Delegated to {@link RoleMatrix}, which is also what the templates are shown, so the roles
     * offered on the form and the roles the server will accept are the same list rather than two
     * lists that agree today.
     */
    public List<Role> allowedRolesFor(AppUserPrincipal principal) {
        return roleMatrix.assignableRoles(principal);
    }

    /**
     * The roles this target holds that the given actor may not assign - i.e. the ones the edit form
     * must show as present and refuse to let them change (T275).
     *
     * <p>Public because the CONTROLLER needs the same answer the merge uses. One method, so the
     * screen and the save cannot disagree about which roles are off-limits; two computations of this
     * would be the original defect in a new place, where the form shows one thing and the service
     * keeps another.
     */
    public List<Role> rolesNotAssignableBy(User user, AppUserPrincipal principal) {
        List<Role> assignable = allowedRolesFor(principal);
        return user.getRoles().stream().filter(role -> !assignable.contains(role)).sorted().toList();
    }

    /**
     * Which roles a principal may SEE, and it is deliberately the same list as the roles they may
     * ASSIGN (T281).
     *
     * <p><strong>The defect this closes: a care-provider org admin could create a VIEWER and then
     * never see it again.</strong> {@code RoleMatrix.assignableRoles} said HOME_STAFF and VIEWER;
     * visibility said HOME_STAFF. Both rules were right on their own and neither was wrong when it
     * was written - they were two statements about the same people that agreed by coincidence, and
     * one of them moved. An account you can create but never afterwards see, disable or correct is
     * broken on its own terms, and on a safeguarding system it is an account nobody can retire.
     *
     * <p><strong>This is a method rather than an inlined call so the RELATIONSHIP is the thing in
     * the code</strong>, not a coincidence a reader has to reconstruct. Derived, not duplicated:
     * add a role to the grant matrix and visibility follows it, which is the only reason the two
     * cannot drift apart again.
     *
     * <p><strong>Why identity is the right relationship rather than merely a safe one:</strong> the
     * capability being granted is "administer this person". Being able to create an account you
     * cannot then reach is not a narrower permission, it is a broken one. If a case is ever found
     * for seeing more than you may assign - or less - it belongs here, stated, with its reason.
     */
    private List<Role> rolesVisibleTo(AppUserPrincipal principal) {
        return allowedRolesFor(principal);
    }

    public User getAuthorized(Long id, AppUserPrincipal principal) {
        // findDetailedById, not findById: this method reads the target's homes, and with
        // open-in-view disabled a lazy collection on a detached entity is a 500 rather than a
        // decision. See the repository method for why fetching beats wrapping this in a transaction.
        User user = userRepository.findDetailedById(id)
                .orElseThrow(() -> new IllegalArgumentException("No such user: " + id));
        if (principal.hasRole(Role.ADMIN)) {
            return user;
        }
        // Same shape as listVisible above, and deliberately so: the list and the detail page must
        // agree about who is visible, or an account denied the list could still fetch a row by id.
        //
        // Two states reach the final deny. An ORG_ADMIN with no organisation is neither side, as in
        // listVisible - that one the old code also refused, because a null organisation id matches
        // no target. The one it did NOT refuse is a principal that is neither side but DOES have an
        // organisation: HOME_STAFF, COORDINATOR, VIEWER, VISITOR or REVIEWER, all of which
        // needsOrganisation() gives one to. The old else read "same organisation as the target?",
        // which is true for every user in that organisation, so it handed the row over. Only
        // SecurityConfig keeping those roles off /admin/** stopped it - a routing fact that a new
        // controller or a widened rule changes without touching this file.
        boolean visible;
        if (roleMatrix.isCareProviderOrgAdmin(principal)) {
            HomeScope scope = organisationAccessService.homeScopeFor(principal);
            // Reads the SAME set as the list query above rather than restating HOME_STAFF, which is
            // the whole of T281: the list and the detail page must agree about who is visible, and
            // they now agree because they ask one question rather than because two answers match.
            List<Role> visibleRoles = rolesVisibleTo(principal);
            visible = user.getRoles().stream().anyMatch(visibleRoles::contains)
                    && !user.getHomes().isEmpty()
                    && user.getHomes().stream().allMatch(scope::canView);
        } else if (roleMatrix.isSupplierOrgAdmin(principal)) {
            visible = user.getOrganisation() != null
                    && user.getOrganisation().getId().equals(principal.getOrganisationId());
        } else {
            visible = false;
        }
        if (!visible) {
            throw new AccessDeniedException("Not authorized to view user " + id);
        }
        return user;
    }

    @Transactional
    public User create(CreateUserForm form, AppUserPrincipal principal) {
        validateAssignable(form.getRoles(), principal);
        validateCombination(form.getRoles());

        User user = new User();
        user.setUsername(form.getUsername());
        // No password means no local credential, which must stay null rather than becoming the
        // encoding of an empty string - that would be a real, matchable credential, and anyone
        // submitting a blank password would authenticate as this account.
        user.setPassword(form.getPassword() == null ? null : passwordEncoder.encode(form.getPassword()));
        applyProfile(user, form.getFirstName(), form.getLastName(), form.getEmail(), form.getContactPhone());
        user.setRoles(form.getRoles());
        user.setOrganisation(needsOrganisation(form.getRoles()) ? resolveOrganisation(form.getOrganisationId(), principal) : null);
        user.setHomes(resolveHomes(form.getRoles(), form.getHomeIds(), principal));
        user.setEnabled(true);
        User saved = userRepository.save(user);
        auditEventPublisher.userCreated(saved, principal);
        return saved;
    }

    @Transactional
    public User update(Long id, EditUserForm form, AppUserPrincipal principal) {
        User user = getAuthorized(id, principal);
        validateAssignable(form.getRoles(), principal);

        // Snapshotted before the setters below mutate the managed entity, so the audit row can
        // record the actual role/enabled transition rather than just the end state.
        Set<Role> rolesBefore = Set.copyOf(user.getRoles());
        Set<Role> roles = mergeWithRolesTheActorCannotAssign(rolesBefore, form.getRoles(), principal);
        // The COMBINATION is checked on the merged result, not on what was submitted. The submitted
        // set can be perfectly legal on its own and still produce an illegal account once a retained
        // role is added back - and the exclusivity rules exist to describe the ACCOUNT, not the
        // request. This is also what stops the care-provider side going quiet: it is safe today only
        // because HOME_STAFF may not be combined with anything, so relaxing that rule later would
        // silently re-open this if the check still looked at the submission.
        validateCombination(roles);
        refuseToRemoveYourOwnAdministrativeRole(user, rolesBefore, roles, principal);
        boolean enabledBefore = user.isEnabled();
        refuseToStrandTheOrganisation(user, rolesBefore, roles, form.isEnabled());
        applyProfile(user, form.getFirstName(), form.getLastName(), form.getEmail(), form.getContactPhone());
        user.setRoles(roles);
        user.setOrganisation(needsOrganisation(roles) ? resolveOrganisation(form.getOrganisationId(), principal) : null);
        user.setHomes(resolveHomes(roles, form.getHomeIds(), principal));
        user.setEnabled(form.isEnabled());
        // No password handling here any more (T277). Setting a credential is its own action on its
        // own form, so no future widening of "who may edit this user" can include it by accident -
        // which is what happened twice while it was a field in this bundle.
        User saved = userRepository.save(user);
        auditEventPublisher.userUpdated(saved, rolesBefore, enabledBefore, principal);
        return saved;
    }

    /**
     * Set another account's password (T277).
     *
     * <p>Authorised through the SAME {@link #getAuthorized} the edit form uses, so this action is
     * reachable by exactly the people who could already administer the account - the card removes a
     * capability from a BUNDLE, it does not narrow or widen who holds it.
     *
     * <p>Audited as its own event rather than a flag on a user update, because a credential change
     * is the one user-admin action whose consequence is that somebody else can sign in as this
     * person.
     */
    @Transactional
    public User setPassword(Long id, String newPassword, AppUserPrincipal principal) {
        User user = getAuthorized(id, principal);
        user.setPassword(passwordEncoder.encode(newPassword));
        User saved = userRepository.save(user);
        auditEventPublisher.userPasswordReset(saved, principal);
        return saved;
    }

    /**
     * A role the actor cannot grant, they cannot remove (T275, Oscar's rule).
     *
     * <p>{@code update} used to do {@code user.setRoles(form.getRoles())}, and the submitted set was
     * checked only for being a SUBSET OF WHAT THE ACTOR MAY ASSIGN - it never looked at what the
     * target already held. So every role outside the actor's assignable set was replaced away.
     *
     * <p><strong>And it was not merely uneditable, it was invisible.</strong> The edit template
     * iterates the actor's assignable roles, so such a role rendered no checkbox at all: unrendered,
     * unsubmitted, and silently gone. A supplier org-admin editing their own contact details saved
     * away their own ORG_ADMIN, and only a platform admin could put it back.
     *
     * <p>So the retained set is computed from the TARGET's roles rather than from the form: the form
     * cannot be trusted to carry what it was never shown.
     */
    private Set<Role> mergeWithRolesTheActorCannotAssign(Set<Role> held, Set<Role> submitted,
            AppUserPrincipal principal) {
        List<Role> assignable = allowedRolesFor(principal);
        Set<Role> merged = new LinkedHashSet<>(submitted == null ? Set.of() : submitted);
        held.stream().filter(role -> !assignable.contains(role)).forEach(merged::add);
        return merged;
    }

    /**
     * An actor may not remove their own ADMIN or ORG_ADMIN role.
     *
     * <p>The merge above closes the SILENT case - a role nobody was shown cannot be dropped. This
     * closes the VISIBLE one: a role the actor can both see and assign, unticked on their own
     * account. Everything else on this screen is recoverable by the person who did it; this is the
     * one action that is not, because removing the role removes the ability to put it back.
     *
     * <p><strong>Scope, stated accurately rather than generously:</strong> no ORG_ADMIN can reach
     * this today. {@code RoleMatrix.assignableRoles} gives a care-provider org-admin
     * {HOME_STAFF, VIEWER} and a supplier org-admin {COORDINATOR, VISITOR, REVIEWER} - neither
     * includes ORG_ADMIN, so their own role is retained by the merge and never offered as a
     * checkbox. In practice this guard fires for a platform ADMIN removing their own ADMIN.
     * ORG_ADMIN is listed anyway because the guard should not start depending on a matrix entry that
     * a later card may widen - which is the same mistake as the care-provider side being safe only
     * by accident of HOME_STAFF exclusivity.
     */
    /**
     * An organisation must always have at least one ENABLED org administrator (T278, Oscar's hole 2).
     *
     * <p><strong>Two routes lead to one outcome, and only one of them is a role change.</strong>
     * T275 closed the accidental role route - a merged submission cannot strip a role the actor
     * could not grant - but that fix has nothing to say about the {@code enabled} flag, which is a
     * CHECKBOX ON THE SAME FORM. A whole week of discussion about roles would not have led anyone to
     * look there. A third route, archiving a user (T170), does not exist yet and will arrive here
     * rather than somewhere new: <strong>this lives in the service because that is where the routes
     * converge</strong>, and a rule placed on the form would have to be rewritten for each of them.
     *
     * <p><strong>Why the consequence is not administrative tidiness.</strong> An organisation with no
     * enabled administrator cannot add users and cannot fix itself; it has to come to us. For a care
     * provider that means it cannot onboard home staff - so a home that needs to raise a request for
     * a missing young person has nobody able to give them an account. <em>The failure is
     * administrative; the consequence is that a child's interview does not get requested.</em>
     *
     * <p>The refusal states the reason rather than only refusing, because the actor's next move
     * ("appoint another administrator first") is not guessable from a bare denial.
     *
     * <p>Deliberately checked against the MERGED role set and the SUBMITTED enabled flag - the state
     * the account would actually be left in. Checking the submission alone would miss the case where
     * a retained role keeps them an administrator, and checking roles alone would miss disabling
     * entirely, which is the route this card exists for.
     */
    private void refuseToStrandTheOrganisation(User user, Set<Role> before, Set<Role> after,
            boolean enabledAfter) {
        Long organisationId = user.getOrganisation() == null ? null : user.getOrganisation().getId();
        boolean wasTheirOrganisationsAdmin = organisationId != null
                && before.contains(Role.ORG_ADMIN) && user.isEnabled();
        if (!wasTheirOrganisationsAdmin) {
            return;
        }
        boolean stillIs = after.contains(Role.ORG_ADMIN) && enabledAfter;
        if (stillIs || userRepository.hasAnotherEnabledOrgAdmin(organisationId, user.getId())) {
            return;
        }
        throw new IllegalArgumentException(
                "This is the last enabled administrator for " + user.getOrganisation().getName()
                        + ". Appoint another administrator first, or the organisation will have "
                        + "nobody able to manage its accounts.");
    }

    private void refuseToRemoveYourOwnAdministrativeRole(User user, Set<Role> before, Set<Role> after,
            AppUserPrincipal principal) {
        if (!user.getId().equals(principal.getUserId())) {
            return;
        }
        for (Role role : List.of(Role.ADMIN, Role.ORG_ADMIN)) {
            if (before.contains(role) && !after.contains(role)) {
                throw new IllegalArgumentException("You cannot remove your own " + role.getDisplayName()
                        + " role. Ask another administrator to change it, or you will not be able to "
                        + "change it back.");
            }
        }
    }

    private void validateAssignable(Set<Role> roles, AppUserPrincipal principal) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("At least one role is required");
        }
        if (!allowedRolesFor(principal).containsAll(roles)) {
            throw new AccessDeniedException("You cannot assign one or more of the selected roles");
        }
    }

    /** The rules about what an ACCOUNT may hold at once - asked of the result, never of the request. */
    private void validateCombination(Set<Role> roles) {
        if (roles.size() > 1 && roles.contains(Role.HOME_STAFF)) {
            throw new IllegalArgumentException("Home Staff cannot be combined with any other role");
        }
        if (roles.size() > 1 && roles.contains(Role.ADMIN)) {
            throw new IllegalArgumentException("Admin cannot be combined with any other role");
        }
        boolean hasCareProviderOnlyRole = roles.stream().anyMatch(CARE_PROVIDER_ONLY::contains);
        boolean hasSupplierOnlyRole = roles.stream().anyMatch(SUPPLIER_ONLY::contains);
        if (hasCareProviderOnlyRole && hasSupplierOnlyRole) {
            throw new IllegalArgumentException("Roles cannot span both a Care Provider and a Supplier organisation");
        }
    }

    /** Any role other than HOME_STAFF/ADMIN is org-scoped (ORG_ADMIN, COORDINATOR, VISITOR). */
    /**
     * The profile fields, set the same way on create and edit so the two paths cannot drift.
     *
     * <p>Trimmed here rather than in the form, because this is the last point before persistence
     * and a name with a trailing space sorts and displays wrongly for the life of the row.
     */
    private void applyProfile(User user, String firstName, String lastName, String email, String contactPhone) {
        user.setFirstName(trimToNull(firstName));
        user.setLastName(trimToNull(lastName));
        user.setEmail(trimToNull(email));
        user.setContactPhone(trimToNull(contactPhone));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean needsOrganisation(Set<Role> roles) {
        return roles.stream().anyMatch(role -> role != Role.HOME_STAFF && role != Role.ADMIN);
    }


    /**
     * The homes to attach, for whichever role needs them - one path since V16, where HOME_STAFF and
     * VIEWER stopped being two different relationships.
     *
     * <p>Both roles may hold several. Every id is validated against the caller's own scope, so an
     * org-admin cannot attach a user to a home they cannot see themselves.
     */
    private Set<Home> resolveHomes(Set<Role> roles, Set<Long> homeIds, AppUserPrincipal principal) {
        boolean needsHomes = roles.contains(Role.HOME_STAFF) || roles.contains(Role.VIEWER);
        if (!needsHomes) {
            return new HashSet<>();
        }
        if (homeIds == null || homeIds.isEmpty()) {
            throw new IllegalArgumentException("Select at least one home");
        }
        Set<Home> homes = new LinkedHashSet<>();
        for (Long homeId : homeIds) {
            homes.add(resolveHome(homeId, principal));
        }
        requireOneCareProviderOrganisation(homes);
        return homes;
    }

    private Home resolveHome(Long homeId, AppUserPrincipal principal) {
        if (homeId == null) {
            throw new IllegalArgumentException("Home is required");
        }
        Home home = homeRepository.findById(homeId)
                .orElseThrow(() -> new IllegalArgumentException("No such home: " + homeId));
        if (!principal.hasRole(Role.ADMIN) && !organisationAccessService.canViewHome(principal, home)) {
            throw new AccessDeniedException("Home does not belong to your organisation");
        }
        return home;
    }

    /**
     * A user's homes must all sit under one Care Provider organisation.
     *
     * <p>Not a tidiness rule. Home staff have no organisation of their own - theirs is derived
     * through a home - so a user spanning two organisations would have no single answer to "which
     * organisation are you in", and the places that ask (audit scoping, theme resolution) would
     * each silently pick whichever home they happened to see first. Refusing at the point of entry
     * is the only place this is cheap to say.
     */
    private void requireOneCareProviderOrganisation(Set<Home> homes) {
        long distinctOrgs = homes.stream()
                .map(home -> home.getOrganisation() == null ? null : home.getOrganisation().getId())
                .distinct()
                .count();
        if (distinctOrgs > 1) {
            throw new IllegalArgumentException(
                    "All of a user's homes must belong to the same care provider organisation");
        }
    }

    /**
     * ADMIN picks any organisation explicitly; a supplier may pick one of the care providers it
     * serves; everyone else's new users are pinned to their own organisation.
     *
     * <p><b>T249 is the first version of this method that READS the submitted id for a non-ADMIN,
     * and that is why the authorisation check ships in the same commit.</b> Until now the parameter
     * was ignored on this path - the method returned the principal's own organisation whatever was
     * posted - so a supplier could not place a user outside its scope because <b>you cannot get a
     * check wrong on an input you never read</b>. The feature the check guards is what creates the
     * escalation path; the check is a precondition of the change rather than a hardening of it.
     *
     * <p>The scope comes from {@code OrganisationAccessService}, which resolves the supplier side
     * through T139's single supplier-link resolution. A second reading of
     * {@code supplier_organisation_id} here would be a second definition of who serves whom.
     *
     * <p>The ADMIN branch is deliberately unscoped: a platform admin's scope IS every organisation.
     *
     * <p>A null id still means "my own organisation", so a care-provider org-admin - who has no
     * picker and posts nothing - behaves exactly as before.
     */
    private Organisation resolveOrganisation(Long organisationId, AppUserPrincipal principal) {
        if (principal.hasRole(Role.ADMIN)) {
            if (organisationId == null) {
                throw new IllegalArgumentException("Organisation is required");
            }
            return organisationRepository.findById(organisationId)
                    .orElseThrow(() -> new IllegalArgumentException("No such organisation: " + organisationId));
        }
        if (organisationId == null || organisationId.equals(principal.getOrganisationId())) {
            return organisationRepository.findById(principal.getOrganisationId()).orElseThrow();
        }
        if (!organisationAccessService.canPlaceUserIn(principal, organisationId)) {
            throw new AccessDeniedException(
                    "You cannot create a user in organisation " + organisationId);
        }
        return organisationRepository.findById(organisationId)
                .orElseThrow(() -> new IllegalArgumentException("No such organisation: " + organisationId));
    }
}
