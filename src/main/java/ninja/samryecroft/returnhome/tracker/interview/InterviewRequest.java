package ninja.samryecroft.returnhome.tracker.interview;

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
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.user.User;

@Entity
@Table(name = "interview_requests")
public class InterviewRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "child_id", nullable = false)
    private Child child;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_id", nullable = false)
    private Home home;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requested_by_id", nullable = false)
    private User requestedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InterviewStatus status = InterviewStatus.REQUESTED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "allocated_visitor_id")
    private User allocatedVisitor;

    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Column(columnDefinition = "TEXT")
    private String notes;

    // --- Details of the Young Person ---

    @Column(name = "legal_status")
    private String legalStatus;

    @Column(name = "missing_since")
    private LocalDateTime missingSince;

    @Column(name = "known_risks", columnDefinition = "TEXT")
    private String knownRisks;

    @Column(name = "childs_comments", columnDefinition = "TEXT")
    private String childsComments;

    @Column(name = "missing_episode_details", columnDefinition = "TEXT")
    private String missingEpisodeDetails;

    @Column(name = "missing_in_last_6_months")
    private Boolean missingInLast6Months;

    @Column(name = "missing_5_times_in_30_days")
    private Boolean missingFiveTimesIn30Days;

    @Column(name = "strategy_meeting_requested")
    private Boolean strategyMeetingRequested;

    @Column(name = "important_people", columnDefinition = "TEXT")
    private String importantPeople;

    @Column(name = "about_young_person", columnDefinition = "TEXT")
    private String aboutYoungPerson;

    // --- Details of Professionals ---

    @Column(name = "social_worker_details", columnDefinition = "TEXT")
    private String socialWorkerDetails;

    @Column(name = "consent_provided")
    private Boolean consentProvided;

    @Column(name = "placing_local_authority")
    private String placingLocalAuthority;

    @Column(name = "police_mfh_coordinator_details", columnDefinition = "TEXT")
    private String policeMfhCoordinatorDetails;

    @Column(name = "parents_details", columnDefinition = "TEXT")
    private String parentsDetails;

    @Column(name = "other_professionals", columnDefinition = "TEXT")
    private String otherProfessionals;

    // --- Your Details (the submitter) ---

    @Column(name = "submitter_organisation")
    private String submitterOrganisation;

    @Column(name = "submitter_name_and_role")
    private String submitterNameAndRole;

    @Column(name = "relationship_to_young_person")
    private String relationshipToYoungPerson;

    @Column(name = "submitter_address")
    private String submitterAddress;

    @Column(name = "submitter_contact_details")
    private String submitterContactDetails;

    @Column(name = "best_times_to_visit")
    private String bestTimesToVisit;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() {
        return id;
    }

    public Child getChild() {
        return child;
    }

    public void setChild(Child child) {
        this.child = child;
    }

    public Home getHome() {
        return home;
    }

    public void setHome(Home home) {
        this.home = home;
    }

    public User getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(User requestedBy) {
        this.requestedBy = requestedBy;
    }

    public InterviewStatus getStatus() {
        return status;
    }

    public void setStatus(InterviewStatus status) {
        this.status = status;
    }

    public User getAllocatedVisitor() {
        return allocatedVisitor;
    }

    public void setAllocatedVisitor(User allocatedVisitor) {
        this.allocatedVisitor = allocatedVisitor;
    }

    public LocalDateTime getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(LocalDateTime scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    public LocalDateTime getReturnedAt() {
        return returnedAt;
    }

    public void setReturnedAt(LocalDateTime returnedAt) {
        this.returnedAt = returnedAt;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
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
