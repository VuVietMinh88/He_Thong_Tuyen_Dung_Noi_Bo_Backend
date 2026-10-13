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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 292: V25 adds the final posting status CLOSED and its history step, and closes the postings of requisitions that
// were filled or cancelled before it ran, with the person, time and reason of the requisition's closure.
class JobPostingClosureMigrationTest {
    private static final MigrationVersion CLOSURE_VERSION = MigrationVersion.fromVersion("25");
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-11T00:00:00Z"));
    private static final Timestamp AT = Timestamp.from(Instant.parse("2026-10-11T02:30:00.123456Z"));
    private static final Timestamp PUBLISHED_AT = Timestamp.from(Instant.parse("2026-10-11T04:00:00.654321Z"));
    private static final Timestamp CLOSED_AT = Timestamp.from(Instant.parse("2026-10-12T01:15:00.987654Z"));
    private static final LocalDate DEADLINE = LocalDate.of(2026, 11, 30);
    private static final List<String> OPEN_STATUSES = List.of("DRAFT", "PENDING_APPROVAL", "REVISION_REQUESTED",
            "APPROVED", "PUBLISHED");

    // One database migrated to V25 shared by the rule tests; each test starts without data. The target stays V25, so
    // these tests keep checking V25 itself when later migrations change the tables. The upgrade tests start their own
    // database because they need the schema before V25.
    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startMigratedDatabase() throws Exception {
        postgres = startPostgres();
        var dataSource = postgres.getPostgresDatabase();
        flyway(dataSource).target(CLOSURE_VERSION).load().migrate();
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

    @Test
    void theUpgradeLeavesThePostingsOfOpenRequisitionsAsTheyAre() throws Exception {
        try (var upgradePostgres = startPostgres()) {
            var dataSource = upgradePostgres.getPostgresDatabase();
            flyway(dataSource).target(latestVersionBefore(dataSource, CLOSURE_VERSION)).load().migrate();
            var db = new JdbcTemplate(dataSource);
            var fixture = insertFixture(db);
            // Under V24: one posting in each of the five statuses, each with the steps that led to it.
            for (String status : OPEN_STATUSES) {
                insertWithSteps(db, fixture, insertRequisition(db, fixture, "APPROVED"), status);
            }
            var previousPostings = db.queryForList("SELECT * FROM job_postings ORDER BY id");
            var previousEvents = db.queryForList("SELECT * FROM job_posting_events ORDER BY id");
            var previousGrants = db.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code");

            var flyway = flyway(dataSource).target(CLOSURE_VERSION).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            assertThat(db.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(previousPostings);
            assertThat(db.queryForList("SELECT * FROM job_posting_events ORDER BY id")).isEqualTo(previousEvents);
            assertThat(db.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code"))
                    .isEqualTo(previousGrants);
        }
    }

    // Requisitions filled or cancelled under V24 (task 290 ran before task 292): their postings are closed by the
    // upgrade exactly as the API would have closed them.
    @Test
    void theUpgradeClosesThePostingsOfRequisitionsClosedBefore() throws Exception {
        try (var upgradePostgres = startPostgres()) {
            var dataSource = upgradePostgres.getPostgresDatabase();
            flyway(dataSource).target(latestVersionBefore(dataSource, CLOSURE_VERSION)).load().migrate();
            var db = new JdbcTemplate(dataSource);
            var fixture = insertFixture(db);
            UUID filled = insertRequisition(db, fixture, "FILLED");
            insertRequisitionEvent(db, filled, 1, "FILLED", "APPROVED", fixture.hrId(), CLOSED_AT,
                    "Đã tuyển đủ 2 người\nqua đợt tháng 10.");
            UUID published = insertWithSteps(db, fixture, filled, "PUBLISHED");
            // A draft that never got its portal details, of a cancelled requisition.
            UUID cancelled = insertRequisition(db, fixture, "CANCELLED");
            insertRequisitionEvent(db, cancelled, 1, "CANCELLED", "APPROVED", fixture.reviewerId(), AT,
                    "Không còn nhu cầu");
            Map<String, Object> bare = posting(fixture, cancelled, "DRAFT", 1);
            bare.put("work_location_id", null);
            bare.put("employment_type_id", null);
            bare.put("application_deadline", null);
            UUID draft = insert(db, bare);
            // Paused only: nothing closes.
            UUID paused = insertRequisition(db, fixture, "PAUSED");
            insertRequisitionEvent(db, paused, 1, "PAUSED", "APPROVED", fixture.hrId(), AT, "Chờ ngân sách");
            UUID hidden = insertWithSteps(db, fixture, paused, "PUBLISHED");
            UUID open = insertWithSteps(db, fixture, insertRequisition(db, fixture, "APPROVED"), "APPROVED");
            Map<String, Object> publishedBefore = row(db, published);
            Map<String, Object> draftBefore = row(db, draft);
            var untouched = db.queryForList("SELECT * FROM job_postings WHERE id IN (?, ?) ORDER BY id", hidden, open);
            var untouchedEvents = db.queryForList(
                    "SELECT * FROM job_posting_events WHERE job_posting_id IN (?, ?) ORDER BY id", hidden, open);

            assertThat(flyway(dataSource).target(CLOSURE_VERSION).load().migrate().migrationsExecuted).isEqualTo(1);

            // CLOSED with one more revision; the trail, the content and updated_at are kept.
            Map<String, Object> closed = row(db, published);
            assertThat(closed).containsEntry("status", "CLOSED").containsEntry("revision", 5)
                    .containsEntry("published_by", fixture.reviewerId()).containsEntry("published_at", PUBLISHED_AT);
            assertThat(withoutStatus(closed)).isEqualTo(withoutStatus(publishedBefore));
            assertThat(lastEvent(db, published)).containsEntry("revision", 5).containsEntry("event_type", "CLOSED")
                    .containsEntry("status_before", "PUBLISHED").containsEntry("status_after", "CLOSED")
                    .containsEntry("actor_id", fixture.hrId()).containsEntry("occurred_at", CLOSED_AT)
                    .containsEntry("comment", "Đã tuyển đủ 2 người\nqua đợt tháng 10.");
            assertThat(db.queryForList("SELECT event_type FROM job_posting_events WHERE job_posting_id = ? "
                    + "ORDER BY revision", String.class, published))
                    .containsExactly("SUBMITTED", "APPROVED", "PUBLISHED", "CLOSED");
            // The bare draft is closed too, without its portal details.
            assertThat(row(db, draft)).containsEntry("status", "CLOSED").containsEntry("revision", 2)
                    .containsEntry("application_deadline", null).containsEntry("published_at", null);
            assertThat(withoutStatus(row(db, draft))).isEqualTo(withoutStatus(draftBefore));
            assertThat(lastEvent(db, draft)).containsEntry("revision", 2).containsEntry("status_before", "DRAFT")
                    .containsEntry("actor_id", fixture.reviewerId()).containsEntry("occurred_at", AT)
                    .containsEntry("comment", "Không còn nhu cầu");
            assertThat(db.queryForList("SELECT * FROM job_postings WHERE id IN (?, ?) ORDER BY id", hidden, open))
                    .isEqualTo(untouched);
            assertThat(db.queryForList("SELECT * FROM job_posting_events WHERE job_posting_id IN (?, ?) ORDER BY id",
                    hidden, open)).isEqualTo(untouchedEvents);
        }
    }

    @Test
    void closedIsTheOnlyNewStatus() {
        var fixture = insertFixture(jdbc);
        UUID id = insert(jdbc, posting(fixture, fixture.requisitionId(), "DRAFT", 1));

        jdbc.update("UPDATE job_postings SET status = 'CLOSED' WHERE id = ?", id);
        for (String status : new String[]{"REJECTED", "UNPUBLISHED", "closed", ""}) {
            assertRejected("UPDATE job_postings SET status = ? WHERE id = ?", "valid_job_posting_status", status, id);
        }
        assertThat(row(jdbc, id)).containsEntry("status", "CLOSED");
    }

    @Test
    void onlyAPostingClosedBeforeItsPublicationMayLackThePortalDetails() {
        var fixture = insertFixture(jdbc);
        Map<String, Object> bare = posting(fixture, fixture.requisitionId(), "CLOSED", 2);
        bare.put("work_location_id", null);
        bare.put("employment_type_id", null);
        bare.put("application_deadline", null);
        UUID id = insert(jdbc, bare);
        assertThat(row(jdbc, id)).containsEntry("status", "CLOSED").containsEntry("application_deadline", null);

        // Closed after its publication: it had every detail and keeps them.
        UUID published = insert(jdbc, withTrail(fixture, posting(fixture, insertRequisition(jdbc, fixture, "FILLED"),
                "CLOSED", 5)));
        for (String detail : new String[]{"application_deadline", "work_location_id", "employment_type_id"}) {
            assertRejected("UPDATE job_postings SET " + detail + " = NULL WHERE id = ?", "job_posting_details_required",
                    published);
        }
        // The other statuses after the drafts still need them.
        for (String status : new String[]{"PENDING_APPROVAL", "APPROVED"}) {
            assertRejected("UPDATE job_postings SET status = ? WHERE id = ?", "job_posting_details_required", status,
                    id);
        }
    }

    @Test
    void closingIsAStepFromEveryOpenStatusAndNoOther() {
        var fixture = insertFixture(jdbc);
        for (String before : OPEN_STATUSES) {
            UUID posting = insert(jdbc, posting(fixture, insertRequisition(jdbc, fixture, "CANCELLED"), "CLOSED", 9));
            insertEvent(jdbc, closure(posting, 9, before, fixture.hrId()));
        }
        assertThat(jdbc.queryForList("SELECT status_before FROM job_posting_events ORDER BY status_before",
                String.class)).containsExactlyInAnyOrderElementsOf(OPEN_STATUSES);

        UUID posting = insert(jdbc, posting(fixture, insertRequisition(jdbc, fixture, "FILLED"), "CLOSED", 9));
        var fromClosed = closure(posting, 9, "CLOSED", fixture.hrId());
        assertEventRejected(fromClosed, "valid_job_posting_event_transition");
        var notToClosed = closure(posting, 9, "PUBLISHED", fixture.hrId());
        notToClosed.put("status_after", "PUBLISHED");
        assertEventRejected(notToClosed, "valid_job_posting_event_transition");
        // Nothing leaves CLOSED: publishing it again is no step.
        var republished = event(posting, 9, "PUBLISHED", "CLOSED", "PUBLISHED", fixture.reviewerId(), AT);
        assertEventRejected(republished, "valid_job_posting_event_transition");
        var reopened = event(posting, 9, "REVISION_REQUESTED", "CLOSED", "REVISION_REQUESTED", fixture.hrId(), AT);
        reopened.put("comment", "Mở lại");
        assertEventRejected(reopened, "valid_job_posting_event_transition");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_posting_events WHERE job_posting_id = ?",
                Integer.class, posting)).isZero();
    }

    // V25 drops and re-creates the transition and comment CHECKs of V22, so the steps before closing keep their V22
    // rules at V25 too, not only at V22 (JobPostingWorkflowMigrationTest).
    @Test
    void theStepsBeforeClosingKeepTheirRulesOfV22() {
        var fixture = insertFixture(jdbc);
        // Every step of V22 is still accepted: sent, changes asked while under review, sent again, approved, changes
        // asked after the approval, sent and approved again, published.
        UUID published = insert(jdbc, withTrail(fixture, posting(fixture, fixture.requisitionId(), "PUBLISHED", 9)));
        insertEvent(jdbc, event(published, 2, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", fixture.creatorId(), AT));
        insertEvent(jdbc, revisionRequested(published, 3, "PENDING_APPROVAL", fixture.hrId()));
        insertEvent(jdbc, event(published, 4, "SUBMITTED", "REVISION_REQUESTED", "PENDING_APPROVAL",
                fixture.creatorId(), AT));
        insertEvent(jdbc, event(published, 5, "APPROVED", "PENDING_APPROVAL", "APPROVED", fixture.hrId(), AT));
        insertEvent(jdbc, revisionRequested(published, 6, "APPROVED", fixture.hrId()));
        insertEvent(jdbc, event(published, 7, "SUBMITTED", "REVISION_REQUESTED", "PENDING_APPROVAL",
                fixture.creatorId(), AT));
        insertEvent(jdbc, event(published, 8, "APPROVED", "PENDING_APPROVAL", "APPROVED", fixture.hrId(), AT));
        insertEvent(jdbc, event(published, 9, "PUBLISHED", "APPROVED", "PUBLISHED", fixture.reviewerId(),
                PUBLISHED_AT));
        assertThat(jdbc.queryForList("SELECT event_type FROM job_posting_events ORDER BY revision", String.class))
                .containsExactly("SUBMITTED", "REVISION_REQUESTED", "SUBMITTED", "APPROVED", "REVISION_REQUESTED",
                        "SUBMITTED", "APPROVED", "PUBLISHED");

        // A wrong status before or after is still refused for each of them.
        UUID posting = insert(jdbc, posting(fixture, insertRequisition(jdbc, fixture, "APPROVED"), "DRAFT", 1));
        for (var step : List.of(
                event(posting, 2, "SUBMITTED", "APPROVED", "PENDING_APPROVAL", fixture.creatorId(), AT),
                event(posting, 2, "SUBMITTED", "DRAFT", "APPROVED", fixture.creatorId(), AT),
                event(posting, 2, "APPROVED", "DRAFT", "APPROVED", fixture.hrId(), AT),
                event(posting, 2, "APPROVED", "PENDING_APPROVAL", "PUBLISHED", fixture.hrId(), AT),
                revisionRequested(posting, 2, "PUBLISHED", fixture.hrId()),
                revisionRequested(posting, 2, "DRAFT", fixture.hrId()),
                withStatusAfter(revisionRequested(posting, 2, "PENDING_APPROVAL", fixture.hrId()), "DRAFT"),
                event(posting, 2, "PUBLISHED", "PENDING_APPROVAL", "PUBLISHED", fixture.reviewerId(), AT),
                event(posting, 2, "PUBLISHED", "APPROVED", "APPROVED", fixture.reviewerId(), AT))) {
            assertEventRejected(step, "valid_job_posting_event_transition");
        }
        // Asking for changes still says why.
        var withoutComment = revisionRequested(posting, 2, "PENDING_APPROVAL", fixture.hrId());
        withoutComment.put("comment", null);
        assertEventRejected(withoutComment, "job_posting_event_comment_required");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_posting_events WHERE job_posting_id = ?",
                Integer.class, posting)).isZero();
    }

    @Test
    void aClosureCarriesTheRequisitionsReasonAndHappensOnce() {
        var fixture = insertFixture(jdbc);
        UUID posting = insert(jdbc, posting(fixture, fixture.requisitionId(), "CLOSED", 2));

        var noReason = closure(posting, 2, "DRAFT", fixture.hrId());
        noReason.put("comment", null);
        assertEventRejected(noReason, "job_posting_event_comment_required");
        var blank = closure(posting, 2, "DRAFT", fixture.hrId());
        blank.put("comment", "  \n ");
        assertEventRejected(blank, "valid_job_posting_event_comment");

        insertEvent(jdbc, closure(posting, 2, "DRAFT", fixture.hrId()));
        assertEventRejected(closure(posting, 3, "DRAFT", fixture.reviewerId()), "job_posting_events_one_closure_idx");
        // Another posting has its own closure.
        UUID other = insert(jdbc, posting(fixture, insertRequisition(jdbc, fixture, "FILLED"), "CLOSED", 2));
        insertEvent(jdbc, closure(other, 2, "DRAFT", fixture.hrId()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_posting_events", Integer.class)).isEqualTo(2);
    }

    @Test
    void closuresStayAppendOnly() {
        var fixture = insertFixture(jdbc);
        UUID posting = insert(jdbc, posting(fixture, fixture.requisitionId(), "CLOSED", 2));
        insertEvent(jdbc, closure(posting, 2, "DRAFT", fixture.hrId()));
        var before = jdbc.queryForList("SELECT * FROM job_posting_events");

        for (String change : new String[]{
                "UPDATE job_posting_events SET comment = 'Sửa tay'",
                "UPDATE job_posting_events SET event_type = 'PUBLISHED', status_after = 'PUBLISHED'",
                "DELETE FROM job_posting_events"}) {
            assertThatThrownBy(() -> jdbc.update(change)).as(change)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("job_posting_events is append-only");
        }
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events")).isEqualTo(before);
    }

    @Test
    void theOneClosureIndexJoinsTheIndexesOfTheHistory() {
        Map<String, String> indexes = new HashMap<>();
        jdbc.queryForList("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public' "
                + "AND tablename = 'job_posting_events'").forEach(index -> indexes.put((String) index.get("indexname"),
                (String) index.get("indexdef")));

        assertThat(indexes).containsOnlyKeys("job_posting_events_pkey", "job_posting_events_revision_key",
                "job_posting_events_one_publication_idx", "job_posting_events_actor_id_idx",
                "job_posting_events_one_closure_idx");
        assertThat(indexes.get("job_posting_events_one_closure_idx"))
                .startsWith("CREATE UNIQUE INDEX job_posting_events_one_closure_idx ON ")
                .contains("(job_posting_id) WHERE ((event_type)::text = 'CLOSED'::text)");
    }

    // A posting of the requisition in this status, with the history that led there: the recruiter sent it (2), HR
    // asked for changes or approved it (3), ADMIN published it (4, with the trail).
    private static UUID insertWithSteps(JdbcTemplate db, Fixture fixture, UUID requisition, String status) {
        int revision = switch (status) {
            case "DRAFT" -> 1;
            case "PENDING_APPROVAL" -> 2;
            case "REVISION_REQUESTED", "APPROVED" -> 3;
            default -> 4;
        };
        Map<String, Object> row = posting(fixture, requisition, status, revision);
        UUID id = insert(db, status.equals("PUBLISHED") ? withTrail(fixture, row) : row);
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
        if (revision == 4) {
            insertEvent(db, event(id, 4, "PUBLISHED", "APPROVED", "PUBLISHED", fixture.reviewerId(), PUBLISHED_AT));
        }
        return id;
    }

    private static Map<String, Object> row(JdbcTemplate db, UUID posting) {
        return db.queryForMap("SELECT * FROM job_postings WHERE id = ?", posting);
    }

    private static Map<String, Object> lastEvent(JdbcTemplate db, UUID posting) {
        return db.queryForMap("SELECT * FROM job_posting_events WHERE job_posting_id = ? ORDER BY revision DESC "
                + "LIMIT 1", posting);
    }

    // The row without what a closure changes.
    private static Map<String, Object> withoutStatus(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.remove("status");
        copy.remove("revision");
        return copy;
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

    // HR asks for changes, with a comment.
    private static Map<String, Object> revisionRequested(UUID posting, int revision, String before, UUID actor) {
        var row = event(posting, revision, "REVISION_REQUESTED", before, "REVISION_REQUESTED", actor, AT);
        row.put("comment", "Bổ sung quyền lợi.");
        return row;
    }

    private static Map<String, Object> withStatusAfter(Map<String, Object> row, String after) {
        row.put("status_after", after);
        return row;
    }

    // The closure of a posting with the requisition's reason.
    private static Map<String, Object> closure(UUID posting, int revision, String before, UUID actor) {
        var row = event(posting, revision, "CLOSED", before, "CLOSED", actor, CLOSED_AT);
        row.put("comment", "Đã tuyển đủ");
        return row;
    }

    private static void insertEvent(JdbcTemplate db, Map<String, Object> row) {
        db.update("INSERT INTO job_posting_events (" + String.join(",", row.keySet()) + ") VALUES ("
                + String.join(",", row.keySet().stream().map(key -> key.equals("comment")
                        ? "CAST(? AS text)" : "?").toList()) + ")", row.values().toArray());
    }

    // A status change of HR in the requisition's history (V24): no round, level or snapshot.
    private static void insertRequisitionEvent(JdbcTemplate db, UUID requisition, int revision, String type,
                                               String before, UUID actor, Timestamp at, String comment) {
        db.update("""
                INSERT INTO requisition_workflow_events (id, requisition_id, revision, event_type, status_before,
                    status_after, actor_id, occurred_at, comment)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), requisition, revision, type, before, type.equals("RESUMED") ? "APPROVED" : type,
                actor, at, comment);
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

    // The upgrade starts from the newest migration below V25, whichever task added it.
    private static MigrationVersion latestVersionBefore(DataSource dataSource, MigrationVersion version) {
        return Arrays.stream(flyway(dataSource).load().info().all())
                .map(MigrationInfo::getVersion)
                .filter(candidate -> candidate.compareTo(version) < 0)
                .max(Comparator.naturalOrder())
                .orElseThrow();
    }

    // An HR manager who made the requisitions and closes them, the head of IT, a recruiter who writes the postings, an
    // ADMIN who publishes them, one position, one work location, one employment type and one APPROVED requisition.
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
