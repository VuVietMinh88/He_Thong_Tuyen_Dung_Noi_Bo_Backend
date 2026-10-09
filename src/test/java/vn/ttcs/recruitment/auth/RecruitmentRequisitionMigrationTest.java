package vn.ttcs.recruitment.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecruitmentRequisitionMigrationTest {
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-07T00:00:00Z"));
    private static final Timestamp UPDATED_AT = Timestamp.from(Instant.parse("2026-10-07T02:30:00Z"));
    private static final MigrationVersion REQUISITION_VERSION = MigrationVersion.fromVersion("13");
    // Larger than Integer.MAX_VALUE, so an INTEGER column could not hold it.
    private static final long THREE_BILLION_VND = 3_000_000_000L;
    private static final String JOB_DESCRIPTION = """
            Phát triển và bảo trì API tuyển dụng.

            - Làm việc với Spring Boot và PostgreSQL
            - Viết test tự động
            """;
    private static final String CANDIDATE_REQUIREMENTS = "Tối thiểu 2 năm kinh nghiệm Java.\nĐọc hiểu tài liệu tiếng Anh.";

    @Test
    void upgradesWithoutChangingAccountsDepartmentsPositionsOrPermissionGrants() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            flyway(dataSource).target(latestVersionBefore(dataSource, REQUISITION_VERSION)).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            var fixture = insertFixture(jdbc);
            var previousAccounts = jdbc.queryForList("SELECT * FROM user_accounts ORDER BY id");
            var previousRoles = jdbc.queryForList("SELECT * FROM user_roles ORDER BY user_id,role");
            var previousDepartments = jdbc.queryForList("SELECT * FROM departments ORDER BY id");
            var previousPositions = jdbc.queryForList("SELECT * FROM positions ORDER BY id");
            var previousPermissions = jdbc.queryForList("SELECT * FROM permissions ORDER BY code");
            var previousGrants = jdbc.queryForList(
                    "SELECT * FROM role_permissions ORDER BY role_code,permission_code");

            var flyway = flyway(dataSource).target(REQUISITION_VERSION).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            assertThat(flyway.info().current().getVersion()).isEqualTo(REQUISITION_VERSION);
            assertThat(jdbc.queryForList("SELECT * FROM user_accounts ORDER BY id")).isEqualTo(previousAccounts);
            assertThat(jdbc.queryForList("SELECT * FROM user_roles ORDER BY user_id,role")).isEqualTo(previousRoles);
            assertThat(jdbc.queryForList("SELECT * FROM departments ORDER BY id")).isEqualTo(previousDepartments);
            assertThat(jdbc.queryForList("SELECT * FROM positions ORDER BY id")).isEqualTo(previousPositions);
            assertThat(jdbc.queryForList("SELECT * FROM permissions ORDER BY code")).isEqualTo(previousPermissions);
            assertThat(jdbc.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code"))
                    .isEqualTo(previousGrants);
            // No requisition is invented for existing departments; managers create them through the API.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recruitment_requisitions", Integer.class)).isZero();
            // Existing data can be referenced right after the upgrade.
            UUID requisitionId = insertMinimalDraft(jdbc, fixture);
            assertThat(jdbc.queryForObject("SELECT status FROM recruitment_requisitions WHERE id=?",
                    String.class, requisitionId)).isEqualTo("DRAFT");
        }
    }

    @Test
    void storesCompleteDraftWithJobDescriptionAndCandidateRequirements() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            var fixture = insertFixture(jdbc);
            UUID id = UUID.randomUUID();
            // The status column is left out on purpose: the database default must make the row a draft.
            jdbc.update("""
                    INSERT INTO recruitment_requisitions (id,position_id,department_id,headcount,reason,
                        proposed_salary_min,proposed_salary_max,salary_justification,needed_by,
                        job_description,candidate_requirements,created_by,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, id, fixture.positionId(), fixture.departmentId(), 3, "NEW_HEADCOUNT",
                    2_000_000_000L, THREE_BILLION_VND, "Cần người có kinh nghiệm quản lý dự án lớn.",
                    LocalDate.of(2026, 12, 31), JOB_DESCRIPTION, CANDIDATE_REQUIREMENTS,
                    fixture.creatorId(), CREATED_AT, UPDATED_AT);

            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM recruitment_requisitions WHERE id=?", id);
            assertThat(row.get("position_id")).isEqualTo(fixture.positionId());
            assertThat(row.get("department_id")).isEqualTo(fixture.departmentId());
            assertThat(row.get("headcount")).isEqualTo(3);
            assertThat(row.get("reason")).isEqualTo("NEW_HEADCOUNT");
            assertThat(row.get("proposed_salary_min")).isEqualTo(2_000_000_000L);
            assertThat(row.get("proposed_salary_max")).isEqualTo(THREE_BILLION_VND);
            assertThat(row.get("salary_justification")).isEqualTo("Cần người có kinh nghiệm quản lý dự án lớn.");
            // Line breaks, blank lines and the trailing newline of the texts are kept exactly.
            assertThat(row.get("job_description")).isEqualTo(JOB_DESCRIPTION);
            assertThat(row.get("candidate_requirements")).isEqualTo(CANDIDATE_REQUIREMENTS);
            assertThat(row.get("status")).isEqualTo("DRAFT");
            assertThat(row.get("created_by")).isEqualTo(fixture.creatorId());
            assertThat(row.get("created_at")).isEqualTo(CREATED_AT);
            assertThat(row.get("updated_at")).isEqualTo(UPDATED_AT);
            assertThat(jdbc.queryForObject("SELECT needed_by FROM recruitment_requisitions WHERE id=?",
                    LocalDate.class, id)).isEqualTo(LocalDate.of(2026, 12, 31));

            // TEXT has no 255-character limit, so a long job description fits.
            String longText = "Mô tả công việc chi tiết. ".repeat(2_000);
            jdbc.update("UPDATE recruitment_requisitions SET job_description=? WHERE id=?", longText, id);
            assertThat(jdbc.queryForObject("SELECT job_description FROM recruitment_requisitions WHERE id=?",
                    String.class, id)).isEqualTo(longText);
        }
    }

    @Test
    void storesDraftWithOnlyRequiredFieldsAndPartialSalaryBand() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            var fixture = insertFixture(jdbc);
            UUID id = insertMinimalDraft(jdbc, fixture);

            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM recruitment_requisitions WHERE id=?", id);
            assertThat(row.get("headcount")).isEqualTo(1);
            assertThat(row.get("reason")).isEqualTo("REPLACEMENT");
            assertThat(row.get("status")).isEqualTo("DRAFT");
            for (String optional : new String[]{"proposed_salary_min", "proposed_salary_max", "salary_justification",
                    "needed_by", "job_description", "candidate_requirements"}) {
                assertThat(row.get(optional)).as(optional).isNull();
            }

            // While drafting, the manager may fill only one end of the band, or a fixed salary (min = max).
            jdbc.update("UPDATE recruitment_requisitions SET proposed_salary_min=15000000 WHERE id=?", id);
            jdbc.update("UPDATE recruitment_requisitions SET proposed_salary_min=NULL, proposed_salary_max=0 WHERE id=?",
                    id);
            jdbc.update("UPDATE recruitment_requisitions SET proposed_salary_min=20000000, proposed_salary_max=20000000"
                    + " WHERE id=?", id);
            assertThat(jdbc.queryForObject("SELECT proposed_salary_min = proposed_salary_max"
                    + " FROM recruitment_requisitions WHERE id=?", Boolean.class, id)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recruitment_requisitions", Integer.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void rejectsInvalidHeadcountReasonStatusSalaryAndBlankTexts() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            var fixture = insertFixture(jdbc);
            UUID id = insertMinimalDraft(jdbc, fixture);
            var original = jdbc.queryForMap("SELECT * FROM recruitment_requisitions WHERE id=?", id);

            for (int headcount : new int[]{0, -1}) {
                assertRejected(jdbc, "UPDATE recruitment_requisitions SET headcount=? WHERE id=?",
                        "positive_requisition_headcount", headcount, id);
            }
            for (String reason : new String[]{"OTHER", "replacement", "", " REPLACEMENT"}) {
                assertRejected(jdbc, "UPDATE recruitment_requisitions SET reason=? WHERE id=?",
                        "valid_requisition_reason", reason, id);
            }
            // Workflow statuses do not exist yet; a later migration widens this CHECK.
            for (String status : new String[]{"SUBMITTED", "APPROVED", "draft", ""}) {
                assertRejected(jdbc, "UPDATE recruitment_requisitions SET status=? WHERE id=?",
                        "valid_requisition_status", status, id);
            }
            assertRejected(jdbc, "UPDATE recruitment_requisitions SET proposed_salary_min=?, proposed_salary_max=?"
                    + " WHERE id=?", "valid_requisition_salary_range", 25_000_000L, 15_000_000L, id);
            assertRejected(jdbc, "UPDATE recruitment_requisitions SET proposed_salary_min=? WHERE id=?",
                    "non_negative_requisition_salary", -1L, id);
            assertRejected(jdbc, "UPDATE recruitment_requisitions SET proposed_salary_max=? WHERE id=?",
                    "non_negative_requisition_salary", -1L, id);
            for (String blank : new String[]{"", " ", "\n", " \t\r\n "}) {
                assertRejected(jdbc, "UPDATE recruitment_requisitions SET salary_justification=? WHERE id=?",
                        "valid_requisition_salary_justification", blank, id);
                assertRejected(jdbc, "UPDATE recruitment_requisitions SET job_description=? WHERE id=?",
                        "valid_requisition_job_description", blank, id);
                assertRejected(jdbc, "UPDATE recruitment_requisitions SET candidate_requirements=? WHERE id=?",
                        "valid_requisition_candidate_requirements", blank, id);
            }

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO recruitment_requisitions (id,position_id,department_id,headcount,reason,status,
                        created_by,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, UUID.randomUUID(), fixture.positionId(), fixture.departmentId(), 1, "REPLACEMENT",
                    "SUBMITTED", fixture.creatorId(), CREATED_AT, CREATED_AT))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("valid_requisition_status");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recruitment_requisitions", Integer.class))
                    .isEqualTo(1);
            assertThat(jdbc.queryForMap("SELECT * FROM recruitment_requisitions WHERE id=?", id)).isEqualTo(original);
        }
    }

    @Test
    void rejectsMissingRequiredFields() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            var fixture = insertFixture(jdbc);
            UUID id = insertMinimalDraft(jdbc, fixture);

            for (String column : new String[]{"position_id", "department_id", "headcount", "reason", "status",
                    "created_by", "created_at", "updated_at"}) {
                String sql = "UPDATE recruitment_requisitions SET " + column + "=NULL WHERE id=?";
                assertThatThrownBy(() -> jdbc.update(sql, id))
                        .as(column).isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("null value in column \"" + column + "\"");
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recruitment_requisitions WHERE status='DRAFT'",
                    Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void requiresExistingPositionDepartmentAndCreatorAndKeepsThemFromBeingDeleted() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            var fixture = insertFixture(jdbc);
            UUID unknown = UUID.randomUUID();

            assertThatThrownBy(() -> insertDraft(jdbc, unknown, fixture.departmentId(), fixture.creatorId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("recruitment_requisitions_position_id_fkey");
            assertThatThrownBy(() -> insertDraft(jdbc, fixture.positionId(), unknown, fixture.creatorId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("recruitment_requisitions_department_id_fkey");
            assertThatThrownBy(() -> insertDraft(jdbc, fixture.positionId(), fixture.departmentId(), unknown))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("recruitment_requisitions_created_by_fkey");

            UUID requisitionId = insertMinimalDraft(jdbc, fixture);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM positions WHERE id=?", fixture.positionId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("recruitment_requisitions_position_id_fkey");
            assertThatThrownBy(() -> jdbc.update("DELETE FROM departments WHERE id=?", fixture.departmentId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("recruitment_requisitions_department_id_fkey");
            // The creator manages no department, so only the requisition keeps the account from being deleted.
            assertThatThrownBy(() -> jdbc.update("DELETE FROM user_accounts WHERE id=?", fixture.creatorId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("recruitment_requisitions_created_by_fkey");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM positions", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM departments", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM user_accounts WHERE id=?", Integer.class,
                    fixture.creatorId())).isEqualTo(1);

            // Once the requisition is gone nothing references the position any more.
            jdbc.update("DELETE FROM recruitment_requisitions WHERE id=?", requisitionId);
            assertThat(jdbc.update("DELETE FROM positions WHERE id=?", fixture.positionId())).isEqualTo(1);
        }
    }

    @Test
    void indexesDepartmentPositionCreatorAndStatus() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            var indexes = jdbc.queryForList("""
                    SELECT indexname, indexdef FROM pg_indexes
                    WHERE schemaname = 'public' AND tablename = 'recruitment_requisitions'
                    """);
            Map<String, String> definitions = new HashMap<>();
            indexes.forEach(index -> definitions.put((String) index.get("indexname"), (String) index.get("indexdef")));

            assertThat(definitions).containsOnlyKeys("recruitment_requisitions_pkey",
                    "recruitment_requisitions_department_id_idx", "recruitment_requisitions_position_id_idx",
                    "recruitment_requisitions_created_by_idx", "recruitment_requisitions_status_idx");
            assertThat(definitions.get("recruitment_requisitions_department_id_idx")).endsWith("(department_id)");
            assertThat(definitions.get("recruitment_requisitions_position_id_idx")).endsWith("(position_id)");
            assertThat(definitions.get("recruitment_requisitions_created_by_idx")).endsWith("(created_by)");
            assertThat(definitions.get("recruitment_requisitions_status_idx")).endsWith("(status)");
        }
    }

    private record Fixture(UUID creatorId, UUID departmentId, UUID positionId) {
    }

    private static void assertRejected(JdbcTemplate jdbc, String sql, String constraint, Object... arguments) {
        assertThatThrownBy(() -> jdbc.update(sql, arguments))
                .as(constraint + " " + Arrays.toString(arguments))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private static EmbeddedPostgres startPostgres() throws Exception {
        return EmbeddedPostgres.builder().setPort(0)
                .setServerConfig("listen_addresses", "127.0.0.1").start();
    }

    private static FluentConfiguration flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }

    // Versions V8-V12 belong to other modules. Reading the real list keeps the upgrade test correct
    // whether or not those migrations are present on this branch.
    private static MigrationVersion latestVersionBefore(DataSource dataSource, MigrationVersion version) {
        return Arrays.stream(flyway(dataSource).load().info().all())
                .map(MigrationInfo::getVersion)
                .filter(candidate -> candidate.compareTo(version) < 0)
                .max(Comparator.naturalOrder())
                .orElseThrow();
    }

    private static JdbcTemplate migrateAll(EmbeddedPostgres postgres) {
        var dataSource = postgres.getPostgresDatabase();
        flyway(dataSource).load().migrate();
        return new JdbcTemplate(dataSource);
    }

    // A department manager, an HR account that creates the requisition, one department and one position.
    private static Fixture insertFixture(JdbcTemplate jdbc) {
        UUID managerId = insertAccount(jdbc, "manager@example.test", "HIRING_MANAGER");
        UUID creatorId = insertAccount(jdbc, "hr@example.test", "HR_MANAGER");
        UUID departmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id,code,name,manager_user_id) VALUES (?,?,?,?)",
                departmentId, "IT", "Công nghệ thông tin", managerId);
        UUID positionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id,code,name,level,salary_min,salary_max,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, positionId, "DEV_JUNIOR", "Lập trình viên", "Junior", 15_000_000L, 25_000_000L,
                CREATED_AT, CREATED_AT);
        return new Fixture(creatorId, departmentId, positionId);
    }

    private static UUID insertAccount(JdbcTemplate jdbc, String email, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id,email,full_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                id, email, "Tài khoản kiểm thử", "unchanged-password-hash", CREATED_AT);
        jdbc.update("INSERT INTO user_roles (user_id,role) VALUES (?,?)", id, role);
        return id;
    }

    private static UUID insertMinimalDraft(JdbcTemplate jdbc, Fixture fixture) {
        return insertDraft(jdbc, fixture.positionId(), fixture.departmentId(), fixture.creatorId());
    }

    // Only the required columns; everything a manager may still be writing stays NULL.
    private static UUID insertDraft(JdbcTemplate jdbc, UUID positionId, UUID departmentId, UUID creatorId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_requisitions (id,position_id,department_id,headcount,reason,
                    created_by,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, positionId, departmentId, 1, "REPLACEMENT", creatorId, CREATED_AT, CREATED_AT);
        return id;
    }
}
