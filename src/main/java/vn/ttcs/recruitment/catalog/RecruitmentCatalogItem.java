package vn.ttcs.recruitment.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

// One value of a shared recruitment catalog, for example the candidate source "LinkedIn".
@Entity
@Table(name = "recruitment_catalog_items")
public class RecruitmentCatalogItem {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RecruitmentCatalogType catalogType;

    // Unique inside its catalog type (recruitment_catalog_items_type_code_key).
    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false)
    private String name;

    // Display order inside the catalog type: smaller numbers first; PostgreSQL checks sortOrder >= 0.
    @Column(nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected RecruitmentCatalogItem() {
    }

    public RecruitmentCatalogItem(RecruitmentCatalogType catalogType, String code, String name, int sortOrder,
                                  Instant createdAt) {
        this.id = UUID.randomUUID();
        this.catalogType = catalogType;
        this.code = code;
        this.name = name;
        this.sortOrder = sortOrder;
        this.active = true;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public RecruitmentCatalogType getCatalogType() { return catalogType; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public int getSortOrder() { return sortOrder; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
