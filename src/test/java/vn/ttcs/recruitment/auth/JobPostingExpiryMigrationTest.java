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
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 356 (story S4-08): V27 adds the posting status EXPIRED (set by the daily task once the deadline has passed) and
// its history step, which is the only step without an actor, never has a comment and happens once. An expired posting
// keeps who published it and when, may still be taken down (task 355) and is closed with its requisition (task 292).
// No existing row changes.
class JobPostingExpiryMigrationTest {
    private static final MigrationVersion EXPIRY_VERSION = MigrationVersion.fromVersion("27");
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-11T00:00:00Z"));
    private static final Timestamp AT = Timestamp.from(Instant.parse("2026-10-11T02:30:00.123456Z"));
    private static final Timestamp PUBLISHED_AT = Timestamp.from(Instant.parse("2026-10-11T04:00:00.654321Z"));
    private static final Timestamp EXPIRED_AT = Timestamp.from(Instant.parse("2026-11-30T17:05:00.987654Z"));
    private static final LocalDate DEADLINE = LocalDate.of(2026, 11, 30);
    private static final List<String> V26_STATUSES = List.of("DRAFT", "PENDING_APPROVAL", "REVISION_REQUESTED",
            "APPROVED", "PUBLISHED", "CLOSED", "UNPUBLISHED");

    // One database migrated to V27 shared by the rule tests; each test starts without data. The target stays V27, so
    // these tests keep checking V27 itself when later migrations change the tables. The upgrade test starts its own
    // database because it needs the schema before V27.
    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startMigratedDatabase() throws Exception {
        postgres = startPostgres();
        var dataSource = postgres.getPostgresDatabase();
        flyway(dataSource).target(EXPIRY_VERSION).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        postgres.close();
    }

