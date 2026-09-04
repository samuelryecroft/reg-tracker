package ninja.samryecroft.returnhome.tracker.user;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
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
     * <p>The account is not hypothetical. An ORG_ADMIN with no organisation is what a half-applied
     * data repair leaves behind, and what a link-on-first-login Entra account (P4) looks like
     * between the identity landing and the organisation being assigned.
     */
    public List<User> listVisible(AppUserPrincipal principal) {
        if (principal == null) {
            return List.of();
        }
        if (principal.hasRole(Role.ADMIN)) {
            return userRepository.findAllWithHome();
        }
        if (roleMatrix.isCareProviderOrgAdmin(principal)) {
            return userRepository.findHomeStaffByHomeOrganisationId(principal.getOrganisationId());
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

    public User getAuthorized(Long id, AppUserPrincipal principal) {
        User user = userRepository.findById(id)
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
            visible = user.hasRole(Role.HOME_STAFF) && !user.getHomes().isEmpty()
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
        validateRoles(form.getRoles(), principal);

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
        validateRoles(form.getRoles(), principal);

        // Snapshotted before the setters below mutate the managed entity, so the audit row can
        // record the actual role/enabled transition rather than just the end state.
        Set<Role> rolesBefore = Set.copyOf(user.getRoles());
        boolean enabledBefore = user.isEnabled();

        applyProfile(user, form.getFirstName(), form.getLastName(), form.getEmail(), form.getContactPhone());
        user.setRoles(form.getRoles());
        user.setOrganisation(needsOrganisation(form.getRoles()) ? resolveOrganisation(form.getOrganisationId(), principal) : null);
        user.setHomes(resolveHomes(form.getRoles(), form.getHomeIds(), principal));
        user.setEnabled(form.isEnabled());
        boolean passwordChanged = form.getNewPassword() != null && !form.getNewPassword().isBlank();
        if (passwordChanged) {
            user.setPassword(passwordEncoder.encode(form.getNewPassword()));
        }
        User saved = userRepository.save(user);
        auditEventPublisher.userUpdated(saved, rolesBefore, enabledBefore, passwordChanged, principal);
        return saved;
    }

    private void validateRoles(Set<Role> roles, AppUserPrincipal principal) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("At least one role is required");
        }
        if (!allowedRolesFor(principal).containsAll(roles)) {
            throw new AccessDeniedException("You cannot assign one or more of the selected roles");
        }
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

    /** ADMIN picks any organisation explicitly; an org-admin's new users are always pinned to their own org. */
    private Organisation resolveOrganisation(Long organisationId, AppUserPrincipal principal) {
        if (principal.hasRole(Role.ADMIN)) {
            if (organisationId == null) {
                throw new IllegalArgumentException("Organisation is required");
            }
            return organisationRepository.findById(organisationId)
                    .orElseThrow(() -> new IllegalArgumentException("No such organisation: " + organisationId));
        }
        return organisationRepository.findById(principal.getOrganisationId()).orElseThrow();
    }
}
