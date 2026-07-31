package ninja.samryecroft.returnhome.tracker.organisation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrganisationRepository extends JpaRepository<Organisation, Long> {

    List<Organisation> findByTypeOrderByName(OrgType type);

    @EntityGraph(attributePaths = "supplierOrganisation")
    @Query("select o from Organisation o order by o.type, o.name")
    List<Organisation> findAllWithSupplier();

    @EntityGraph(attributePaths = "supplierOrganisation")
    @Query("select o from Organisation o where o.id = :id")
    Optional<Organisation> findDetailedById(@Param("id") Long id);

    @Query("select o.supplierOrganisation.id from Organisation o where o.id = :careProviderOrgId")
    Optional<Long> findSupplierOrganisationIdByCareProviderId(@Param("careProviderOrgId") Long careProviderOrgId);
}
