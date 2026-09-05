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

    /**
     * One type's organisations WITH their supplier fetched, for a template that names it.
     *
     * <p>Separate from {@link #findByTypeOrderByName} rather than adding a graph to it:
     * {@code KeyWarmupRunner} and the lifecycle paths use that finder and never touch the
     * supplier, and widening it would put a join on every one of them to serve one screen.
     *
     * <p>The reason this exists at all is {@code spring.jpa.open-in-view=false}: a lazy
     * association read from a template throws {@code LazyInitializationException} at render time
     * rather than quietly issuing a query. That is the right setting, and its cost is that
     * <b>adding a property to a template is a question about the query behind it</b> - which the
     * compiler cannot ask, so it surfaces as a 500 in CI.
     */
    @EntityGraph(attributePaths = "supplierOrganisation")
    @Query("select o from Organisation o where o.type = :type order by o.name")
    List<Organisation> findByTypeWithSupplier(@Param("type") OrgType type);

    @EntityGraph(attributePaths = "supplierOrganisation")
    @Query("select o from Organisation o where o.id = :id")
    Optional<Organisation> findDetailedById(@Param("id") Long id);

    @Query("select o.supplierOrganisation.id from Organisation o where o.id = :careProviderOrgId")
    Optional<Long> findSupplierOrganisationIdByCareProviderId(@Param("careProviderOrgId") Long careProviderOrgId);

    /** The Care Provider organisations a given Supplier serves - the dashboard's "care provider" switcher (roadmap 2.3). */
    List<Organisation> findBySupplierOrganisationIdOrderByName(Long supplierOrganisationId);
}
