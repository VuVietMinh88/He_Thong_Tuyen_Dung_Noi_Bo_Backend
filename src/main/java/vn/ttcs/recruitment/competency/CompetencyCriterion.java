package vn.ttcs.recruitment.competency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

// One evaluation criterion of a competency framework, e.g. "Giao tiếp" with weight 30.00 (%).
@Entity
@Table(name = "competency_criteria")
public class CompetencyCriterion {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID frameworkId;

    @Column(nullable = false)
    private String name;

    @Column(length = 1000)
    private String description;

    // Percentage with two decimals, stored as NUMERIC(5,2). BigDecimal keeps 33.33 exact, unlike double.
    // PostgreSQL checks 0 < weight <= 100; the total of a framework is checked by the API, not here.
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal weight;

    // Display order inside the framework, starting at 1 and unique per framework.
    @Column(nullable = false)
    private int sortOrder;

    protected CompetencyCriterion() {
    }

    public CompetencyCriterion(UUID frameworkId, String name, String description, BigDecimal weight, int sortOrder) {
        this.id = UUID.randomUUID();
        this.frameworkId = frameworkId;
        this.name = name;
        this.description = description;
        this.weight = weight;
        this.sortOrder = sortOrder;
    }

    public UUID getId() { return id; }
    public UUID getFrameworkId() { return frameworkId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public BigDecimal getWeight() { return weight; }
    public int getSortOrder() { return sortOrder; }
}
