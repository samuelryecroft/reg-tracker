package ninja.samryecroft.returnhome.tracker.interview.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public class AllocateAndScheduleForm {

    @NotNull
    private Long visitorId;

    /** Optional - if left blank the request moves to ALLOCATED and the visitor confirms a time themselves. */
    private LocalDateTime scheduledAt;

    public Long getVisitorId() {
        return visitorId;
    }

    public void setVisitorId(Long visitorId) {
        this.visitorId = visitorId;
    }

    public LocalDateTime getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(LocalDateTime scheduledAt) {
        this.scheduledAt = scheduledAt;
    }
}
