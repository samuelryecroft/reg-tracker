package ninja.samryecroft.returnhome.tracker.interview.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

/** The "Add return time" form (roadmap 2.1 no-clock remedy) - a single field, deliberately not a general edit. */
public class ReturnTimeForm {

    @NotNull
    private LocalDateTime returnedAt;

    public LocalDateTime getReturnedAt() {
        return returnedAt;
    }

    public void setReturnedAt(LocalDateTime returnedAt) {
        this.returnedAt = returnedAt;
    }
}
