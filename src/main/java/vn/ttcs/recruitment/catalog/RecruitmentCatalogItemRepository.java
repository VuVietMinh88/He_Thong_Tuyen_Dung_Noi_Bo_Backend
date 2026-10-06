package vn.ttcs.recruitment.catalog;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecruitmentCatalogItemRepository extends JpaRepository<RecruitmentCatalogItem, UUID> {

    // Display order: smaller sortOrder first. Equal sortOrder values are allowed, so the name users see breaks ties;
    // code is unique inside one catalog type, so two values with the same name still keep the same order every time.
    @Query("""
            select i from RecruitmentCatalogItem i
            where i.catalogType = :type and i.active in :activeValues
            order by i.sortOrder, i.name, i.code
            """)
    List<RecruitmentCatalogItem> findForDisplay(@Param("type") RecruitmentCatalogType type,
                                                @Param("activeValues") Collection<Boolean> activeValues);

    // An id from another catalog type is treated as not found, so the URL type always matches the item.
    Optional<RecruitmentCatalogItem> findByIdAndCatalogType(UUID id, RecruitmentCatalogType catalogType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from RecruitmentCatalogItem i where i.id = :id and i.catalogType = :type")
    Optional<RecruitmentCatalogItem> findByIdAndCatalogTypeForUpdate(@Param("id") UUID id,
                                                                     @Param("type") RecruitmentCatalogType type);

    // Locks every value of one catalog type, always in id order so two callers never lock them crosswise.
    // A second reorder of the same type waits here until the first one commits, then reads the saved rows.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from RecruitmentCatalogItem i where i.catalogType = :type order by i.id")
    List<RecruitmentCatalogItem> findAllByCatalogTypeForUpdate(@Param("type") RecruitmentCatalogType type);

    boolean existsByCatalogTypeAndCode(RecruitmentCatalogType catalogType, String code);

    boolean existsByCatalogTypeAndCodeAndIdNot(RecruitmentCatalogType catalogType, String code, UUID id);

    // Null when the catalog type has no values yet.
    @Query("select max(i.sortOrder) from RecruitmentCatalogItem i where i.catalogType = :type")
    Integer findMaxSortOrder(@Param("type") RecruitmentCatalogType type);
}
