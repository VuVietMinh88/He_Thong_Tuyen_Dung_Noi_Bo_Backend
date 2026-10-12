package vn.ttcs.recruitment.position;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "positions")
public class Position {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 50)
    private String level;

    // Salary band in VND (whole dong); PostgreSQL checks 0 <= salaryMin <= salaryMax.
    @Column(nullable = false)
    private long salaryMin;

    @Column(nullable = false)
    private long salaryMax;

    @Column(nullable = false)
    private boolean active;

    // Competency framework used to evaluate candidates for this position; null until one is assigned.
    // Several positions may share one framework, so its criteria are never copied per position.
    // update() leaves it alone: only useCompetencyFramework() changes it.
    private UUID competencyFrameworkId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Position() {
    }

    public Position(String code, String name, String level, long salaryMin, long salaryMax, Instant createdAt) {
        this(code, name, level, salaryMin, salaryMax, true, createdAt);
    }

    public Position(String code, String name, String level, long salaryMin, long salaryMax, boolean active,
                    Instant createdAt) {
        this.id = UUID.randomUUID();
        this.code = code;
        this.name = name;
        this.level = level;
        this.salaryMin = salaryMin;
        this.salaryMax = salaryMax;
        this.active = active;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void update(String code, String name, String level, long salaryMin, long salaryMax, boolean active,
                       Instant updatedAt) {
        this.code = code;
        this.name = name;
        this.level = level;
        this.salaryMin = salaryMin;
        this.salaryMax = salaryMax;
        this.active = active;
        this.updatedAt = updatedAt;
    }

    // Jira 214: the position now uses this shared framework, or none when frameworkId is null. Only the id is
    // stored, so the criteria stay in the framework and an edit of the framework reaches every position using it.
    // PositionService checks that the framework exists and is ACTIVE before calling this.
    public void useCompetencyFramework(UUID frameworkId, Instant updatedAt) {
        this.competencyFrameworkId = frameworkId;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getLevel() { return level; }
    public long getSalaryMin() { return salaryMin; }
    public long getSalaryMax() { return salaryMax; }
    public boolean isActive() { return active; }
    public UUID getCompetencyFrameworkId() { return competencyFrameworkId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
