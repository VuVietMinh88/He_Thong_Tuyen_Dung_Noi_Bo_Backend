package vn.ttcs.recruitment.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 268: V20 makes the approval history append-only, also against SQL written by hand.
class RequisitionWorkflowHistoryProtectionMigrationTest {
    private static final Timestamp AT = Timestamp.from(Instant.parse("2026-10-11T03:00:00Z"));
    private static final MigrationVersion PROTECTION_VERSION = MigrationVersion.fromVersion("20");

    @Test
    void savedDecisionsAndCommentsCanNeitherBeChangedNorDeleted() throws Exception {
        try (var postgres = EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses", "127.0.0.1").start()) {
            var dataSource = postgres.getPostgresDatabase();
            flyway(dataSource).target(latestVersionBefore(dataSource, PROTECTION_VERSION)).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            var fixture = insertFixture(jdbc, dataSource);
            insertEvent(jdbc, fixture, 1, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", null, null);
            insertEvent(jdbc, fixture, 2, "REJECTED", "PENDING_APPROVAL", "REJECTED", 1, "Không phù hợp");
            var before = jdbc.queryForList("SELECT * FROM requisition_workflow_events ORDER BY revision");

            var flyway = flyway(dataSource).target(PROTECTION_VERSION).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();
            assertThat(jdbc.queryForList("SELECT * FROM requisition_workflow_events ORDER BY revision")).isEqualTo(before);

            for (String change : new String[]{
                    "UPDATE requisition_workflow_events SET comment = 'Đồng ý' WHERE revision = 2",
                    "UPDATE requisition_workflow_events SET event_type = 'APPROVED', status_after = 'APPROVED' WHERE revision = 2",
                    "UPDATE requisition_workflow_events SET actor_id = '" + fixture.headId() + "' WHERE revision = 2",
                    "UPDATE requisition_workflow_events SET occurred_at = now() WHERE revision = 2",
                    "DELETE FROM requisition_workflow_events WHERE revision = 2",
                    "DELETE FROM requisition_workflow_events"}) {
                assertThatThrownBy(() -> jdbc.update(change)).as(change)
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("requisition_workflow_events is append-only");
            }
            assertThat(jdbc.queryForList("SELECT * FROM requisition_workflow_events ORDER BY revision")).isEqualTo(before);

            // Adding is still allowed, and the requisition still cannot be deleted while it has a history.
            insertEvent(jdbc, fixture, 3, "SUBMITTED", "RETURNED", "PENDING_APPROVAL", null, null);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM recruitment_requisitions"))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("requisition_workflow_events_requisition_id_fkey");
            // TRUNCATE (table owner only) does not fire row triggers.
            jdbc.execute("TRUNCATE requisition_workflow_events");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM requisition_workflow_events", Integer.class)).isZero();
        }
    }

    private static void insertEvent(JdbcTemplate jdbc, Fixture fixture, int revision, String type, String before,
                                    String after, Integer level, String comment) {
        boolean submitted = type.equals("SUBMITTED");
        jdbc.update("""
                INSERT INTO requisition_workflow_events (id, requisition_id, revision, event_type, status_before,
                    status_after, round_number, level_number, flow_id, flow_version, level_count, salary_basis,
                    actor_id, occurred_at, comment)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS smallint), CAST(? AS uuid), CAST(? AS integer),
                    CAST(? AS smallint), CAST(? AS bigint), ?, ?, CAST(? AS text))
                """, UUID.randomUUID(), fixture.requisitionId(), revision, type, before, after,
                submitted && before.equals("RETURNED") ? 2 : 1, level, submitted ? fixture.flowId() : null,
                submitted ? 1 : null, submitted ? 1 : null, submitted ? 22_000_000L : null,
                submitted ? fixture.headId() : fixture.hrId(), AT, comment);
    }

    private static FluentConfiguration flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }

    private static MigrationVersion latestVersionBefore(DataSource dataSource, MigrationVersion version) {
        return Arrays.stream(flyway(dataSource).load().info().all())
                .map(MigrationInfo::getVersion)
                .filter(candidate -> candidate.compareTo(version) < 0)
                .max(Comparator.naturalOrder())
                .orElseThrow();
    }

    private static Fixture insertFixture(JdbcTemplate jdbc, DataSource dataSource) {
        UUID hrId = insertAccount(jdbc, "hr@example.test", "HR_MANAGER");
        UUID headId = insertAccount(jdbc, "head@example.test", "HIRING_MANAGER");
        UUID departmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id,code,name,manager_user_id) VALUES (?,?,?,?)", departmentId, "IT", "IT",
                headId);
        UUID positionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id,code,name,level,salary_min,salary_max,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, positionId, "DEV", "Lập trình viên", "Junior", 15_000_000L, 25_000_000L, AT, AT);
        UUID requisitionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_requisitions (id,position_id,department_id,headcount,reason,created_by,
                    created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, requisitionId, positionId, departmentId, 1, "NEW_HEADCOUNT", headId, AT, AT);
        UUID flowId = UUID.randomUUID();
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO approval_flows (id, department_id, current_version) VALUES (?, ?, 1)", flowId,
                    departmentId);
            jdbc.update("INSERT INTO approval_flow_versions (flow_id, version, name, created_by, created_at) VALUES (?, 1, 'IT', ?, ?)",
                    flowId, hrId, AT);
            jdbc.update("INSERT INTO approval_flow_steps (flow_id, version, position, approver_role) VALUES (?, 1, 1, 'HR_MANAGER')",
                    flowId);
            jdbc.update("UPDATE approval_flow_versions SET sealed = TRUE WHERE flow_id = ?", flowId);
        });
        return new Fixture(hrId, headId, requisitionId, flowId);
    }

    private static UUID insertAccount(JdbcTemplate jdbc, String email, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id,email,full_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                id, email, "Tài khoản kiểm thử", "unchanged-password-hash", AT);
        jdbc.update("INSERT INTO user_roles (user_id,role) VALUES (?,?)", id, role);
        return id;
    }

    private record Fixture(UUID hrId, UUID headId, UUID requisitionId, UUID flowId) { }
}
