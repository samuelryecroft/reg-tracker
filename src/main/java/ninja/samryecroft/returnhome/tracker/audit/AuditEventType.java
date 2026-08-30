package ninja.samryecroft.returnhome.tracker.audit;

/**
 * The phase-1 audit event catalog (AUDIT-PLAN.md §A - every row marked ✅). Phase-2 events
 * (logout, lockout, org/home/theme edits, child record created, report viewed) are deliberately
 * absent; add them here when phase 2 lands.
 */
public enum AuditEventType {

    LOGIN_SUCCESS,
    LOGIN_FAILURE,

    USER_CREATED,
    USER_UPDATED,

    INTERVIEW_REQUEST_CREATED,
    INTERVIEW_REQUEST_ALLOCATED,
    INTERVIEW_REQUEST_SCHEDULED,

    REPORT_DRAFT_SAVED,
    REPORT_SUBMITTED,
    REPORT_APPROVED,
    REPORT_REJECTED,

    DOCX_GENERATED,
    DOCX_DOWNLOADED,

    ACCESS_DENIED
}
