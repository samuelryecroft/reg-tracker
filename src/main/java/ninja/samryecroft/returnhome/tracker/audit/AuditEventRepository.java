package ninja.samryecroft.returnhome.tracker.audit;

import java.util.Collection;
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

    /**
     * Batched form of the finder above - a child's case history spans several interview requests
     * (and each one's report), so building it one id at a time would be N+1. Same target type across
     * every id in the collection; callers combine two calls (one per target type) when they need both.
     */
    List<AuditEvent> findByTargetTypeAndTargetIdInOrderByOccurredAtDesc(String targetType, Collection<Long> targetIds);

    @Query("select a from AuditEvent a where a.organisationId = :organisationId order by a.occurredAt desc")
    List<AuditEvent> findByOrganisationId(@Param("organisationId") Long organisationId);

    /** A Supplier's case-activity feed spans every Care Provider org it serves - a Supplier org id alone matches none of them. */
    @Query("select a from AuditEvent a where a.organisationId in :organisationIds order by a.occurredAt desc")
    List<AuditEvent> findByOrganisationIdIn(@Param("organisationIds") Collection<Long> organisationIds);

    @Query("select a from AuditEvent a where a.actorId = :actorId order by a.occurredAt desc")
    List<AuditEvent> findByActorId(@Param("actorId") Long actorId);
}
