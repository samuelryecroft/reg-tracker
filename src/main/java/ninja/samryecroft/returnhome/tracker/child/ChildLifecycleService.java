package ninja.samryecroft.returnhome.tracker.child;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import ninja.samryecroft.returnhome.tracker.audit.AuditEventPublisher;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequestRepository;
import ninja.samryecroft.returnhome.tracker.interview.InterviewStatus;
import ninja.samryecroft.returnhome.tracker.user.AppUserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Taking a young person off the active lists, and putting them back (T170).
 *
 * <h2>The rule that outranks the feature</h2>
 *
 * <p><strong>Archiving a young person must never hide, alter or make unreachable any of their
 * interview records.</strong> An approved report is a statutory document. <em>If archiving could
 * take safeguarding history out of view, archiving becomes the way to make it disappear</em> - which
 * is the worst thing this feature could do. So this service sets one flag that the LISTS read, and
 * touches nothing that resolves a record for viewing or export.
 *
 * <h2>Blocked while the record is unfinished, and never a silent cascade</h2>
 *
 * <p>An open request, an allocated interview or a report awaiting review means not archivable, and
 * the refusal states the count and the route. <strong>Archiving never cascades:</strong> a child's
 * interviews are not closed, cancelled or hidden to make the archive possible. Finish the record,
 * then archive.
 *
 * <h2>Removal does not live here (Kevin, T176)</h2>
 *
 * <p><strong>Archive and remove are different acts with different privileges.</strong> Every method
 * on this class is reversible - that is what makes them safe to reach through one scope check. A
 * removal is not a bigger archive: destroying a young person is a GRAPH deletion across requests,
 * reports, documents and draft state, and the export bundle it produces becomes THE ONLY COPY IN
 * EXISTENCE, so the sequence is export, VERIFY, confirm custody, then destroy.
 *
 * <p>This class is called "lifecycle", which makes it the obvious place to add {@code delete}. It is
 * not one. <strong>Someone tidying a list must not be able to destroy evidence</strong>, and the
 * cheapest way for that to happen is a destructive method appearing beside two reversible ones and
 * inheriting their reachability.
 *
 * <p><strong>Specifically: do not let this path grow an "export and purge" affordance as a
 * convenience.</strong> That is how removal arrives without anyone deciding to build it - the
 * plausible fix a tired builder reaches for, which quietly answers a question nobody asked. It is
 * the same shape as retargeting an audit event so it resolves: a small change that makes the screen
 * do the wanted thing and falsifies what the system claims.
 *
 * <p><strong>And the fact that settles it, because it is not a matter of taste:</strong> with a
 * 35-day backup window, <em>"destroyed" does not mean destroyed for 35 days</em>. A restore taken
 * for an unrelated incident would silently resurrect destroyed records - and because this system
 * deliberately keeps its audit trail, that trail would point at live records again and RE-IDENTIFY
 * what was supposed to be anonymous. Removal therefore has a TIME DIMENSION that archiving has no
 * reason to know about, which is the strongest possible argument for the two never sharing a
 * control, a predicate, or a class.
 *
 * <h2>Restore is as easy as archive</h2>
 *
 * <p>Deliberately the same shape, one call each. <em>"An asymmetric pair teaches people not to use
 * the cheap half"</em> - if restoring were the harder half, people would avoid archiving and the
 * list would rot, or they would hesitate at the moment it mattered.
 */
@Service
public class ChildLifecycleService {

    /**
     * The states that mean a young person's record is still in progress.
     *
     * <p>Derived from what is NOT finished rather than listed as "the blocking ones", so a new
     * interview state is blocking by default. A state added later that nobody remembers to classify
     * should stop an archive rather than silently permit one - the safe direction for a rule whose
     * failure mode is a record leaving view while work is outstanding.
     */
    private static final List<InterviewStatus> FINISHED =
            List.of(InterviewStatus.REPORT_APPROVED, InterviewStatus.CANCELLED);

    private final ChildRepository childRepository;
    private final InterviewRequestRepository interviewRequestRepository;
    private final AuditEventPublisher auditEventPublisher;

    public ChildLifecycleService(ChildRepository childRepository,
            InterviewRequestRepository interviewRequestRepository, AuditEventPublisher auditEventPublisher) {
        this.childRepository = childRepository;
        this.interviewRequestRepository = interviewRequestRepository;
        this.auditEventPublisher = auditEventPublisher;
    }

