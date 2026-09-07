package ninja.samryecroft.returnhome.tracker.interview;

public enum InterviewStatus {
    REQUESTED("Requested"),
    ALLOCATED("Allocated"),
    SCHEDULED("Scheduled"),
    REPORT_SUBMITTED("Pending review"),
    // Display name only, not the constant (Creed's review, spec D-1a-2, 1f04c68): the action that
    // produces this state is "Send back with comments", and the visitor's own card (2f) is a
    // sent-back card - "Rejected" reads as a verdict where the reality is a request for more
    // detail, and in a safeguarding context that's what a visitor sees when their work comes back
    // to them, not a cosmetic wording choice. Brings the status tag, the status rail (1a), the
    // button and the card into one vocabulary.
    REPORT_REJECTED("Sent back"),
    REPORT_APPROVED("Report approved"),

    /**
     * An interview that will not happen. <b>Nothing in production can reach this state today, and
     * T146 RULED THAT IT STAYS.</b>
     *
     * <p>The question the card asked was whether this is live vocabulary needing a way in, or dead
     * vocabulary to delete. Option B only - the answer is <b>kept, documented and guarded</b>, and
     * every other place that used to describe this as open now points here instead of restating the
     * question. The evidence, all of it measured rather than reasoned about:
     *
     * <ul>
     *   <li><b>It is not dead vocabulary, it is RENDERED.</b> {@link StatusRail} has a designed
     *       {@code StepState.CANCELLED} - its own glyph and its own colour pair, from Creed's
     *       D-1a-2a table, deliberately distinct from {@code REPORT_REJECTED} - and {@code app.css}
     *       carries {@code .status.CANCELLED} plus four {@code .rail-CANCELLED} rules. Two templates
     *       switch on it. Deleting the constant would delete a design decision somebody made on
     *       purpose, which is a different act from removing something nobody chose.</li>
     *   <li><b>It is read by a safeguarding rule.</b> {@code ChildLifecycleService.FINISHED} counts
     *       a cancelled interview as done, which is what lets a young person's record be archived.
     *       Removing it changes WHEN a record may be archived - a rule change wearing a cleanup's
     *       clothes. {@code QueueFilter.CLOSED} reads it too.</li>
     *   <li><b>Deleting it would make existing rows unreadable.</b> The column is
     *       {@code @Enumerated(STRING)} with no CHECK constraint, so a row holding {@code
     *       'CANCELLED'} deserialises into nothing once the constant is gone. The demo seeder writes
     *       exactly such a row on purpose. On a safeguarding system an interview record that cannot
     *       be read is a worse outcome than a state that cannot be reached.</li>
     *   <li><b>The gap is fenced rather than latent</b>, which is what makes deferring the design
     *       safe rather than lazy. {@code InterviewStatusTransitions} records the empty in-edge set
     *       explicitly; {@code InterviewStatusTransitionsTest.noTransitionReachesCancelled} fails
     *       the moment an edge is added; and T154's writer guard fails a direct in-package
     *       {@code setStatus(CANCELLED)} naming file and line. So cancellation cannot arrive by
     *       accident - only as a reviewed change with a red test attached.</li>
     * </ul>
     *
     * <p><b>What is deliberately NOT decided here:</b> what cancellation MEANS - who may do it, from
     * which states, whether it is reversible, and what it does to a child's 72-hour clock. That is
     * product design and it does not belong in a backlog tidy-up. This card decided only that the
     * word survives until somebody designs the act.
     */
    CANCELLED("Cancelled");

    private final String displayName;

    InterviewStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
