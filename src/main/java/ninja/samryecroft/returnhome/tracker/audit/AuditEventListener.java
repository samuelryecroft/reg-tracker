package ninja.samryecroft.returnhome.tracker.audit;

import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The single place an {@link AuditEventRecord} becomes a row (AUDIT-PLAN.md §B.2).
 *
 * <p>{@code AFTER_COMMIT} so a business action that ultimately rolled back is never recorded as
 * having happened. {@code fallbackExecution = true} because three phase-1 events - login
 * success/failure, access-denied and docx download - are published with no transaction in scope at
 * all; without it those would be silently dropped. {@code REQUIRES_NEW} because by the time this
 * runs the publishing transaction has already completed, so the insert needs one of its own.
 */
@Component
public class AuditEventListener {

    private static final Logger log = LoggerFactory.getLogger(AuditEventListener.class);

    /**
     * Rows that could not be written, by event type (T379). The catch below is deliberate and
     * stays; what changes is that it is now visible somewhere other than a log line, because the
     * question "did we lose audit rows this month" needs an answer that does not involve reading
     * logs. Metrics reach Application Insights through the agent, where a threshold can page.
     */
    static final String LOST_ROWS_METRIC = "audit.events.lost";

    private final AuditEventRepository auditEventRepository;
    private final MeterRegistry meterRegistry;

    public AuditEventListener(AuditEventRepository auditEventRepository, MeterRegistry meterRegistry) {
        this.auditEventRepository = auditEventRepository;
        this.meterRegistry = meterRegistry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(AuditEventRecord record) {
        try {
            auditEventRepository.save(new AuditEvent(record));
        } catch (RuntimeException ex) {
            // The business action has already committed; throwing here cannot undo it and would
            // only surface a spurious error for work that actually succeeded. Losing an audit row
            // is bad, so this is loud - but it must not break a safeguarding workflow.
            log.error("Failed to persist audit event {} for actor {} on {} {}", record.eventType(),
                    record.actorIdentifier(), record.targetType(), record.targetId(), ex);
            meterRegistry.counter(LOST_ROWS_METRIC, "eventType", record.eventType().name()).increment();
        }
    }
}
