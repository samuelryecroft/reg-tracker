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
     * <p>The {@code not exists} clause is the queue's half of the self-review separation-of-duties
     * control that {@code ReportService.getReviewable} enforces at the endpoint. That control tests
     * the report's <em>author</em> ({@code InterviewReport.visitor} - who produced the artefact);
     * the queue used to test only the request's <em>allocated visitor</em> (who was assigned it).
     * Those are usually the same person, but not always - a platform admin submitting on a visitor's
     * behalf becomes the author while the allocation stays with the visitor - and where they differ
     * the queue was offering an action the endpoint then refused (T145/T143).
     *
     * <p>Query-level rather than a Java filter deliberately: the request-to-report direction is not
     * mapped ({@code InterviewReport} owns the one-to-one), so filtering in Java would need a report
     * lookup per row - an N+1 across the whole queue to answer one question per request.
     */
    @EntityGraph(attributePaths = {"child", "home", "requestedBy", "allocatedVisitor"})
    @Query("""
            select r from InterviewRequest r
            where r.status = :status
              and not exists (select 1 from InterviewReport rep
                              where rep.interviewRequest = r and rep.visitor.id = :authorToExcludeId)
            order by r.createdAt desc
            """)
    List<InterviewRequest> findByStatusExcludingReportsAuthoredBy(
            @Param("status") InterviewStatus status, @Param("authorToExcludeId") Long authorToExcludeId);

    /** A Reviewer's pending-review queue, scoped to their Supplier org. Excludes their own authored
     * reports for the reason recorded on {@link #findByStatusExcludingReportsAuthoredBy}. */
    @EntityGraph(attributePaths = {"child", "home", "requestedBy", "allocatedVisitor"})
    @Query("""
            select r from InterviewRequest r
            where r.status = :status
              and r.home.organisation.supplierOrganisation.id = :supplierOrgId
              and not exists (select 1 from InterviewReport rep
                              where rep.interviewRequest = r and rep.visitor.id = :authorToExcludeId)
            order by r.createdAt desc
            """)
    List<InterviewRequest> findByStatusAndHomeOrganisationSupplierOrganisationIdExcludingReportsAuthoredBy(
            @Param("status") InterviewStatus status, @Param("supplierOrgId") Long supplierOrgId,
            @Param("authorToExcludeId") Long authorToExcludeId);

    @EntityGraph(attributePaths = {"child", "home", "requestedBy", "allocatedVisitor"})
    @Query("select r from InterviewRequest r where r.child.id = :childId order by r.createdAt desc")
    List<InterviewRequest> findByChildIdOrderByCreatedAtDesc(@Param("childId") Long childId);

    /** A VIEWER's dashboard/list scope: their specific set of visible homes, not a whole organisation. */
    @EntityGraph(attributePaths = {"child", "home", "requestedBy", "allocatedVisitor"})
    @Query("select r from InterviewRequest r where r.home.id in :homeIds order by r.createdAt desc")
    List<InterviewRequest> findByHomeIdIn(@Param("homeIds") Collection<Long> homeIds);
}
