package ninja.samryecroft.returnhome.tracker.interview;

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
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import ninja.samryecroft.returnhome.tracker.fieldcrypto.Encrypted;
import ninja.samryecroft.returnhome.tracker.fieldcrypto.EncryptedEntity;
import ninja.samryecroft.returnhome.tracker.fieldcrypto.EncryptedFieldListener;
import java.time.LocalDateTime;
import ninja.samryecroft.returnhome.tracker.child.Child;
import ninja.samryecroft.returnhome.tracker.home.Home;
import ninja.samryecroft.returnhome.tracker.user.User;

@Entity
@Table(name = "interview_requests")
@EntityListeners(EncryptedFieldListener.class)
public class InterviewRequest implements EncryptedEntity {

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

    /**
     * When the child returned - the start of the statutory 72-hour clock, and required as of V15.
     *
     * <p>Made non-null while the database was still empty. Afterwards it becomes a backfill with a
     * policy question attached ("what return time do we invent for a record that never had one?"),
     * and there is no honest answer to that for a statutory record.
     */
    @Column(name = "returned_at", nullable = false)
    private LocalDateTime returnedAt;

    /**
     * When a coordinator allocated this request to a visitor. Not encrypted, for the same reason as
     * {@code heldAt}: allocation latency is only measurable if it can be aggregated (see V15).
     */
    @Column(name = "allocated_at")
    private LocalDateTime allocatedAt;

    @Column(name = "notes_enc", columnDefinition = "TEXT")
    private String notesCiphertext;

    @Transient
    @Encrypted(ciphertextField = "notesCiphertext")
    private String notes;

    // --- Details of the Young Person ---

    @Column(name = "legal_status_enc", columnDefinition = "TEXT")
    private String legalStatusCiphertext;

    @Transient
    @Encrypted(ciphertextField = "legalStatusCiphertext")
    private String legalStatus;

    @Column(name = "missing_since")
    private LocalDateTime missingSince;

    @Column(name = "known_risks_enc", columnDefinition = "TEXT")
    private String knownRisksCiphertext;

    @Transient
    @Encrypted(ciphertextField = "knownRisksCiphertext")
    private String knownRisks;

    @Column(name = "childs_comments_enc", columnDefinition = "TEXT")
    private String childsCommentsCiphertext;

    @Transient
    @Encrypted(ciphertextField = "childsCommentsCiphertext")
    private String childsComments;

    @Column(name = "missing_episode_details_enc", columnDefinition = "TEXT")
    private String missingEpisodeDetailsCiphertext;

    @Transient
    @Encrypted(ciphertextField = "missingEpisodeDetailsCiphertext")
    private String missingEpisodeDetails;

    @Column(name = "missing_in_last_6_months")
    private Boolean missingInLast6Months;

    @Column(name = "missing_5_times_in_30_days")
    private Boolean missingFiveTimesIn30Days;

    @Column(name = "strategy_meeting_requested")
    private Boolean strategyMeetingRequested;

    @Column(name = "important_people_enc", columnDefinition = "TEXT")
    private String importantPeopleCiphertext;

    @Transient
    @Encrypted(ciphertextField = "importantPeopleCiphertext")
    private String importantPeople;

    @Column(name = "about_young_person_enc", columnDefinition = "TEXT")
    private String aboutYoungPersonCiphertext;

    @Transient
    @Encrypted(ciphertextField = "aboutYoungPersonCiphertext")
    private String aboutYoungPerson;

    // --- Details of Professionals ---

    @Column(name = "social_worker_details_enc", columnDefinition = "TEXT")
    private String socialWorkerDetailsCiphertext;

    @Transient
    @Encrypted(ciphertextField = "socialWorkerDetailsCiphertext")
    private String socialWorkerDetails;

    @Column(name = "consent_provided")
    private Boolean consentProvided;

    @Column(name = "placing_local_authority_enc", columnDefinition = "TEXT")
    private String placingLocalAuthorityCiphertext;

    @Transient
    @Encrypted(ciphertextField = "placingLocalAuthorityCiphertext")
    private String placingLocalAuthority;

    @Column(name = "police_mfh_coordinator_details_enc", columnDefinition = "TEXT")
    private String policeMfhCoordinatorDetailsCiphertext;

    @Transient
    @Encrypted(ciphertextField = "policeMfhCoordinatorDetailsCiphertext")
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

    /**
     * The canonical walk - request to home to organisation - resolved from the domain model rather
     * than from the request that asked for it, so the key is chosen independently of the access
     * check. This is the same path the document encryption uses, deliberately: two encryption
     * schemes that disagree about who owns a record would be worse than either alone.
     */
    @Override
    public Long owningOrganisationId() {
        if (home == null || home.getOrganisation() == null) {
            return null;
        }
        return home.getOrganisation().getId();
    }

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

    public LocalDateTime getAllocatedAt() {
        return allocatedAt;
    }

    public void setAllocatedAt(LocalDateTime allocatedAt) {
        this.allocatedAt = allocatedAt;
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
