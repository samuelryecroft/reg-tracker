package ninja.samryecroft.returnhome.tracker.interview;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InterviewRequestRepository extends JpaRepository<InterviewRequest, Long> {

    @EntityGraph(attributePaths = {"child", "home", "requestedBy", "allocatedVisitor"})
    @Query("select r from InterviewRequest r where r.id = :id")
    Optional<InterviewRequest> findDetailedById(@Param("id") Long id);

    @EntityGraph(attributePaths = {"child", "home", "requestedBy", "allocatedVisitor"})
    @Query("select r from InterviewRequest r where r.home.id = :homeId order by r.createdAt desc")
    List<InterviewRequest> findByHomeId(@Param("homeId") Long homeId);

    @EntityGraph(attributePaths = {"child", "home", "requestedBy", "allocatedVisitor"})
    @Query("select r from InterviewRequest r where r.allocatedVisitor.id = :visitorId order by r.scheduledAt asc nulls last")
    List<InterviewRequest> findByAllocatedVisitorId(@Param("visitorId") Long visitorId);

    @EntityGraph(attributePaths = {"child", "home", "requestedBy", "allocatedVisitor"})
    @Query("select r from InterviewRequest r order by r.createdAt desc")
    List<InterviewRequest> findAllDetailed();

    @EntityGraph(attributePaths = {"child", "home", "requestedBy", "allocatedVisitor"})
    @Query("select r from InterviewRequest r where r.home.organisation.id = :careProviderOrgId order by r.createdAt desc")
    List<InterviewRequest> findByHomeOrganisationId(@Param("careProviderOrgId") Long careProviderOrgId);

    @EntityGraph(attributePaths = {"child", "home", "requestedBy", "allocatedVisitor"})
    @Query("select r from InterviewRequest r where r.home.organisation.supplierOrganisation.id = :supplierOrgId order by r.createdAt desc")
    List<InterviewRequest> findByHomeOrganisationSupplierOrganisationId(@Param("supplierOrgId") Long supplierOrgId);

    @EntityGraph(attributePaths = {"child", "home", "requestedBy", "allocatedVisitor"})
    @Query("select r from InterviewRequest r where r.status = :status order by r.createdAt desc")
    List<InterviewRequest> findByStatusOrderByCreatedAtDesc(@Param("status") InterviewStatus status);

    @EntityGraph(attributePaths = {"child", "home", "requestedBy", "allocatedVisitor"})
    @Query("select r from InterviewRequest r where r.status = :status and r.home.organisation.supplierOrganisation.id = :supplierOrgId order by r.createdAt desc")
    List<InterviewRequest> findByStatusAndHomeOrganisationSupplierOrganisationId(
            @Param("status") InterviewStatus status, @Param("supplierOrgId") Long supplierOrgId);

    @EntityGraph(attributePaths = {"child", "home", "requestedBy", "allocatedVisitor"})
    @Query("select r from InterviewRequest r where r.child.id = :childId order by r.createdAt desc")
    List<InterviewRequest> findByChildIdOrderByCreatedAtDesc(@Param("childId") Long childId);
}
