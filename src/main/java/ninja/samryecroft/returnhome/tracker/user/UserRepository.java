package ninja.samryecroft.returnhome.tracker.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    @EntityGraph(attributePaths = {"homes", "organisation", "roles"})
    Optional<User> findByUsername(String username);

    /**
     * One user with the collections its authorisation check reads.
     *
     * <p><strong>{@code getAuthorized} used a bare {@code findById} and then asked the result for its
     * homes.</strong> {@code homes} is LAZY and {@code spring.jpa.open-in-view=false}, so outside a
     * transaction that is a {@code LazyInitializationException} - a 500, on the user edit page, for a
     * care-provider org admin. It went unseen because the check SHORT-CIRCUITS for a platform admin
     * before reaching the collection, and every existing test of that page signs in as one.
     *
     * <p>Fetched rather than made transactional on purpose: the authorisation decision needs the
     * data, so the data is what it asks for. Wrapping the method in a transaction would fix the
     * symptom by holding a session open around a read that has no other reason to want one.
     */
    @EntityGraph(attributePaths = {"homes", "organisation", "roles"})
    @Query("select u from User u where u.id = :id")
    Optional<User> findDetailedById(@Param("id") Long id);

    @EntityGraph(attributePaths = {"homes", "organisation", "roles"})

    @Query("select case when count(u) > 0 then true else false end from User u where :role member of u.roles")
    boolean existsByRole(@Param("role") Role role);

    /**
     * Whether this organisation still has an enabled org administrator OTHER than {@code excludedId}
     * (T278).
     *
     * <p>Asks about the SURVIVORS rather than counting admins, because the question the invariant
     * actually poses is "would this action leave the organisation with none" - and the user being
     * changed must be excluded from their own answer, since the change under consideration has not
     * been written yet.
     */
    @Query("select case when count(u) > 0 then true else false end from User u "
            + "where u.organisation.id = :organisationId and u.id <> :excludedId and u.enabled = true "
            + "and ninja.samryecroft.returnhome.tracker.user.Role.ORG_ADMIN member of u.roles")
    boolean hasAnotherEnabledOrgAdmin(@Param("organisationId") Long organisationId,
            @Param("excludedId") Long excludedId);

    /**
     * Per-organisation account counts for the 4e tree, in one query.
     *
     * <p>Returns {@code [organisationId, count]} pairs rather than a map, because JPQL cannot
     * construct one; the caller collects it. Organisations with no users are simply absent, so the
     * caller must default to zero rather than assume every organisation appears - a supplier that
     * has just been created and has nobody in it yet is a normal state, and the row it is on has
     * to render either way.
     *
     * <p>Users with no organisation (HOME_STAFF is tied to homes, and the platform ADMIN to
     * neither) are excluded rather than grouped under a null key.
     */
    @Query("select u.organisation.id, count(u) from User u where u.organisation is not null "
            + "group by u.organisation.id")
    List<Object[]> countByOrganisation();

    @EntityGraph(attributePaths = {"roles"})
    @Query("select u from User u where :role member of u.roles order by u.lastName, u.firstName")
    List<User> findByRoleOrderByFullName(@Param("role") Role role);

    @EntityGraph(attributePaths = {"homes", "organisation", "roles"})
    @Query("select u from User u order by u.username")
    List<User> findAllWithHome();

    @EntityGraph(attributePaths = {"homes", "organisation", "roles"})
    @Query("select u from User u where :role member of u.roles and u.organisation.id = :organisationId order by u.lastName, u.firstName")
    List<User> findByRoleAndOrganisationId(@Param("role") Role role, @Param("organisationId") Long organisationId);

    @EntityGraph(attributePaths = {"homes", "organisation", "roles"})
    @Query("select u from User u where u.organisation.id = :organisationId order by u.lastName, u.firstName")
    List<User> findByOrganisationId(@Param("organisationId") Long organisationId);

    /**
     * Users in this organisation's homes holding ANY of {@code roles} (T281).
     *
     * <p>Replaces a HOME_STAFF-only query. The role set is a PARAMETER because the caller passes the
     * roles it may assign: <strong>a care-provider org admin could create a VIEWER and then never
     * see it again</strong>, because the grant rule said HOME_STAFF and VIEWER while this query said
     * HOME_STAFF. Two rules about the same people, agreeing by coincidence until one of them moved.
     *
     * <p>The join is through HOMES rather than {@code u.organisation}, unchanged and deliberate: it
     * is home membership that places a user inside a care provider's tenancy.
     */
    @EntityGraph(attributePaths = {"homes", "organisation", "roles"})
    @Query("select distinct u from User u join u.homes h join u.roles r "
            + "where r in :roles and h.organisation.id = :organisationId order by u.lastName, u.firstName")
    List<User> findByAnyRoleAndHomeOrganisationId(@Param("roles") Collection<Role> roles,
            @Param("organisationId") Long organisationId);

    /** The homes a user is attached to, whichever role attaches them. Not viewer-specific since V16. */
    @Query("select h.id from User u join u.homes h where u.id = :userId")
    List<Long> findHomeIds(@Param("userId") Long userId);

    @Query("select case when count(u) > 0 then true else false end from User u join u.homes h where u.id = :userId and h.id = :homeId")
    boolean hasHomeAccess(@Param("userId") Long userId, @Param("homeId") Long homeId);
}
