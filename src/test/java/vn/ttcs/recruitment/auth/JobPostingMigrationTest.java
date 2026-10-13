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

// Task 302: V21 adds job_postings, the job postings made from approved requisitions, and job_posting_authors, the
// accounts that wrote the content of each posting.
class JobPostingMigrationTest {
    private static final MigrationVersion POSTING_VERSION = MigrationVersion.fromVersion("21");
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-11T00:00:00Z"));
    private static final Timestamp UPDATED_AT = Timestamp.from(Instant.parse("2026-10-11T02:30:00.123456Z"));
    private static final LocalDate DEADLINE = LocalDate.of(2026, 11, 30);
    // Indentation, an empty line and the trailing line break must survive exactly.
    private static final String JOB_DESCRIPTION = "  Phát triển API tuyển dụng.\n\n- Spring Boot\n- PostgreSQL\n";
    private static final String REQUIREMENTS = "Tối thiểu 2 năm kinh nghiệm Java.\n    Đọc hiểu tài liệu tiếng Anh.";
    // NULL parameters need a type, otherwise PostgreSQL cannot tell what they are.
    private static final Map<String, String> CASTS = Map.of("benefits", "text", "work_location_id", "uuid",
            "employment_type_id", "uuid", "application_deadline", "date");

    // One database migrated to V21 shared by the rule tests; each test starts without data. The target stays V21, so
    // these tests keep checking V21 itself when later migrations change the table. The upgrade test starts its own
    // database because it needs the schema before V21.
    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startMigratedDatabase() throws Exception {
        postgres = startPostgres();
        var dataSource = postgres.getPostgresDatabase();
        flyway(dataSource).target(POSTING_VERSION).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        postgres.close();
    }

