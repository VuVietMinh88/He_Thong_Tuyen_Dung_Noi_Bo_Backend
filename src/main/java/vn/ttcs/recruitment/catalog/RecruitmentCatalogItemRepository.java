package vn.ttcs.recruitment.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RecruitmentCatalogItemRepository extends JpaRepository<RecruitmentCatalogItem, UUID> {
}
