package ninja.samryecroft.returnhome.tracker.audit;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The immutable application event published by the service layer and consumed by
 * {@link AuditEventListener}.
 *
 * <p>Every field is a plain value resolved <em>eagerly, at publish time</em> - ids and strings,
 * never entity references. The listener runs {@code AFTER_COMMIT}, by which point the publishing
 * transaction's persistence context is closed, so holding an entity here would risk a
 * {@code LazyInitializationException} at write time.
 */
public record AuditEventRecord(
        AuditEventType eventType,
        LocalDateTime occurredAt,
        Long actorId,
        String actorIdentifier,
        String actorRoles,
        String targetType,
        Long targetId,
        Long organisationId,
        Long homeId,
        String metadata) {

    static Builder of(AuditEventType eventType) {
        return new Builder(eventType);
    }

    /**
     * Renders audit metadata as a compact {@code k=v; k=v} string.
     *
     * <p>Only ever holds status transitions, reference ids and flags - never report or interview
     * content. AUDIT-PLAN.md §B.5 makes that a hard rule: the audit trail proves who did what
     * when, it is not a second copy of what a child said.
     */
    static final class Builder {

        private final AuditEventType eventType;
        private final Map<String, String> metadata = new LinkedHashMap<>();
        private Long actorId;
        private String actorIdentifier;
        private String actorRoles;
        private String targetType;
        private Long targetId;
        private Long organisationId;
        private Long homeId;

        private Builder(AuditEventType eventType) {
            this.eventType = eventType;
        }

        /**
         * @param identifier what identified the actor AT THIS MOMENT - an email address for
         *        everyone since T344, and a name for the break-glass account, which has no address.
         *        It is a SNAPSHOT: it must not be re-derived when the row is read, because the point
         *        of the column is to survive the account's address changing afterwards.
         */
        Builder actor(Long id, String identifier, String roles) {
            this.actorId = id;
            this.actorIdentifier = identifier;
            this.actorRoles = roles;
            return this;
        }

        Builder target(String type, Long id) {
            this.targetType = type;
            this.targetId = id;
            return this;
        }

        Builder scope(Long organisationId, Long homeId) {
            this.organisationId = organisationId;
            this.homeId = homeId;
            return this;
        }

        Builder meta(String key, Object value) {
            metadata.put(key, value == null ? "none" : value.toString());
            return this;
        }

        AuditEventRecord build() {
            String rendered = metadata.isEmpty() ? null : metadata.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining("; "));
            return new AuditEventRecord(eventType, LocalDateTime.now(), actorId, actorIdentifier,
                    actorRoles, targetType, targetId, organisationId, homeId, truncate(rendered));
        }

        /** The column is VARCHAR(1000); an over-long value must not cost us the whole audit row. */
        private String truncate(String value) {
            if (value == null || value.length() <= 1000) {
                return value;
            }
            return value.substring(0, 997) + "...";
        }
    }
}
