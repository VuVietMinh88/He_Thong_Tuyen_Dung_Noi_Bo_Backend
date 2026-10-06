package vn.ttcs.recruitment.competency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// A reusable set of weighted evaluation criteria. Positions point to it through positions.competency_framework_id,
// so several positions share one framework. Its criteria live in CompetencyCriterion rows.
@Entity
@Table(name = "competency_frameworks")
public class CompetencyFramework {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CompetencyFrameworkStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected CompetencyFramework() {
    }

    // A new framework starts as DRAFT. CompetencyFrameworkService calls activate() before saving it when the
    // request asks for ACTIVE.
    public CompetencyFramework(String code, String name, String description, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.code = code;
        this.name = name;
        this.description = description;
        this.status = CompetencyFrameworkStatus.DRAFT;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    // Edits the framework itself; its criteria are separate rows. The status only changes through activate().
    public void update(String code, String name, String description, Instant updatedAt) {
        this.code = code;
        this.name = name;
        this.description = description;
        this.updatedAt = updatedAt;
    }

    // Marks the framework complete (Jira 213). The caller must first check that the criteria weights total
    // exactly 100%. There is no way back to DRAFT: positions and interview forms rely on an ACTIVE framework
    // staying complete.
    public void activate() {
        this.status = CompetencyFrameworkStatus.ACTIVE;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public CompetencyFrameworkStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
