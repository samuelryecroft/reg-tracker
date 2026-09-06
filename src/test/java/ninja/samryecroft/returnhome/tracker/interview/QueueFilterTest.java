package ninja.samryecroft.returnhome.tracker.interview;

import static ninja.samryecroft.returnhome.tracker.interview.InterviewRequestTestFixtures.requestAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import ninja.samryecroft.returnhome.tracker.child.NameRevealService;
import ninja.samryecroft.returnhome.tracker.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ConcurrentModel;
import ninja.samryecroft.returnhome.tracker.child.Child;
import org.springframework.ui.Model;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The coordinator queue's filter chips (screen 2a) and the {@code ?filter=} links that land on
 * them, guarded at the SHAPE of the two ways they can break - both of which fail OPEN.
 *
 * <p>Every check here is written over {@link QueueFilter#values()} rather than over a list of the
 * six filters that exist today, so a filter added later is covered the moment it is declared. A
 * guard scoped to the instances it was written from is a guard whose scope nobody chose.
 */
@ExtendWith(MockitoExtension.class)
class QueueFilterTest {

    /**
     * Real "now": the controller calls {@link LocalDateTime#now()} itself, so the fixture's
     * offsets have to be relative to the same clock. They are all far enough from a boundary
     * (80h past, 60h in, no clock at all) that the seconds between two calls cannot move one.
     */
    private static final LocalDateTime NOW = LocalDateTime.now();

    /**
     * The fail-open shape: a chip's count and the list that chip's own link produces are two
     * computations of one number, and when they disagree the screen looks entirely ordinary -
     * "Needs allocating 3" above a list of five reads as a perfectly normal queue. Nothing throws,
     * nothing renders wrong, and the number a coordinator acts on is the wrong one.
     *
     * <p>So the assertion goes through the real controller method twice: once for the chip row,
     * once for the narrowed list, exactly as two requests from a browser would. A unit test on
     * {@code chipsFor} alone would be true by construction and would not survive somebody changing
     * how the controller narrows.
     */
    @Test
    void everyChipsCountIsTheLengthOfTheListItsOwnLinkOpens() {
        List<InterviewRequest> world = aWorldWithEveryFilterNonEmpty();
        CoordinatorController controller = controllerSeeing(world);

        Model chipRow = new ConcurrentModel();
        controller.list(null, null, null, chipRow);
        @SuppressWarnings("unchecked")
        List<QueueFilterChip> chips = (List<QueueFilterChip>) chipRow.getAttribute("filterChips");

        for (QueueFilterChip chip : chips) {
            Model landed = new ConcurrentModel();
            controller.list(null, null, chip.key().isEmpty() ? null : chip.key(), landed);
            @SuppressWarnings("unchecked")
            List<InterviewRequest> shown = (List<InterviewRequest>) landed.getAttribute("requests");

            assertThat(shown)
                    .as("chip \"%s\" claims %d request(s); the list its own link opens has %d",
                            chip.label(), chip.count(), shown.size())
                    .hasSize(chip.count());
        }
    }

    /**
     * D-2a-6: a filter that is not on the menu still has to show a chip when it is applied, or a
     * dashboard tile deep-linking to it lands on a row with nothing selected and Oscar's "the list
     * it opens visibly matches the tile" contract breaks with nothing visible to say it has.
     */
    @Test
    void anAppliedOffMenuFilterStillGetsAChipAndItIsTheSelectedOne() {
        List<InterviewRequest> world = aWorldWithEveryFilterNonEmpty();
        CoordinatorController controller = controllerSeeing(world);

        for (QueueFilter filter : QueueFilter.values()) {
            Model model = new ConcurrentModel();
            controller.list(null, null, filter.key(), model);
            @SuppressWarnings("unchecked")
            List<QueueFilterChip> chips = (List<QueueFilterChip>) model.getAttribute("filterChips");

            assertThat(chips).as("%s applied, but no chip is selected", filter)
                    .anyMatch(c -> c.selected() && c.key().equals(filter.key()));
            assertThat(chips).as("exactly one chip may be selected at a time (%s)", filter)
                    .filteredOn(QueueFilterChip::selected).hasSize(1);
        }
    }

    /**
     * The menu chips PARTITION the statuses: every {@link InterviewStatus} falls under exactly one
     * of them. This is a fail-open guard rather than a tidiness one - a status covered by no stage
     * is a request that swells the "All" count while appearing under no chip a coordinator would
     * click, which looks like an ordinary queue and hides a case. A status covered by two makes the
     * chip counts sum to more than the queue holds, for no visible reason.
     *
     * <p>Written over {@code InterviewStatus.values()}, so a status added later is covered the
     * moment it is declared rather than the moment somebody remembers this test exists.
     */
    @Test
    void theMenuStagesCoverEveryInterviewStatusExactlyOnce() {
        LocalDateTime now = LocalDateTime.now();
        for (InterviewStatus status : InterviewStatus.values()) {
            InterviewRequest request = requestAt(status);
            request.setReturnedAt(now.minusHours(1));
            List<QueueFilter> covering = Stream.of(QueueFilter.values())
                    .filter(QueueFilter::inMenu)
                    .filter(f -> f.matches(request, now))
                    .toList();

            assertThat(covering)
                    .as("%s is in %s menu stage(s); it must be in exactly one, or it is either "
                            + "unreachable from the chip row or double-counted by it", status, covering.size())
                    .hasSize(1);
        }
    }

    /** A chip that can never be anything but zero is chrome, so the world above must exercise each. */
    @Test
    void theWorldUnderThatCheckActuallyPopulatesEveryFilter() {
        List<InterviewRequest> world = aWorldWithEveryFilterNonEmpty();
        for (QueueFilter filter : QueueFilter.values()) {
            assertThat(world.stream().filter(r -> filter.matches(r, NOW)).count())
                    .as("no request in the fixture world matches %s, so the count check cannot see it", filter)
                    .isPositive();
        }
    }

    /**
     * The other fail-open shape. The 2c dashboard's "needs attention" tiles link into this queue,
     * and Oscar's dashboard brief pins the contract they keep: "the list it opens visibly matches
     * the tile". A tile whose href names a key the controller no longer knows does not error - it
     * lands on a queue showing EVERYTHING, under a heading promising a subset.
     *
     * <p>The fix was to derive those hrefs from {@link QueueFilter#href()} so the duplication does
     * not exist; this keeps it that way by looking for the shape (any hand-written
     * {@code ?filter=...} aimed at the queue) in main sources, not for the six literals that were
     * there when it was written.
     */
    @Test
    void noSourceHandWritesAQueueFilterQueryString() throws IOException {
        Pattern handWritten = Pattern.compile(Pattern.quote(QueueFilter.QUEUE_PATH) + "\\?filter=(\\w+)");
        List<String> violations = new ArrayList<>();

        for (Path dir : List.of(Path.of("src/main/java"), Path.of("src/main/resources/templates"))) {
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    if (file.endsWith("QueueFilter.java")) {
                        continue;
                    }
                    Matcher m = handWritten.matcher(Files.readString(file, StandardCharsets.UTF_8));
                    while (m.find()) {
                        violations.add(file + ": " + m.group());
                    }
                }
            }
        }

        assertThat(violations)
                .as("build the link from QueueFilter#href() - a hand-written key that drifts from the "
                        + "enum lands on the whole queue under a heading promising a subset, silently")
                .isEmpty();
    }

    /**
     * An unrecognised key degrades to ABSENCE - no narrowing, and no claim that one was applied.
     * The old behaviour narrowed nothing but still put the raw parameter into the model, so the
     * page announced a filtered view over a list that was in fact everything: the misleading half
     * of a stale link rather than the honest half.
     */
    @Test
    void anUnrecognisedFilterNarrowsNothingAndClaimsNothing() {
        List<InterviewRequest> world = aWorldWithEveryFilterNonEmpty();
        Model model = new ConcurrentModel();
        controllerSeeing(world).list(null, null, "overdu", model);

        assertThat((List<?>) model.getAttribute("requests")).hasSameSizeAs(world);
        assertThat(model.getAttribute("filter")).isNull();
    }

    /** The keys are the app's URL surface: the dashboard links and any bookmark carry them. */
    @Test
    void everyKeyRoundTripsAndTheKeysAreDistinct() {
        for (QueueFilter filter : QueueFilter.values()) {
            assertThat(QueueFilter.byKey(filter.key())).contains(filter);
            assertThat(filter.href()).isEqualTo(QueueFilter.QUEUE_PATH + "?filter=" + filter.key());
        }
        assertThat(Stream.of(QueueFilter.values()).map(QueueFilter::key).distinct())
                .hasSize(QueueFilter.values().length);
        assertThat(QueueFilter.byKey(null)).isEmpty();
        assertThat(QueueFilter.byKey("  ")).isEmpty();
    }

    private CoordinatorController controllerSeeing(List<InterviewRequest> world) {
        InterviewRequestService requests = mock(InterviewRequestService.class);
        when(requests.listVisible(null)).thenReturn(world);
        // No stubbing on this mock: CoordinatorController only calls identitiesFor/identityFor,
        // never isRevealed(). A when(reveal.isRevealed()) here matched nothing, and because an
        // unstubbed boolean already returns false it could never have failed either. Strict stubs
        // (below) is what stops that from growing back.
        NameRevealService reveal = mock(NameRevealService.class);
        DeadlineTrackingService deadlines = mock(DeadlineTrackingService.class);
        when(deadlines.groupByUrgency(anyList())).thenReturn(List.of());
        return new CoordinatorController(requests, mock(UserRepository.class), deadlines, reveal);
    }

    /** One request per filter, plus one matching none, so "All" is larger than any single chip. */
    private List<InterviewRequest> aWorldWithEveryFilterNonEmpty() {
        return Stream.of(
                returnedAt(requestAt(InterviewStatus.SCHEDULED), NOW.minusHours(80)),   // OVERDUE
                returnedAt(requestAt(InterviewStatus.ALLOCATED), NOW.minusHours(60)),   // DUE_SOON + CONSENT
                returnedAt(requestAt(InterviewStatus.SCHEDULED), null),                 // NO_CLOCK
                returnedAt(requestAt(InterviewStatus.REQUESTED), NOW.minusHours(1)),    // UNALLOCATED
                requestAt(InterviewStatus.REPORT_SUBMITTED),                            // AWAITING_REVIEW
                requestAt(InterviewStatus.REPORT_APPROVED))                             // no filter at all
                .map(r -> {
                    r.setChild(THE_CHILD);
                    return r;
                })
                .toList();
    }

    private InterviewRequest returnedAt(InterviewRequest request, LocalDateTime returnedAt) {
        request.setReturnedAt(returnedAt);
        return request;
    }

    /**
     * Every row needs a child because the controller builds the masked-identity map off one; the
     * queue's filters have nothing to do with which child it is, so they all share this one. The id
     * is set reflectively because it is JPA's to assign - there is no setter, and adding one to
     * production code to satisfy a fixture is the wrong direction.
     */
    private static final Child THE_CHILD = aChild();

    private static Child aChild() {
        Child child = new Child();
        child.setFirstName("Ada");
        child.setLastName("Bell");
        ReflectionTestUtils.setField(child, "id", 1L);
        return child;
    }
}
