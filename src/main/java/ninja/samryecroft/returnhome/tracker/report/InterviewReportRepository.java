package ninja.samryecroft.returnhome.tracker.report;

import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InterviewReportRepository extends JpaRepository<InterviewReport, Long> {

    @EntityGraph(attributePaths = {"visitor", "reviewedBy"})
    Optional<InterviewReport> findByInterviewRequestId(Long interviewRequestId);
}
