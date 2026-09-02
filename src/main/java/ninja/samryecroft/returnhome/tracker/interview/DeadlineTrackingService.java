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
        String currentLabel = null;
        List<DeadlineRow> currentRows = null;
        for (InterviewRequest request : sorted) {
            Optional<DueState> state = DeadlineTracker.stateOf(request, now);
            String label = labelFor(state);
            if (!label.equals(currentLabel)) {
                flush(groups, currentLabel, currentRows);
                currentLabel = label;
                currentRows = new ArrayList<>();
            }
            currentRows.add(new DeadlineRow(request, DeadlineTracker.badgeFor(request, now).orElse(null)));
        }
        flush(groups, currentLabel, currentRows);
        return groups;
    }

    private void flush(List<DeadlineGroup> groups, String label, List<DeadlineRow> rows) {
        if (rows != null && !rows.isEmpty()) {
            groups.add(new DeadlineGroup(label + " (" + rows.size() + ")", rows));
        }
    }

    private String labelFor(Optional<DueState> state) {
        if (state.isEmpty()) {
            return "No active deadline";
        }
        return switch (state.get()) {
            case OVERDUE -> "▲ Overdue — statutory 72 hours passed";
            case DUE_SOON -> "◷ Due soon — under 24 hours remaining";
            case NO_CLOCK -> "Return time not recorded";
            case ON_TRACK -> "✓ On track";
        };
    }
}
