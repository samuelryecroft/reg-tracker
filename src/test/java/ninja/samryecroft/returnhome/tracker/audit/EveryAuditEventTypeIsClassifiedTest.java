package ninja.samryecroft.returnhome.tracker.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Every {@link AuditEventType} must be explicitly classified for a person's own account history:
 * either hidden by {@code AuditHistoryService.EXCLUDED_FROM_USER_HISTORY}, or listed below as
 * deliberately shown. <b>Adding an event type without answering that question fails this test.</b>
 *
 * <p><b>Why this exists, and it is worth reading before editing the list.</b> Per-user sign-in
 * monitoring is parked on an unresolved GDPR policy call, and the exclusion set is what keeps it
 * parked. When T322 added four MFA types - all of them sign-in events - leaving them out of that set
 * would not have broken anything. No test would have failed, no screen would have looked wrong, and
 * a capability that was deliberately deferred would have quietly shipped through a new door. <b>A
 * policy parked in one place gets un-parked by a feature added in another, and the tell is that
 * nothing breaks.</b>
 *
 * <p>That was caught only because somebody happened to read why the set existed before adding to it.
 * That is not a repeatable method, so this test replaces it with one: <b>the next person does not
 * need to happen to read anything.</b>
 *
 * <p><b>The classification is not duplicated here.</b> This test reads the real
 * {@code EXCLUDED_FROM_USER_HISTORY} rather than restating it - a guard holding its own copy of the
 * answer would be the same "one question, two places" defect that {@code User}'s HOME_STAFF/VIEWER
 * history records, and it would drift from production silently. The only list held here is the
 * acknowledgement of the types that are fine to show, which exists nowhere else.
 */
class EveryAuditEventTypeIsClassifiedTest {

    /**
     * Types that may appear on a person's own account history.
     *
     * <p>Adding a name here is an assertion that it is safe for a user-account page: it describes an
     * action taken on a record, not a pattern of when a member of staff works. If a new type is a
     * sign-in event, or would let a reader build a picture of somebody's working hours, it belongs in
     * {@code EXCLUDED_FROM_USER_HISTORY} instead - not here.
     */
    private static final Set<AuditEventType> DELIBERATELY_SHOWN = Set.of(
            // Emergency access. Shown deliberately, and this is the one entry most worth defending:
            // break-glass is the highest-attention event in the catalogue, so hiding it on the
            // grounds that it is technically a sign-in would remove the trail that matters most.
            AuditEventType.BREAK_GLASS_LOGIN,
            AuditEventType.BREAK_GLASS_ENABLED,

            // Account administration - what was done TO an account, by whom.
            AuditEventType.USER_CREATED,
            AuditEventType.USER_UPDATED,
            AuditEventType.USER_PASSWORD_RESET,

            // Interview request lifecycle.
            AuditEventType.INTERVIEW_REQUEST_CREATED,
            AuditEventType.INTERVIEW_REQUEST_ALLOCATED,
            AuditEventType.INTERVIEW_REQUEST_SCHEDULED,
            AuditEventType.INTERVIEW_REQUEST_RETURN_TIME_RECORDED,

            // Report lifecycle.
            AuditEventType.REPORT_DRAFT_SAVED,
            AuditEventType.REPORT_SUBMITTED,
            AuditEventType.REPORT_APPROVED,
            AuditEventType.REPORT_REJECTED,

            // Documents and the keys that protect them.
            AuditEventType.DOCX_GENERATED,
            AuditEventType.DOCX_DOWNLOADED,
            AuditEventType.DOCUMENT_KEY_WRAPPED,
            AuditEventType.DOCUMENT_KEY_UNWRAPPED,
            AuditEventType.DOCUMENT_CRYPTO_FAILED,

            // Extraction, which is a separate act from reading (D-6).
            AuditEventType.CASE_FILE_EXPORTED,
            AuditEventType.AUDIT_QUERY_EXPORTED,
            AuditEventType.EXPORT_FAILED,

            // Access to records, and revealing a name.
            AuditEventType.AUDIT_VIEW_OPENED,
            AuditEventType.NAMES_REVEALED,
            AuditEventType.ACCESS_DENIED,

            // Child and organisation lifecycle.
            AuditEventType.CHILD_UPDATED,
            AuditEventType.CHILD_ARCHIVED,
            AuditEventType.CHILD_RESTORED,
            AuditEventType.ORGANISATION_ACTIVATED,
            AuditEventType.ORGANISATION_ARCHIVED,
            AuditEventType.ORGANISATION_RESTORED);

    @Test
    void everyEventTypeIsEitherHiddenOrDeliberatelyShown() {
        List<AuditEventType> unclassified = Arrays.stream(AuditEventType.values())
                .filter(type -> !AuditHistoryService.EXCLUDED_FROM_USER_HISTORY.contains(type))
                .filter(type -> !DELIBERATELY_SHOWN.contains(type))
                .collect(Collectors.toList());

        assertThat(unclassified)
                .as("These audit event types are not classified for a user's own account history. "
                        + "Decide, do not default: if the type is a sign-in event, or would let a "
                        + "reader build a picture of when a member of staff works, add it to "
                        + "AuditHistoryService.EXCLUDED_FROM_USER_HISTORY - per-user sign-in "
                        + "monitoring is parked on an unresolved GDPR policy call and that set is "
                        + "what keeps it parked. Otherwise add it to DELIBERATELY_SHOWN here. "
                        + "Leaving a type out of both is how a parked capability ships without "
                        + "anything failing")
                .isEmpty();
    }

    /**
     * The two sets must not overlap. An overlap would mean a type was classified both ways, and the
     * hidden set would silently win - so the "deliberately shown" entry would be a statement someone
     * had made and the code was ignoring.
     */
    @Test
    void noTypeIsClassifiedBothWays() {
        Set<AuditEventType> both = DELIBERATELY_SHOWN.stream()
                .filter(AuditHistoryService.EXCLUDED_FROM_USER_HISTORY::contains)
                .collect(Collectors.toSet());

        assertThat(both)
                .as("classified as both hidden and shown; the hidden set wins, so the entry in "
                        + "DELIBERATELY_SHOWN is a decision the code is quietly overriding")
                .isEmpty();
    }
}
