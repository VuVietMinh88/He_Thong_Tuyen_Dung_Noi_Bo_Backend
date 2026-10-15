package vn.ttcs.recruitment.jobposting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// Task 302 (story S3-09): a job posting made from an approved requisition. It keeps its own copy of the title, the job
// description and the candidate requirements, so editing the posting never changes the requisition. Position,
// department, headcount and the salary shown are read from the requisition and are not stored here.
@Entity
@Table(name = "job_postings")
public class JobPosting {

    @Id
    private UUID id;

    // The source requisition never changes; V21 allows one posting per requisition.
    @Column(nullable = false, updatable = false)
    private UUID requisitionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobPostingStatus status;

    // 1 when created, one more for every save and every status change. Clients send it back as expectedRevision; the
    // services compare it under the row lock themselves, so this is not a JPA @Version.
    @Column(nullable = false)
    private int revision;

    // At most 255 characters, like positions.name, so the inherited position name always fits.
    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String jobDescription;

    @Column(nullable = false, columnDefinition = "text")
    private String candidateRequirements;

    // Optional; V21 checks it is not blank and at most 10,000 characters.
    @Column(columnDefinition = "text")
    private String benefits;

    // Values of the shared catalogs WORK_LOCATION and EMPLOYMENT_TYPE (V10). The database only checks that they exist;
    // the API checks their catalog type.
    private UUID workLocationId;

    private UUID employmentTypeId;

    // Last day to apply, inclusive, a calendar date in the business time zone.
    private LocalDate applicationDeadline;

    // Whether candidates see the requisition's approved salary proposal; no amount is stored.
    @Column(nullable = false)
    private boolean showSalary;

    @Column(nullable = false, updatable = false)
    private UUID createdBy;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // The last content edit; status changes do not touch it.
    @Column(nullable = false)
    private Instant updatedAt;

    // Task 310: who published the posting and when, set once by publish(); both null until then. V23 requires both on
    // a published posting and neither before it.
    private UUID publishedBy;

    private Instant publishedAt;

    protected JobPosting() {
    }

    // A new posting always starts as a draft at revision 1.
    public JobPosting(UUID requisitionId, JobPostingContent content, UUID createdBy, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.requisitionId = requisitionId;
        this.status = JobPostingStatus.DRAFT;
        this.revision = 1;
        this.title = content.title();
        this.jobDescription = content.jobDescription();
        this.candidateRequirements = content.candidateRequirements();
        this.benefits = content.benefits();
        this.workLocationId = content.workLocationId();
        this.employmentTypeId = content.employmentTypeId();
        this.applicationDeadline = content.applicationDeadline();
        this.showSalary = content.showSalary();
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    // Task 303: a save replaces the whole content (PUT). The caller holds the posting row lock and has checked the
    // status and the expected revision.
    void replaceContent(JobPostingContent content, Instant updatedAt) {
        this.title = content.title();
        this.jobDescription = content.jobDescription();
        this.candidateRequirements = content.candidateRequirements();
        this.benefits = content.benefits();
        this.workLocationId = content.workLocationId();
        this.employmentTypeId = content.employmentTypeId();
        this.applicationDeadline = content.applicationDeadline();
        this.showSalary = content.showSalary();
        this.revision++;
        this.updatedAt = updatedAt;
    }

    // Task 308: a step of the review workflow (submit, a decision, the publication). The caller holds the posting row
    // lock and has checked that the posting is in expected and that the client saw its latest revision. A status
    // change is a new revision too, so a client still showing the old status is refused. updatedAt is the time of the
    // last content edit and does not change here.
    void moveTo(JobPostingStatus expected, JobPostingStatus next) {
        if (status != expected) {
            throw new IllegalStateException("Job posting " + id + " is " + status + ", not " + expected);
        }
        this.status = next;
        this.revision++;
    }

    // Task 310: the publication of an approved posting, with its trail: the publisher (from the token, never the body)
    // and the server time, the same values as the PUBLISHED event. A posting is published once; nothing clears the
    // trail.
    void publish(UUID publishedBy, Instant publishedAt) {
        if (this.publishedAt != null) {
            throw new IllegalStateException("Job posting " + id + " was already published at " + this.publishedAt);
        }
        moveTo(JobPostingStatus.APPROVED, JobPostingStatus.PUBLISHED);
        this.publishedBy = publishedBy;
        this.publishedAt = publishedAt;
    }

    public UUID getId() { return id; }
    public UUID getRequisitionId() { return requisitionId; }
    public JobPostingStatus getStatus() { return status; }
    public int getRevision() { return revision; }
    public String getTitle() { return title; }
    public String getJobDescription() { return jobDescription; }
    public String getCandidateRequirements() { return candidateRequirements; }
    public String getBenefits() { return benefits; }
    public UUID getWorkLocationId() { return workLocationId; }
    public UUID getEmploymentTypeId() { return employmentTypeId; }
    public LocalDate getApplicationDeadline() { return applicationDeadline; }
    public boolean isShowSalary() { return showSalary; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public UUID getPublishedBy() { return publishedBy; }
    public Instant getPublishedAt() { return publishedAt; }
}
