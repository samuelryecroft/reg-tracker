package ninja.samryecroft.returnhome.tracker.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * One persisted audit row. Intentionally has no setters and no public constructor other than the
 * one used at write time: the table is append-only (AUDIT-PLAN.md §B.4, enforced by a DB trigger
 * in V11), so nothing should ever mutate a loaded instance.
 *
 * <p>Foreign keys are held as raw ids rather than {@code @ManyToOne} associations on purpose. An
 * audit row must survive its subject being deleted or anonymised under GDPR, and the row is
 * written from an {@code AFTER_COMMIT} listener where the original persistence context is already
 * gone - raw ids keep both cases simple and avoid a lazy-loading trap.
 */
@Entity
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, updatable = false)
    private AuditEventType eventType;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private LocalDateTime occurredAt;

    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Column(name = "actor_username_at_time", updatable = false)
    private String actorUsernameAtTime;

    @Column(name = "actor_roles_at_time", updatable = false)
    private String actorRolesAtTime;

    @Column(name = "target_type", updatable = false)
    private String targetType;

    @Column(name = "target_id", updatable = false)
    private Long targetId;

    @Column(name = "organisation_id", updatable = false)
    private Long organisationId;

    @Column(name = "home_id", updatable = false)
    private Long homeId;

    @Column(name = "metadata", updatable = false)
    private String metadata;

    protected AuditEvent() {
        // for JPA
    }

    AuditEvent(AuditEventRecord record) {
        this.eventType = record.eventType();
        this.occurredAt = record.occurredAt();
        this.actorId = record.actorId();
        this.actorUsernameAtTime = record.actorUsername();
        this.actorRolesAtTime = record.actorRoles();
        this.targetType = record.targetType();
        this.targetId = record.targetId();
        this.organisationId = record.organisationId();
        this.homeId = record.homeId();
        this.metadata = record.metadata();
    }

    public Long getId() {
        return id;
    }

    public AuditEventType getEventType() {
        return eventType;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getActorUsernameAtTime() {
        return actorUsernameAtTime;
    }

    public String getActorRolesAtTime() {
        return actorRolesAtTime;
    }

    public String getTargetType() {
        return targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public Long getOrganisationId() {
        return organisationId;
    }

    public Long getHomeId() {
        return homeId;
    }

    public String getMetadata() {
        return metadata;
    }
}
