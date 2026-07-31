package ninja.samryecroft.returnhome.tracker.interview.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public class ConfirmScheduleForm {

    @NotNull(message = "A visit date/time is required")
    private LocalDateTime scheduledAt;

    public LocalDateTime getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(LocalDateTime scheduledAt) {
        this.scheduledAt = scheduledAt;
    }
}