    @BeforeEach
    void deleteData() {
        jdbc.update("DELETE FROM job_posting_authors");
        jdbc.update("DELETE FROM job_postings");
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM recruitment_catalog_items");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM user_accounts");
    }

    @Test
    void theUpgradeKeepsRequisitionsCatalogsAndGrantsAndStartsWithoutPostings() throws Exception {
        try (var upgradePostgres = startPostgres()) {
            var dataSource = upgradePostgres.getPostgresDatabase();
            flyway(dataSource).target(latestVersionBefore(dataSource, POSTING_VERSION)).load().migrate();
            var db = new JdbcTemplate(dataSource);
            insertFixture(db);
            var previousRequisitions = db.queryForList("SELECT * FROM recruitment_requisitions ORDER BY id");
            var previousCatalogs = db.queryForList("SELECT * FROM recruitment_catalog_items ORDER BY id");
            var previousPermissions = db.queryForList("SELECT * FROM permissions ORDER BY code");
            var previousGrants = db.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code");

            var flyway = flyway(dataSource).target(POSTING_VERSION).load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            assertThat(db.queryForList("SELECT * FROM recruitment_requisitions ORDER BY id"))
                    .isEqualTo(previousRequisitions);
            assertThat(db.queryForList("SELECT * FROM recruitment_catalog_items ORDER BY id"))
                    .isEqualTo(previousCatalogs);
            assertThat(db.queryForList("SELECT * FROM permissions ORDER BY code")).isEqualTo(previousPermissions);
            assertThat(db.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code"))
                    .isEqualTo(previousGrants);
            // No posting is invented for approved requisitions; recruiters create them through the API.
            assertThat(db.queryForObject("SELECT count(*) FROM job_postings", Integer.class)).isZero();
            assertThat(db.queryForObject("SELECT count(*) FROM job_posting_authors", Integer.class)).isZero();
        }
    }

    @Test
    void keepsItsOwnCopyOfTheTexts() {
        var fixture = insertFixture(jdbc);
        // The status column is left out on purpose: the database default must make the posting a draft.
        UUID id = insert(posting(fixture, fixture.requisitionId()));

        Map<String, Object> row = row(id);
        assertThat(row.get("requisition_id")).isEqualTo(fixture.requisitionId());
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("revision")).isEqualTo(1);
        assertThat(row.get("title")).isEqualTo("Lập trình viên Java");
        assertThat(row.get("job_description")).isEqualTo(JOB_DESCRIPTION);
        assertThat(row.get("candidate_requirements")).isEqualTo(REQUIREMENTS);
        assertThat(row.get("benefits")).isNull();
        assertThat(row.get("work_location_id")).isEqualTo(fixture.workLocationId());
        assertThat(row.get("employment_type_id")).isEqualTo(fixture.employmentTypeId());
        assertThat(row.get("show_salary")).isEqualTo(true);
        assertThat(row.get("created_by")).isEqualTo(fixture.creatorId());
        assertThat(row.get("created_at")).isEqualTo(CREATED_AT);
        assertThat(row.get("updated_at")).isEqualTo(UPDATED_AT);
        assertThat(jdbc.queryForObject("SELECT application_deadline FROM job_postings WHERE id = ?",
                LocalDate.class, id)).isEqualTo(DEADLINE);

        // The requisition and the posting change independently: neither text follows the other.
        jdbc.update("UPDATE recruitment_requisitions SET job_description = 'Khác', candidate_requirements = 'Khác'"
                + " WHERE id = ?", fixture.requisitionId());
        assertThat(row(id)).isEqualTo(row);
        jdbc.update("UPDATE job_postings SET job_description = 'Mô tả mới' WHERE id = ?", id);
        assertThat(jdbc.queryForObject("SELECT job_description FROM recruitment_requisitions WHERE id = ?",
                String.class, fixture.requisitionId())).isEqualTo("Khác");
    }

    @Test
    void rejectsMissingRequiredColumns() {
        var fixture = insertFixture(jdbc);
        UUID id = insert(posting(fixture, fixture.requisitionId()));

        for (String column : new String[]{"requisition_id", "status", "revision", "title", "job_description",
                "candidate_requirements", "show_salary", "created_by", "created_at", "updated_at"}) {
            assertRejected("UPDATE job_postings SET " + column + " = NULL WHERE id = ?",
                    "null value in column \"" + column + "\"", id);
        }
        // A draft may leave the benefits and every public detail empty.
        jdbc.update("""
                UPDATE job_postings SET benefits = NULL, work_location_id = NULL, employment_type_id = NULL,
                    application_deadline = NULL
                WHERE id = ?
                """, id);
        assertThat(jdbc.queryForObject("""
                SELECT benefits IS NULL AND work_location_id IS NULL AND employment_type_id IS NULL
                    AND application_deadline IS NULL
                FROM job_postings WHERE id = ?
                """, Boolean.class, id)).isTrue();
    }

    @Test
    void rejectsInvalidValues() {
        var fixture = insertFixture(jdbc);
        UUID id = insert(posting(fixture, fixture.requisitionId()));
        var original = row(id);

        for (String blank : new String[]{"", "  "}) {
            assertRejected("UPDATE job_postings SET title = ? WHERE id = ?", "valid_job_posting_title", blank, id);
        }
        assertRejected("UPDATE job_postings SET title = ? WHERE id = ?", "value too long", "T".repeat(256), id);
        for (String blank : new String[]{"", " \n "}) {
            assertRejected("UPDATE job_postings SET job_description = ? WHERE id = ?",
                    "valid_job_posting_job_description", blank, id);
            assertRejected("UPDATE job_postings SET candidate_requirements = ? WHERE id = ?",
                    "valid_job_posting_candidate_requirements", blank, id);
            assertRejected("UPDATE job_postings SET benefits = ? WHERE id = ?", "valid_job_posting_benefits", blank,
                    id);
        }
        assertRejected("UPDATE job_postings SET benefits = ? WHERE id = ?", "valid_job_posting_benefits",
                "b".repeat(10_001), id);
        for (int revision : new int[]{0, -1}) {
            assertRejected("UPDATE job_postings SET revision = ? WHERE id = ?", "positive_job_posting_revision",
                    revision, id);
        }
        // Only DRAFT exists until the review workflow (task 308) widens the CHECK.
        for (String status : new String[]{"PUBLISHED", "PENDING_APPROVAL", "draft", ""}) {
            assertRejected("UPDATE job_postings SET status = ? WHERE id = ?", "valid_job_posting_status", status, id);
        }
        assertThat(row(id)).isEqualTo(original);

        // The limits themselves are allowed.
        jdbc.update("UPDATE job_postings SET title = ?, benefits = ? WHERE id = ?", "T".repeat(255),
                "b".repeat(10_000), id);
        assertThat(jdbc.queryForObject("SELECT char_length(title) + char_length(benefits) FROM job_postings"
                + " WHERE id = ?", Integer.class, id)).isEqualTo(10_255);
    }

    @Test
    void allowsOnePostingPerRequisition() {
        var fixture = insertFixture(jdbc);
        insert(posting(fixture, fixture.requisitionId()));

        assertThatThrownBy(() -> insert(posting(fixture, fixture.requisitionId())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("job_postings_requisition_id_key");
        UUID other = insertRequisition(jdbc, fixture);
        insert(posting(fixture, other));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_postings", Integer.class)).isEqualTo(2);
    }

    @Test
    void recordsEachAuthorOnce() {
        var fixture = insertFixture(jdbc);
        UUID posting = insert(posting(fixture, fixture.requisitionId()));
        UUID other = insert(posting(fixture, insertRequisition(jdbc, fixture)));
        // A posting may have several authors, and an account may write several postings.
        addAuthor(posting, fixture.creatorId());
        addAuthor(posting, fixture.authorId());
        addAuthor(other, fixture.creatorId());

        assertThatThrownBy(() -> addAuthor(null, fixture.authorId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("null value in column \"job_posting_id\"");
        assertThatThrownBy(() -> addAuthor(posting, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("null value in column \"account_id\"");
        assertThatThrownBy(() -> addAuthor(posting, fixture.creatorId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("job_posting_authors_pkey");
        assertThat(jdbc.queryForList("SELECT account_id FROM job_posting_authors WHERE job_posting_id = ?",
                UUID.class, posting)).containsExactlyInAnyOrder(fixture.creatorId(), fixture.authorId());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_posting_authors", Integer.class)).isEqualTo(3);
    }

    @Test
    void keepsReferencedRowsFromBeingDeleted() {
        var fixture = insertFixture(jdbc);
        UUID posting = insert(posting(fixture, fixture.requisitionId()));
        // The author is an account that nothing else references, unlike the creator.
        addAuthor(posting, fixture.authorId());

        assertRejected("DELETE FROM recruitment_requisitions WHERE id = ?", "job_postings_requisition_id_fkey",
                fixture.requisitionId());
        assertRejected("DELETE FROM user_accounts WHERE id = ?", "job_postings_created_by_fkey", fixture.creatorId());
        assertRejected("DELETE FROM recruitment_catalog_items WHERE id = ?", "job_postings_work_location_id_fkey",
                fixture.workLocationId());
        assertRejected("DELETE FROM recruitment_catalog_items WHERE id = ?", "job_postings_employment_type_id_fkey",
                fixture.employmentTypeId());
        assertRejected("DELETE FROM job_postings WHERE id = ?", "job_posting_authors_job_posting_id_fkey", posting);
        assertRejected("DELETE FROM user_accounts WHERE id = ?", "job_posting_authors_account_id_fkey",
                fixture.authorId());

        // Unknown references are refused as well.
        UUID other = insertRequisition(jdbc, fixture);
        for (String column : new String[]{"requisition_id", "work_location_id", "employment_type_id", "created_by"}) {
            var row = posting(fixture, other);
            row.put(column, UUID.randomUUID());
            assertThatThrownBy(() -> insert(row)).as(column)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("job_postings_" + column + "_fkey");
        }
        assertThatThrownBy(() -> addAuthor(UUID.randomUUID(), fixture.authorId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("job_posting_authors_job_posting_id_fkey");
        assertThatThrownBy(() -> addAuthor(posting, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("job_posting_authors_account_id_fkey");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_postings", Integer.class)).isOne();
    }

    @Test
    void theDatabaseLeavesTheCatalogTypeToTheApi() {
        var fixture = insertFixture(jdbc);
        // The foreign key only knows the catalog table, not the catalog type: the API checks the type (task 303).
        var row = posting(fixture, fixture.requisitionId());
        row.put("work_location_id", fixture.employmentTypeId());
        UUID id = insert(row);

        assertThat(row(id).get("work_location_id")).isEqualTo(fixture.employmentTypeId());
    }

    @Test
    void indexes() {
        Map<String, String> postings = indexDefinitions("job_postings");
        assertThat(postings).containsOnlyKeys("job_postings_pkey", "job_postings_requisition_id_key",
                "job_postings_work_location_id_idx", "job_postings_employment_type_id_idx",
                "job_postings_created_by_idx", "job_postings_status_idx");
        assertThat(postings.get("job_postings_requisition_id_key")).contains("UNIQUE").endsWith("(requisition_id)");
        assertThat(postings.get("job_postings_work_location_id_idx")).endsWith("(work_location_id)");
        assertThat(postings.get("job_postings_employment_type_id_idx")).endsWith("(employment_type_id)");
        assertThat(postings.get("job_postings_created_by_idx")).endsWith("(created_by)");
        assertThat(postings.get("job_postings_status_idx")).endsWith("(status)");

        Map<String, String> authors = indexDefinitions("job_posting_authors");
        assertThat(authors).containsOnlyKeys("job_posting_authors_pkey", "job_posting_authors_account_id_idx");
        assertThat(authors.get("job_posting_authors_pkey")).endsWith("(job_posting_id, account_id)");
        assertThat(authors.get("job_posting_authors_account_id_idx")).endsWith("(account_id)");
    }

    private static Map<String, String> indexDefinitions(String table) {
        Map<String, String> definitions = new HashMap<>();
        jdbc.queryForList("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = ?",
                table).forEach(index -> definitions.put((String) index.get("indexname"),
                (String) index.get("indexdef")));
        return definitions;
    }

    // A complete draft of the fixture's position for the given requisition, made by the fixture's recruiter.
    private static Map<String, Object> posting(Fixture fixture, UUID requisitionId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("requisition_id", requisitionId);
        row.put("revision", 1);
        row.put("title", "Lập trình viên Java");
        row.put("job_description", JOB_DESCRIPTION);
        row.put("candidate_requirements", REQUIREMENTS);
        row.put("benefits", null);
        row.put("work_location_id", fixture.workLocationId());
        row.put("employment_type_id", fixture.employmentTypeId());
        row.put("application_deadline", DEADLINE);
        row.put("show_salary", true);
        row.put("created_by", fixture.creatorId());
        row.put("created_at", CREATED_AT);
        row.put("updated_at", UPDATED_AT);
        return row;
    }

    private static UUID insert(Map<String, Object> row) {
        jdbc.update("INSERT INTO job_postings (" + String.join(",", row.keySet()) + ") VALUES ("
                + String.join(",", row.keySet().stream().map(key -> CASTS.containsKey(key)
                        ? "CAST(? AS " + CASTS.get(key) + ")" : "?").toList()) + ")", row.values().toArray());
        return (UUID) row.get("id");
    }

    private static void addAuthor(UUID postingId, UUID accountId) {
        jdbc.update("INSERT INTO job_posting_authors (job_posting_id, account_id)"
                + " VALUES (CAST(? AS uuid), CAST(? AS uuid))", postingId, accountId);
    }

    private static Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM job_postings WHERE id = ?", id);
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

    // The upgrade starts from the newest migration below V21, whichever task added it.
    private static MigrationVersion latestVersionBefore(DataSource dataSource, MigrationVersion version) {
        return Arrays.stream(flyway(dataSource).load().info().all())
                .map(MigrationInfo::getVersion)
                .filter(candidate -> candidate.compareTo(version) < 0)
                .max(Comparator.naturalOrder())
                .orElseThrow();
    }

    // An HR manager who made an APPROVED requisition of IT, the head of IT, a recruiter who writes the posting, another
    // author, one position, and one work location and one employment type of the shared catalogs.
    private static Fixture insertFixture(JdbcTemplate db) {
        UUID hrId = insertAccount(db, "hr@example.test", "HR_MANAGER");
        UUID headId = insertAccount(db, "head@example.test", "HIRING_MANAGER");
        UUID creatorId = insertAccount(db, "recruiter@example.test", "RECRUITER");
        UUID authorId = insertAccount(db, "author@example.test", "RECRUITER");
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
        var fixture = new Fixture(hrId, creatorId, authorId, departmentId, positionId, workLocationId,
                employmentTypeId, null);
        return new Fixture(hrId, creatorId, authorId, departmentId, positionId, workLocationId, employmentTypeId,
                insertRequisition(db, fixture));
    }

    // A complete APPROVED requisition (the only status a posting is made from, task 303).
    private static UUID insertRequisition(JdbcTemplate db, Fixture fixture) {
        UUID id = UUID.randomUUID();
        db.update("""
                INSERT INTO recruitment_requisitions (id,position_id,department_id,headcount,reason,proposed_salary_min,
                    proposed_salary_max,needed_by,job_description,candidate_requirements,status,created_by,created_at,
                    updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, fixture.positionId(), fixture.departmentId(), 2, "NEW_HEADCOUNT", 20_000_000L, 22_000_000L,
                LocalDate.of(2026, 12, 1), JOB_DESCRIPTION, REQUIREMENTS, "APPROVED", fixture.hrId(), CREATED_AT,
                CREATED_AT);
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

    private record Fixture(UUID hrId, UUID creatorId, UUID authorId, UUID departmentId, UUID positionId,
                           UUID workLocationId, UUID employmentTypeId, UUID requisitionId) { }
}
