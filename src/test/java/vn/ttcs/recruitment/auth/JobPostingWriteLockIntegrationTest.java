package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.requisition.RequisitionRecruiterRole;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.WORK_LOCATION;

// Task 303: what a save of a posting does when it has to wait for a lock. The caller is a recruiter assigned to the
// requisition; whatever changed while it waited is checked once the lock is granted. Task 304: a catalog value HR
// stopped using while the save waited is refused like one deleted meanwhile.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobPostingWriteLockIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;

    private JobPostingTestSupport postings;
    private String passwordHash;
    private UUID adminId;
    private UUID hrId;
    private UUID recruiterId;
    private String recruiterToken;
    private UUID requisition;
    // A work location no posting uses yet; the saves under test choose it.
    private UUID chosen;
    // A second position, which a save of the requisition moves it to while the posting's create waits.
    private UUID otherPosition;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void resetFixture() throws Exception {
        clock.set(START);
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                + "VALUES ('RECRUITER', 'JOB_POSTINGS_WRITE_SCOPED') ON CONFLICT DO NOTHING");
        postings = new JobPostingTestSupport(jdbc, json, transactions,
                Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        postings.reset();
        bootstrap.run(new DefaultApplicationArguments());
        adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = account("hr@example.test", Role.HR_MANAGER);
        UUID headId = account("head@example.test", Role.HIRING_MANAGER);
        recruiterId = account("recruiter@example.test", Role.RECRUITER);
        // One login, so the recruiter has exactly one session.
        recruiterToken = postings.api.token("recruiter@example.test");
        UUID department = postings.api.department("IT", headId, null, START);
        UUID position = postings.api.position(START);
        requisition = postings.requisition(position, department, hrId, RequisitionStatus.APPROVED, START);
        postings.assign(requisition, recruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
        chosen = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, START);
        otherPosition = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id, code, name, level, salary_min, salary_max, active, created_at, updated_at)
                VALUES (?, 'QA', 'Kiểm thử viên', 'Junior', 15000000, 70000000, TRUE, ?, ?)
                """, otherPosition, Timestamp.from(START), Timestamp.from(START));
    }

    // A save waits for the requisition row (a save, a decision or a recruiter change holds it), the caller's account
    // (an admin lock or a logout holds it) or the chosen catalog value (HR changing it); the update also for the
    // posting row (another save). Combined changes make the re-check under test answer before a later error:
    // token-expired+rejected is refused right after the requisition lock (otherwise 409 NOT_APPROVED), and
    // permission-lost+edited right after the posting lock (otherwise 409 CHANGED).
    // position-changed: the requisition moves to another position while the create waits. Only a draft or returned
    // requisition can (RequisitionService.update), and the create then answers with its status (draft-position-changed,
    // 409), never with "does not exist" (400): the lock reads the position name again on the row as it is after the
    // wait. The approved case shows the new name reaches the title, though no API changes an approved requisition.
    @ParameterizedTest(name = "{0}: {1} held, then {2}")
    @CsvSource({
            "create, requisition, nothing, 201", "create, requisition, token-expired, 401",
            "create, requisition, permission-lost, 403", "create, requisition, unassigned, 403",
            "create, requisition, posting-created-meanwhile, 409", "create, requisition, requisition-rejected, 409",
            "create, requisition, draft-position-changed, 409", "create, requisition, position-changed, 201",
            "update, posting, nothing, 200", "update, posting, edited-meanwhile, 409",
            "update, requisition, unassigned, 403", "update, posting, permission-lost, 403",
            "create, actor, locked-actor, 401", "update, actor, revoked-session, 401",
            "create, requisition, token-expired+rejected, 401", "update, posting, permission-lost+edited, 403",
            "update, catalog, deleted-meanwhile, 400", "update, catalog, token-expired, 401",
            "create, catalog, token-expired, 401", "create, catalog, permission-lost, 403",
            "update, requisition, requisition-rejected, 409", "update, catalog, deactivated, 400"
    })
    void theComposerIsCheckedAgainAfterEachLockWait(String action, String held, String change, int expected)
            throws Exception {
        UUID posting = null;
        Callable<HttpResponse<String>> call;
        if (action.equals("create")) {
            call = () -> postings.create(Map.of("requisitionId", requisition, "workLocationId", chosen),
                    recruiterToken);
        } else {
            JsonNode created = postings.created(Map.of("requisitionId", requisition), recruiterToken);
            posting = UUID.fromString(created.path("id").asText());
            Map<String, Object> body = postings.putBody(created);
            body.put("title", "Lập trình viên Java");
            body.put("workLocationId", chosen);
            UUID id = posting;
            call = () -> postings.update(id, body, recruiterToken);
        }
        if (change.startsWith("draft-")) {
            jdbc.update("UPDATE recruitment_requisitions SET status = 'DRAFT' WHERE id = ?", requisition);
        }
        UUID meanwhile = UUID.randomUUID();
        List<Map<String, Object>> postingsBefore = rows("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> authorsBefore = rows("SELECT * FROM job_posting_authors ORDER BY 1, 2");
        Map<String, Object> requisitionBefore = postings.requisitionRow(requisition);
        String sql = switch (held) {
            case "requisition" -> "SELECT pg_backend_pid() FROM recruitment_requisitions WHERE id = ? FOR SHARE";
            case "posting" -> "SELECT pg_backend_pid() FROM job_postings WHERE id = ? FOR SHARE";
            case "catalog" -> "SELECT pg_backend_pid() FROM recruitment_catalog_items WHERE id = ? FOR UPDATE";
            default -> "SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR SHARE";
        };
        UUID heldId = switch (held) {
            case "requisition" -> requisition;
            case "posting" -> posting;
            case "catalog" -> chosen;
            default -> recruiterId;
        };
        HttpResponse<String> result;
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, sql, heldId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(call);
                awaitWaiters(blocker, 1);
                if (change.contains("token-expired")) {
                    clock.set(START.plus(Duration.ofMinutes(16)));
                }
                if (change.contains("rejected")) {
                    execute(connection, "UPDATE recruitment_requisitions SET status = 'REJECTED' WHERE id = ?",
                            requisition);
                }
                if (change.contains("position-changed")) {
                    execute(connection, "UPDATE recruitment_requisitions SET position_id = ? WHERE id = ?",
                            otherPosition, requisition);
                }
                if (change.startsWith("permission-lost")) {
                    jdbc.update("DELETE FROM role_permissions WHERE role_code = 'RECRUITER' "
                            + "AND permission_code = 'JOB_POSTINGS_WRITE_SCOPED'");
                }
                if (change.contains("edited")) {
                    execute(connection, "UPDATE job_postings SET revision = revision + 1 WHERE id = ?", posting);
                }
                switch (change) {
                    case "unassigned" -> execute(connection, "DELETE FROM requisition_recruiters "
                            + "WHERE requisition_id = ? AND recruiter_id = ?", requisition, recruiterId);
                    case "posting-created-meanwhile" -> execute(connection, """
                            INSERT INTO job_postings (id, requisition_id, status, revision, title, job_description,
                                candidate_requirements, show_salary, created_by, created_at, updated_at)
                            VALUES (?, ?, 'DRAFT', 1, 'Tin của HR', 'Mô tả', 'Yêu cầu', FALSE, ?, ?, ?)""",
                            meanwhile, requisition, hrId, Timestamp.from(START), Timestamp.from(START));
                    case "locked-actor" -> execute(connection, "UPDATE user_accounts SET admin_locked_at = ?, "
                            + "admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                            Timestamp.from(START), adminId, recruiterId);
                    case "revoked-session" -> execute(connection,
                            "UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?", Timestamp.from(START),
                            recruiterId);
                    case "deleted-meanwhile" -> execute(connection,
                            "DELETE FROM recruitment_catalog_items WHERE id = ?", chosen);
                    case "deactivated" -> execute(connection,
                            "UPDATE recruitment_catalog_items SET active = FALSE WHERE id = ?", chosen);
                    default -> { }
                }
                connection.commit();
                result = response.get(15, TimeUnit.SECONDS);
            }
        }

        assertThat(result.statusCode()).as(result.body()).isEqualTo(expected);
        JsonNode body = json.readTree(result.body());
        switch (expected) {
            case 401 -> assertThat(body.path("code").asText()).isEqualTo("SESSION_INVALID");
            case 403 -> assertThat(body.path("code").asText()).isEqualTo("FORBIDDEN");
            case 400 -> assertThat(body.path("code").asText()).isEqualTo(change.equals("deactivated")
                    ? "JOB_POSTING_WORK_LOCATION_INACTIVE" : "INVALID_JOB_POSTING_WORK_LOCATION");
            case 409 -> assertThat(body.path("code").asText()).isEqualTo(switch (change) {
                case "posting-created-meanwhile" -> "JOB_POSTING_ALREADY_EXISTS";
                case "requisition-rejected", "draft-position-changed" -> "JOB_POSTING_REQUISITION_NOT_APPROVED";
                default -> "JOB_POSTING_CHANGED";
            });
            default -> { }
        }
        if (expected == 201) {
            UUID created = UUID.fromString(body.path("id").asText());
            assertThat(postings.row(created).get("work_location_id")).isEqualTo(chosen);
            assertThat(postings.authorsOf(created)).containsExactly(recruiterId);
            // The title the body leaves out is the name of the position the requisition has once the lock is granted.
            assertThat(postings.row(created).get("title"))
                    .isEqualTo(change.equals("position-changed") ? "Kiểm thử viên" : "Lập trình viên");
        } else if (expected == 200) {
            assertThat(postings.row(posting).get("revision")).isEqualTo(2);
            assertThat(postings.row(posting).get("work_location_id")).isEqualTo(chosen);
            assertThat(postings.authorsOf(posting)).containsExactly(recruiterId);
        } else {
            // Nothing written by the refused request; only the change made while it waited.
            List<Map<String, Object>> expectedPostings = new ArrayList<>();
            for (var row : postingsBefore) {
                Map<String, Object> copy = new LinkedHashMap<>(row);
                if (change.contains("edited")) {
                    copy.put("revision", (Integer) copy.get("revision") + 1);
                }
                expectedPostings.add(copy);
            }
            List<Map<String, Object>> postingsAfter = rows("SELECT * FROM job_postings WHERE id <> ? ORDER BY id",
                    meanwhile);
            assertThat(postingsAfter).isEqualTo(expectedPostings);
            assertThat(rows("SELECT * FROM job_posting_authors ORDER BY 1, 2")).isEqualTo(authorsBefore);
        }
        // The API never writes the requisition.
        Map<String, Object> requisitionExpected = new LinkedHashMap<>(requisitionBefore);
        if (change.contains("rejected")) {
            requisitionExpected.put("status", "REJECTED");
        }
        if (change.contains("position-changed")) {
            requisitionExpected.put("position_id", otherPosition);
        }
        assertThat(postings.requisitionRow(requisition)).isEqualTo(requisitionExpected);
    }

    private UUID account(String email, Role role) {
        return accounts.saveAndFlush(new Account(email, "Lock test", passwordHash, Set.of(role), START)).getId();
    }

    private List<Map<String, Object>> rows(String sql, Object... values) {
        return jdbc.queryForList(sql, values);
    }

    private int lock(Connection connection, String sql, UUID id) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); return row.getInt(1); }
        }
    }

    private void execute(Connection connection, String sql, Object... values) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) { statement.setObject(index + 1, values[index]); }
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private void awaitWaiters(int blockerPid, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int observed = 0;
        while (System.nanoTime() < deadline) {
            observed = jdbc.queryForObject("""
                    WITH RECURSIVE blocked(pid) AS (
                        SELECT pid FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))
                        UNION
                        SELECT activity.pid FROM pg_stat_activity activity
                        JOIN blocked ON blocked.pid = ANY(pg_blocking_pids(activity.pid))
                    )
                    SELECT count(*) FROM pg_stat_activity activity JOIN blocked ON blocked.pid = activity.pid
                    WHERE activity.datname = current_database() AND activity.wait_event_type = 'Lock'
                    """, Integer.class, blockerPid);
            if (observed >= expected) { return; }
            Thread.sleep(20);
        }
        assertThat(observed).as("HTTP requests must reach PostgreSQL locks before release").isGreaterThanOrEqualTo(expected);
    }
}
