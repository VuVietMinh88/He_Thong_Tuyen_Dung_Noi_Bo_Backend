package vn.ttcs.recruitment.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 293: V24 adds the statuses PAUSED, FILLED and CANCELLED and keeps HR's status changes in the workflow history
// of V19: no round, level or snapshot, a reason except when resuming, and at most one end per requisition.
class RequisitionStatusChangeMigrationTest {
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-12T00:00:00Z"));
    private static final Timestamp AT = Timestamp.from(Instant.parse("2026-10-12T02:00:00Z"));
    private static final MigrationVersion V24 = MigrationVersion.fromVersion("24");

    @Test
    void theUpgradeKeepsEveryRowAndAllowsTheEightStatusesOnly() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            flyway(dataSource).target(latestVersionBefore(dataSource, V24)).load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            var fixture = insertFixture(jdbc, dataSource);
            UUID draft = insertRequisition(jdbc, fixture, "DRAFT");
            UUID pending = insertRequisition(jdbc, fixture, "PENDING_APPROVAL");
            insert(jdbc, submitted(fixture, pending, 1, 2));
            UUID approved = insertRequisition(jdbc, fixture, "APPROVED");
            insert(jdbc, submitted(fixture, approved, 1, 1));
            insert(jdbc, decision(fixture, approved, 2, 1, "APPROVED", "APPROVED", null));
            UUID rejected = insertRequisition(jdbc, fixture, "REJECTED");
            insert(jdbc, submitted(fixture, rejected, 1, 2));
            insert(jdbc, decision(fixture, rejected, 2, 1, "REJECTED", "REJECTED", "Không phù hợp\nngân sách"));
            var requisitions = jdbc.queryForList("SELECT * FROM recruitment_requisitions ORDER BY id");
            String history = "SELECT * FROM requisition_workflow_events ORDER BY requisition_id, revision";
            var events = jdbc.queryForList(history);
            var permissions = jdbc.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code");

