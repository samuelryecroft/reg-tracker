package ninja.samryecroft.returnhome.tracker.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import ninja.samryecroft.returnhome.tracker.fieldcrypto.Encrypted;
import ninja.samryecroft.returnhome.tracker.fieldcrypto.EncryptedEntity;
import ninja.samryecroft.returnhome.tracker.fieldcrypto.EncryptedFieldListener;
import java.time.LocalDate;
import java.time.LocalDateTime;
import ninja.samryecroft.returnhome.tracker.interview.DeadlineTracker;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.user.User;

@Entity
@Table(name = "interview_reports")
@EntityListeners(EncryptedFieldListener.class)
public class InterviewReport implements EncryptedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interview_request_id", nullable = false, unique = true)
    private InterviewRequest interviewRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visitor_id", nullable = false)
    private User visitor;


    @Column(name = "interview_location_enc", columnDefinition = "TEXT")
    private String interviewLocationCiphertext;

    @Transient
    @Encrypted(ciphertextField = "interviewLocationCiphertext")
    private String interviewLocation;

    // --- Details ---

    /**
     * When the interview was actually held - the end of the statutory 72-hour clock, whose start is
     * the request's {@code returnedAt}. Captured at submission.
     *
     * <p>Not encrypted, deliberately: the compliance rate aggregates this across an organisation,
     * and a bare timestamp carries no name, location or narrative. See V15.
     */
    @Column(name = "held_at")
    private LocalDateTime heldAt;

    @Column(name = "if_not_why_late_enc", columnDefinition = "TEXT")
    private String ifNotWhyLateCiphertext;

    @Transient
    @Encrypted(ciphertextField = "ifNotWhyLateCiphertext")
    private String ifNotWhyLate;

    @Column(name = "consultation_with_home_staff_enc", columnDefinition = "TEXT")
    private String consultationWithHomeStaffCiphertext;

    @Transient
    @Encrypted(ciphertextField = "consultationWithHomeStaffCiphertext")
    private String consultationWithHomeStaff;

    @Column(name = "previously_missing")
    private Boolean previouslyMissing;

    @Column(name = "missing_occasions_last_30_days")
    private Integer missingOccasionsLast30Days;

    @Column(name = "confidentiality_explained")
    private Boolean confidentialityExplained;

    // --- Return Home Interview ---

    @Column(name = "interview_accepted")
    private Boolean interviewAccepted;

    @Column(name = "interview_declined_reason_enc", columnDefinition = "TEXT")
    private String interviewDeclinedReasonCiphertext;

    @Transient
    @Encrypted(ciphertextField = "interviewDeclinedReasonCiphertext")
    private String interviewDeclinedReason;

    @Column(name = "where_were_you_while_missing_enc", columnDefinition = "TEXT")
    private String whereWereYouWhileMissingCiphertext;

    @Transient
    @Encrypted(ciphertextField = "whereWereYouWhileMissingCiphertext")
    private String whereWereYouWhileMissing;

    @Column(name = "who_were_you_with_while_missing_enc", columnDefinition = "TEXT")
    private String whoWereYouWithWhileMissingCiphertext;

    @Transient
    @Encrypted(ciphertextField = "whoWereYouWithWhileMissingCiphertext")
    private String whoWereYouWithWhileMissing;

    @Column(name = "what_made_you_go_missing_enc", columnDefinition = "TEXT")
    private String whatMadeYouGoMissingCiphertext;

    @Transient
    @Encrypted(ciphertextField = "whatMadeYouGoMissingCiphertext")
    private String whatMadeYouGoMissing;

    @Column(name = "what_can_be_done_to_address_reasons_enc", columnDefinition = "TEXT")
    private String whatCanBeDoneToAddressReasonsCiphertext;

    @Transient
    @Encrypted(ciphertextField = "whatCanBeDoneToAddressReasonsCiphertext")
    private String whatCanBeDoneToAddressReasons;

    @Column(name = "considered_self_missing")
    private Boolean consideredSelfMissing;

    @Column(name = "what_did_you_do_while_missing_enc", columnDefinition = "TEXT")
    private String whatDidYouDoWhileMissingCiphertext;

    @Transient
    @Encrypted(ciphertextField = "whatDidYouDoWhileMissingCiphertext")
    private String whatDidYouDoWhileMissing;

    @Column(name = "what_happened_when_returned_enc", columnDefinition = "TEXT")
    private String whatHappenedWhenReturnedCiphertext;

    @Transient
    @Encrypted(ciphertextField = "whatHappenedWhenReturnedCiphertext")
    private String whatHappenedWhenReturned;

    @Column(name = "prevent_future_missing_suggestions_enc", columnDefinition = "TEXT")
    private String preventFutureMissingSuggestionsCiphertext;

    @Transient
    @Encrypted(ciphertextField = "preventFutureMissingSuggestionsCiphertext")
    private String preventFutureMissingSuggestions;

    @Column(name = "additional_comments_from_young_person_enc", columnDefinition = "TEXT")
    private String additionalCommentsFromYoungPersonCiphertext;

    @Transient
    @Encrypted(ciphertextField = "additionalCommentsFromYoungPersonCiphertext")
    private String additionalCommentsFromYoungPerson;

    @Column(name = "additional_info_from_parent_carer_enc", columnDefinition = "TEXT")
    private String additionalInfoFromParentCarerCiphertext;

    @Transient
    @Encrypted(ciphertextField = "additionalInfoFromParentCarerCiphertext")
    private String additionalInfoFromParentCarer;

    // --- Future Incidents ---

    @Column(name = "risks_identified_during_episode_enc", columnDefinition = "TEXT")
    private String risksIdentifiedDuringEpisodeCiphertext;

    @Transient
    @Encrypted(ciphertextField = "risksIdentifiedDuringEpisodeCiphertext")
    private String risksIdentifiedDuringEpisode;

    @Column(name = "risks_increase_future_episodes_enc", columnDefinition = "TEXT")
    private String risksIncreaseFutureEpisodesCiphertext;

    @Transient
    @Encrypted(ciphertextField = "risksIncreaseFutureEpisodesCiphertext")
    private String risksIncreaseFutureEpisodes;

    @Column(name = "safeguarding_concerns_to_explore_enc", columnDefinition = "TEXT")
    private String safeguardingConcernsToExploreCiphertext;

    @Transient
    @Encrypted(ciphertextField = "safeguardingConcernsToExploreCiphertext")
    private String safeguardingConcernsToExplore;

    @Column(name = "info_to_help_locate_future_enc", columnDefinition = "TEXT")
    private String infoToHelpLocateFutureCiphertext;

    @Transient
    @Encrypted(ciphertextField = "infoToHelpLocateFutureCiphertext")
    private String infoToHelpLocateFuture;

    // --- Interviewer's Comments / Recommendations / Declaration ---

    @Column(name = "interviewer_comments_enc", columnDefinition = "TEXT")
    private String interviewerCommentsCiphertext;

    @Transient
    @Encrypted(ciphertextField = "interviewerCommentsCiphertext")
    private String interviewerComments;

    @Column(name = "recommendations_enc", columnDefinition = "TEXT")
    private String recommendationsCiphertext;

    @Transient
    @Encrypted(ciphertextField = "recommendationsCiphertext")
    private String recommendations;

    @Column(name = "conducted_by_statement_enc", columnDefinition = "TEXT")
    private String conductedByStatementCiphertext;

    @Transient
    @Encrypted(ciphertextField = "conductedByStatementCiphertext")
    private String conductedByStatement;

    @Column(name = "date_report_shared")
    private LocalDate dateReportShared;

    @Column(name = "generated_document_path")
    private String generatedDocumentPath;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReportStatus status;

    @Column(name = "review_comments_enc", columnDefinition = "TEXT")
    private String reviewCommentsCiphertext;

    @Transient
    @Encrypted(ciphertextField = "reviewCommentsCiphertext")
    private String reviewComments;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_id")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    /**
     * Delegates to the request, which is where the home and therefore the organisation live. A
     * report has no organisation of its own, and inventing one here would be a second opinion about
     * ownership that could disagree with the first.
     */
    @Override
    public Long owningOrganisationId() {
        return interviewRequest == null ? null : interviewRequest.owningOrganisationId();
    }

    public Long getId() {
        return id;
    }

    public InterviewRequest getInterviewRequest() {
        return interviewRequest;
    }

    public void setInterviewRequest(InterviewRequest interviewRequest) {
        this.interviewRequest = interviewRequest;
    }

    public User getVisitor() {
        return visitor;
    }

    public void setVisitor(User visitor) {
        this.visitor = visitor;
    }

    /**
     * The calendar date the interview was held - derived, so it can never disagree with the
     * timestamp the compliance rate is measured from. Everything that only wants to display a date
     * (the docx, the report view) keeps working unchanged.
     */
    @Transient
    public LocalDate getInterviewDate() {
        return heldAt == null ? null : heldAt.toLocalDate();
    }

    public String getInterviewLocation() {
        return interviewLocation;
    }

    public void setInterviewLocation(String interviewLocation) {
        this.interviewLocation = interviewLocation;
    }

    public LocalDateTime getHeldAt() {
        return heldAt;
    }

    public void setHeldAt(LocalDateTime heldAt) {
        this.heldAt = heldAt;
    }

    /**
     * Whether the interview met the statutory 72 hours - <em>derived</em>, never declared.
     *
     * <p>It used to be a stored Yes/No/Unknown the interviewer answered about their own compliance.
     * Two things were wrong with that: it asked someone to grade their own work, and its Unknown
     * state was counted as a breach while still sitting in the denominator, so an unanswered
     * question cost an organisation exactly what a real failure did.
     *
     * <p>Null means <b>the clock cannot be read</b>, which has more than one cause. That is an
     * exclusion from the rate, not a failure, and {@link ninja.samryecroft.returnhome.tracker.dashboard.RateStat}
     * keeps it visible rather than folding it into either side. The clock's start cannot be missing:
     * {@code returned_at} is NOT NULL as of V15.
     *
     * <p>The causes are a missing {@code heldAt}, and <b>an interview recorded before the return</b>
     * - an impossible sequence, and a data-entry error rather than a measurement. T187 found the
     * second one, and it was not a display problem: {@code !heldAt.isAfter(returnedAt.plus(72h))} is
     * <em>satisfied</em> by an impossible sequence, so such a report was counted in the NUMERATOR of
     * the published compliance rate. This method's own history above records fixing that defect in
     * mirror image - an unanswered question counted as a breach while sitting in the denominator.
     * <b>That was half of it. This is the other half: an impossible state counted as a pass.</b>
     *
     * <p>Equality stays measurable. A held time equal to the return is odd, not impossible, and
     * zero elapsed is a reading rather than a contradiction.
     */
    @Transient
    public Boolean getWithin72Hours() {
        if (heldAt == null || interviewRequest == null || interviewRequest.getReturnedAt() == null) {
            return null;
        }
        if (heldAt.isBefore(interviewRequest.getReturnedAt())) {
            return null;
        }
        return !heldAt.isAfter(interviewRequest.getReturnedAt().plus(DeadlineTracker.RETURN_WINDOW));
    }

    /**
     * Whether an explanation for a late interview is <em>owed</em> on this report.
     *
     * <p>True only when the window was measured <b>and</b> missed. Derived here rather than in a
     * template because two screens and the export all need it, and the rule is the kind that reads
     * as obvious and is not: the natural thing to write is "is the reason blank", which is a
     * question about a field rather than about whether anybody was ever asked.
     */
    @Transient
    public boolean isLateExplanationOwed() {
        return Boolean.FALSE.equals(getWithin72Hours());
    }

    /**
     * Whether this report has a <b>gap</b> where a late explanation should be, as opposed to a field
     * that simply does not apply.
     *
     * <p><b>A blank {@code ifNotWhyLate} means two opposite things</b> - the interview was on time so
     * nothing is owed, or it was late and nobody explained why - and the stored value is identical in
     * both. Reading the field alone cannot tell them apart, and both screens did exactly that: they
     * printed "Not answered" with the unanswered styling, and counted the blank into the section's
     * "N not answered" badge. So <b>a fully completed, on-time interview displayed "1 not answered"</b>
     * on the screen a reviewer approves from - a compliance-shaped number counting a question nobody
     * was owed - and the record stated that the visitor had declined to justify a breach that never
     * happened.
     *
     * <p>The harm landed on the honest, on-time visitor exactly as readily as on a confused one:
     * leaving an inapplicable field empty is the correct thing to do, and doing it correctly was
     * recorded as a refusal.
     *
     * <p>It is here, on the entity, because it is a fact about the report rather than a decision
     * either screen gets to make. A template that recomputed it would be the second definition -
     * and this is a rule where the wrong version is the one that looks natural.
     */
    @Transient
    public boolean isLateExplanationMissing() {
        return isLateExplanationOwed() && (ifNotWhyLate == null || ifNotWhyLate.isBlank());
    }

    public String getIfNotWhyLate() {
        return ifNotWhyLate;
    }

    public void setIfNotWhyLate(String ifNotWhyLate) {
        this.ifNotWhyLate = ifNotWhyLate;
    }

    public String getConsultationWithHomeStaff() {
        return consultationWithHomeStaff;
    }

    public void setConsultationWithHomeStaff(String consultationWithHomeStaff) {
        this.consultationWithHomeStaff = consultationWithHomeStaff;
    }

    public Boolean getPreviouslyMissing() {
        return previouslyMissing;
    }

    public void setPreviouslyMissing(Boolean previouslyMissing) {
        this.previouslyMissing = previouslyMissing;
    }

    public Integer getMissingOccasionsLast30Days() {
        return missingOccasionsLast30Days;
    }

    public void setMissingOccasionsLast30Days(Integer missingOccasionsLast30Days) {
        this.missingOccasionsLast30Days = missingOccasionsLast30Days;
    }

    public Boolean getConfidentialityExplained() {
        return confidentialityExplained;
    }

    public void setConfidentialityExplained(Boolean confidentialityExplained) {
        this.confidentialityExplained = confidentialityExplained;
    }

    public Boolean getInterviewAccepted() {
        return interviewAccepted;
    }

    public void setInterviewAccepted(Boolean interviewAccepted) {
        this.interviewAccepted = interviewAccepted;
    }

    public String getInterviewDeclinedReason() {
        return interviewDeclinedReason;
    }

    public void setInterviewDeclinedReason(String interviewDeclinedReason) {
        this.interviewDeclinedReason = interviewDeclinedReason;
    }

    public String getWhereWereYouWhileMissing() {
        return whereWereYouWhileMissing;
    }

    public void setWhereWereYouWhileMissing(String whereWereYouWhileMissing) {
        this.whereWereYouWhileMissing = whereWereYouWhileMissing;
    }

    public String getWhoWereYouWithWhileMissing() {
        return whoWereYouWithWhileMissing;
    }

    public void setWhoWereYouWithWhileMissing(String whoWereYouWithWhileMissing) {
        this.whoWereYouWithWhileMissing = whoWereYouWithWhileMissing;
    }

    public String getWhatMadeYouGoMissing() {
        return whatMadeYouGoMissing;
    }

    public void setWhatMadeYouGoMissing(String whatMadeYouGoMissing) {
        this.whatMadeYouGoMissing = whatMadeYouGoMissing;
    }

    public String getWhatCanBeDoneToAddressReasons() {
        return whatCanBeDoneToAddressReasons;
    }

    public void setWhatCanBeDoneToAddressReasons(String whatCanBeDoneToAddressReasons) {
        this.whatCanBeDoneToAddressReasons = whatCanBeDoneToAddressReasons;
    }

    public Boolean getConsideredSelfMissing() {
        return consideredSelfMissing;
    }

    public void setConsideredSelfMissing(Boolean consideredSelfMissing) {
        this.consideredSelfMissing = consideredSelfMissing;
    }

    public String getWhatDidYouDoWhileMissing() {
        return whatDidYouDoWhileMissing;
    }

    public void setWhatDidYouDoWhileMissing(String whatDidYouDoWhileMissing) {
        this.whatDidYouDoWhileMissing = whatDidYouDoWhileMissing;
    }

    public String getWhatHappenedWhenReturned() {
        return whatHappenedWhenReturned;
    }

    public void setWhatHappenedWhenReturned(String whatHappenedWhenReturned) {
        this.whatHappenedWhenReturned = whatHappenedWhenReturned;
    }

    public String getPreventFutureMissingSuggestions() {
        return preventFutureMissingSuggestions;
    }

    public void setPreventFutureMissingSuggestions(String preventFutureMissingSuggestions) {
        this.preventFutureMissingSuggestions = preventFutureMissingSuggestions;
    }

    public String getAdditionalCommentsFromYoungPerson() {
        return additionalCommentsFromYoungPerson;
    }

    public void setAdditionalCommentsFromYoungPerson(String additionalCommentsFromYoungPerson) {
        this.additionalCommentsFromYoungPerson = additionalCommentsFromYoungPerson;
    }

    public String getAdditionalInfoFromParentCarer() {
        return additionalInfoFromParentCarer;
    }

    public void setAdditionalInfoFromParentCarer(String additionalInfoFromParentCarer) {
        this.additionalInfoFromParentCarer = additionalInfoFromParentCarer;
    }

    public String getRisksIdentifiedDuringEpisode() {
        return risksIdentifiedDuringEpisode;
    }

    public void setRisksIdentifiedDuringEpisode(String risksIdentifiedDuringEpisode) {
        this.risksIdentifiedDuringEpisode = risksIdentifiedDuringEpisode;
    }

    public String getRisksIncreaseFutureEpisodes() {
        return risksIncreaseFutureEpisodes;
    }

    public void setRisksIncreaseFutureEpisodes(String risksIncreaseFutureEpisodes) {
        this.risksIncreaseFutureEpisodes = risksIncreaseFutureEpisodes;
    }

    public String getSafeguardingConcernsToExplore() {
        return safeguardingConcernsToExplore;
    }

    public void setSafeguardingConcernsToExplore(String safeguardingConcernsToExplore) {
        this.safeguardingConcernsToExplore = safeguardingConcernsToExplore;
    }

    public String getInfoToHelpLocateFuture() {
        return infoToHelpLocateFuture;
    }

    public void setInfoToHelpLocateFuture(String infoToHelpLocateFuture) {
        this.infoToHelpLocateFuture = infoToHelpLocateFuture;
    }

    public String getInterviewerComments() {
        return interviewerComments;
    }

    public void setInterviewerComments(String interviewerComments) {
        this.interviewerComments = interviewerComments;
    }

    public String getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(String recommendations) {
        this.recommendations = recommendations;
    }

    public String getConductedByStatement() {
        return conductedByStatement;
    }

    public void setConductedByStatement(String conductedByStatement) {
        this.conductedByStatement = conductedByStatement;
    }

    public LocalDate getDateReportShared() {
        return dateReportShared;
    }

    public void setDateReportShared(LocalDate dateReportShared) {
        this.dateReportShared = dateReportShared;
    }

    public String getGeneratedDocumentPath() {
        return generatedDocumentPath;
    }

    public void setGeneratedDocumentPath(String generatedDocumentPath) {
        this.generatedDocumentPath = generatedDocumentPath;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public void setStatus(ReportStatus status) {
        this.status = status;
    }

    public String getReviewComments() {
        return reviewComments;
    }

    public void setReviewComments(String reviewComments) {
        this.reviewComments = reviewComments;
    }

    public User getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(User reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
