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

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Position() {
    }

    public Position(String code, String name, String level, long salaryMin, long salaryMax, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.code = code;
        this.name = name;
        this.level = level;
        this.salaryMin = salaryMin;
        this.salaryMax = salaryMax;
        this.active = true;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getLevel() { return level; }
    public long getSalaryMin() { return salaryMin; }
    public long getSalaryMax() { return salaryMax; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
