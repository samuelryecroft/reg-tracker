package ninja.samryecroft.returnhome.tracker.interview;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import ninja.samryecroft.returnhome.tracker.child.ChildIdentity;
import ninja.samryecroft.returnhome.tracker.report.InterviewReport;

/**
 * The five-position status rail shown on 1a and 1b (spec D-1a-2, Creed's review, 1f04c68) - a pure
 * function of {@code (InterviewRequest, InterviewReport)}, deliberately not a Spring bean, same
 * shape as {@link ChildIdentity} for the same reason: one place, trivially unit-testable, no
 * decisions left for a template to make beyond printing {@code step.label()}/{@code
 * step.state()}.
 *
 * <p><b>The rail has five positions; {@link InterviewStatus} has seven.</b> Creed's review found
 * this while designing the rail, not before: the README's rail was drawn from the happy path and
 * never reconciled with the model, and two states have no natural slot -
 *
 * <ul>
 *   <li>{@link InterviewStatus#REPORT_REJECTED} renders AT the {@code REPORT_SUBMITTED} position,
 *       in the {@link StepState#SENT_BACK} treatment - not as an invented sixth position. That is
 *       where the process actually is: the report exists and has gone back to the visitor. A
 *       linear rail cannot show a backwards transition as forward progress without lying about it.
 *   <li>{@link InterviewStatus#CANCELLED} stops the rail at whichever position it reached, marks
 *       that position {@link StepState#CANCELLED}, and marks every later position {@link
 *       StepState#NOT_APPLICABLE} rather than {@link StepState#UPCOMING} - a pending-looking step
 *       on a cancelled request would be a false statement about future work that will never
 *       happen.
 * </ul>
 *
 * <p>Labels come from {@link InterviewStatus#getDisplayName()} directly, never separate copy
 * (ruling 1) - the status tag on the same screen reads the same enum, so the two can never say two
 * different things for one state.
 *
 * <p><b>{@code CANCELLED} is presently unreachable in production</b> ({@link
 * InterviewStatusTransitions} has no in-edges to it, and T146 has not yet decided whether it is
 * live vocabulary or dead) - this method still implements the ruling correctly and is tested
 * against constructed fixtures (which {@code InterviewStatusTransitions}'s own javadoc explicitly
 * allows), so the rail is already correct the day T146 resolves either way. Because nothing
 * currently transitions a request TO cancelled, "how far it got" cannot be read off a status
 * transition log that does not exist - it is inferred from which of the request/report's own
 * timestamp fields are already populated, the same fields the happy path itself reads.
 */
public final class StatusRail {

    private static final List<InterviewStatus> HAPPY_PATH = List.of(
            InterviewStatus.REQUESTED,
            InterviewStatus.ALLOCATED,
            InterviewStatus.SCHEDULED,
            InterviewStatus.REPORT_SUBMITTED,
            InterviewStatus.REPORT_APPROVED);

    private StatusRail() {}

    /** @param report may be {@code null} - a request that has not yet reached REPORT_SUBMITTED has none. */
    public static List<Step> forRequest(InterviewRequest request, InterviewReport report) {
        if (request.getStatus() == InterviewStatus.CANCELLED) {
            return cancelledRail(request, report);
        }

        boolean rejected = request.getStatus() == InterviewStatus.REPORT_REJECTED;
        int currentIndex = rejected
                ? HAPPY_PATH.indexOf(InterviewStatus.REPORT_SUBMITTED)
                : HAPPY_PATH.indexOf(request.getStatus());

        List<Step> steps = new ArrayList<>(HAPPY_PATH.size());
        for (int i = 0; i < HAPPY_PATH.size(); i++) {
            InterviewStatus positionStatus = HAPPY_PATH.get(i);
            if (i == currentIndex && rejected) {
                steps.add(new Step(InterviewStatus.REPORT_REJECTED.getDisplayName(),
                        occurredAtFor(InterviewStatus.REPORT_REJECTED, request, report), StepState.SENT_BACK));
            } else if (i < currentIndex || (i == currentIndex && i == HAPPY_PATH.size() - 1)) {
                // The terminal position, once reached, is finished rather than "in progress with
                // something still ahead of it" - CURRENT only means something when there is a
                // later position for it to be current RELATIVE TO.
                steps.add(new Step(positionStatus.getDisplayName(),
                        occurredAtFor(positionStatus, request, report), StepState.COMPLETE));
            } else if (i == currentIndex) {
                steps.add(new Step(positionStatus.getDisplayName(),
                        occurredAtFor(positionStatus, request, report), StepState.CURRENT));
            } else {
                steps.add(new Step(positionStatus.getDisplayName(), null, StepState.UPCOMING));
            }
        }
        return List.copyOf(steps);
    }

