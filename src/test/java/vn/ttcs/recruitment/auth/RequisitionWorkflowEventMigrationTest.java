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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 257: V19 widens the requisition statuses and adds requisition_workflow_events, the approval history.
class RequisitionWorkflowEventMigrationTest {
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-11T00:00:00Z"));
    private static final Timestamp AT = Timestamp.from(Instant.parse("2026-10-11T02:00:00Z"));
    private static final MigrationVersion WORKFLOW_VERSION = MigrationVersion.fromVersion("19");

    @Test
    void theUpgradeKeepsExistingDraftsAndAllowsTheFiveStatusesOnly() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            flyway(dataSource).target(latestVersionBefore(dataSource, WORKFLOW_VERSION)).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            var fixture = insertFixture(jdbc, dataSource);
            var previous = jdbc.queryForList("SELECT * FROM recruitment_requisitions ORDER BY id");
            var previousPermissions = jdbc.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code");

            var flyway = flyway(dataSource).target(WORKFLOW_VERSION).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            assertThat(jdbc.queryForList("SELECT * FROM recruitment_requisitions ORDER BY id")).isEqualTo(previous);
            assertThat(jdbc.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code"))
                    .isEqualTo(previousPermissions);
            for (String status : new String[]{"PENDING_APPROVAL", "RETURNED", "APPROVED", "REJECTED", "DRAFT"}) {
                jdbc.update("UPDATE recruitment_requisitions SET status = ? WHERE id = ?", status, fixture.requisitionId());
            }
            for (String status : new String[]{"PENDING", "SUBMITTED", "draft", ""}) {
                assertThatThrownBy(() -> jdbc.update("UPDATE recruitment_requisitions SET status = ? WHERE id = ?",
                        status, fixture.requisitionId())).as(status)
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("valid_requisition_status");
            }
        }
    }

    @Test
    void eachEventTypeHasItsOwnTransitionShapeAndComment() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            var jdbc = migrate(dataSource);
            var fixture = insertFixture(jdbc, dataSource);
            insert(jdbc, submitted(fixture, 1, 1, "DRAFT"));
            insert(jdbc, decision(fixture, 2, 1, 1, "APPROVED", "PENDING_APPROVAL", null));
            insert(jdbc, decision(fixture, 3, 1, 2, "INFO_REQUESTED", "RETURNED", "Bổ sung\nmô tả"));
            insert(jdbc, submitted(fixture, 4, 2, "RETURNED"));
            insert(jdbc, decision(fixture, 5, 2, 1, "REJECTED", "REJECTED", "Không phù hợp"));

            var wrongSource = submitted(fixture, 6, 3, "APPROVED");
            assertViolation(jdbc, wrongSource, "valid_requisition_workflow_event_transition");
            var rejectedStillPending = decision(fixture, 6, 3, 1, "REJECTED", "PENDING_APPROVAL", "Không");
            assertViolation(jdbc, rejectedStillPending, "valid_requisition_workflow_event_transition");
            var finalWithoutApproval = decision(fixture, 6, 3, 1, "INFO_REQUESTED", "APPROVED", "Không");
            assertViolation(jdbc, finalWithoutApproval, "valid_requisition_workflow_event_transition");
            // An unknown type breaks several checks at once; PostgreSQL reports one of them.
            var unknownType = decision(fixture, 6, 3, 1, "ESCALATED", "PENDING_APPROVAL", null);
            unknownType.put("round_number", null);
            unknownType.put("level_number", null);
            assertThatThrownBy(() -> insert(jdbc, unknownType)).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageMatching("(?s).*valid_requisition_workflow_event_(type|transition).*");

            var noSnapshot = submitted(fixture, 6, 3, "DRAFT");
            noSnapshot.put("flow_id", null);
            noSnapshot.put("flow_version", null);
            noSnapshot.put("level_count", null);
            noSnapshot.put("salary_basis", null);
            assertViolation(jdbc, noSnapshot, "valid_requisition_workflow_event_snapshot");
            var submittedWithLevel = submitted(fixture, 6, 3, "DRAFT");
            submittedWithLevel.put("level_number", 1);
            assertViolation(jdbc, submittedWithLevel, "valid_requisition_workflow_event_level");
            var decisionWithSnapshot = decision(fixture, 6, 3, 1, "APPROVED", "APPROVED", null);
            decisionWithSnapshot.put("flow_id", fixture.flowId());
            decisionWithSnapshot.put("flow_version", 1);
            decisionWithSnapshot.put("level_count", 1);
            decisionWithSnapshot.put("salary_basis", 1L);
            assertViolation(jdbc, decisionWithSnapshot, "valid_requisition_workflow_event_snapshot");
            var decisionWithoutLevel = decision(fixture, 6, 3, 1, "APPROVED", "APPROVED", null);
            decisionWithoutLevel.put("level_number", null);
            assertViolation(jdbc, decisionWithoutLevel, "valid_requisition_workflow_event_level");
            var noRound = submitted(fixture, 6, 3, "DRAFT");
            noRound.put("round_number", null);
            assertViolation(jdbc, noRound, "valid_requisition_workflow_event_round");

            assertViolation(jdbc, decision(fixture, 6, 3, 1, "REJECTED", "REJECTED", null),
                    "requisition_workflow_event_comment_required");
            assertViolation(jdbc, decision(fixture, 6, 3, 1, "INFO_REQUESTED", "RETURNED", null),
                    "requisition_workflow_event_comment_required");
            assertViolation(jdbc, decision(fixture, 6, 3, 1, "REJECTED", "REJECTED", " \n\t "),
                    "valid_requisition_workflow_event_comment");
            assertViolation(jdbc, decision(fixture, 6, 3, 1, "REJECTED", "REJECTED", "a".repeat(2_001)),
                    "valid_requisition_workflow_event_comment");
            insert(jdbc, decision(fixture, 6, 3, 1, "REJECTED", "REJECTED", "a".repeat(2_000)));
            assertThat(jdbc.queryForObject("SELECT comment FROM requisition_workflow_events WHERE revision = 3",
                    String.class)).isEqualTo("Bổ sung\nmô tả");
        }
    }

    @Test
    void aSubmissionPerRoundADecisionPerLevelAndRevisionsAreUnique() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            var jdbc = migrate(dataSource);
            var fixture = insertFixture(jdbc, dataSource);
            insert(jdbc, submitted(fixture, 1, 1, "DRAFT"));
            insert(jdbc, decision(fixture, 2, 1, 1, "APPROVED", "PENDING_APPROVAL", null));

            assertViolation(jdbc, decision(fixture, 2, 1, 2, "APPROVED", "APPROVED", null),
                    "requisition_workflow_events_revision_key");
            assertViolation(jdbc, decision(fixture, 0, 1, 2, "APPROVED", "APPROVED", null),
                    "positive_requisition_workflow_event_revision");
            assertViolation(jdbc, submitted(fixture, 3, 1, "DRAFT"), "requisition_workflow_events_one_submission_idx");
            assertViolation(jdbc, decision(fixture, 3, 1, 1, "REJECTED", "REJECTED", "Lần hai"),
                    "requisition_workflow_events_one_decision_idx");
            // The same round and level of another requisition is fine.
            UUID other = insertRequisition(jdbc, fixture);
            var otherSubmission = submitted(fixture, 1, 1, "DRAFT");
            otherSubmission.put("requisition_id", other);
            insert(jdbc, otherSubmission);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM requisition_workflow_events", Integer.class))
                    .isEqualTo(3);
        }
    }

    @Test
    void theSnapshotPointsAtLevelsThatExistAndKeepsTheRequisitionAndAccounts() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            var jdbc = migrate(dataSource);
            var fixture = insertFixture(jdbc, dataSource);
            // The version has two levels.
            var tooLong = submitted(fixture, 1, 1, "DRAFT");
            tooLong.put("level_count", 3);
            assertViolation(jdbc, tooLong, "requisition_workflow_events_last_level_fkey");
            var unknownVersion = submitted(fixture, 1, 1, "DRAFT");
            unknownVersion.put("flow_version", 2);
            assertViolation(jdbc, unknownVersion, "requisition_workflow_events_flow_version_fkey");
            // Submitted by an account that nothing else references.
            UUID clerk = insertAccount(jdbc, "clerk@example.test", "RECRUITER");
            var row = submitted(fixture, 1, 1, "DRAFT");
            row.put("actor_id", clerk);
            insert(jdbc, row);

            assertDeleteRefused(jdbc, "DELETE FROM recruitment_requisitions",
                    "requisition_workflow_events_requisition_id_fkey");
            assertDeleteRefused(jdbc, "DELETE FROM user_accounts WHERE id = '" + clerk + "'",
                    "requisition_workflow_events_actor_id_fkey");
        }
    }

    // round 1.. submitted by the head of IT on the 2-level flow, salary 30M.
    private static Map<String, Object> submitted(Fixture fixture, int revision, int round, String before) {
        Map<String, Object> row = base(fixture, revision, "SUBMITTED", before, "PENDING_APPROVAL");
        row.put("round_number", round);
        row.put("level_number", null);
        row.put("flow_id", fixture.flowId());
        row.put("flow_version", 1);
        row.put("level_count", 2);
        row.put("salary_basis", 30_000_000L);
        row.put("actor_id", fixture.headId());
        row.put("comment", null);
        return row;
    }

    private static Map<String, Object> decision(Fixture fixture, int revision, int round, int level, String type,
                                                String after, String comment) {
        Map<String, Object> row = base(fixture, revision, type, "PENDING_APPROVAL", after);
        row.put("round_number", round);
        row.put("level_number", level);
        row.put("flow_id", null);
        row.put("flow_version", null);
        row.put("level_count", null);
        row.put("salary_basis", null);
        row.put("actor_id", fixture.hrId());
        row.put("comment", comment);
        return row;
    }

    private static Map<String, Object> base(Fixture fixture, int revision, String type, String before, String after) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("requisition_id", fixture.requisitionId());
        row.put("revision", revision);
        row.put("event_type", type);
        row.put("status_before", before);
        row.put("status_after", after);
        row.put("occurred_at", AT);
        return row;
    }

    private static final Map<String, String> CASTS = Map.of("round_number", "integer", "level_number", "smallint",
            "flow_id", "uuid", "flow_version", "integer", "level_count", "smallint", "salary_basis", "bigint",
            "comment", "text");

    private static void insert(JdbcTemplate jdbc, Map<String, Object> row) {
        jdbc.update("INSERT INTO requisition_workflow_events (" + String.join(",", row.keySet()) + ") VALUES ("
                + String.join(",", row.keySet().stream().map(key -> CASTS.containsKey(key)
                        ? "CAST(? AS " + CASTS.get(key) + ")" : "?").toList()) + ")", row.values().toArray());
    }

    private static void assertViolation(JdbcTemplate jdbc, Map<String, Object> row, String constraint) {
        assertThatThrownBy(() -> insert(jdbc, row)).as(constraint)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private static void assertDeleteRefused(JdbcTemplate jdbc, String sql, String constraint) {
        assertThatThrownBy(() -> jdbc.update(sql)).as(constraint)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private static EmbeddedPostgres startPostgres() throws Exception {
        return EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses", "127.0.0.1").start();
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

    private static JdbcTemplate migrate(DataSource dataSource) {
        flyway(dataSource).target(WORKFLOW_VERSION).load().migrate();
        return new JdbcTemplate(dataSource);
    }

    // An HR manager, the head of IT, a draft of IT and a sealed 2-level approval flow of IT.
    private static Fixture insertFixture(JdbcTemplate jdbc, DataSource dataSource) {
        UUID hrId = insertAccount(jdbc, "hr@example.test", "HR_MANAGER");
        UUID headId = insertAccount(jdbc, "head@example.test", "HIRING_MANAGER");
        UUID departmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id,code,name,manager_user_id) VALUES (?,?,?,?)",
                departmentId, "IT", "Công nghệ thông tin", headId);
        UUID positionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id,code,name,level,salary_min,salary_max,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, positionId, "DEV", "Lập trình viên", "Junior", 15_000_000L, 25_000_000L, CREATED_AT, CREATED_AT);
        UUID flowId = UUID.randomUUID();
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO approval_flows (id, department_id, current_version) VALUES (?, ?, 1)", flowId,
                    departmentId);
            jdbc.update("INSERT INTO approval_flow_versions (flow_id, version, name, created_by, created_at) VALUES (?, 1, 'IT', ?, ?)",
                    flowId, hrId, CREATED_AT);
            jdbc.update("INSERT INTO approval_flow_steps (flow_id, version, position, approver_role) VALUES (?, 1, 1, 'HR_MANAGER')",
                    flowId);
            jdbc.update("INSERT INTO approval_flow_steps (flow_id, version, position, salary_threshold, approver_role) VALUES (?, 1, 2, 20000000, 'APPROVER')",
                    flowId);
            jdbc.update("UPDATE approval_flow_versions SET sealed = TRUE WHERE flow_id = ?", flowId);
        });
        var fixture = new Fixture(hrId, headId, departmentId, positionId, flowId, null);
        return new Fixture(hrId, headId, departmentId, positionId, flowId, insertRequisition(jdbc, fixture));
    }

    private static UUID insertRequisition(JdbcTemplate jdbc, Fixture fixture) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_requisitions (id,position_id,department_id,headcount,reason,created_by,
                    created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, fixture.positionId(), fixture.departmentId(), 1, "NEW_HEADCOUNT", fixture.headId(),
                CREATED_AT, CREATED_AT);
        return id;
    }

    private static UUID insertAccount(JdbcTemplate jdbc, String email, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id,email,full_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                id, email, "Tài khoản kiểm thử", "unchanged-password-hash", CREATED_AT);
        jdbc.update("INSERT INTO user_roles (user_id,role) VALUES (?,?)", id, role);
        return id;
    }

    private record Fixture(UUID hrId, UUID headId, UUID departmentId, UUID positionId, UUID flowId,
                           UUID requisitionId) { }
}
