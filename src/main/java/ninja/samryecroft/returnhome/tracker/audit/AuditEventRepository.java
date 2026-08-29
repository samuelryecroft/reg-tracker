package ninja.samryecroft.returnhome.tracker.audit;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Read/append only by design (AUDIT-PLAN.md §B.4): this interface deliberately exposes no update
 * or delete method, and {@code audit_events} carries a DB trigger rejecting both. Inherited
 * {@code JpaRepository} mutators exist on the type but are never called by application code, and
 * would be refused by the database if they were.
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByEventTypeOrderByOccurredAtDesc(AuditEventType eventType);

    List<AuditEvent> findByTargetTypeAndTargetIdOrderByOccurredAtDesc(String targetType, Long targetId);

    @Query("select a from AuditEvent a where a.organisationId = :organisationId order by a.occurredAt desc")
    List<AuditEvent> findByOrganisationId(@Param("organisationId") Long organisationId);

    @Query("select a from AuditEvent a where a.actorId = :actorId order by a.occurredAt desc")
    List<AuditEvent> findByActorId(@Param("actorId") Long actorId);
}