    private static List<Step> cancelledRail(InterviewRequest request, InterviewReport report) {
        int reachedIndex = lastReachedIndex(request, report);
        List<Step> steps = new ArrayList<>(HAPPY_PATH.size());
        for (int i = 0; i < HAPPY_PATH.size(); i++) {
            InterviewStatus positionStatus = HAPPY_PATH.get(i);
            if (i < reachedIndex) {
                steps.add(new Step(positionStatus.getDisplayName(),
                        occurredAtFor(positionStatus, request, report), StepState.COMPLETE));
            } else if (i == reachedIndex) {
                steps.add(new Step(InterviewStatus.CANCELLED.getDisplayName(), request.getUpdatedAt(),
                        StepState.CANCELLED));
            } else {
                steps.add(new Step(positionStatus.getDisplayName(), null, StepState.NOT_APPLICABLE));
            }
        }
        return List.copyOf(steps);
    }

    /** Which happy-path position a since-cancelled request last actually reached, read off timestamps. */
    private static int lastReachedIndex(InterviewRequest request, InterviewReport report) {
        if (report != null && report.getSubmittedAt() != null) {
            return HAPPY_PATH.indexOf(InterviewStatus.REPORT_SUBMITTED);
        }
        if (request.getScheduledAt() != null) {
            return HAPPY_PATH.indexOf(InterviewStatus.SCHEDULED);
        }
        if (request.getAllocatedAt() != null) {
            return HAPPY_PATH.indexOf(InterviewStatus.ALLOCATED);
        }
        return HAPPY_PATH.indexOf(InterviewStatus.REQUESTED);
    }

    private static LocalDateTime occurredAtFor(InterviewStatus status, InterviewRequest request,
            InterviewReport report) {
        return switch (status) {
            case REQUESTED -> request.getCreatedAt();
            case ALLOCATED -> request.getAllocatedAt();
            case SCHEDULED -> request.getScheduledAt();
            case REPORT_SUBMITTED -> report == null ? null : report.getSubmittedAt();
            case REPORT_REJECTED, REPORT_APPROVED -> report == null ? null : report.getReviewedAt();
            case CANCELLED -> null;
        };
    }

    /** One of the rail's five fixed positions. {@code occurredAt} is null for UPCOMING/NOT_APPLICABLE. */
    public record Step(String label, LocalDateTime occurredAt, StepState state) {}

    public enum StepState {
        /** A position the request has already passed through, on the current path. */
        COMPLETE,
        /** Where the request is right now, on the current (non-exception) path. */
        CURRENT,
        /** A position the request has not reached yet, but genuinely still could. */
        UPCOMING,
        /**
         * REPORT_REJECTED, at the REPORT_SUBMITTED position: {@code ph-arrow-u-up-left} on
         * {@code --sent-back}/{@code --sent-back-bg} (spec D-1a-2a). A distinct value from {@link
         * #CANCELLED} even though both are "off the happy path" - Creed's D-1a-2a table gives the
         * two a different glyph AND a different colour pair (arrow-back/sent-back vs. x-circle/
         * neutral), so one shared "EXCEPTION" state can't drive the template's glyph choice; the
         * label text would be the only remaining signal, and that's a worse contract than two names.
         */
        SENT_BACK,
        /**
         * The position a CANCELLED request last reached: {@code ph-x-circle} on {@code --neutral}/
         * {@code --neutral-bg} (spec D-1a-2a's "the cancelled position itself" row) - never colour
         * alone (the standing "never colour alone" rule, spec §D-Q4).
         */
        CANCELLED,
        /**
         * A position after a CANCELLED request's last-reached one - never "still to come".
         * {@code ph-prohibit}, muted, with the connector itself turning dashed (spec D-1a-2a): the
         * shape change, not just the colour, is what stops these reading as pending work.
         */
        NOT_APPLICABLE
    }
}