    @BeforeEach
    void deleteData() {
        // Both histories are append-only; TRUNCATE fires no row trigger.
        jdbc.execute("TRUNCATE job_posting_events, requisition_workflow_events");
        jdbc.update("DELETE FROM job_posting_authors");
        jdbc.update("DELETE FROM job_postings");
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM recruitment_catalog_items");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM user_accounts");
    }

    // Under V26: one posting in each of its seven statuses, each with the steps that led to it. V27 changes none of
    // them, only makes the actor of a step optional for the expiry.
    @Test
    void theUpgradeChangesNoRow() throws Exception {
        try (var upgradePostgres = startPostgres()) {
            var dataSource = upgradePostgres.getPostgresDatabase();
            flyway(dataSource).target(latestVersionBefore(dataSource, EXPIRY_VERSION)).load().migrate();
            var db = new JdbcTemplate(dataSource);
            var fixture = insertFixture(db);
            for (String status : V26_STATUSES) {
                insertWithSteps(db, fixture, insertRequisition(db, fixture, status.equals("CLOSED") ? "FILLED"
                        : "APPROVED"), status);
            }
            assertThat(db.queryForList("SELECT status FROM job_postings", String.class))
                    .containsExactlyInAnyOrderElementsOf(V26_STATUSES);
            assertThat(actorNullable(db)).isEqualTo("NO");
            var previousPostings = db.queryForList("SELECT * FROM job_postings ORDER BY id");
            var previousEvents = db.queryForList("SELECT * FROM job_posting_events ORDER BY id");
            var previousGrants = db.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code");

            var flyway = flyway(dataSource).target(EXPIRY_VERSION).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            assertThat(db.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(previousPostings);
            assertThat(db.queryForList("SELECT * FROM job_posting_events ORDER BY id")).isEqualTo(previousEvents);
            assertThat(db.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code"))
                    .isEqualTo(previousGrants);
            assertThat(actorNullable(db)).isEqualTo("YES");
        }
    }

    // EXPIRED is the only new status, and a posting in it was published: it names who published it and when, and has
    // every portal detail.
    @Test
    void anExpiredPostingKeepsItsPublicationTrail() {
        var fixture = insertFixture(jdbc);
        UUID id = insert(jdbc, withTrail(fixture, posting(fixture, fixture.requisitionId(), "EXPIRED", 5)));
        assertThat(row(jdbc, id)).containsEntry("status", "EXPIRED")
                .containsEntry("published_by", fixture.reviewerId()).containsEntry("published_at", PUBLISHED_AT);

        for (String status : new String[]{"ARCHIVED", "expired", ""}) {
            assertRejected("UPDATE job_postings SET status = ? WHERE id = ?", "valid_job_posting_status", status, id);
        }
        assertRejected("UPDATE job_postings SET published_by = NULL, published_at = NULL WHERE id = ?",
                "job_posting_publication_required", id);
        assertRejected("UPDATE job_postings SET application_deadline = NULL WHERE id = ?",
                "job_posting_details_required", id);
        Map<String, Object> withoutTrail = posting(fixture, insertRequisition(jdbc, fixture, "APPROVED"), "EXPIRED",
                5);
        assertThatThrownBy(() -> insert(jdbc, withoutTrail)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("job_posting_publication_required");
        // V26 still holds: a posting taken down keeps its trail; one before the publication has none.
        UUID takenDown = insert(jdbc, withTrail(fixture, posting(fixture, insertRequisition(jdbc, fixture,
                "APPROVED"), "UNPUBLISHED", 5)));
        assertRejected("UPDATE job_postings SET published_by = NULL, published_at = NULL WHERE id = ?",
                "job_posting_publication_required", takenDown);
        assertRejected("UPDATE job_postings SET status = 'APPROVED' WHERE id = ?",
                "job_posting_unpublished_without_trail", id);
        assertThat(row(jdbc, id)).containsEntry("status", "EXPIRED");
    }

    // The expiry is a step from PUBLISHED only, without an actor or a comment, once per posting; then only a take-down
    // or the closure of its requisition leaves EXPIRED.
    @Test
    void theExpiryIsAStepFromPublishedWithoutAnActorOnce() {
        var fixture = insertFixture(jdbc);
        UUID posting = insert(jdbc, withTrail(fixture, posting(fixture, fixture.requisitionId(), "EXPIRED", 5)));
        insertEvent(jdbc, expiry(posting, 5, "PUBLISHED"));
        assertThat(jdbc.queryForMap("SELECT * FROM job_posting_events WHERE job_posting_id = ?", posting))
                .containsEntry("event_type", "EXPIRED").containsEntry("status_before", "PUBLISHED")
                .containsEntry("status_after", "EXPIRED").containsEntry("actor_id", null)
                .containsEntry("comment", null);

        UUID other = insert(jdbc, withTrail(fixture, posting(fixture, insertRequisition(jdbc, fixture, "APPROVED"),
                "EXPIRED", 5)));
        for (String before : new String[]{"DRAFT", "PENDING_APPROVAL", "REVISION_REQUESTED", "APPROVED", "CLOSED",
                "UNPUBLISHED", "EXPIRED"}) {
            assertEventRejected(expiry(other, 5, before), "valid_job_posting_event_transition");
        }
        var toClosed = expiry(other, 5, "PUBLISHED");
        toClosed.put("status_after", "CLOSED");
        assertEventRejected(toClosed, "valid_job_posting_event_transition");
        // Only the task expires a posting: an expiry never names an actor.
        var withActor = expiry(other, 5, "PUBLISHED");
        withActor.put("actor_id", fixture.hrId());
        assertEventRejected(withActor, "job_posting_event_actor_required");
        var commented = expiry(other, 5, "PUBLISHED");
        commented.put("comment", "Hết hạn");
        assertEventRejected(commented, "job_posting_event_comment_allowed");
        // Expired once, behind the row locks and the re-check.
        assertEventRejected(expiry(posting, 6, "PUBLISHED"), "job_posting_events_one_expiry_idx");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_posting_events WHERE job_posting_id = ?",
                Integer.class, other)).isZero();

        // Nothing leaves EXPIRED but a take-down or the closure: no publication, review or submission again.
        assertEventRejected(event(posting, 6, "PUBLISHED", "EXPIRED", "PUBLISHED", fixture.reviewerId(), AT),
                "valid_job_posting_event_transition");
        var reopened = event(posting, 6, "REVISION_REQUESTED", "EXPIRED", "REVISION_REQUESTED", fixture.hrId(), AT);
        reopened.put("comment", "Mở lại");
        assertEventRejected(reopened, "valid_job_posting_event_transition");
        assertEventRejected(event(posting, 6, "SUBMITTED", "EXPIRED", "PENDING_APPROVAL", fixture.creatorId(), AT),
                "valid_job_posting_event_transition");
        var takeDown = event(posting, 6, "UNPUBLISHED", "EXPIRED", "UNPUBLISHED", fixture.creatorId(), AT);
        takeDown.put("comment", "Gỡ tin đã hết hạn.");
        insertEvent(jdbc, takeDown);
        var closure = event(posting, 7, "CLOSED", "UNPUBLISHED", "CLOSED", fixture.hrId(), AT);
        closure.put("comment", "Đã tuyển đủ");
        insertEvent(jdbc, closure);
        assertThat(jdbc.queryForList("SELECT event_type FROM job_posting_events WHERE job_posting_id = ? "
                + "ORDER BY revision", String.class, posting)).containsExactly("EXPIRED", "UNPUBLISHED", "CLOSED");

        UUID closed = insert(jdbc, withTrail(fixture, posting(fixture, insertRequisition(jdbc, fixture, "FILLED"),
                "CLOSED", 6)));
        insertEvent(jdbc, expiry(closed, 5, "PUBLISHED"));
        var closedFromExpired = event(closed, 6, "CLOSED", "EXPIRED", "CLOSED", fixture.hrId(), AT);
        closedFromExpired.put("comment", "Đã tuyển đủ");
        insertEvent(jdbc, closedFromExpired);
        assertThat(jdbc.queryForList("SELECT status_before FROM job_posting_events WHERE job_posting_id = ? "
                + "ORDER BY revision", String.class, closed)).containsExactly("PUBLISHED", "EXPIRED");
    }

    // Every other step still names its actor, who must exist; V27 re-creates the transition and comment CHECKs, so
    // the other steps keep their rules of V26.
    @Test
    void theOtherStepsKeepTheirActorsAndRules() {
        var fixture = insertFixture(jdbc);
        UUID published = insert(jdbc, withTrail(fixture, posting(fixture, fixture.requisitionId(), "PUBLISHED", 4)));
        insertEvent(jdbc, event(published, 2, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", fixture.creatorId(), AT));
        insertEvent(jdbc, event(published, 3, "APPROVED", "PENDING_APPROVAL", "APPROVED", fixture.hrId(), AT));
        insertEvent(jdbc, event(published, 4, "PUBLISHED", "APPROVED", "PUBLISHED", fixture.reviewerId(),
                PUBLISHED_AT));

        UUID posting = insert(jdbc, posting(fixture, insertRequisition(jdbc, fixture, "APPROVED"), "DRAFT", 1));
        assertEventRejected(event(posting, 2, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", null, AT),
                "job_posting_event_actor_required");
        assertEventRejected(event(posting, 2, "APPROVED", "PENDING_APPROVAL", "APPROVED", null, AT),
                "job_posting_event_actor_required");
        assertEventRejected(event(posting, 2, "PUBLISHED", "APPROVED", "PUBLISHED", null, AT),
                "job_posting_event_actor_required");
        var anonymousRevision = event(posting, 2, "REVISION_REQUESTED", "PENDING_APPROVAL", "REVISION_REQUESTED",
                null, AT);
        anonymousRevision.put("comment", "Sửa lại");
        assertEventRejected(anonymousRevision, "job_posting_event_actor_required");
        for (String type : new String[]{"CLOSED", "UNPUBLISHED"}) {
            var anonymous = event(posting, 2, type, "PUBLISHED", type, null, AT);
            anonymous.put("comment", "Không còn nhu cầu");
            assertEventRejected(anonymous, "job_posting_event_actor_required");
        }
        assertEventRejected(event(posting, 2, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", UUID.randomUUID(), AT),
                "job_posting_events_actor_id_fkey");
        var commentedPublication = event(posting, 2, "PUBLISHED", "APPROVED", "PUBLISHED", fixture.reviewerId(), AT);
        commentedPublication.put("comment", "Đăng ngay");
        assertEventRejected(commentedPublication, "job_posting_event_comment_allowed");
        assertEventRejected(event(posting, 2, "REVISION_REQUESTED", "PENDING_APPROVAL", "REVISION_REQUESTED",
                fixture.hrId(), AT), "job_posting_event_comment_required");
        assertEventRejected(event(posting, 2, "UNPUBLISHED", "PUBLISHED", "UNPUBLISHED", fixture.hrId(), AT),
                "job_posting_event_comment_required");
        var unpublishedFromApproved = event(posting, 2, "UNPUBLISHED", "APPROVED", "UNPUBLISHED", fixture.hrId(), AT);
        unpublishedFromApproved.put("comment", "Gỡ tin");
        assertEventRejected(unpublishedFromApproved, "valid_job_posting_event_transition");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_posting_events WHERE job_posting_id = ?",
                Integer.class, posting)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_posting_events", Integer.class)).isEqualTo(3);
    }

    // The expiry stays in the history for good: V23's trigger still refuses every change and removal.
    @Test
    void expiriesStayAppendOnly() {
        var fixture = insertFixture(jdbc);
        UUID posting = insert(jdbc, withTrail(fixture, posting(fixture, fixture.requisitionId(), "EXPIRED", 5)));
        insertEvent(jdbc, expiry(posting, 5, "PUBLISHED"));
        var before = jdbc.queryForList("SELECT * FROM job_posting_events");

        for (String change : new String[]{
                "UPDATE job_posting_events SET actor_id = (SELECT min(id::text)::uuid FROM user_accounts)",
                "UPDATE job_posting_events SET occurred_at = now()",
                "DELETE FROM job_posting_events"}) {
            assertThatThrownBy(() -> jdbc.update(change)).as(change)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("job_posting_events is append-only")
                    .cause().isInstanceOfSatisfying(SQLException.class,
                            exception -> assertThat(exception.getSQLState()).isEqualTo("23514"));
        }
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events")).isEqualTo(before);
    }

    @Test
    void theOneExpiryIndexJoinsTheIndexesOfTheHistory() {
        Map<String, String> indexes = new HashMap<>();
        jdbc.queryForList("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public' "
                + "AND tablename = 'job_posting_events'").forEach(index -> indexes.put((String) index.get("indexname"),
                (String) index.get("indexdef")));

        assertThat(indexes).containsOnlyKeys("job_posting_events_pkey", "job_posting_events_revision_key",
                "job_posting_events_one_publication_idx", "job_posting_events_actor_id_idx",
                "job_posting_events_one_closure_idx", "job_posting_events_one_unpublication_idx",
                "job_posting_events_one_expiry_idx");
        assertThat(indexes.get("job_posting_events_one_expiry_idx"))
                .startsWith("CREATE UNIQUE INDEX job_posting_events_one_expiry_idx ON ")
                .contains("(job_posting_id) WHERE ((event_type)::text = 'EXPIRED'::text)");
    }

    // A posting of the requisition in this V26 status, with the history that led there: the recruiter sent it (2), HR
    // asked for changes or approved it (3), ADMIN published it (4, with the trail); a closed posting was published,
    // then closed with its requisition (5); a posting taken down was published, then taken down by HR (5).
    private static UUID insertWithSteps(JdbcTemplate db, Fixture fixture, UUID requisition, String status) {
        int revision = switch (status) {
            case "DRAFT" -> 1;
            case "PENDING_APPROVAL" -> 2;
            case "REVISION_REQUESTED", "APPROVED" -> 3;
            case "PUBLISHED" -> 4;
            default -> 5;
        };
        Map<String, Object> row = posting(fixture, requisition, status, revision);
        UUID id = insert(db, revision >= 4 ? withTrail(fixture, row) : row);
        if (revision >= 2) {
            insertEvent(db, event(id, 2, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", fixture.creatorId(), AT));
        }
        if (status.equals("REVISION_REQUESTED")) {
            var returned = event(id, 3, "REVISION_REQUESTED", "PENDING_APPROVAL", "REVISION_REQUESTED", fixture.hrId(),
                    AT);
            returned.put("comment", "Bổ sung quyền lợi.");
            insertEvent(db, returned);
        } else if (revision >= 3) {
            insertEvent(db, event(id, 3, "APPROVED", "PENDING_APPROVAL", "APPROVED", fixture.hrId(), AT));
        }
        if (revision >= 4) {
            insertEvent(db, event(id, 4, "PUBLISHED", "APPROVED", "PUBLISHED", fixture.reviewerId(), PUBLISHED_AT));
        }
        if (revision == 5) {
            var last = event(id, 5, status, "PUBLISHED", status, fixture.hrId(), EXPIRED_AT);
            last.put("comment", status.equals("CLOSED") ? "Đã tuyển đủ" : "Đã nhận đủ hồ sơ.");
            insertEvent(db, last);
        }
        return id;
    }

    private static String actorNullable(JdbcTemplate db) {
        return db.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = 'job_posting_events' AND column_name = 'actor_id'", String.class);
    }

    private static Map<String, Object> row(JdbcTemplate db, UUID posting) {
        return db.queryForMap("SELECT * FROM job_postings WHERE id = ?", posting);
    }

    // A complete posting of the requisition in the given status and revision, made by the fixture's recruiter, without
    // a publication trail.
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

    // NULL parameters need a type, otherwise PostgreSQL cannot tell what they are.
    private static final Map<String, String> CASTS = Map.of("work_location_id", "uuid", "employment_type_id", "uuid",
            "application_deadline", "date", "published_by", "uuid", "published_at", "timestamptz");
    private static final Map<String, String> EVENT_CASTS = Map.of("actor_id", "uuid", "comment", "text");

    private static UUID insert(JdbcTemplate db, Map<String, Object> row) {
        db.update("INSERT INTO job_postings (" + String.join(",", row.keySet()) + ") VALUES ("
                + String.join(",", row.keySet().stream().map(key -> CASTS.containsKey(key)
                        ? "CAST(? AS " + CASTS.get(key) + ")" : "?").toList()) + ")", row.values().toArray());
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

    // The expiry of a posting by the daily task: no actor, no comment.
    private static Map<String, Object> expiry(UUID posting, int revision, String before) {
        return event(posting, revision, "EXPIRED", before, "EXPIRED", null, EXPIRED_AT);
    }

    private static void insertEvent(JdbcTemplate db, Map<String, Object> row) {
        db.update("INSERT INTO job_posting_events (" + String.join(",", row.keySet()) + ") VALUES ("
                + String.join(",", row.keySet().stream().map(key -> EVENT_CASTS.containsKey(key)
                        ? "CAST(? AS " + EVENT_CASTS.get(key) + ")" : "?").toList()) + ")", row.values().toArray());
    }

    private static void assertEventRejected(Map<String, Object> row, String message) {
        assertThatThrownBy(() -> insertEvent(jdbc, row)).as(message + " " + row)
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

    // The upgrade starts from the newest migration below V27, whichever task added it.
    private static MigrationVersion latestVersionBefore(DataSource dataSource, MigrationVersion version) {
        return Arrays.stream(flyway(dataSource).load().info().all())
                .map(MigrationInfo::getVersion)
                .filter(candidate -> candidate.compareTo(version) < 0)
                .max(Comparator.naturalOrder())
                .orElseThrow();
    }

    // An HR manager who made the requisitions, the head of IT, a recruiter who writes the postings, an ADMIN who
    // publishes them, one position, one work location, one employment type and one APPROVED requisition.
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
                insertRequisition(db, fixture, "APPROVED"));
    }

    private static UUID insertRequisition(JdbcTemplate db, Fixture fixture, String status) {
        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO recruitment_requisitions (id,position_id,department_id,headcount,reason,proposed_salary_min,
                    proposed_salary_max,needed_by,job_description,candidate_requirements,status,created_by,created_at,
                    updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, fixture.positionId(), fixture.departmentId(), 2, "NEW_HEADCOUNT", 20_000_000L, 22_000_000L,
                LocalDate.of(2026, 12, 1), "Mô tả", "Yêu cầu", status, fixture.hrId(), CREATED_AT, CREATED_AT);
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
