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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 310: V23 keeps who published a posting and when on the posting itself, takes both from the PUBLISHED event of
// the postings published before it, and makes the review and publication history append-only.
class JobPostingPublicationTrailMigrationTest {
    private static final MigrationVersion TRAIL_VERSION = MigrationVersion.fromVersion("23");
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-11T00:00:00Z"));
    private static final Timestamp AT = Timestamp.from(Instant.parse("2026-10-11T02:30:00.123456Z"));
    private static final Timestamp PUBLISHED_AT = Timestamp.from(Instant.parse("2026-10-11T04:00:00.654321Z"));
    private static final LocalDate DEADLINE = LocalDate.of(2026, 11, 30);

    // One database migrated to V23 shared by the rule tests; each test starts without data. The target stays V23, so
    // these tests keep checking V23 itself when later migrations change the tables. The upgrade test starts its own
    // database because it needs the schema before V23.
    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startMigratedDatabase() throws Exception {
        postgres = startPostgres();
        var dataSource = postgres.getPostgresDatabase();
        flyway(dataSource).target(TRAIL_VERSION).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        postgres.close();
    }

    @BeforeEach
    void deleteData() {
        // The history is append-only from V23 on; TRUNCATE fires no row trigger.
        jdbc.execute("TRUNCATE job_posting_events");
        jdbc.update("DELETE FROM job_posting_authors");
        jdbc.update("DELETE FROM job_postings");
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM recruitment_catalog_items");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM user_accounts");
    }

    @Test
    void theUpgradeTakesTheTrailFromThePublishedEvent() throws Exception {
        try (var upgradePostgres = startPostgres()) {
            var dataSource = upgradePostgres.getPostgresDatabase();
            flyway(dataSource).target(latestVersionBefore(dataSource, TRAIL_VERSION)).load().migrate();
            var db = new JdbcTemplate(dataSource);
            var fixture = insertFixture(db);
            // Under V22: a draft, a posting approved but not published yet, and one the reviewer published.
            UUID draft = insert(db, posting(fixture, fixture.requisitionId(), "DRAFT", 1));
            UUID approved = insert(db, posting(fixture, insertRequisition(db, fixture), "APPROVED", 3));
            insertEvent(db, event(approved, 2, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", fixture.creatorId(), AT));
            insertEvent(db, event(approved, 3, "APPROVED", "PENDING_APPROVAL", "APPROVED", fixture.hrId(), AT));
            UUID published = insert(db, posting(fixture, insertRequisition(db, fixture), "PUBLISHED", 4));
            insertEvent(db, event(published, 2, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", fixture.creatorId(), AT));
            insertEvent(db, event(published, 3, "APPROVED", "PENDING_APPROVAL", "APPROVED", fixture.hrId(), AT));
            insertEvent(db, event(published, 4, "PUBLISHED", "APPROVED", "PUBLISHED", fixture.reviewerId(),
                    PUBLISHED_AT));
            var previousPostings = db.queryForList("SELECT * FROM job_postings ORDER BY id");
            var previousEvents = db.queryForList("SELECT * FROM job_posting_events ORDER BY id");
            var previousGrants = db.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code");

            var flyway = flyway(dataSource).target(TRAIL_VERSION).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            // Every column V22 had keeps its value; V23 only adds the two columns of the trail.
            var postings = db.queryForList("SELECT * FROM job_postings ORDER BY id");
            assertThat(withoutTrail(postings)).isEqualTo(previousPostings);
            assertThat(db.queryForMap("SELECT published_by, published_at FROM job_postings WHERE id = ?", published))
                    .containsEntry("published_by", fixture.reviewerId()).containsEntry("published_at", PUBLISHED_AT);
            for (UUID unpublished : List.of(draft, approved)) {
                assertThat(db.queryForMap("SELECT published_by, published_at FROM job_postings WHERE id = ?",
                        unpublished)).as(unpublished.toString())
                        .containsEntry("published_by", null).containsEntry("published_at", null);
            }
            assertThat(db.queryForList("SELECT * FROM job_posting_events ORDER BY id")).isEqualTo(previousEvents);
            assertThat(db.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code"))
                    .isEqualTo(previousGrants);
        }
    }

    @Test
    void onlyAPublishedPostingHasATrailAndItAlwaysHasBothParts() {
        var fixture = insertFixture(jdbc);
        UUID id = insert(jdbc, withTrail(fixture, posting(fixture, fixture.requisitionId(), "PUBLISHED", 4)));

        // Both parts or neither.
        assertRejected("UPDATE job_postings SET published_by = NULL WHERE id = ?", "valid_job_posting_publication",
                id);
        // A published posting always names who published it and when.
        assertRejected("UPDATE job_postings SET published_by = NULL, published_at = NULL WHERE id = ?",
                "job_posting_publication_required", id);
        // No status before the publication keeps a trail, so a posting cannot go back and keep it.
        for (String status : new String[]{"DRAFT", "PENDING_APPROVAL", "REVISION_REQUESTED", "APPROVED"}) {
            assertRejected("UPDATE job_postings SET status = ? WHERE id = ?", "job_posting_unpublished_without_trail",
                    status, id);
        }

        jdbc.update("UPDATE job_postings SET status = 'APPROVED', published_by = NULL, published_at = NULL "
                + "WHERE id = ?", id);
        assertRejected("UPDATE job_postings SET published_by = ?, published_at = ? WHERE id = ?",
                "job_posting_unpublished_without_trail", fixture.reviewerId(), PUBLISHED_AT, id);
        assertRejected("UPDATE job_postings SET published_by = ? WHERE id = ?", "valid_job_posting_publication",
                fixture.reviewerId(), id);
        assertRejected("UPDATE job_postings SET status = 'PUBLISHED' WHERE id = ?", "job_posting_publication_required",
                id);
        // Publishing with the whole trail is the only way to PUBLISHED.
        jdbc.update("UPDATE job_postings SET status = 'PUBLISHED', published_by = ?, published_at = ? WHERE id = ?",
                fixture.hrId(), AT, id);
        assertThat(jdbc.queryForMap("SELECT status, published_by, published_at FROM job_postings WHERE id = ?", id))
                .containsEntry("status", "PUBLISHED").containsEntry("published_by", fixture.hrId())
                .containsEntry("published_at", AT);
    }

    @Test
    void thePublisherIsAnAccountThatCannotBeDeleted() {
        var fixture = insertFixture(jdbc);
        // The reviewer is referenced by nothing but the trail.
        UUID id = insert(jdbc, withTrail(fixture, posting(fixture, fixture.requisitionId(), "PUBLISHED", 4)));

        assertRejected("DELETE FROM user_accounts WHERE id = ?", "job_postings_published_by_fkey",
                fixture.reviewerId());
        assertRejected("UPDATE job_postings SET published_by = ? WHERE id = ?", "job_postings_published_by_fkey",
                UUID.randomUUID(), id);
        // RESTRICT like created_by, not deferrable.
        assertThat(jdbc.queryForMap("""
                SELECT confdeltype, condeferrable FROM pg_constraint WHERE conname = 'job_postings_published_by_fkey'
                """)).containsEntry("confdeltype", "r").containsEntry("condeferrable", false);
    }

    @Test
    void eventsAreAppendOnly() {
        var fixture = insertFixture(jdbc);
        UUID posting = insert(jdbc, withTrail(fixture, posting(fixture, fixture.requisitionId(), "PUBLISHED", 4)));
        var submitted = event(posting, 2, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", fixture.creatorId(), AT);
        submitted.put("comment", "Nhờ chị duyệt giúp.");
        insertEvent(jdbc, submitted);
        insertEvent(jdbc, event(posting, 3, "APPROVED", "PENDING_APPROVAL", "APPROVED", fixture.hrId(), AT));
        insertEvent(jdbc, event(posting, 4, "PUBLISHED", "APPROVED", "PUBLISHED", fixture.reviewerId(),
                PUBLISHED_AT));
        var before = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY revision");

        for (String change : new String[]{
                "UPDATE job_posting_events SET comment = 'Sửa tay' WHERE revision = 2",
                "UPDATE job_posting_events SET comment = NULL WHERE revision = 2",
                "UPDATE job_posting_events SET actor_id = '" + fixture.hrId() + "' WHERE revision = 4",
                "UPDATE job_posting_events SET occurred_at = now() WHERE revision = 4",
                "UPDATE job_posting_events SET revision = 5 WHERE revision = 4",
                "DELETE FROM job_posting_events WHERE revision = 4",
                "DELETE FROM job_posting_events"}) {
            assertThatThrownBy(() -> jdbc.update(change)).as(change)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("job_posting_events is append-only");
        }
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY revision")).isEqualTo(before);

        // Adding is still allowed, and the posting still cannot be deleted while it has a history.
        UUID other = insert(jdbc, posting(fixture, insertRequisition(jdbc, fixture), "PENDING_APPROVAL", 2));
        insertEvent(jdbc, event(other, 2, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", fixture.creatorId(), AT));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_posting_events", Integer.class)).isEqualTo(4);
        assertRejected("DELETE FROM job_postings WHERE id = ?", "job_posting_events_job_posting_id_fkey", posting);
        // TRUNCATE (table owner only) does not fire row triggers.
        jdbc.execute("TRUNCATE job_posting_events");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_posting_events", Integer.class)).isZero();
    }

    @Test
    void indexes() {
        Map<String, String> postings = indexDefinitions("job_postings");
        assertThat(postings).containsOnlyKeys("job_postings_pkey", "job_postings_requisition_id_key",
                "job_postings_work_location_id_idx", "job_postings_employment_type_id_idx",
                "job_postings_created_by_idx", "job_postings_status_idx", "job_postings_published_by_idx",
                "job_postings_published_at_idx");
        assertThat(postings.get("job_postings_published_by_idx")).endsWith("(published_by)");
        // The public list: published postings, newest publication first, the id keeping the order stable.
        assertThat(postings.get("job_postings_published_at_idx")).contains("(published_at DESC, id DESC)")
                .contains("WHERE").contains("'PUBLISHED'");
        // V23 adds no index to the history.
        assertThat(indexDefinitions("job_posting_events")).containsOnlyKeys("job_posting_events_pkey",
                "job_posting_events_revision_key", "job_posting_events_one_publication_idx",
                "job_posting_events_actor_id_idx");
    }

    // The rows as V22 knew them: without the two columns V23 adds.
    private static List<Map<String, Object>> withoutTrail(List<Map<String, Object>> rows) {
        List<Map<String, Object>> stripped = new ArrayList<>();
        for (var row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            copy.remove("published_by");
            copy.remove("published_at");
            stripped.add(copy);
        }
        return stripped;
    }

    private static Map<String, String> indexDefinitions(String table) {
        Map<String, String> definitions = new HashMap<>();
        jdbc.queryForList("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = ?",
                table).forEach(index -> definitions.put((String) index.get("indexname"),
                (String) index.get("indexdef")));
        return definitions;
    }

    // A complete posting of the fixture's requisition in the given status and revision, made by the fixture's
    // recruiter, without a publication trail.
    private static Map<String, Object> posting(Fixture fixture, UUID requisitionId, String status, int revision) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("requisition_id", requisitionId);
        row.put("status", status);
        row.put("revision", revision);
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

    // The same posting published by the fixture's reviewer (ADMIN).
    private static Map<String, Object> withTrail(Fixture fixture, Map<String, Object> row) {
        row.put("published_by", fixture.reviewerId());
        row.put("published_at", PUBLISHED_AT);
        return row;
    }

    private static UUID insert(JdbcTemplate db, Map<String, Object> row) {
        db.update("INSERT INTO job_postings (" + String.join(",", row.keySet()) + ") VALUES ("
                + String.join(",", row.keySet().stream().map(key -> "?").toList()) + ")", row.values().toArray());
        return (UUID) row.get("id");
    }

    // One step of the history, without a comment.
    private static Map<String, Object> event(UUID posting, int revision, String type, String before, String after,
                                             UUID actor, Timestamp at) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("job_posting_id", posting);
        row.put("revision", revision);
        row.put("event_type", type);
        row.put("status_before", before);
        row.put("status_after", after);
        row.put("actor_id", actor);
        row.put("occurred_at", at);
        row.put("comment", null);
        return row;
    }

    // NULL parameters need a type, otherwise PostgreSQL cannot tell what they are.
    private static void insertEvent(JdbcTemplate db, Map<String, Object> row) {
        db.update("INSERT INTO job_posting_events (" + String.join(",", row.keySet()) + ") VALUES ("
                + String.join(",", row.keySet().stream().map(key -> key.equals("comment")
                        ? "CAST(? AS text)" : "?").toList()) + ")", row.values().toArray());
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

    // The upgrade starts from the newest migration below V23, whichever task added it.
    private static MigrationVersion latestVersionBefore(DataSource dataSource, MigrationVersion version) {
        return Arrays.stream(flyway(dataSource).load().info().all())
                .map(MigrationInfo::getVersion)
                .filter(candidate -> candidate.compareTo(version) < 0)
                .max(Comparator.naturalOrder())
                .orElseThrow();
    }

    // An HR manager who made an APPROVED requisition of IT and approves its postings, the head of IT, a recruiter who
    // writes the postings, an ADMIN who publishes them, one position, and one work location and one employment type.
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
