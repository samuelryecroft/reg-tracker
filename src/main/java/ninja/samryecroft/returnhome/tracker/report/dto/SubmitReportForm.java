package ninja.samryecroft.returnhome.tracker.report.dto;

import java.time.LocalDate;

public class SubmitReportForm {

    /** Required to submit for review, but not to save a draft - enforced manually by the
     * controller rather than with a Bean Validation annotation, since this DTO backs both actions. */
    private LocalDate interviewDate;

    private String interviewLocation;

    // --- Details ---

    private Boolean within72Hours;
    private String ifNotWhyLate;
    private String consultationWithHomeStaff;
    private Boolean previouslyMissing;
    private Integer missingOccasionsLast30Days;
    private Boolean confidentialityExplained;

    // --- Return Home Interview ---

    private Boolean interviewAccepted;
    private String interviewDeclinedReason;
    private String whereWereYouWhileMissing;
    private String whoWereYouWithWhileMissing;
    private String whatMadeYouGoMissing;
    private String whatCanBeDoneToAddressReasons;
    private Boolean consideredSelfMissing;
    private String whatDidYouDoWhileMissing;
    private String whatHappenedWhenReturned;
    private String preventFutureMissingSuggestions;
    private String additionalCommentsFromYoungPerson;
    private String additionalInfoFromParentCarer;

    // --- Future Incidents ---

    private String risksIdentifiedDuringEpisode;
    private String risksIncreaseFutureEpisodes;
    private String safeguardingConcernsToExplore;
    private String infoToHelpLocateFuture;

    // --- Interviewer's Comments / Recommendations / Declaration ---

    private String interviewerComments;
    private String recommendations;
    private String conductedByStatement;
    private LocalDate dateReportShared;

    /** Only used by the Reviewer's approve/reject action - required (non-blank) to reject. */
    private String reviewComments;

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

    public String getReviewComments() {
        return reviewComments;
    }

    public void setReviewComments(String reviewComments) {
        this.reviewComments = reviewComments;
    }
}
