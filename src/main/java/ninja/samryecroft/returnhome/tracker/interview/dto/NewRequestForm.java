package ninja.samryecroft.returnhome.tracker.interview.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public class NewRequestForm {

    @NotNull
    private Long childId;

    private LocalDateTime returnedAt;

    // --- Details of the Young Person ---

    private String legalStatus;
    private LocalDateTime missingSince;
    private String knownRisks;
    private String childsComments;
    private String missingEpisodeDetails;
    private Boolean missingInLast6Months;
    private Boolean missingFiveTimesIn30Days;
    private Boolean strategyMeetingRequested;
    private String importantPeople;
    private String aboutYoungPerson;

    // --- Details of Professionals ---

    private String socialWorkerDetails;
    private Boolean consentProvided;
    private String placingLocalAuthority;
    private String policeMfhCoordinatorDetails;
    private String parentsDetails;
    private String otherProfessionals;

    // --- Your Details (the submitter) ---

    private String submitterOrganisation;
    private String submitterNameAndRole;
    private String relationshipToYoungPerson;
    private String submitterAddress;
    private String submitterContactDetails;
    private String bestTimesToVisit;

    private String notes;

    public Long getChildId() {
        return childId;
    }

    public void setChildId(Long childId) {
        this.childId = childId;
    }

    public LocalDateTime getReturnedAt() {
        return returnedAt;
    }

    public void setReturnedAt(LocalDateTime returnedAt) {
        this.returnedAt = returnedAt;
    }

    public String getLegalStatus() {
        return legalStatus;
    }

    public void setLegalStatus(String legalStatus) {
        this.legalStatus = legalStatus;
    }

    public LocalDateTime getMissingSince() {
        return missingSince;
    }

    public void setMissingSince(LocalDateTime missingSince) {
        this.missingSince = missingSince;
    }

    public String getKnownRisks() {
        return knownRisks;
    }

    public void setKnownRisks(String knownRisks) {
        this.knownRisks = knownRisks;
    }

    public String getChildsComments() {
        return childsComments;
    }

    public void setChildsComments(String childsComments) {
        this.childsComments = childsComments;
    }

    public String getMissingEpisodeDetails() {
        return missingEpisodeDetails;
    }

    public void setMissingEpisodeDetails(String missingEpisodeDetails) {
        this.missingEpisodeDetails = missingEpisodeDetails;
    }

    public Boolean getMissingInLast6Months() {
        return missingInLast6Months;
    }

    public void setMissingInLast6Months(Boolean missingInLast6Months) {
        this.missingInLast6Months = missingInLast6Months;
    }

    public Boolean getMissingFiveTimesIn30Days() {
        return missingFiveTimesIn30Days;
    }

    public void setMissingFiveTimesIn30Days(Boolean missingFiveTimesIn30Days) {
        this.missingFiveTimesIn30Days = missingFiveTimesIn30Days;
    }

    public Boolean getStrategyMeetingRequested() {
        return strategyMeetingRequested;
    }

    public void setStrategyMeetingRequested(Boolean strategyMeetingRequested) {
        this.strategyMeetingRequested = strategyMeetingRequested;
    }

    public String getImportantPeople() {
        return importantPeople;
    }

    public void setImportantPeople(String importantPeople) {
        this.importantPeople = importantPeople;
    }

    public String getAboutYoungPerson() {
        return aboutYoungPerson;
    }

    public void setAboutYoungPerson(String aboutYoungPerson) {
        this.aboutYoungPerson = aboutYoungPerson;
    }

    public String getSocialWorkerDetails() {
        return socialWorkerDetails;
    }

    public void setSocialWorkerDetails(String socialWorkerDetails) {
        this.socialWorkerDetails = socialWorkerDetails;
    }

    public Boolean getConsentProvided() {
        return consentProvided;
    }

    public void setConsentProvided(Boolean consentProvided) {
        this.consentProvided = consentProvided;
    }

    public String getPlacingLocalAuthority() {
        return placingLocalAuthority;
    }

    public void setPlacingLocalAuthority(String placingLocalAuthority) {
        this.placingLocalAuthority = placingLocalAuthority;
    }

    public String getPoliceMfhCoordinatorDetails() {
        return policeMfhCoordinatorDetails;
    }

    public void setPoliceMfhCoordinatorDetails(String policeMfhCoordinatorDetails) {
        this.policeMfhCoordinatorDetails = policeMfhCoordinatorDetails;
    }

    public String getParentsDetails() {
        return parentsDetails;
    }

    public void setParentsDetails(String parentsDetails) {
        this.parentsDetails = parentsDetails;
    }

    public String getOtherProfessionals() {
        return otherProfessionals;
    }

    public void setOtherProfessionals(String otherProfessionals) {
        this.otherProfessionals = otherProfessionals;
    }

    public String getSubmitterOrganisation() {
        return submitterOrganisation;
    }

    public void setSubmitterOrganisation(String submitterOrganisation) {
        this.submitterOrganisation = submitterOrganisation;
    }

    public String getSubmitterNameAndRole() {
        return submitterNameAndRole;
    }

    public void setSubmitterNameAndRole(String submitterNameAndRole) {
        this.submitterNameAndRole = submitterNameAndRole;
    }

    public String getRelationshipToYoungPerson() {
        return relationshipToYoungPerson;
    }

    public void setRelationshipToYoungPerson(String relationshipToYoungPerson) {
        this.relationshipToYoungPerson = relationshipToYoungPerson;
    }

    public String getSubmitterAddress() {
        return submitterAddress;
    }

    public void setSubmitterAddress(String submitterAddress) {
        this.submitterAddress = submitterAddress;
    }

    public String getSubmitterContactDetails() {
        return submitterContactDetails;
    }

    public void setSubmitterContactDetails(String submitterContactDetails) {
        this.submitterContactDetails = submitterContactDetails;
    }

    public String getBestTimesToVisit() {
        return bestTimesToVisit;
    }

    public void setBestTimesToVisit(String bestTimesToVisit) {
        this.bestTimesToVisit = bestTimesToVisit;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
