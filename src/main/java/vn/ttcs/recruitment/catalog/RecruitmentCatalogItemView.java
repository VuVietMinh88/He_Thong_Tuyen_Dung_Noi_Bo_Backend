package vn.ttcs.recruitment.catalog;

import java.time.Instant;
import java.util.UUID;

public record RecruitmentCatalogItemView(UUID id, RecruitmentCatalogType type, String code, String name,
                                         int sortOrder, boolean active, Instant createdAt, Instant updatedAt) {

    static RecruitmentCatalogItemView from(RecruitmentCatalogItem item) {
        return new RecruitmentCatalogItemView(item.getId(), item.getCatalogType(), item.getCode(), item.getName(),
                item.getSortOrder(), item.isActive(), item.getCreatedAt(), item.getUpdatedAt());
    }
}
