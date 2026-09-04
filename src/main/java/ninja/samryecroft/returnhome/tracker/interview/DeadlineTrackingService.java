package ninja.samryecroft.returnhome.tracker.interview;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Groups a request list into urgency tiers for display (coordinator/home lists, roadmap 2.1).
 * All state and sort logic is delegated to {@link DeadlineTracker} - this class only shapes the
 * result for the template, against a single "now" so every row in one render agrees with itself.
 */
@Service
public class DeadlineTrackingService {

    /** Urgency-first, most urgent group first. Empty groups are omitted. */
    public List<DeadlineGroup> groupByUrgency(List<InterviewRequest> requests) {
        LocalDateTime now = LocalDateTime.now();
        List<InterviewRequest> sorted = requests.stream().sorted(DeadlineTracker.byUrgency(now)).toList();

        List<DeadlineGroup> groups = new ArrayList<>();
        Optional<DueState> currentState = null;
        List<DeadlineRow> currentRows = null;
        for (InterviewRequest request : sorted) {
            Optional<DueState> state = DeadlineTracker.stateOf(request, now);
            if (!state.equals(currentState)) {
                flush(groups, currentState, currentRows);
                currentState = state;
                currentRows = new ArrayList<>();
            }
            currentRows.add(new DeadlineRow(request, DeadlineTracker.badgeFor(request, now).orElse(null)));
        }
        flush(groups, currentState, currentRows);
        return groups;
    }

    /** {@code state} is null only on the priming call, which {@code rows} short-circuits first. */
    private void flush(List<DeadlineGroup> groups, Optional<DueState> state, List<DeadlineRow> rows) {
        if (rows != null && !rows.isEmpty()) {
            groups.add(new DeadlineGroup(state.orElse(null), labelFor(state) + " (" + rows.size() + ")", rows));
        }
    }

    /**
     * T165: the state word comes from {@link DueStateCopy} so the heading and the badges inside it
     * cannot drift apart, and the glyph that used to prefix these strings is gone - headings are
     * announced, so it was being read as the character's name. The icon is now aria-hidden markup
     * chosen from {@link DeadlineGroup#state()}.
     */
    private String labelFor(Optional<DueState> state) {
        if (state.isEmpty()) {
            return "No active deadline";
        }
        return switch (state.get()) {
            case OVERDUE -> DueStateCopy.stateWord(DueState.OVERDUE) + " — statutory 72 hours passed";
            case DUE_SOON -> DueStateCopy.stateWord(DueState.DUE_SOON) + " — under 24 hours remaining";
            case NO_CLOCK -> DueStateCopy.stateWord(DueState.NO_CLOCK);
            case ON_TRACK -> DueStateCopy.stateWord(DueState.ON_TRACK);
        };
    }
}
