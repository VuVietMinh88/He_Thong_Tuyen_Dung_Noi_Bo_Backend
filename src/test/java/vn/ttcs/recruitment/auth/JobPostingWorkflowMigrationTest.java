package vn.ttcs.recruitment.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 308: V22 widens the job posting statuses, requires the portal details once a posting left the editable
// statuses, and adds job_posting_events, the review and publication history.
class JobPostingWorkflowMigrationTest {
    private static final MigrationVersion WORKFLOW_VERSION = MigrationVersion.fromVersion("22");
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-11T00:00:00Z"));
    private static final Timestamp AT = Timestamp.from(Instant.parse("2026-10-11T02:30:00.123456Z"));
    private static final LocalDate DEADLINE = LocalDate.of(2026, 11, 30);

    // One database migrated to V22 shared by the rule tests; each test starts without data. The target stays V22, so
    // these tests keep checking V22 itself when later migrations change the tables (V23 makes the history
    // append-only). The upgrade test starts its own database because it needs the schema before V22.
    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startMigratedDatabase() throws Exception {
        postgres = startPostgres();
        var dataSource = postgres.getPostgresDatabase();
        flyway(dataSource).target(WORKFLOW_VERSION).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        postgres.close();
    }

    @BeforeEach
    void deleteData() {
        jdbc.update("DELETE FROM job_posting_events");
        jdbc.update("DELETE FROM job_posting_authors");
        jdbc.update("DELETE FROM job_postings");
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM recruitment_catalog_items");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM user_accounts");
    }

    @Test
    void theUpgradeKeepsDraftPostingsAndTheirAuthors() throws Exception {
        try (var upgradePostgres = startPostgres()) {
            var dataSource = upgradePostgres.getPostgresDatabase();
            flyway(dataSource).target(latestVersionBefore(dataSource, WORKFLOW_VERSION)).load().migrate();
            var db = new JdbcTemplate(dataSource);
            var fixture = insertFixture(db);
            // A complete draft and one without any portal detail: both stay drafts, V22 only constrains later steps.
            UUID complete = insert(db, posting(fixture, fixture.requisitionId(), "DRAFT"));
            Map<String, Object> bare = posting(fixture, insertRequisition(db, fixture), "DRAFT");
            bare.put("work_location_id", null);
            bare.put("employment_type_id", null);
            bare.put("application_deadline", null);
            insert(db, bare);
            db.update("INSERT INTO job_posting_authors (job_posting_id, account_id) VALUES (?, ?)", complete,
                    fixture.creatorId());
            var previousPostings = db.queryForList("SELECT * FROM job_postings ORDER BY id");
            var previousAuthors = db.queryForList("SELECT * FROM job_posting_authors ORDER BY 1, 2");
            var previousGrants = db.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code");

            var flyway = flyway(dataSource).target(WORKFLOW_VERSION).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            assertThat(db.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(previousPostings);
            assertThat(db.queryForList("SELECT * FROM job_posting_authors ORDER BY 1, 2")).isEqualTo(previousAuthors);
            assertThat(db.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code"))
                    .isEqualTo(previousGrants);
            assertThat(db.queryForObject("SELECT count(*) FROM job_posting_events", Integer.class)).isZero();
        }
    }

    @Test
    void theFiveStatusesAreAllowedAndThePortalDetailsAreRequiredOutsideTheEditableOnes() {
        var fixture = insertFixture(jdbc);
        UUID id = insert(jdbc, posting(fixture, fixture.requisitionId(), "DRAFT"));

        for (String status : new String[]{"PENDING_APPROVAL", "REVISION_REQUESTED", "APPROVED", "PUBLISHED", "DRAFT"}) {
            jdbc.update("UPDATE job_postings SET status = ? WHERE id = ?", status, id);
        }
        // Same constraint name as in V21, so the API and the docs keep one name.
        for (String status : new String[]{"REJECTED", "CLOSED", "draft", ""}) {
            assertRejected("UPDATE job_postings SET status = ? WHERE id = ?", "valid_job_posting_status", status, id);
        }

        // A draft or a posting sent back for changes may lack every portal detail.
        jdbc.update("""
                UPDATE job_postings SET work_location_id = NULL, employment_type_id = NULL, application_deadline = NULL
                WHERE id = ?""", id);
        jdbc.update("UPDATE job_postings SET status = 'REVISION_REQUESTED' WHERE id = ?", id);
        jdbc.update("UPDATE job_postings SET status = 'DRAFT' WHERE id = ?", id);
        // Under review, approved or published, each of the three is required.
        for (String column : new String[]{"work_location_id", "employment_type_id", "application_deadline"}) {
            jdbc.update("""
                    UPDATE job_postings SET work_location_id = ?, employment_type_id = ?, application_deadline = ?
                    WHERE id = ?""", fixture.workLocationId(), fixture.employmentTypeId(), DEADLINE, id);
            jdbc.update("UPDATE job_postings SET " + column + " = NULL WHERE id = ?", id);
            for (String status : new String[]{"PENDING_APPROVAL", "APPROVED", "PUBLISHED"}) {
                assertRejected("UPDATE job_postings SET status = ? WHERE id = ?", "job_posting_details_required",
                        status, id);
            }
        }
        jdbc.update("""
                UPDATE job_postings SET work_location_id = ?, employment_type_id = ?, application_deadline = ?,
                    status = 'PENDING_APPROVAL'
                WHERE id = ?""", fixture.workLocationId(), fixture.employmentTypeId(), DEADLINE, id);
        assertRejected("UPDATE job_postings SET application_deadline = NULL WHERE id = ?",
                "job_posting_details_required", id);
    }

    @Test
    void eachEventTypeHasItsOwnTransitionAndComment() {
        var fixture = insertFixture(jdbc);
        UUID posting = insert(jdbc, posting(fixture, fixture.requisitionId(), "PUBLISHED"));
        // Every step V22 allows, in the order a posting can take them.
        insertEvent(event(fixture, posting, 2, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", null));
        insertEvent(event(fixture, posting, 3, "REVISION_REQUESTED", "PENDING_APPROVAL", "REVISION_REQUESTED",
                "Bổ sung\nquyền lợi"));
        insertEvent(event(fixture, posting, 5, "SUBMITTED", "REVISION_REQUESTED", "PENDING_APPROVAL", "Đã sửa"));
        insertEvent(event(fixture, posting, 6, "APPROVED", "PENDING_APPROVAL", "APPROVED", "Đạt"));
        insertEvent(event(fixture, posting, 7, "REVISION_REQUESTED", "APPROVED", "REVISION_REQUESTED", "Đổi hạn"));
        insertEvent(event(fixture, posting, 8, "SUBMITTED", "REVISION_REQUESTED", "PENDING_APPROVAL", null));
        insertEvent(event(fixture, posting, 9, "APPROVED", "PENDING_APPROVAL", "APPROVED", null));
        insertEvent(event(fixture, posting, 10, "PUBLISHED", "APPROVED", "PUBLISHED", null));
        assertThat(jdbc.queryForObject("SELECT comment FROM job_posting_events WHERE revision = 3", String.class))
                .isEqualTo("Bổ sung\nquyền lợi");
        assertThat(jdbc.queryForObject("SELECT occurred_at FROM job_posting_events WHERE revision = 10",
                Timestamp.class)).isEqualTo(AT);

        // One refused pair per type.
        assertEventRejected(event(fixture, posting, 11, "SUBMITTED", "APPROVED", "PENDING_APPROVAL", null),
                "valid_job_posting_event_transition");
        assertEventRejected(event(fixture, posting, 11, "APPROVED", "DRAFT", "APPROVED", null),
                "valid_job_posting_event_transition");
        assertEventRejected(event(fixture, posting, 11, "REVISION_REQUESTED", "PUBLISHED", "REVISION_REQUESTED",
                "Gỡ tin"), "valid_job_posting_event_transition");
        assertEventRejected(event(fixture, posting, 11, "PUBLISHED", "PENDING_APPROVAL", "PUBLISHED", null),
                "valid_job_posting_event_transition");
        // An unknown type breaks several checks at once; PostgreSQL reports one of them.
        assertThatThrownBy(() -> insertEvent(event(fixture, posting, 11, "REJECTED", "PENDING_APPROVAL", "DRAFT",
                "Không")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageMatching("(?s).*valid_job_posting_event_(type|transition).*");
        // A CHECK whose result is NULL passes, so both statuses must be NOT NULL for the transition CHECK to hold.
        for (String column : new String[]{"status_before", "status_after", "event_type", "actor_id", "occurred_at",
                "job_posting_id", "revision"}) {
            var row = event(fixture, posting, 11, "PUBLISHED", "APPROVED", "PUBLISHED", null);
            row.put(column, null);
            assertEventRejected(row, "null value in column \"" + column + "\"");
        }

        for (String comment : new String[]{"", " \n\t ", "c".repeat(2_001)}) {
            assertEventRejected(event(fixture, posting, 11, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", comment),
                    "valid_job_posting_event_comment");
        }
        assertEventRejected(event(fixture, posting, 11, "REVISION_REQUESTED", "APPROVED", "REVISION_REQUESTED", null),
                "job_posting_event_comment_required");
        assertEventRejected(event(fixture, posting, 11, "PUBLISHED", "APPROVED", "PUBLISHED", "Đã đăng"),
                "job_posting_event_comment_allowed");
        insertEvent(event(fixture, posting, 11, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", "c".repeat(2_000)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_posting_events", Integer.class)).isEqualTo(9);
    }

    @Test
    void revisionsAreUniqueAndAPostingIsPublishedOnce() {
        var fixture = insertFixture(jdbc);
        UUID posting = insert(jdbc, posting(fixture, fixture.requisitionId(), "PUBLISHED"));
        insertEvent(event(fixture, posting, 2, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", null));
        insertEvent(event(fixture, posting, 3, "APPROVED", "PENDING_APPROVAL", "APPROVED", null));
        insertEvent(event(fixture, posting, 4, "PUBLISHED", "APPROVED", "PUBLISHED", null));

        assertEventRejected(event(fixture, posting, 4, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", null),
                "job_posting_events_revision_key");
        for (int revision : new int[]{0, -1}) {
            assertEventRejected(event(fixture, posting, revision, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", null),
                    "positive_job_posting_event_revision");
        }
        assertEventRejected(event(fixture, posting, 5, "PUBLISHED", "APPROVED", "PUBLISHED", null),
                "job_posting_events_one_publication_idx");
        // The same revision and a publication of another posting are fine.
        UUID other = insert(jdbc, posting(fixture, insertRequisition(jdbc, fixture), "PUBLISHED"));
        insertEvent(event(fixture, other, 4, "PUBLISHED", "APPROVED", "PUBLISHED", null));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_posting_events", Integer.class)).isEqualTo(4);
    }

    @Test
    void theHistoryKeepsItsPostingAndActorAndCanStillBeChangedUntilV23() {
        var fixture = insertFixture(jdbc);
        UUID posting = insert(jdbc, posting(fixture, fixture.requisitionId(), "PENDING_APPROVAL"));
        // The actor is an account nothing else references.
        var submitted = event(fixture, posting, 2, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", null);
        submitted.put("actor_id", fixture.reviewerId());
        UUID event = insertEvent(submitted);

        assertRejected("DELETE FROM job_postings WHERE id = ?", "job_posting_events_job_posting_id_fkey", posting);
        assertRejected("DELETE FROM user_accounts WHERE id = ?", "job_posting_events_actor_id_fkey",
                fixture.reviewerId());
        for (String column : new String[]{"job_posting_id", "actor_id"}) {
            var row = event(fixture, posting, 3, "APPROVED", "PENDING_APPROVAL", "APPROVED", null);
            row.put(column, UUID.randomUUID());
            assertThatThrownBy(() -> insertEvent(row)).as(column)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("job_posting_events_" + column + "_fkey");
        }

        // V22 has no trigger yet: the history becomes append-only in V23 (task 310).
        jdbc.update("UPDATE job_posting_events SET comment = 'Sửa tay' WHERE id = ?", event);
        assertThat(jdbc.queryForObject("SELECT comment FROM job_posting_events WHERE id = ?", String.class, event))
                .isEqualTo("Sửa tay");
        jdbc.update("DELETE FROM job_posting_events WHERE id = ?", event);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_posting_events", Integer.class)).isZero();
    }

    @Test
    void indexes() {
        Map<String, String> events = indexDefinitions("job_posting_events");
        assertThat(events).containsOnlyKeys("job_posting_events_pkey", "job_posting_events_revision_key",
                "job_posting_events_one_publication_idx", "job_posting_events_actor_id_idx");
        assertThat(events.get("job_posting_events_revision_key")).contains("UNIQUE")
                .endsWith("(job_posting_id, revision)");
        assertThat(events.get("job_posting_events_one_publication_idx")).contains("UNIQUE")
                .contains("(job_posting_id)").contains("WHERE").contains("'PUBLISHED'");
        assertThat(events.get("job_posting_events_actor_id_idx")).endsWith("(actor_id)");
        // V22 adds no index to job_postings.
        assertThat(indexDefinitions("job_postings")).containsOnlyKeys("job_postings_pkey",
                "job_postings_requisition_id_key", "job_postings_work_location_id_idx",
                "job_postings_employment_type_id_idx", "job_postings_created_by_idx", "job_postings_status_idx");
    }

    private static Map<String, String> indexDefinitions(String table) {
        Map<String, String> definitions = new HashMap<>();
        jdbc.queryForList("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = ?",
                table).forEach(index -> definitions.put((String) index.get("indexname"),
                (String) index.get("indexdef")));
        return definitions;
    }

    // A complete posting of the fixture's requisition in the given status, made by the fixture's recruiter.
    private static Map<String, Object> posting(Fixture fixture, UUID requisitionId, String status) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("requisition_id", requisitionId);
        row.put("status", status);
        row.put("revision", 1);
        row.put("title", "Lập trình viên Java");
        row.put("job_description", "Phát triển API tuyển dụng.");
        row.put("candidate_requirements", "Tối thiểu 2 năm kinh nghiệm Java.");
        row.put("work_location_id", fixture.workLocationId());
        row.put("employment_type_id", fixture.employmentTypeId());
        row.put("application_deadline", DEADLINE);
        row.put("show_salary", false);
        row.put("created_by", fixture.creatorId());
        row.put("created_at", CREATED_AT);
        row.put("updated_at", CREATED_AT);
        return row;
    }

    // NULL parameters need a type, otherwise PostgreSQL cannot tell what they are.
    private static UUID insert(JdbcTemplate db, Map<String, Object> row) {
        Map<String, String> casts = Map.of("work_location_id", "uuid", "employment_type_id", "uuid",
                "application_deadline", "date");
        db.update("INSERT INTO job_postings (" + String.join(",", row.keySet()) + ") VALUES ("
                + String.join(",", row.keySet().stream().map(key -> casts.containsKey(key)
                        ? "CAST(? AS " + casts.get(key) + ")" : "?").toList()) + ")", row.values().toArray());
        return (UUID) row.get("id");
    }

    // One step of the history, made by the fixture's HR manager.
    private static Map<String, Object> event(Fixture fixture, UUID posting, int revision, String type, String before,
                                             String after, String comment) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("job_posting_id", posting);
        row.put("revision", revision);
        row.put("event_type", type);
        row.put("status_before", before);
        row.put("status_after", after);
        row.put("actor_id", fixture.hrId());
        row.put("occurred_at", AT);
        row.put("comment", comment);
        return row;
    }

    private static UUID insertEvent(Map<String, Object> row) {
        Map<String, String> casts = Map.of("job_posting_id", "uuid", "revision", "integer", "event_type", "text",
                "status_before", "text", "status_after", "text", "actor_id", "uuid", "occurred_at", "timestamptz",
                "comment", "text");
        jdbc.update("INSERT INTO job_posting_events (" + String.join(",", row.keySet()) + ") VALUES ("
                + String.join(",", row.keySet().stream().map(key -> casts.containsKey(key)
                        ? "CAST(? AS " + casts.get(key) + ")" : "?").toList()) + ")", row.values().toArray());
        return (UUID) row.get("id");
    }

    private static void assertEventRejected(Map<String, Object> row, String message) {
        assertThatThrownBy(() -> insertEvent(row))
                .as(message + " " + row)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(message);
    }

    private static void assertRejected(String sql, String message, Object... arguments) {
        assertThatThrownBy(() -> jdbc.update(sql, arguments))
                .as(message + " " + Arrays.toString(arguments))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(message);
    }

    private static EmbeddedPostgres startPostgres() throws Exception {
        return EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses", "127.0.0.1").start();
    }

    private static FluentConfiguration flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration");
    }

    // The upgrade starts from the newest migration below V22, whichever task added it.
    private static MigrationVersion latestVersionBefore(DataSource dataSource, MigrationVersion version) {
        return Arrays.stream(flyway(dataSource).load().info().all())
                .map(MigrationInfo::getVersion)
                .filter(candidate -> candidate.compareTo(version) < 0)
                .max(Comparator.naturalOrder())
                .orElseThrow();
    }

    // An HR manager who made an APPROVED requisition of IT and reviews its posting, the head of IT, a recruiter who
    // writes the posting, another reviewer, one position, and one work location and one employment type.
    private static Fixture insertFixture(JdbcTemplate db) {
        UUID hrId = insertAccount(db, "hr@example.test", "HR_MANAGER");
        UUID headId = insertAccount(db, "head@example.test", "HIRING_MANAGER");
        UUID creatorId = insertAccount(db, "recruiter@example.test", "RECRUITER");
        UUID reviewerId = insertAccount(db, "admin@example.test", "ADMIN");
        UUID departmentId = UUID.randomUUID();
        db.update("INSERT INTO departments (id,code,name,manager_user_id) VALUES (?,?,?,?)",
                departmentId, "IT", "Công nghệ thông tin", headId);
        UUID positionId = UUID.randomUUID();
        db.update("""
                INSERT INTO positions (id,code,name,level,salary_min,salary_max,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, positionId, "DEV", "Lập trình viên Java", "Junior", 15_000_000L, 25_000_000L, CREATED_AT,
                CREATED_AT);
        UUID workLocationId = insertCatalogValue(db, "WORK_LOCATION", "HN", "Hà Nội");
        UUID employmentTypeId = insertCatalogValue(db, "EMPLOYMENT_TYPE", "FULL_TIME", "Toàn thời gian");
        var fixture = new Fixture(hrId, creatorId, reviewerId, departmentId, positionId, workLocationId,
                employmentTypeId, null);
        return new Fixture(hrId, creatorId, reviewerId, departmentId, positionId, workLocationId, employmentTypeId,
                insertRequisition(db, fixture));
    }

    private static UUID insertRequisition(JdbcTemplate db, Fixture fixture) {
        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO recruitment_requisitions (id,position_id,department_id,headcount,reason,proposed_salary_min,
                    proposed_salary_max,needed_by,job_description,candidate_requirements,status,created_by,created_at,
                    updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, fixture.positionId(), fixture.departmentId(), 2, "NEW_HEADCOUNT", 20_000_000L, 22_000_000L,
                LocalDate.of(2026, 12, 1), "Mô tả", "Yêu cầu", "APPROVED", fixture.hrId(), CREATED_AT, CREATED_AT);
        return id;
    }

    private static UUID insertCatalogValue(JdbcTemplate db, String type, String code, String name) {
        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO recruitment_catalog_items (id,catalog_type,code,name,sort_order,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?)
                """, id, type, code, name, 0, CREATED_AT, CREATED_AT);
        return id;
    }

    private static UUID insertAccount(JdbcTemplate db, String email, String role) {
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO user_accounts (id,email,full_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                id, email, "Tài khoản kiểm thử", "unchanged-password-hash", CREATED_AT);
        db.update("INSERT INTO user_roles (user_id,role) VALUES (?,?)", id, role);
        return id;
    }

    private record Fixture(UUID hrId, UUID creatorId, UUID reviewerId, UUID departmentId, UUID positionId,
                           UUID workLocationId, UUID employmentTypeId, UUID requisitionId) { }
}
