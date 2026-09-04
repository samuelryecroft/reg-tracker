package ninja.samryecroft.returnhome.tracker.audit;

/**
 * The phase-1 audit event catalog (AUDIT-PLAN.md §A - every row marked ✅). Phase-2 events
 * (logout, lockout, org/home/theme edits, child record created, report viewed) are deliberately
 * absent; add them here when phase 2 lands.
 */
public enum AuditEventType {

    LOGIN_SUCCESS,
    LOGIN_FAILURE,

    /**
     * A sign-in through the emergency local credential path, and the highest-attention event in this
     * catalogue. Recorded IN ADDITION to LOGIN_SUCCESS rather than instead of it: the ordinary event
     * keeps the sign-in trail uniform, and this one exists so that "did anyone use break-glass" is a
     * question the feed answers directly rather than by inference over usernames.
     */
    BREAK_GLASS_LOGIN,

    /**
     * The emergency path being switched on, which is the act worth noticing early - before it is
     * used rather than after. Raised at startup when the flag is set, because on App Service a
     * configuration change restarts the app, so startup is when the change becomes real.
     */
    BREAK_GLASS_ENABLED,

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

    // Compliance export (roadmap 2.5). Extraction is the act this whole trail exists to make
    // reviewable: a pack leaves the building and cannot be recalled, so every attempt is recorded -
    // including the ones that failed, because the error screen quotes an attempt number and that
    // means nothing if the attempt was never written down.
    CASE_FILE_EXPORTED,
    AUDIT_QUERY_EXPORTED,
    EXPORT_FAILED,

    // Opening the audit trail is itself case-activity access to a child's safeguarding record - the
    // same expectation as an access log on a health record. A cover sheet that invites someone to
    // verify an export against the trail reads oddly if consulting the trail is the one thing the
    // trail does not record. This is NOT sign-in monitoring and does not touch that decision.
    AUDIT_VIEW_OPENED,

    // Masking (T138 1c, spec §2.5). Revealing a masked list of children's names is professional
    // access to safeguarding data - at least as much as opening one child's own record, which
    // AUDIT_VIEW_OPENED already covers. One event per reveal ACTION, not per row shown, matching
    // the click that caused it rather than the count of names it happened to affect.
    NAMES_REVEALED,

    ACCESS_DENIED
}
