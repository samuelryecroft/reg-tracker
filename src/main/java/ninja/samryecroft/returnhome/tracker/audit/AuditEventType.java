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
    INTERVIEW_REQUEST_RETURN_TIME_RECORDED,

    REPORT_DRAFT_SAVED,
    REPORT_SUBMITTED,
    REPORT_APPROVED,
    REPORT_REJECTED,

    DOCX_GENERATED,
    DOCX_DOWNLOADED,

    // Document encryption (WS-B / DOCUMENT-ENCRYPTION-DESIGN.md §4). Key use is the closest thing
    // we have to a tamper-evident record of document access, and Key Vault logs the same
    // operations independently, in a place this application cannot edit - so the two can be
    // reconciled. DOCUMENT_CRYPTO_FAILED is the fail-closed trip: a document that could not be
    // decrypted was never served.
    DOCUMENT_KEY_WRAPPED,
    DOCUMENT_KEY_UNWRAPPED,
    DOCUMENT_CRYPTO_FAILED,

    // Roadmap 2.5 - export takes children's data OUT of the encrypted boundary, so the act itself
    // is audited (export-build-brief.md, CONFIRMED non-negotiable). Both record whether the
    // attempt succeeded; a failed export is recorded precisely so a quoted attempt number means
    // something.
    CASE_FILE_EXPORTED,
    AUDIT_QUERY_EXPORTED,

    ACCESS_DENIED
}