    /**
     * Correct a young person's own details (T170).
     *
     * <p><strong>The audit records WHICH FIELDS changed and never their values</strong>, so the
     * comparison happens here and only field NAMES leave the method. Nothing in this class ever
     * passes a name, a date of birth or a case reference to the publisher.
     *
     * <p>A submission that changes nothing writes NO audit row. An event saying "these fields
     * changed" when none did is not a harmless extra line - it is a false statement in an
     * append-only record that cannot be corrected.
     */
    @Transactional
    public Child update(Long childId, String firstName, String lastName, LocalDate dateOfBirth,
            String localCaseReference, AppUserPrincipal principal) {
        // LOADED INSIDE THE TRANSACTION, and that is required rather than tidy. The plaintext name
        // and date of birth are TRANSIENT - only the ciphertext columns are mapped - and the
        // encryption listener fires on a MANAGED entity at flush. Handed a DETACHED child (which is
        // all a controller can produce, since open-in-view is off), save() becomes merge(), the
        // transient fields do not survive the copy, and the ciphertext columns keep their old
        // values: the edit appears to succeed and silently changes nothing. Found by this method's
        // own test, not by reasoning.
        Child child = childRepository.findDetailedById(childId)
                .orElseThrow(() -> new IllegalArgumentException("No such young person: " + childId));
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(child.getFirstName(), firstName)) {
            changed.add("firstName");
        }
        if (!Objects.equals(child.getLastName(), lastName)) {
            changed.add("lastName");
        }
        if (!Objects.equals(child.getDateOfBirth(), dateOfBirth)) {
            changed.add("dateOfBirth");
        }
        if (!Objects.equals(child.getLocalCaseReference(), localCaseReference)) {
            changed.add("localCaseReference");
        }
        child.setFirstName(firstName);
        child.setLastName(lastName);
        child.setDateOfBirth(dateOfBirth);
        child.setLocalCaseReference(localCaseReference);
        Child saved = childRepository.save(child);
        if (!changed.isEmpty()) {
            auditEventPublisher.childUpdated(saved, changed, principal);
        }
        return saved;
    }

    @Transactional
    public Child archive(Child child, AppUserPrincipal principal) {
        List<InterviewRequest> unfinished = interviewRequestRepository
                .findByChildIdOrderByCreatedAtDesc(child.getId()).stream()
                .filter(request -> !FINISHED.contains(request.getStatus()))
                .toList();
        if (!unfinished.isEmpty()) {
            throw new ChildNotArchivableException(describe(unfinished)
                    + " Finish the interview, then archive this young person.");
        }
        // T321: the moment, not the fact. Taken here rather than left to a DB default so the value
        // is the application's clock - the same clock the audit event about to be written uses, so
        // the row and the trail agree about when this happened rather than being minutes apart on a
        // server whose time drifted.
        child.setArchivedAt(LocalDateTime.now());
        Child saved = childRepository.save(child);
        auditEventPublisher.childArchived(saved, principal);
        return saved;
    }

    @Transactional
    public Child restore(Child child, AppUserPrincipal principal) {
        // Cleared, not kept. This column is the CURRENT state's date; the history of every archive
        // and restore is on audit_events, which refuses UPDATE and DELETE by trigger. Keeping the
        // last archive date on a restored record would make "archived_at is not null" stop meaning
        // "archived", which is the one thing every list query on this column relies on.
        child.setArchivedAt(null);
        Child saved = childRepository.save(child);
        auditEventPublisher.childRestored(saved, principal);
        return saved;
    }

    /**
     * The count and what it is waiting on, because "cannot archive" alone sends someone hunting.
     *
     * <p>Named by STATUS rather than by interview id: the person reading this is looking at a list
     * of interviews and needs to know which one to go and finish, and "awaiting review" identifies
     * it far better to them than a database identifier does.
     */
    private String describe(List<InterviewRequest> unfinished) {
        String plural = unfinished.size() == 1 ? " interview is" : " interviews are";
        String states = unfinished.stream()
                .map(request -> request.getStatus().getDisplayName().toLowerCase())
                .distinct()
                .reduce((a, b) -> a + ", " + b)
                .orElse("in progress");
        return unfinished.size() + plural + " still open (" + states + ").";
    }
}
