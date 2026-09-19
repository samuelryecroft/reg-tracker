package ninja.samryecroft.returnhome.tracker.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** T379: a row the listener could not write is still swallowed, and is now counted. */
class AuditEventListenerCountsLostRowsTest {

    @Test
    void aFailedWriteIsSwallowedAndCountedByEventType() {
        AuditEventRepository repository = mock(AuditEventRepository.class);
        when(repository.save(any(AuditEvent.class))).thenThrow(new DataAccessResourceFailureException("down"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuditEventListener listener = new AuditEventListener(repository, registry);
        AuditEventRecord record = new AuditEventRecord(AuditEventType.LOGIN_SUCCESS, LocalDateTime.now(),
                1L, "someone", "VIEWER", "User", 1L, null, null, null);

        assertThatCode(() -> listener.on(record))
                .as("the business action has committed; this must not surface as its failure")
                .doesNotThrowAnyException();

        assertThat(registry.counter(AuditEventListener.LOST_ROWS_METRIC, "eventType", "LOGIN_SUCCESS").count())
                .isEqualTo(1.0);
    }
}
