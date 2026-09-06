package ninja.samryecroft.returnhome.tracker.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import ninja.samryecroft.returnhome.tracker.user.Role;
import ninja.samryecroft.returnhome.tracker.user.User;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * T283: the unit of an access record is the EPISODE, not the HTTP request.
 *
 * <p>Every {@code auditViewOpened} call site is a GET handler, so a refresh re-emitted. Twenty rows
 * do not record twenty accesses - <strong>they record one access and nineteen artefacts of HTTP.</strong>
 * This is not dropping an event; it is declining to over-count one, which is why it does not
 * contradict T177: multiple draft saves are multiple ACTS, multiple refreshes are one act.
 */
@ExtendWith(MockitoExtension.class)
class AccessEpisodeTest {

    private static final long ACTOR_ID = 42L;
    private static final long CHILD_ID = 7L;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuditEventRepository auditEventRepository;

    private AuditEventPublisher publisher() {
        return new AuditEventPublisher(applicationEventPublisher, userRepository, auditEventRepository);
    }

    @Test
    void aRefreshOfTheSameRecordWritesNothing() {
        previousEventOfActor(viewOf("Child", CHILD_ID, minutesAgo(2)));

        publisher().auditViewOpened("Child", CHILD_ID, 1L, null, actor());

        verify(applicationEventPublisher, never()).publishEvent(any(AuditEventRecord.class));
    }

    /**
     * R1's whole content, and the reason a time window alone was rejected. The actor did something
     * else in between - a different child - so the decision to open this record was made AGAIN, and
     * the second view is a second access however close together they are.
     */
    @Test
    void anythingElseTheActorDidEndsTheEpisode() {
        previousEventOfActor(viewOf("Child", 999L, minutesAgo(2)));

        publisher().auditViewOpened("Child", CHILD_ID, 1L, null, actor());

        assertThat(published().eventType()).isEqualTo(AuditEventType.AUDIT_VIEW_OPENED);
    }

    /** Including an event that is not a view at all: an edit, an export, anything they did. */
    @Test
    void anEditBetweenTwoViewsEndsTheEpisodeToo() {
        AuditEvent edit = mock(AuditEvent.class);
        when(edit.getEventType()).thenReturn(AuditEventType.USER_UPDATED);
        previousEventOfActor(edit);

        publisher().auditViewOpened("Child", CHILD_ID, 1L, null, actor());

        assertThat(published().eventType()).isEqualTo(AuditEventType.AUDIT_VIEW_OPENED);
    }

    /** R2, the backstop: an episode never spans more than the cap, however quiet the actor was. */
    @Test
    void returningToTheSameRecordAfterTheCapIsRecordedAgain() {
        previousEventOfActor(viewOf("Child", CHILD_ID, minutesAgo(31)));

        publisher().auditViewOpened("Child", CHILD_ID, 1L, null, actor());

        assertThat(published().eventType()).isEqualTo(AuditEventType.AUDIT_VIEW_OPENED);
    }

    /**
     * The same id under a different target type is a different record. Cheap to get wrong, because
     * the rule lives in the publisher and every call site passes its own type string.
     */
    @Test
    void theSameIdOnADifferentKindOfRecordIsNotTheSameAccess() {
        previousEventOfActor(viewOf("Child", CHILD_ID, minutesAgo(2)));

        publisher().auditViewOpened("InterviewRequest", CHILD_ID, 1L, null, actor());

        assertThat(published().eventType()).isEqualTo(AuditEventType.AUDIT_VIEW_OPENED);
    }

    /**
     * R3. THE CLAUSE THAT MAKES SUPPRESSION SAFE: a suppressed event is invisible, so the trail
     * cannot tell "suppressed" from "never happened" unless the surviving row says what it is.
     *
     * <p>And the WINDOW is in the row, not only in the code. If the policy becomes 5 minutes next
     * year, rows written under the old rule must still say 30 - a window living only in the code
     * silently reinterprets every historical row the day someone edits the constant.
     */
    @Test
    void theSurvivingRowSaysItIsAnEpisodeAndSaysTheWindowItWasWrittenUnder() {
        previousEventOfActor(null);

        publisher().auditViewOpened("Child", CHILD_ID, 1L, null, actor());

        assertThat(published().metadata())
                .contains("deduplication=access-episode")
                .contains("deduplicationWindowMinutes=30");
    }

    /**
     * An actor we cannot identify is never suppressed. One of three deliberate fail-open cases: on
     * an access trail an extra row is noise, and a missing one is a gap nobody can see.
     */
    @Test
    void anUnidentifiableActorIsNeverSuppressed() {
        publisher().auditViewOpened("Child", CHILD_ID, 1L, null, actorWithNoId());

        assertThat(published().eventType()).isEqualTo(AuditEventType.AUDIT_VIEW_OPENED);
    }

    // --- fixtures ---

    private void previousEventOfActor(AuditEvent previous) {
        when(auditEventRepository.findFirstByActorIdOrderByOccurredAtDesc(ACTOR_ID))
                .thenReturn(Optional.ofNullable(previous));
    }

    private AuditEventRecord published() {
        ArgumentCaptor<AuditEventRecord> captor = ArgumentCaptor.forClass(AuditEventRecord.class);
        verify(applicationEventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }

    /**
     * A complete previous event. The target and time stubs are {@code lenient} for one reason, not
     * as a blanket: <strong>which of these fields the episode test reads depends on where its filter
     * chain short-circuits</strong> - a different target type means the id and the timestamp are
     * never asked for. Under strict stubs each case would have to stub exactly the fields the
     * current implementation happens to reach, which would pin its EVALUATION ORDER into the
     * fixtures and make an internal reordering break tests that are about behaviour.
     * {@code getEventType} stays strict because every path reads it.
     */
    private static AuditEvent viewOf(String targetType, Long targetId, LocalDateTime occurredAt) {
        AuditEvent event = mock(AuditEvent.class);
        when(event.getEventType()).thenReturn(AuditEventType.AUDIT_VIEW_OPENED);
        org.mockito.Mockito.lenient().when(event.getTargetType()).thenReturn(targetType);
        org.mockito.Mockito.lenient().when(event.getTargetId()).thenReturn(targetId);
        org.mockito.Mockito.lenient().when(event.getOccurredAt()).thenReturn(occurredAt);
        return event;
    }

    private static LocalDateTime minutesAgo(int minutes) {
        return LocalDateTime.now().minusMinutes(minutes);
    }

    private static AppUserPrincipal actor() {
        return principal(ACTOR_ID);
    }

    private static AppUserPrincipal actorWithNoId() {
        return principal(null);
    }

    private static AppUserPrincipal principal(Long id) {
        User user = new User();
        if (id != null) {
            ReflectionTestUtils.setField(user, "id", id);
        }
        user.setUsername("viewer");
        user.setRoles(Set.of(Role.VIEWER));
        return new AppUserPrincipal(user);
    }
}
