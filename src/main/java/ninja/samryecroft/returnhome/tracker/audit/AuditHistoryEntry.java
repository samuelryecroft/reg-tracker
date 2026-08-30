package ninja.samryecroft.returnhome.tracker.audit;

import java.time.LocalDateTime;

/**
 * One curated row for the V1 "History" timeline (Creed's audit-mockups.html §01). Deliberately not
 * {@link AuditEvent} itself: this is the GDPR-safe projection a template is allowed to render -
 * ids, statuses and timestamps only, never a free-text field off the raw audit row (a filename, an
 * access-denied reason, a review comment). {@link AuditHistoryService} is the only place that
 * builds one of these, and it holds the per-event-type allow-list that keeps it that way.
 *
 * @param headline    a short sentence naming what happened, e.g. "Report approved"
 * @param occurredAt  when it happened - kept alongside {@code when} so day-grouping can bucket on
 *                     the real instant regardless of how that instant is displayed
 * @param when        the display string for the WHEN column - bare "HH:mm" inside a day-grouped
 *                     section (the day is already the section heading), or "dd MMM yyyy" inside a
 *                     request-grouped section (audit-mockups.html §01's child-page case history,
 *                     which can span months and needs the date on every row)
 * @param actorRole   the actor's role(s) at the time, e.g. "Reviewer" - never a name or username.
 *                     Null for a system-generated row (e.g. the .docx being produced on approval).
 * @param detail      an optional structured detail line - a status transition or a timestamp,
 *                     never free text. Null when the headline says everything there is to say.
 * @param tone        which timeline dot colour to use: {@code "ok"}, {@code "info"}, {@code "back"},
 *                     or {@code ""} for the neutral/default dot. Reinforces the wording; the row's
 *                     text must never rely on colour alone to say what happened.
 */
public record AuditHistoryEntry(
        String headline,
        LocalDateTime occurredAt,
        String when,
        String actorRole,
        String detail,
        String tone) {
}
