package ninja.samryecroft.returnhome.tracker.home;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface HomeRepository extends JpaRepository<Home, Long> {

    @Query("select h.organisation.supplierOrganisation.id from Home h where h.id = :homeId")
    Optional<Long> findSupplierOrganisationIdByHomeId(@Param("homeId") Long homeId);

    @EntityGraph(attributePaths = "organisation")
    @Query("select h from Home h order by h.name")
    List<Home> findAllWithOrganisation();

    @EntityGraph(attributePaths = "organisation")
    @Query("select h from Home h where h.organisation.id = :organisationId order by h.name")
    List<Home> findByOrganisationIdWithOrganisation(@Param("organisationId") Long organisationId);

    @EntityGraph(attributePaths = "organisation")
    @Query("select h from Home h where h.organisation.supplierOrganisation.id = :supplierOrgId order by h.name")
    List<Home> findByOrganisationSupplierOrganisationId(@Param("supplierOrgId") Long supplierOrgId);
}
