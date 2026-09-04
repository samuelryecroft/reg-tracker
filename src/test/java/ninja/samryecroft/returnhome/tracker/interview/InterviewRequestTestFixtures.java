package ninja.samryecroft.returnhome.tracker.interview;

/**
 * Builds an {@link InterviewRequest} already in a given state, for tests that need the world to
 * contain such a row rather than to walk the workflow that produces one.
 *
 * <p>Lives in this package because {@code InterviewRequest.setStatus} is package-private - T145 made
 * {@code InterviewRequestService.markStatus} the only writer so the transition table cannot be
 * reached past. That narrowing is deliberate, and so is this seam: a fixture asserts "the world
 * contains this row", not "this transition happened", so it is a construction rather than a
 * transition and the table has no business governing it. The alternative - widening the table until
 * every fixture is reachable through it - would put edges in production code that exist only to
 * satisfy test setup.
 */
public final class InterviewRequestTestFixtures {

    private InterviewRequestTestFixtures() {
    }

    public static InterviewRequest requestAt(InterviewStatus status) {
        InterviewRequest request = new InterviewRequest();
        request.setStatus(status);
        return request;
    }
}