            var flyway = flyway(dataSource).target(V24).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            assertThat(jdbc.queryForList("SELECT * FROM recruitment_requisitions ORDER BY id")).isEqualTo(requisitions);
            assertThat(jdbc.queryForList(history)).isEqualTo(events);
            assertThat(jdbc.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code"))
                    .isEqualTo(permissions);
            for (String status : new String[]{"DRAFT", "PENDING_APPROVAL", "RETURNED", "APPROVED", "REJECTED", "PAUSED",
                    "FILLED", "CANCELLED"}) {
                jdbc.update("UPDATE recruitment_requisitions SET status = ? WHERE id = ?", status, draft);
            }
            // The frontend's OPEN and CLOSED are not statuses of the backend: "Đã tuyển đủ" is FILLED.
            for (String status : new String[]{"CLOSED", "OPEN", "PENDING", "paused", ""}) {
                assertThatThrownBy(() -> jdbc.update("UPDATE recruitment_requisitions SET status = ? WHERE id = ?",
                        status, draft)).as(status)
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("valid_requisition_status");
            }
        }
    }

    // The status changes of story S3-07 (RequisitionStatusChange): type, status before, status after.
    static Stream<Arguments> allowedStatusChanges() {
        return Stream.of(
                Arguments.of("PAUSED", "APPROVED", "PAUSED"),
                Arguments.of("RESUMED", "PAUSED", "APPROVED"),
                Arguments.of("FILLED", "APPROVED", "FILLED"),
                Arguments.of("FILLED", "PAUSED", "FILLED"),
                Arguments.of("CANCELLED", "DRAFT", "CANCELLED"),
                Arguments.of("CANCELLED", "PENDING_APPROVAL", "CANCELLED"),
                Arguments.of("CANCELLED", "RETURNED", "CANCELLED"),
                Arguments.of("CANCELLED", "APPROVED", "CANCELLED"),
                Arguments.of("CANCELLED", "PAUSED", "CANCELLED"));
    }

    @ParameterizedTest(name = "{0}: {1} -> {2}")
    @MethodSource("allowedStatusChanges")
    void everyStatusChangeOfTheTableIsAccepted(String type, String before, String after) throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            var jdbc = migrate(dataSource);
            var fixture = insertFixture(jdbc, dataSource);
            UUID requisition = insertRequisition(jdbc, fixture, before);

            insert(jdbc, statusChange(fixture, requisition, 1, type, before, after, "Lý do của HR"));

            assertThat(jdbc.queryForMap("""
                    SELECT event_type, status_before, status_after, round_number, level_number, flow_id, comment
                    FROM requisition_workflow_events WHERE requisition_id = ?
                    """, requisition)).containsExactlyEntriesOf(row("event_type", type, "status_before", before,
                    "status_after", after, "round_number", null, "level_number", null, "flow_id", null,
                    "comment", "Lý do của HR"));
        }
    }

    @Test
    void anyOtherStatusChangeIsRefused() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            var jdbc = migrate(dataSource);
            var fixture = insertFixture(jdbc, dataSource);
            UUID requisition = insertRequisition(jdbc, fixture, "DRAFT");

            String[][] refused = {
                    {"PAUSED", "PENDING_APPROVAL", "PAUSED"},
                    {"RESUMED", "APPROVED", "APPROVED"},
                    {"RESUMED", "PAUSED", "PAUSED"},
                    {"FILLED", "DRAFT", "FILLED"},
                    {"FILLED", "RETURNED", "FILLED"},
                    {"FILLED", "REJECTED", "FILLED"},
                    {"CANCELLED", "REJECTED", "CANCELLED"},
                    {"CANCELLED", "FILLED", "CANCELLED"},
                    {"CANCELLED", "CANCELLED", "CANCELLED"},
                    {"PAUSED", "APPROVED", "APPROVED"}};
            for (String[] change : refused) {
                assertViolation(jdbc, statusChange(fixture, requisition, 1, change[0], change[1], change[2], "Lý do"),
                        "valid_requisition_workflow_event_transition");
            }
            assertThat(events(jdbc)).isZero();
        }
    }

    // V24 drops and re-creates the transition and comment CHECKs of V19, so the steps of the approval workflow keep
    // their V19 rules at V24 too, not only at V19 (RequisitionWorkflowEventMigrationTest).
    @Test
    void theApprovalStepsKeepTheirRulesOfV19() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            var jdbc = migrate(dataSource);
            var fixture = insertFixture(jdbc, dataSource);

            // Every step of V19 is still accepted: submitted, a level approved, more information asked (with a
            // comment), submitted again, rejected (with a comment); and on another requisition the last level approved.
            UUID rejected = insertRequisition(jdbc, fixture, "REJECTED");
            insert(jdbc, submitted(fixture, rejected, 1, 2));
            insert(jdbc, decision(fixture, rejected, 2, 1, "APPROVED", "PENDING_APPROVAL", null));
            insert(jdbc, decision(fixture, rejected, 3, 2, "INFO_REQUESTED", "RETURNED", "Bổ sung mô tả"));
            var resubmitted = withStatusBefore(submitted(fixture, rejected, 4, 2), "RETURNED");
            resubmitted.put("round_number", 2);
            insert(jdbc, resubmitted);
            var rejection = decision(fixture, rejected, 5, 1, "REJECTED", "REJECTED", "Không phù hợp ngân sách");
            rejection.put("round_number", 2);
            insert(jdbc, rejection);
            UUID approved = insertRequisition(jdbc, fixture, "APPROVED");
            insert(jdbc, submitted(fixture, approved, 1, 1));
            insert(jdbc, decision(fixture, approved, 2, 1, "APPROVED", "APPROVED", null));
            assertThat(events(jdbc)).isEqualTo(7);

            // A wrong status before or after is still refused for each of them.
            UUID other = insertRequisition(jdbc, fixture, "DRAFT");
            var notToPending = submitted(fixture, other, 1, 2);
            notToPending.put("status_after", "APPROVED");
            var wrongSteps = List.of(
                    withStatusBefore(submitted(fixture, other, 1, 2), "APPROVED"),
                    notToPending,
                    withStatusBefore(decision(fixture, other, 1, 1, "APPROVED", "APPROVED", null), "DRAFT"),
                    decision(fixture, other, 1, 1, "APPROVED", "REJECTED", null),
                    withStatusBefore(decision(fixture, other, 1, 1, "REJECTED", "REJECTED", "Lý do"), "APPROVED"),
                    decision(fixture, other, 1, 1, "REJECTED", "PENDING_APPROVAL", "Lý do"),
                    withStatusBefore(decision(fixture, other, 1, 1, "INFO_REQUESTED", "RETURNED", "Lý do"),
                            "RETURNED"),
                    decision(fixture, other, 1, 1, "INFO_REQUESTED", "REJECTED", "Lý do"));
            for (var step : wrongSteps) {
                assertViolation(jdbc, step, "valid_requisition_workflow_event_transition");
            }
            // Rejecting and asking for more information still say why.
            assertViolation(jdbc, decision(fixture, other, 1, 1, "REJECTED", "REJECTED", null),
                    "requisition_workflow_event_comment_required");
            assertViolation(jdbc, decision(fixture, other, 1, 1, "INFO_REQUESTED", "RETURNED", null),
                    "requisition_workflow_event_comment_required");
            assertThat(events(jdbc)).isEqualTo(7);
        }
    }

    @Test
    void aStatusChangeHasNoRoundLevelOrSnapshot() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            var jdbc = migrate(dataSource);
            var fixture = insertFixture(jdbc, dataSource);
            UUID requisition = insertRequisition(jdbc, fixture, "APPROVED");

            var withRound = statusChange(fixture, requisition, 1, "PAUSED", "APPROVED", "PAUSED", "Lý do");
            withRound.put("round_number", 1);
            assertViolation(jdbc, withRound, "valid_requisition_workflow_event_round");
            var withLevel = statusChange(fixture, requisition, 1, "PAUSED", "APPROVED", "PAUSED", "Lý do");
            withLevel.put("level_number", 1);
            assertViolation(jdbc, withLevel, "valid_requisition_workflow_event_level");
            var withSnapshot = statusChange(fixture, requisition, 1, "CANCELLED", "APPROVED", "CANCELLED", "Lý do");
            withSnapshot.put("flow_id", fixture.flowId());
            assertViolation(jdbc, withSnapshot, "valid_requisition_workflow_event_snapshot");
            assertThat(events(jdbc)).isZero();
        }
    }

    @Test
    void pausingFillingAndCancellingSayWhyAndResumingMay() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            var jdbc = migrate(dataSource);
            var fixture = insertFixture(jdbc, dataSource);
            UUID requisition = insertRequisition(jdbc, fixture, "APPROVED");

            assertViolation(jdbc, statusChange(fixture, requisition, 1, "PAUSED", "APPROVED", "PAUSED", null),
                    "requisition_workflow_event_comment_required");
            assertViolation(jdbc, statusChange(fixture, requisition, 1, "FILLED", "APPROVED", "FILLED", null),
                    "requisition_workflow_event_comment_required");
            assertViolation(jdbc, statusChange(fixture, requisition, 1, "CANCELLED", "APPROVED", "CANCELLED", null),
                    "requisition_workflow_event_comment_required");
            assertViolation(jdbc, statusChange(fixture, requisition, 1, "PAUSED", "APPROVED", "PAUSED", "  "),
                    "valid_requisition_workflow_event_comment");
            assertViolation(jdbc, statusChange(fixture, requisition, 1, "PAUSED", "APPROVED", "PAUSED",
                    "a".repeat(2_001)), "valid_requisition_workflow_event_comment");

            insert(jdbc, statusChange(fixture, requisition, 1, "PAUSED", "APPROVED", "PAUSED",
                    "Chờ ngân sách\nquý 4"));
            insert(jdbc, statusChange(fixture, requisition, 2, "RESUMED", "PAUSED", "APPROVED", null));
            assertThat(jdbc.queryForList("SELECT comment FROM requisition_workflow_events ORDER BY revision",
                    String.class)).containsExactly("Chờ ngân sách\nquý 4", null);
        }
    }

    @Test
    void aRequisitionIsFilledOrCancelledOnlyOnce() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            var jdbc = migrate(dataSource);
            var fixture = insertFixture(jdbc, dataSource);
            UUID requisition = insertRequisition(jdbc, fixture, "APPROVED");
            // Pausing and resuming may happen more than once.
            insert(jdbc, statusChange(fixture, requisition, 1, "PAUSED", "APPROVED", "PAUSED", "Chờ ngân sách"));
            insert(jdbc, statusChange(fixture, requisition, 2, "RESUMED", "PAUSED", "APPROVED", null));
            insert(jdbc, statusChange(fixture, requisition, 3, "PAUSED", "APPROVED", "PAUSED", "Chờ lần nữa"));
            insert(jdbc, statusChange(fixture, requisition, 4, "FILLED", "PAUSED", "FILLED", "Đã đủ người"));

            assertViolation(jdbc, statusChange(fixture, requisition, 5, "CANCELLED", "PAUSED", "CANCELLED", "Hủy"),
                    "requisition_workflow_events_one_end_idx");
            // Another requisition has its own end.
            UUID other = insertRequisition(jdbc, fixture, "DRAFT");
            insert(jdbc, statusChange(fixture, other, 1, "CANCELLED", "DRAFT", "CANCELLED", "Không còn nhu cầu"));
            assertThat(events(jdbc)).isEqualTo(5);
        }
    }

    @Test
    void statusChangesStayAppendOnly() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            var jdbc = migrate(dataSource);
            var fixture = insertFixture(jdbc, dataSource);
            UUID requisition = insertRequisition(jdbc, fixture, "DRAFT");
            insert(jdbc, statusChange(fixture, requisition, 1, "CANCELLED", "DRAFT", "CANCELLED",
                    "Không còn nhu cầu"));
            var before = jdbc.queryForList("SELECT * FROM requisition_workflow_events");

            for (String change : new String[]{
                    "UPDATE requisition_workflow_events SET comment = 'Nhầm' WHERE revision = 1",
                    "UPDATE requisition_workflow_events SET event_type = 'FILLED', status_after = 'FILLED'",
                    "DELETE FROM requisition_workflow_events"}) {
                assertThatThrownBy(() -> jdbc.update(change)).as(change)
                        .isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("requisition_workflow_events is append-only");
            }
            assertThat(jdbc.queryForList("SELECT * FROM requisition_workflow_events")).isEqualTo(before);
            // TRUNCATE (table owner only) still does not fire row triggers.
            jdbc.execute("TRUNCATE requisition_workflow_events");
            assertThat(events(jdbc)).isZero();
        }
    }

    @Test
    void theOneEndIndexJoinsTheSixIndexesOfV19() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            var jdbc = migrate(dataSource);
            Map<String, String> indexes = new LinkedHashMap<>();
            jdbc.query("SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'requisition_workflow_events'",
                    (java.sql.ResultSet row) -> {
                        indexes.put(row.getString("indexname"), row.getString("indexdef"));
                    });

            assertThat(indexes).containsOnlyKeys("requisition_workflow_events_pkey",
                    "requisition_workflow_events_revision_key", "requisition_workflow_events_one_submission_idx",
                    "requisition_workflow_events_one_decision_idx", "requisition_workflow_events_actor_id_idx",
                    "requisition_workflow_events_flow_level_idx", "requisition_workflow_events_one_end_idx");
            assertThat(indexes.get("requisition_workflow_events_one_end_idx"))
                    .startsWith("CREATE UNIQUE INDEX requisition_workflow_events_one_end_idx ON ")
                    .contains("(requisition_id) WHERE ((event_type)::text = ANY (")
                    .endsWith("(ARRAY['FILLED'::character varying, 'CANCELLED'::character varying])::text[]))");
        }
    }

    // A status change of HR: no round, level or snapshot; comment is the reason.
    private static Map<String, Object> statusChange(Fixture fixture, UUID requisition, int revision, String type,
                                                    String before, String after, String comment) {
        Map<String, Object> row = base(requisition, revision, type, before, after);
        row.put("round_number", null);
        row.put("level_number", null);
        row.put("flow_id", null);
        row.put("flow_version", null);
        row.put("level_count", null);
        row.put("salary_basis", null);
        row.put("actor_id", fixture.hrId());
        row.put("comment", comment);
        return row;
    }

    // Round 1.. submitted by the head of IT on levelCount levels of the 2-level flow, salary 30M.
    private static Map<String, Object> submitted(Fixture fixture, UUID requisition, int revision, int levelCount) {
        Map<String, Object> row = base(requisition, revision, "SUBMITTED", "DRAFT", "PENDING_APPROVAL");
        row.put("round_number", 1);
        row.put("level_number", null);
        row.put("flow_id", fixture.flowId());
        row.put("flow_version", 1);
        row.put("level_count", levelCount);
        row.put("salary_basis", 30_000_000L);
        row.put("actor_id", fixture.headId());
        row.put("comment", null);
        return row;
    }

    private static Map<String, Object> decision(Fixture fixture, UUID requisition, int revision, int level, String type,
                                                String after, String comment) {
        Map<String, Object> row = base(requisition, revision, type, "PENDING_APPROVAL", after);
        row.put("round_number", 1);
        row.put("level_number", level);
        row.put("flow_id", null);
        row.put("flow_version", null);
        row.put("level_count", null);
        row.put("salary_basis", null);
        row.put("actor_id", fixture.hrId());
        row.put("comment", comment);
        return row;
    }

    private static Map<String, Object> withStatusBefore(Map<String, Object> row, String before) {
        row.put("status_before", before);
        return row;
    }

    private static Map<String, Object> base(UUID requisition, int revision, String type, String before, String after) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("requisition_id", requisition);
        row.put("revision", revision);
        row.put("event_type", type);
        row.put("status_before", before);
        row.put("status_after", after);
        row.put("occurred_at", AT);
        return row;
    }

    private static Map<String, Object> row(Object... keysAndValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            row.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
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
        assertThatThrownBy(() -> insert(jdbc, row)).as(constraint + " " + row.get("event_type"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private static int events(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM requisition_workflow_events", Integer.class);
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

    // The rule tests stop at V24, so a later migration of this table does not change what they check.
    private static JdbcTemplate migrate(DataSource dataSource) {
        flyway(dataSource).target(V24).load().migrate();
        return new JdbcTemplate(dataSource);
    }

    // An HR manager, the head of IT, the IT department, a position and a sealed 2-level approval flow of IT.
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
        return new Fixture(hrId, headId, departmentId, positionId, flowId);
    }

    private static UUID insertRequisition(JdbcTemplate jdbc, Fixture fixture, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_requisitions (id,position_id,department_id,headcount,reason,status,created_by,
                    created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, id, fixture.positionId(), fixture.departmentId(), 1, "NEW_HEADCOUNT", status, fixture.headId(),
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

    private record Fixture(UUID hrId, UUID headId, UUID departmentId, UUID positionId, UUID flowId) { }
}
