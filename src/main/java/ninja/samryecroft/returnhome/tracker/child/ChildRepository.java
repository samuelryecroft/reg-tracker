package ninja.samryecroft.returnhome.tracker.child;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChildRepository extends JpaRepository<Child, Long> {

    List<Child> findByHomeIdOrderByLastNameAscFirstNameAsc(Long homeId);

    @EntityGraph(attributePaths = "home")
    @Query("select c from Child c order by c.home.name, c.lastName, c.firstName")
    List<Child> findAllWithHome();

    @EntityGraph(attributePaths = "home")
    @Query("select c from Child c where c.home.organisation.id = :organisationId order by c.home.name, c.lastName, c.firstName")
    List<Child> findByHomeOrganisationIdWithHome(@Param("organisationId") Long organisationId);

    @EntityGraph(attributePaths = {"home", "home.organisation"})
    @Query("select c from Child c where c.id = :id")
    Optional<Child> findDetailedById(@Param("id") Long id);

    @EntityGraph(attributePaths = "home")
    @Query("select c from Child c where c.home.id in (select h.id from User u join u.viewerHomes h where u.id = :userId) order by c.home.name, c.lastName, c.firstName")
    List<Child> findByViewerAccessOrderByHome(@Param("userId") Long userId);
}
