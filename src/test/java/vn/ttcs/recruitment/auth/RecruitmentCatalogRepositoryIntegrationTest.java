package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import vn.ttcs.recruitment.catalog.RecruitmentCatalogItem;
import vn.ttcs.recruitment.catalog.RecruitmentCatalogItemRepository;
import vn.ttcs.recruitment.catalog.RecruitmentCatalogType;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Hibernate runs with ddl-auto=validate, so this context only starts when the entity matches V10.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecruitmentCatalogRepositoryIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-07T00:00:00Z");

    @Autowired private RecruitmentCatalogItemRepository items;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void resetCatalogs() {
        jdbc.update("DELETE FROM recruitment_catalog_items");
    }

    @Test
    void savesAndReloadsAValueOfEveryCatalogType() {
        // Every Java enum value must pass the SQL CHECK and be stored by its name.
        for (RecruitmentCatalogType type : RecruitmentCatalogType.values()) {
            var item = new RecruitmentCatalogItem(type, "OTHER", "Khác", type.ordinal(), CREATED_AT);
            items.saveAndFlush(item);

            assertThat(jdbc.queryForObject("SELECT catalog_type FROM recruitment_catalog_items WHERE id=?",
                    String.class, item.getId())).as(type.name()).isEqualTo(type.name());
        }
        assertThat(items.count()).isEqualTo(RecruitmentCatalogType.values().length);

        var source = new RecruitmentCatalogItem(RecruitmentCatalogType.CANDIDATE_SOURCE, "REFERRAL",
                "Nhân viên giới thiệu", 7, CREATED_AT);
        items.saveAndFlush(source);

        var row = jdbc.queryForMap("SELECT * FROM recruitment_catalog_items WHERE id=?", source.getId());
        assertThat(row.get("catalog_type")).isEqualTo("CANDIDATE_SOURCE");
        assertThat(row.get("code")).isEqualTo("REFERRAL");
        assertThat(row.get("name")).isEqualTo("Nhân viên giới thiệu");
        assertThat(row.get("sort_order")).isEqualTo(7);
        assertThat(row.get("active")).isEqualTo(true);
        assertThat(row.get("created_at")).isEqualTo(Timestamp.from(CREATED_AT));
        assertThat(row.get("updated_at")).isEqualTo(Timestamp.from(CREATED_AT));

        var reloaded = items.findById(source.getId()).orElseThrow();
        assertThat(reloaded.getCatalogType()).isEqualTo(RecruitmentCatalogType.CANDIDATE_SOURCE);
        assertThat(reloaded.getCode()).isEqualTo("REFERRAL");
        assertThat(reloaded.getName()).isEqualTo("Nhân viên giới thiệu");
        assertThat(reloaded.getSortOrder()).isEqualTo(7);
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void readsRowsWrittenBySqlIntoTheMatchingEnumValue() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_catalog_items (id,catalog_type,code,name,sort_order,active,created_at,updated_at)
                VALUES (?,'EMPLOYMENT_TYPE','PART_TIME','Bán thời gian',3,FALSE,?,?)
                """, id, Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));

        var item = items.findById(id).orElseThrow();
        assertThat(item.getCatalogType()).isEqualTo(RecruitmentCatalogType.EMPLOYMENT_TYPE);
        assertThat(item.getCode()).isEqualTo("PART_TIME");
        assertThat(item.getName()).isEqualTo("Bán thời gian");
        assertThat(item.getSortOrder()).isEqualTo(3);
        assertThat(item.isActive()).isFalse();
    }

    @Test
    void databaseConstraintsRejectInvalidItemsSavedThroughJpa() {
        items.saveAndFlush(new RecruitmentCatalogItem(RecruitmentCatalogType.REJECTION_REASON, "SKILL_MISMATCH",
                "Chưa phù hợp kỹ năng", 1, CREATED_AT));
        // Same code in another catalog type is a different value, not a duplicate.
        items.saveAndFlush(new RecruitmentCatalogItem(RecruitmentCatalogType.CANDIDATE_SOURCE, "SKILL_MISMATCH",
                "Mã trùng ở danh mục khác", 1, CREATED_AT));

        assertThatThrownBy(() -> items.saveAndFlush(new RecruitmentCatalogItem(
                RecruitmentCatalogType.REJECTION_REASON, "SKILL_MISMATCH", "Mã trùng", 2, CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("recruitment_catalog_items_type_code_key");
        assertThatThrownBy(() -> items.saveAndFlush(new RecruitmentCatalogItem(
                RecruitmentCatalogType.WORK_LOCATION, "NEGATIVE", "Thứ tự âm", -1, CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("non_negative_recruitment_catalog_sort_order");
        assertThatThrownBy(() -> items.saveAndFlush(new RecruitmentCatalogItem(
                RecruitmentCatalogType.WORK_LOCATION, " HANOI", "Hà Nội", 1, CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("valid_recruitment_catalog_code");

        assertThat(items.count()).isEqualTo(2);
    }
}
