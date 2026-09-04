package ninja.samryecroft.returnhome.tracker.interview;

import java.util.Collection;
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

    /**
     * The platform ADMIN's pending-review queue.
     *
     * <p>These return EVERY request in scope at the given status, including the ones this reviewer
     * must not act on. The self-review separation-of-duties control lives at the endpoint
     * ({@code ReportService.getReviewable}, which refuses a report whose {@code visitor} is the
     * principal); {@code InterviewRequestService.pendingReviewFor} applies the queue's half in
     * Java, on top of one scope query.
     *
     * <p>It used to be a {@code not exists} clause in here, which discarded those rows entirely -
     * and a discarded row is a row the screen cannot talk about. A reviewer whose own two reports
     * were the only ones waiting saw "Nothing awaiting review", which is the same words a genuinely
     * empty queue shows: the two states were indistinguishable, in a tool where an empty list is
     * already ambiguous between "nothing to do" and "the system is not showing me everything".
     * Partitioning in Java lets the screen say which one it is (R-Q13, D-2d-1) while keeping the
     * reviewable set exactly as it was.
     *
     * <p>The N+1 that the query-level test was avoiding is avoided a different way: the
     * request-to-report direction is still unmapped, but {@code findByInterviewRequestIdIn}
     * resolves the whole page's authors in ONE query, which did not exist when this was written.
     */
    @EntityGraph(attributePaths = {"child", "home", "requestedBy", "allocatedVisitor"})
    @Query("""
            select r from InterviewRequest r
            where r.status = :status
            order by r.createdAt desc
            """)
    List<InterviewRequest> findByStatusWithCaseDetails(@Param("status") InterviewStatus status);

    /** The Supplier-scoped form of {@link #findByStatusWithCaseDetails}. */
    @EntityGraph(attributePaths = {"child", "home", "requestedBy", "allocatedVisitor"})
    @Query("""
            select r from InterviewRequest r
            where r.status = :status
              and r.home.organisation.supplierOrganisation.id = :supplierOrgId
            order by r.createdAt desc
            """)
    List<InterviewRequest> findByStatusAndSupplierOrganisationIdWithCaseDetails(
            @Param("status") InterviewStatus status, @Param("supplierOrgId") Long supplierOrgId);

    @EntityGraph(attributePaths = {"child", "home", "requestedBy", "allocatedVisitor"})
    @Query("select r from InterviewRequest r where r.child.id = :childId order by r.createdAt desc")
    List<InterviewRequest> findByChildIdOrderByCreatedAtDesc(@Param("childId") Long childId);

    /** A VIEWER's dashboard/list scope: their specific set of visible homes, not a whole organisation. */
    @EntityGraph(attributePaths = {"child", "home", "requestedBy", "allocatedVisitor"})
    @Query("select r from InterviewRequest r where r.home.id in :homeIds order by r.createdAt desc")
    List<InterviewRequest> findByHomeIdIn(@Param("homeIds") Collection<Long> homeIds);
}
