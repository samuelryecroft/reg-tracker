package ninja.samryecroft.returnhome.tracker.report;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import ninja.samryecroft.returnhome.tracker.interview.InterviewRequest;
import ninja.samryecroft.returnhome.tracker.user.User;

@Entity
@Table(name = "interview_reports")
public class InterviewReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interview_request_id", nullable = false, unique = true)
    private InterviewRequest interviewRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visitor_id", nullable = false)
    private User visitor;

    @Column(name = "interview_date")
    private LocalDate interviewDate;

    @Column(name = "interview_location")
    private String interviewLocation;

    // --- Details ---

    @Column(name = "within_72_hours")
    private Boolean within72Hours;

    @Column(name = "if_not_why_late", columnDefinition = "TEXT")
    private String ifNotWhyLate;

    @Column(name = "consultation_with_home_staff", columnDefinition = "TEXT")
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

    @Column(name = "interview_declined_reason", columnDefinition = "TEXT")
    private String interviewDeclinedReason;

    @Column(name = "where_were_you_while_missing", columnDefinition = "TEXT")
    private String whereWereYouWhileMissing;

    @Column(name = "who_were_you_with_while_missing", columnDefinition = "TEXT")
    private String whoWereYouWithWhileMissing;

    @Column(name = "what_made_you_go_missing", columnDefinition = "TEXT")
    private String whatMadeYouGoMissing;

    @Column(name = "what_can_be_done_to_address_reasons", columnDefinition = "TEXT")
    private String whatCanBeDoneToAddressReasons;

    @Column(name = "considered_self_missing")
    private Boolean consideredSelfMissing;

    @Column(name = "what_did_you_do_while_missing", columnDefinition = "TEXT")
    private String whatDidYouDoWhileMissing;

    @Column(name = "what_happened_when_returned", columnDefinition = "TEXT")
    private String whatHappenedWhenReturned;

    @Column(name = "prevent_future_missing_suggestions", columnDefinition = "TEXT")
    private String preventFutureMissingSuggestions;

    @Column(name = "additional_comments_from_young_person", columnDefinition = "TEXT")
    private String additionalCommentsFromYoungPerson;

    @Column(name = "additional_info_from_parent_carer", columnDefinition = "TEXT")
    private String additionalInfoFromParentCarer;

    // --- Future Incidents ---

    @Column(name = "risks_identified_during_episode", columnDefinition = "TEXT")
    private String risksIdentifiedDuringEpisode;

    @Column(name = "risks_increase_future_episodes", columnDefinition = "TEXT")
    private String risksIncreaseFutureEpisodes;

    @Column(name = "safeguarding_concerns_to_explore", columnDefinition = "TEXT")
    private String safeguardingConcernsToExplore;

    @Column(name = "info_to_help_locate_future", columnDefinition = "TEXT")
    private String infoToHelpLocateFuture;

    // --- Interviewer's Comments / Recommendations / Declaration ---

    @Column(name = "interviewer_comments", columnDefinition = "TEXT")
    private String interviewerComments;

    @Column(name = "recommendations", columnDefinition = "TEXT")
    private String recommendations;

    @Column(name = "conducted_by_statement", columnDefinition = "TEXT")
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

    @Column(name = "review_comments", columnDefinition = "TEXT")
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

    public LocalDate getInterviewDate() {
        return interviewDate;
    }

    public void setInterviewDate(LocalDate interviewDate) {
        this.interviewDate = interviewDate;
    }

    public String getInterviewLocation() {
        return interviewLocation;
    }

    public void setInterviewLocation(String interviewLocation) {
        this.interviewLocation = interviewLocation;
    }

    public Boolean getWithin72Hours() {
        return within72Hours;
    }

    public void setWithin72Hours(Boolean within72Hours) {
        this.within72Hours = within72Hours;
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
