package ninja.samryecroft.returnhome.tracker.report;

import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.document.DocumentSecurityException;
import ninja.samryecroft.returnhome.tracker.document.ReportStore;
import ninja.samryecroft.returnhome.tracker.document.StoredDocumentRef;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.springframework.stereotype.Service;

/**
 * The domain's view of encrypted report documents: resolves which organisation owns a report,
 * hands the bytes to the {@link ReportStore}, and audits every key operation.
 *
 * <p>It sits between {@code ReportService}/{@code ReportController} and the storage package on
 * purpose. The storage and crypto classes stay free of domain types - which is what lets them be
 * unit-tested without a database - while the code that knows about interview requests, reports and
 * principals is the code that resolves scope and writes the audit trail.
 */
@Service
public class ReportDocumentService {

    private final ReportStore reportStore;
    private final AuditEventPublisher auditEventPublisher;

    public ReportDocumentService(ReportStore reportStore, AuditEventPublisher auditEventPublisher) {
        this.reportStore = reportStore;
        this.auditEventPublisher = auditEventPublisher;
    }

    /**
     * Encrypts and stores a freshly generated document.
     *
     * @return the storage key to record on the report
     * @throws DocumentSecurityException leaving the report unstored; the caller's transaction rolls
     *         back, so an approval never records a document it does not have
     */
    public String store(InterviewReport report, byte[] content, AppUserPrincipal principal) {
        InterviewRequest request = report.getInterviewRequest();
        long organisationId = owningOrganisationId(request);
        StoredDocumentRef stored;
        try {
            stored = reportStore.store(organisationId, request.getId(), content);
        } catch (DocumentSecurityException e) {
            auditEventPublisher.documentCryptoFailed(request, report.getId(), "encrypt",
                    e.getClass().getSimpleName(), principal);
            throw e;
        }
        auditEventPublisher.documentKeyWrapped(report, stored.storageKey(), stored.keyName(),
                stored.keyVersion(), principal);
        return stored.storageKey();
    }

    /**
     * Reads a stored document back.
     *
     * <p>The organisation is resolved here from the report's own home, <em>not</em> from anything
     * the requester supplied and not from the access check that has already run. That independence
     * is deliberate: it is what makes a scoping bug that reaches the wrong report yield a failed
     * decryption rather than another organisation's document
     * (DOCUMENT-ENCRYPTION-DESIGN.md threat T3).
     */
    public byte[] retrieve(InterviewRequest request, InterviewReport report, AppUserPrincipal principal) {
        long organisationId = owningOrganisationId(request);
        String storageKey = report.getGeneratedDocumentPath();
        try {
            byte[] content = reportStore.retrieve(organisationId, storageKey);
            auditEventPublisher.documentKeyUnwrapped(request, report.getId(), storageKey, principal);
            return content;
        } catch (DocumentSecurityException e) {
            auditEventPublisher.documentCryptoFailed(request, report.getId(), "decrypt",
                    e.getClass().getSimpleName(), principal);
            throw e;
        }
    }

    /**
     * The care-provider organisation that owns the home the child lives in - the same walk
     * {@code ThemeService} makes, and the one the encryption design names.
     */
    private long owningOrganisationId(InterviewRequest request) {
        Home home = request.getHome();
        if (home == null || home.getOrganisation() == null || home.getOrganisation().getId() == null) {
            // Without an organisation there is no key to encrypt under, and guessing one would
            // defeat the isolation this exists to provide.
            throw new DocumentSecurityException("Cannot resolve the organisation that owns this report");
        }
        return home.getOrganisation().getId();
    }
}
