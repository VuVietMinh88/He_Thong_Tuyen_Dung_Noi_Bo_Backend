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
import vn.ttcs.recruitment.position.Position;
import vn.ttcs.recruitment.position.PositionRepository;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Hibernate runs with ddl-auto=validate, so this context only starts when the entity matches V7.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PositionRepositoryIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-06T00:00:00Z");
    // Larger than Integer.MAX_VALUE, so the salary fields must stay long/BIGINT end to end.
    private static final long THREE_BILLION_VND = 3_000_000_000L;

    @Autowired private PositionRepository positions;
    @Autowired private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void resetPositions() {
        jdbc.update("DELETE FROM positions");
    }

    @Test
    void savesAndReloadsPositionWithItsSalaryBand() {
        var position = new Position("CEO", "Giám đốc điều hành", "Director",
                1_500_000_000L, THREE_BILLION_VND, CREATED_AT);
        positions.saveAndFlush(position);

        var row = jdbc.queryForMap("SELECT * FROM positions WHERE id=?", position.getId());
        assertThat(row.get("code")).isEqualTo("CEO");
        assertThat(row.get("name")).isEqualTo("Giám đốc điều hành");
        assertThat(row.get("level")).isEqualTo("Director");
        assertThat(row.get("salary_min")).isEqualTo(1_500_000_000L);
        assertThat(row.get("salary_max")).isEqualTo(THREE_BILLION_VND);
        assertThat(row.get("active")).isEqualTo(true);
        assertThat(row.get("created_at")).isEqualTo(Timestamp.from(CREATED_AT));
        assertThat(row.get("updated_at")).isEqualTo(Timestamp.from(CREATED_AT));

        var reloaded = positions.findById(position.getId()).orElseThrow();
        assertThat(reloaded.getCode()).isEqualTo("CEO");
        assertThat(reloaded.getName()).isEqualTo("Giám đốc điều hành");
        assertThat(reloaded.getLevel()).isEqualTo("Director");
        assertThat(reloaded.getSalaryMin()).isEqualTo(1_500_000_000L);
        assertThat(reloaded.getSalaryMax()).isEqualTo(THREE_BILLION_VND);
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void databaseConstraintsRejectInvalidPositionsSavedThroughJpa() {
        positions.saveAndFlush(new Position("DEV_JUNIOR", "Lập trình viên", "Junior",
                15_000_000L, 25_000_000L, CREATED_AT));

        assertThatThrownBy(() -> positions.saveAndFlush(new Position("DEV_JUNIOR", "Mã trùng", "Junior",
                1L, 2L, CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("positions_code_key");
        assertThatThrownBy(() -> positions.saveAndFlush(new Position("REVERSED", "Dải đảo ngược", "Junior",
                25_000_000L, 15_000_000L, CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("valid_position_salary_range");
        assertThatThrownBy(() -> positions.saveAndFlush(new Position("NEGATIVE", "Lương âm", "Junior",
                -1L, 10_000_000L, CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("non_negative_position_salary_min");

        assertThat(positions.count()).isEqualTo(1);
    }
}
