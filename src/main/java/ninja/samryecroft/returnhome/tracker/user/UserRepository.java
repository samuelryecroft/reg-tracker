package ninja.samryecroft.returnhome.tracker.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    @EntityGraph(attributePaths = {"homes", "organisation", "roles"})
    Optional<User> findByUsername(String username);

    @EntityGraph(attributePaths = {"homes", "organisation", "roles"})

    @Query("select case when count(u) > 0 then true else false end from User u where :role member of u.roles")
    boolean existsByRole(@Param("role") Role role);

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

    @EntityGraph(attributePaths = {"homes", "organisation", "roles"})
    @Query("select distinct u from User u join u.homes h where ninja.samryecroft.returnhome.tracker.user.Role.HOME_STAFF member of u.roles and h.organisation.id = :organisationId order by u.lastName, u.firstName")
    List<User> findHomeStaffByHomeOrganisationId(@Param("organisationId") Long organisationId);

    /** The homes a user is attached to, whichever role attaches them. Not viewer-specific since V16. */
    @Query("select h.id from User u join u.homes h where u.id = :userId")
    List<Long> findHomeIds(@Param("userId") Long userId);

    @Query("select case when count(u) > 0 then true else false end from User u join u.homes h where u.id = :userId and h.id = :homeId")
    boolean hasHomeAccess(@Param("userId") Long userId, @Param("homeId") Long homeId);
}
