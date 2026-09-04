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

    /**
     * One home with its organisation already loaded. Load-bearing under {@code open-in-view=false}:
     * {@code Home.organisation} is LAZY, so a plain {@code findById} hands back a proxy that throws
     * {@code LazyInitializationException} the moment anything outside the transaction touches it -
     * which is exactly what the T168(b) activation guard does when it asks whether the organisation
     * is active. Found by the guard's own integration test failing, not by reading the mapping.
     */
    @EntityGraph(attributePaths = "organisation")
    @Query("select h from Home h where h.id = :homeId")
    Optional<Home> findByIdWithOrganisation(@Param("homeId") Long homeId);

    @EntityGraph(attributePaths = "organisation")
    @Query("select h from Home h where h.organisation.id = :organisationId order by h.name")
    List<Home> findByOrganisationIdWithOrganisation(@Param("organisationId") Long organisationId);

    @EntityGraph(attributePaths = "organisation")
    @Query("select h from Home h where h.organisation.supplierOrganisation.id = :supplierOrgId order by h.name")
    List<Home> findByOrganisationSupplierOrganisationId(@Param("supplierOrgId") Long supplierOrgId);
}
