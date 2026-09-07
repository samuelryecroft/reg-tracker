package ninja.samryecroft.returnhome.tracker.child;

import java.util.List;
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
        child.setArchived(true);
        Child saved = childRepository.save(child);
        auditEventPublisher.childArchived(saved, principal);
        return saved;
    }

    @Transactional
    public Child restore(Child child, AppUserPrincipal principal) {
        child.setArchived(false);
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
