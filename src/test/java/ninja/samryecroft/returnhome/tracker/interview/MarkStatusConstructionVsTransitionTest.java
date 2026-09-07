package ninja.samryecroft.returnhome.tracker.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.child.ChildRepository;
import ninja.samryecroft.returnhome.tracker.home.HomeRepository;
import ninja.samryecroft.returnhome.tracker.organisation.OrganisationAccessService;
import ninja.samryecroft.returnhome.tracker.report.InterviewReportRepository;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * T308 (found while getting the demo profile running for the pilot-guide screenshot pass, not the
 * task itself): {@code markStatus}'s own "construction, not transition" branch -
 * {@code InterviewStatusTransitions} and its {@code CANCELLED}-has-no-in-edges rule are directly
 * tested ({@link InterviewStatusTransitionsTest}), but nothing pinned the branch in {@code
 * InterviewRequestService.markStatus} that decides WHETHER to consult that table - {@code if
 * (request.getId() != null)}. That gap is exactly what let {@code DemoDataSeeder} crash the entire
 * demo profile on boot: its CANCELLED row was built through a helper that saves internally before
 * returning, so by the time {@code markStatus(row, CANCELLED)} ran the row already had an id and a
 * persisted REQUESTED status, and the (correctly refused) transition threw - nothing here or in
 * {@code DemoDataSeederTest} (which is deliberately mock-only and never runs the real seed) would
 * have caught it before someone next ran {@code ./scripts/demo-up.sh}.
 */
class MarkStatusConstructionVsTransitionTest {

    private final InterviewRequestRepository interviewRequestRepository = mock(InterviewRequestRepository.class);
    private final InterviewRequestService service = new InterviewRequestService(interviewRequestRepository,
            mock(ChildRepository.class), mock(UserRepository.class), mock(HomeRepository.class),
            mock(OrganisationAccessService.class), mock(AuditEventPublisher.class),
            mock(InterviewReportRepository.class));

    /**
     * The sanctioned path {@code DemoDataSeeder}'s CANCELLED row (and any other fixture that builds
     * a row in a status the table has no in-edge for) relies on: a row that has never been saved has
     * no history to violate, so {@code InterviewStatusTransitions} is never consulted at all - not
     * even to check whether CANCELLED, which has no legal in-edges from anything, is reachable.
     */
    @Test
    void aNeverPersistedRowMayBeGivenAnyInitialStatusWithoutConsultingTheTransitionTable() {
        InterviewRequest neverPersisted = new InterviewRequest();
        assertThat(neverPersisted.getId()).isNull();

        service.markStatus(neverPersisted, InterviewStatus.CANCELLED);

        assertThat(neverPersisted.getStatus()).isEqualTo(InterviewStatus.CANCELLED);
        verify(interviewRequestRepository).save(neverPersisted);
    }

    /**
     * The exact failure this test would have caught: a row that already has an id (persisted, at
     * REQUESTED - the field's own default) is a real transition, not a construction, and
     * REQUESTED -> CANCELLED is refused because CANCELLED has no in-edges (T146) - the same
     * IllegalStateException that crashed DemoDataSeeder.run() on boot before this was fixed.
     */
    @Test
    void anAlreadyPersistedRowRefusesAnIllegalTransitionAndNeverReachesSave() {
        InterviewRequest persisted = new InterviewRequest();
        ReflectionTestUtils.setField(persisted, "id", 1L);
        assertThat(persisted.getStatus()).as("the field's own default").isEqualTo(InterviewStatus.REQUESTED);

        assertThatIllegalStateException()
                .isThrownBy(() -> service.markStatus(persisted, InterviewStatus.CANCELLED))
                .withMessageContaining("REQUESTED").withMessageContaining("CANCELLED");

        verifyNoInteractions(interviewRequestRepository);
    }

    /** The positive case on the same (persisted) branch: a legal transition still goes through. */
    @Test
    void anAlreadyPersistedRowAllowsALegalTransition() {
        InterviewRequest persisted = new InterviewRequest();
        ReflectionTestUtils.setField(persisted, "id", 1L);

        service.markStatus(persisted, InterviewStatus.ALLOCATED);

        assertThat(persisted.getStatus()).isEqualTo(InterviewStatus.ALLOCATED);
        verify(interviewRequestRepository).save(persisted);
    }
}
