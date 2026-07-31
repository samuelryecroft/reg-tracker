package ninja.samryecroft.returnhome.tracker.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    @EntityGraph(attributePaths = {"home", "organisation", "roles"})
    Optional<User> findByUsername(String username);

    @Query("select case when count(u) > 0 then true else false end from User u where :role member of u.roles")
    boolean existsByRole(@Param("role") Role role);

    @EntityGraph(attributePaths = {"roles"})
    @Query("select u from User u where :role member of u.roles order by u.fullName")
    List<User> findByRoleOrderByFullName(@Param("role") Role role);

    @EntityGraph(attributePaths = {"home", "organisation", "roles"})
    @Query("select u from User u order by u.username")
    List<User> findAllWithHome();

    @EntityGraph(attributePaths = {"home", "organisation", "roles"})
    @Query("select u from User u where :role member of u.roles and u.organisation.id = :organisationId order by u.fullName")
    List<User> findByRoleAndOrganisationId(@Param("role") Role role, @Param("organisationId") Long organisationId);

    @EntityGraph(attributePaths = {"home", "organisation", "roles"})
    @Query("select u from User u where u.organisation.id = :organisationId order by u.fullName")
    List<User> findByOrganisationId(@Param("organisationId") Long organisationId);

    @EntityGraph(attributePaths = {"home", "organisation", "roles"})
    @Query("select u from User u where ninja.samryecroft.returnhome.tracker.user.Role.HOME_STAFF member of u.roles and u.home.organisation.id = :organisationId order by u.fullName")
    List<User> findHomeStaffByHomeOrganisationId(@Param("organisationId") Long organisationId);

    @Query("select h.id from User u join u.viewerHomes h where u.id = :userId")
    List<Long> findViewerHomeIds(@Param("userId") Long userId);

    @Query("select case when count(u) > 0 then true else false end from User u join u.viewerHomes h where u.id = :userId and h.id = :homeId")
    boolean hasViewerAccessToHome(@Param("userId") Long userId, @Param("homeId") Long homeId);
}
