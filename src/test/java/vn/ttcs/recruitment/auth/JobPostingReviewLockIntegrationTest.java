package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.EMPLOYMENT_TYPE;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.WORK_LOCATION;

// Task 308: what sending for review, a decision and a publication do when they have to wait for a lock. Whatever
// changed while they waited is checked once the lock is granted; concurrent decisions or publications of one posting
// record one step.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobPostingReviewLockIntegrationTest {
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
    private String adminToken;
    private UUID hrId;
    private String hrToken;
    private UUID recruiterId;
    private String recruiterToken;
    private UUID requisition;
    private UUID workLocation;
    private UUID employmentType;

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
        restorePermissions();
        postings = new JobPostingTestSupport(jdbc, json, transactions,
                Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        postings.reset();
        bootstrap.run(new DefaultApplicationArguments());
        adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = account("hr@example.test", Role.HR_MANAGER);
        UUID headId = account("head@example.test", Role.HIRING_MANAGER);
        recruiterId = account("recruiter@example.test", Role.RECRUITER);
        // One login each, so every account has exactly one session.
        adminToken = postings.api.token("admin@example.test");
        hrToken = postings.api.token("hr@example.test");
        recruiterToken = postings.api.token("recruiter@example.test");
        UUID department = postings.api.department("IT", headId, null, START);
        UUID position = postings.api.position(START);
        requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        postings.assign(requisition, recruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
        workLocation = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, START);
        employmentType = postings.catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", true, START);
    }

    // The seed gives HR_MANAGER JOB_POSTINGS_WRITE_ALL and no JOB_POSTINGS_WRITE_SCOPED, and RECRUITER
    // JOB_POSTINGS_WRITE_SCOPED; some cases change that.
    @AfterEach
    void restorePermissions() {
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' "
                + "AND permission_code = 'JOB_POSTINGS_WRITE_SCOPED'");
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                + "VALUES ('HR_MANAGER', 'JOB_POSTINGS_WRITE_ALL') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                + "VALUES ('RECRUITER', 'JOB_POSTINGS_WRITE_SCOPED') ON CONFLICT DO NOTHING");
    }

    // The recruiter writes and sends every posting, and ADMIN approves the ones HR publishes, so HR is never an author
    // or the latest submitter: only the change under test can refuse HR. A step waits for the requisition row (a save,
    // a recruiter change or another step holds it), the posting row (another step), the caller's account (an admin
    // lock or a logout holds it) or, when sending for review, the posting's work location (HR changing it holds it).
    // permission-lost takes the caller's only JOB_POSTINGS write permission (HR's WRITE_ALL, the recruiter's
    // WRITE_SCOPED). downgraded-to-scoped leaves HR every composer permission but JOB_POSTINGS_WRITE_ALL (the write
    // permission becomes SCOPED, the read stays ALL), so only the reviewer check itself answers 403.
    @ParameterizedTest(name = "{0}: {1} held, then {2}")
    @CsvSource({
            "approve, posting, nothing, 200", "approve, posting, token-expired, 401",
            "approve, posting, permission-lost, 403", "approve, posting, returned-meanwhile, 409",
            "publish, posting, nothing, 200", "publish, posting, edited-meanwhile, 409",
            "publish, requisition, permission-lost, 403", "submit, requisition, unassigned, 403",
            "publish, actor, locked-actor, 401", "approve, actor, revoked-session, 401",
            "approve, posting, downgraded-to-scoped, 403", "publish, requisition, downgraded-to-scoped, 403",
            "submit, catalog, token-expired, 401", "submit, catalog, permission-lost, 403"
    })
    void theCallerIsCheckedAgainAfterEachLockWait(String action, String held, String change, int expected)
            throws Exception {
        UUID posting = UUID.fromString(postings.created(postings.body(requisition, workLocation, employmentType),
                recruiterToken).path("id").asText());
        Callable<HttpResponse<String>> call;
        UUID actor;
        if (action.equals("submit")) {
            call = () -> postings.submit(posting, 1, null, recruiterToken);
            actor = recruiterId;
        } else {
            postings.api.expect(postings.submit(posting, 1, null, recruiterToken), 200);
            if (action.equals("approve")) {
                call = () -> postings.decide(posting, "APPROVE", null, 2, hrToken);
            } else {
                postings.api.expect(postings.decide(posting, "APPROVE", null, 2, adminToken), 200);
                call = () -> postings.publish(posting, 3, hrToken);
            }
            actor = hrId;
        }
        List<Map<String, Object>> postingsBefore = rows("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = rows("SELECT * FROM job_posting_events ORDER BY revision");
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
            case "catalog" -> workLocation;
            default -> actor;
        };
        HttpResponse<String> result;
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, sql, heldId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(call);
                awaitWaiters(blocker, 1);
                switch (change) {
                    case "token-expired" -> clock.set(START.plus(Duration.ofMinutes(16)));
                    case "permission-lost" -> jdbc.update(action.equals("submit")
                            ? "DELETE FROM role_permissions WHERE role_code = 'RECRUITER' "
                                    + "AND permission_code = 'JOB_POSTINGS_WRITE_SCOPED'"
                            : "DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' "
                                    + "AND permission_code = 'JOB_POSTINGS_WRITE_ALL'");
                    case "downgraded-to-scoped" -> jdbc.update("UPDATE role_permissions "
                            + "SET permission_code = 'JOB_POSTINGS_WRITE_SCOPED' "
                            + "WHERE role_code = 'HR_MANAGER' AND permission_code = 'JOB_POSTINGS_WRITE_ALL'");
                    case "returned-meanwhile" -> execute(connection, "UPDATE job_postings "
                            + "SET status = 'REVISION_REQUESTED', revision = revision + 1 WHERE id = ?", posting);
                    case "edited-meanwhile" -> execute(connection,
                            "UPDATE job_postings SET revision = revision + 1 WHERE id = ?", posting);
                    case "unassigned" -> execute(connection, "DELETE FROM requisition_recruiters "
                            + "WHERE requisition_id = ? AND recruiter_id = ?", requisition, recruiterId);
                    case "locked-actor" -> execute(connection, "UPDATE user_accounts SET admin_locked_at = ?, "
                            + "admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                            Timestamp.from(START), adminId, hrId);
                    case "revoked-session" -> execute(connection,
                            "UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?", Timestamp.from(START), hrId);
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
            case 409 -> assertThat(body.path("code").asText()).isEqualTo(change.equals("returned-meanwhile")
                    ? "JOB_POSTING_NOT_UNDER_REVIEW" : "JOB_POSTING_CHANGED");
            default -> { }
        }
        if (expected == 200) {
            String status = action.equals("approve") ? "APPROVED" : "PUBLISHED";
            assertThat(body.path("status").asText()).isEqualTo(status);
            List<Map<String, Object>> events = postings.events(posting);
            assertThat(events).hasSize(eventsBefore.size() + 1);
            assertThat(events.getLast().get("status_after")).isEqualTo(status);
            assertThat(events.getLast().get("actor_id")).isEqualTo(hrId);
        } else {
            // Nothing written by the refused request; only the change made while it waited.
            List<Map<String, Object>> expectedPostings = new ArrayList<>();
            for (var row : postingsBefore) {
                Map<String, Object> copy = new LinkedHashMap<>(row);
                if (change.endsWith("-meanwhile")) {
                    copy.put("revision", (Integer) copy.get("revision") + 1);
                }
                if (change.equals("returned-meanwhile")) {
                    copy.put("status", "REVISION_REQUESTED");
                }
                expectedPostings.add(copy);
            }
            assertThat(rows("SELECT * FROM job_postings ORDER BY id")).isEqualTo(expectedPostings);
            assertThat(rows("SELECT * FROM job_posting_events ORDER BY revision")).isEqualTo(eventsBefore);
        }
        // Workflow steps make nobody an author and never write the requisition.
        assertThat(rows("SELECT * FROM job_posting_authors ORDER BY 1, 2")).isEqualTo(authorsBefore);
        assertThat(postings.requisitionRow(requisition)).isEqualTo(requisitionBefore);
    }

    // HR and ADMIN approve the same posting at the same moment: one approval is recorded, the other is told the posting
    // no longer waits for review.
    @Test
    void twoApprovalsAtOnceRecordOne() throws Exception {
        UUID posting = sent();

        var responses = concurrently(
                () -> postings.decide(posting, "APPROVE", null, 2, hrToken),
                () -> postings.decide(posting, "APPROVE", "Đạt", 2, adminToken));

        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        assertThat(codes(responses, 409)).containsExactly("JOB_POSTING_NOT_UNDER_REVIEW");
        assertThat(postings.events(posting)).extracting(row -> row.get("event_type"))
                .containsExactly("SUBMITTED", "APPROVED");
        assertThat(postings.row(posting).get("status")).isEqualTo("APPROVED");
        assertThat(postings.revision(posting)).isEqualTo(3);
    }

    // HR and ADMIN publish the same approved posting at the same moment: it is published once.
    @Test
    void twoPublicationsAtOnceRecordOne() throws Exception {
        UUID posting = sent();
        postings.api.expect(postings.decide(posting, "APPROVE", null, 2, adminToken), 200);

        var responses = concurrently(
                () -> postings.publish(posting, 3, hrToken),
                () -> postings.publish(posting, 3, adminToken));

        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        assertThat(codes(responses, 409)).containsExactly("JOB_POSTING_ALREADY_PUBLISHED");
        assertThat(postings.events(posting)).extracting(row -> row.get("event_type"))
                .containsExactly("SUBMITTED", "APPROVED", "PUBLISHED");
        assertThat(postings.row(posting).get("status")).isEqualTo("PUBLISHED");
        assertThat(postings.revision(posting)).isEqualTo(4);
    }

    // A complete posting the recruiter wrote and sent for review (revision 2).
    private UUID sent() throws Exception {
        UUID posting = UUID.fromString(postings.created(postings.body(requisition, workLocation, employmentType),
                recruiterToken).path("id").asText());
        postings.api.expect(postings.submit(posting, 1, null, recruiterToken), 200);
        return posting;
    }

    private List<String> codes(List<HttpResponse<String>> responses, int status) {
        return responses.stream().filter(response -> response.statusCode() == status)
                .map(response -> json.readTree(response.body()).path("code").asText()).toList();
    }

    // Holds the requisition row so both requests reach it, then lets them run one after the other.
    @SafeVarargs
    private List<HttpResponse<String>> concurrently(Callable<HttpResponse<String>>... calls) throws Exception {
        List<HttpResponse<String>> results = new ArrayList<>();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection,
                    "SELECT pg_backend_pid() FROM recruitment_requisitions WHERE id = ? FOR SHARE", requisition);
            try (var executor = Executors.newFixedThreadPool(calls.length)) {
                List<Future<HttpResponse<String>>> responses = new ArrayList<>();
                for (var call : calls) {
                    responses.add(executor.submit(call));
                }
                awaitWaiters(blocker, calls.length);
                connection.commit();
                for (var response : responses) {
                    results.add(response.get(20, TimeUnit.SECONDS));
                }
            }
        }
        return results;
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
