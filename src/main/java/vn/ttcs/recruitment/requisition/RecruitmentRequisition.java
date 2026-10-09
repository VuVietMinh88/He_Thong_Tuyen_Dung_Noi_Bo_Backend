package vn.ttcs.recruitment.requisition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "recruitment_requisitions")
public class RecruitmentRequisition {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID positionId;

    @Column(nullable = false)
    private UUID departmentId;

    // Number of people requested; PostgreSQL checks headcount > 0.
    @Column(nullable = false)
    private int headcount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequisitionReason reason;

    // Proposed salary band in VND (whole dong). A draft may leave either end empty (null);
    // PostgreSQL checks both are >= 0 and min <= max when both are present.
    private Long proposedSalaryMin;

    private Long proposedSalaryMax;

    @Column(columnDefinition = "text")
    private String salaryJustification;

    private LocalDate neededBy;

    @Column(columnDefinition = "text")
    private String jobDescription;

    @Column(columnDefinition = "text")
    private String candidateRequirements;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RequisitionStatus status;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected RecruitmentRequisition() {
    }

    // A new requisition always starts as a draft. Optional parts (salary, date, texts) may be null.
    public RecruitmentRequisition(UUID positionId, UUID departmentId, int headcount, RequisitionReason reason,
                                  Long proposedSalaryMin, Long proposedSalaryMax, String salaryJustification,
                                  LocalDate neededBy, String jobDescription, String candidateRequirements,
                                  UUID createdBy, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.positionId = positionId;
        this.departmentId = departmentId;
        this.headcount = headcount;
        this.reason = reason;
        this.proposedSalaryMin = proposedSalaryMin;
        this.proposedSalaryMax = proposedSalaryMax;
        this.salaryJustification = salaryJustification;
        this.neededBy = neededBy;
        this.jobDescription = jobDescription;
        this.candidateRequirements = candidateRequirements;
        this.status = RequisitionStatus.DRAFT;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getPositionId() { return positionId; }
    public UUID getDepartmentId() { return departmentId; }
    public int getHeadcount() { return headcount; }
    public RequisitionReason getReason() { return reason; }
    public Long getProposedSalaryMin() { return proposedSalaryMin; }
    public Long getProposedSalaryMax() { return proposedSalaryMax; }
    public String getSalaryJustification() { return salaryJustification; }
    public LocalDate getNeededBy() { return neededBy; }
    public String getJobDescription() { return jobDescription; }
    public String getCandidateRequirements() { return candidateRequirements; }
    public RequisitionStatus getStatus() { return status; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
