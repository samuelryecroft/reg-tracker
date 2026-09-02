package ninja.samryecroft.returnhome.tracker.report;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InterviewReportRepository extends JpaRepository<InterviewReport, Long> {

    @EntityGraph(attributePaths = {"visitor", "reviewedBy"})
    Optional<InterviewReport> findByInterviewRequestId(Long interviewRequestId);

    /**
     * The roadmap 2.3 dashboard scope, mirroring {@code InterviewRequestRepository}'s org-scoping
     * finders exactly - filtered further by period and status in Java (the same "fetch scope, then
     * compute" approach the request lists already use), since the period breakdown needs the same
     * rows grouped several different ways.
     */
    @EntityGraph(attributePaths = {"visitor", "reviewedBy", "interviewRequest", "interviewRequest.child", "interviewRequest.home"})
    @Query("select r from InterviewReport r where r.interviewRequest.home.organisation.id = :careProviderOrgId")
    List<InterviewReport> findByHomeOrganisationId(@Param("careProviderOrgId") Long careProviderOrgId);

    @EntityGraph(attributePaths = {"visitor", "reviewedBy", "interviewRequest", "interviewRequest.child", "interviewRequest.home"})
    @Query("select r from InterviewReport r where r.interviewRequest.home.organisation.supplierOrganisation.id = :supplierOrgId")
    List<InterviewReport> findByHomeOrganisationSupplierOrganisationId(@Param("supplierOrgId") Long supplierOrgId);

    /** A VIEWER's scope: their specific set of visible homes. */
    @EntityGraph(attributePaths = {"visitor", "reviewedBy", "interviewRequest", "interviewRequest.child", "interviewRequest.home"})
    @Query("select r from InterviewReport r where r.interviewRequest.home.id in :homeIds")
    List<InterviewReport> findByHomeIdIn(@Param("homeIds") Collection<Long> homeIds);
}
