package ninja.samryecroft.returnhome.tracker.child;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * T138 1c: the reveal flag has to behave exactly one way - armed by one request, read by exactly
 * one later request, gone after that regardless of who asked first. No Spring context needed: this
 * is plain servlet API plumbing, tested against a real (mock) session rather than asserted by
 * reading the source.
 */
class NameRevealServiceTest {

    private final NameRevealService service = new NameRevealService();

    @Test
    void aFreshRequestWithNoSessionIsNotRevealed() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThat(service.isRevealed(request)).isFalse();
    }

    @Test
    void armingThenReadingOnTheSameSessionReturnsTrueForTheNextRequest() {
        MockHttpServletRequest arming = new MockHttpServletRequest();
        service.arm(arming);

        // A later, DIFFERENT request object sharing the same underlying session - exactly what
        // happens across the reveal POST's redirect to a fresh GET.
        MockHttpServletRequest next = new MockHttpServletRequest();
        next.setSession(arming.getSession());

        assertThat(service.isRevealed(next)).isTrue();
    }

    @Test
    void theFlagIsConsumedByTheFirstRequestThatReadsIt() {
        MockHttpServletRequest arming = new MockHttpServletRequest();
        service.arm(arming);

        MockHttpServletRequest firstRender = new MockHttpServletRequest();
        firstRender.setSession(arming.getSession());
        assertThat(service.isRevealed(firstRender)).isTrue();

        // A subsequent, unrelated request against the same session - the next page navigated to -
        // must come back masked again. This is the whole point of the control (spec §2.5 / Kevin's
        // review): one click reveals one page, not the rest of the session.
        MockHttpServletRequest secondRender = new MockHttpServletRequest();
        secondRender.setSession(arming.getSession());
        assertThat(service.isRevealed(secondRender)).isFalse();
    }

    @Test
    void readingTwiceWithinTheSameRequestReturnsTheSameAnswerBothTimes() {
        // GlobalControllerAdvice's model attribute and a controller's own handler method can both
        // ask, in either order, within one request - the per-request cache must make that safe
        // rather than the second caller seeing the flag already consumed by the first.
        MockHttpServletRequest arming = new MockHttpServletRequest();
        service.arm(arming);

        MockHttpServletRequest render = new MockHttpServletRequest();
        render.setSession(arming.getSession());

        assertThat(service.isRevealed(render)).isTrue();
        assertThat(service.isRevealed(render)).isTrue();
    }
}
