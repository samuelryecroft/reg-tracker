package ninja.samryecroft.returnhome.tracker.organisation;

import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.document.KeyProvider;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way an organisation changes lifecycle state (T168(b)).
 *
 * <p>It is a service rather than a setter on the entity because reaching {@link OrgStatus#ACTIVE}
 * has a precondition that must be CHECKED, not asserted: the organisation's per-organisation KEK has
 * to exist. A care provider that becomes usable without one cannot store a child's record - its
 * first encrypted write fails closed - which is the incident this exists to make unreachable rather
 * than merely graceful.
 *
 * <p><b>Why verification and not a checkbox.</b> If activation only recorded that somebody said the
 * key was there, a wrong answer would leave a status column asserting everything is fine while the
 * original failure waits underneath. That is worse than having no column at all: it is the same
 * incident with a reassurance attached. So the transition performs the check itself, using
 * {@link KeyProvider#keyExists} - a read with no create path, needing no privilege the application
 * does not already hold.
 *
 * <p>This is also what lets {@code T166 §5}'s future auto-provisioning be a TRANSITION rather than a
 * second mechanism: it provisions the key and then calls this, through the same gate, and cannot
 * accidentally grow its own weaker one.
 */
@Service
public class OrganisationLifecycleService {

    private final OrganisationRepository organisationRepository;
    private final KeyProvider keyProvider;
    private final AuditEventPublisher auditEvents;

    public OrganisationLifecycleService(OrganisationRepository organisationRepository,
            KeyProvider keyProvider, AuditEventPublisher auditEvents) {
        this.organisationRepository = organisationRepository;
        this.keyProvider = keyProvider;
        this.auditEvents = auditEvents;
    }

    /**
     * Makes an organisation able to hold records, having confirmed its KEK exists.
     *
     * @throws OrganisationNotActivatableException if the key is absent - the remedy is provisioning,
     *         not retrying
     */
    @Transactional
    public Organisation activate(Organisation organisation, AppUserPrincipal principal) {
        if (organisation.getStatus() == OrgStatus.ACTIVE) {
            return organisation;
        }
        String keyName = KeyProvider.keyNameFor(organisation.getId());

        // Deliberately NOT caught: if the vault cannot be reached, we do not know whether the key
        // exists, and answering "absent" would refuse an organisation that may be perfectly
        // provisioned. KeyUnavailableException travels up as the transient fault it is.
        if (!keyProvider.keyExists(organisation.getId())) {
            throw new OrganisationNotActivatableException(keyName);
        }

        organisation.setStatus(OrgStatus.ACTIVE);
        Organisation saved = organisationRepository.save(organisation);
        auditEvents.organisationActivated(saved, keyName, principal);
        return saved;
    }

    /**
     * Takes an organisation out of service. Never a physical delete: its children's records are
     * encrypted under its KEK, so destroying the organisation would orphan them, and a safeguarding
     * system retains rather than erases.
     *
     * @param intent what the person meant - "archived" or "removed". {@link OrgStatus} has one state
     *               for both because nothing behaves differently between them today; the distinction
     *               is recorded on the audit event, where a governance question would look for it.
     */
    @Transactional
    public Organisation archive(Organisation organisation, String intent, AppUserPrincipal principal) {
        organisation.setStatus(OrgStatus.ARCHIVED);
        Organisation saved = organisationRepository.save(organisation);
        auditEvents.organisationArchived(saved, intent, principal);
        return saved;
    }

    /**
     * Returns an archived organisation to service. It goes back to PENDING rather than straight to
     * ACTIVE, so it passes through the same KEK verification as any other activation - an
     * organisation archived long enough for its key to be rotated away must not slip back into use
     * on the strength of having once been active.
     */
    @Transactional
    public Organisation restoreToPending(Organisation organisation, AppUserPrincipal principal) {
        organisation.setStatus(OrgStatus.PENDING);
        Organisation saved = organisationRepository.save(organisation);
        auditEvents.organisationRestored(saved, principal);
        return saved;
    }
}
