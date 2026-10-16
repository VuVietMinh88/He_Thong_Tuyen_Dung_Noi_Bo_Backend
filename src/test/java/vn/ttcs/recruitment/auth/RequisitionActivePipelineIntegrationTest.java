package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import vn.ttcs.recruitment.requisition.ActivePipeline;
import vn.ttcs.recruitment.requisition.RequisitionPipelineSource;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import javax.sql.DataSource;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.auth.ApprovalTestSupport.step;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.EMPLOYMENT_TYPE;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.WORK_LOCATION;

// Task 291 (story S3-07): "Kiểm tra ứng viên đang xử lý trước khi đóng yêu cầu". Task 325 supplies the real application
// source, tested by ApplicationStorageIntegrationTest. Here a test-only @Primary source answers PIPELINES so the warning,
// the confirmation and the place of the check in the status change can be exercised.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import({AuthIntegrationTest.DatabaseConfiguration.class,
        RequisitionActivePipelineIntegrationTest.PipelineConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionActivePipelineIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");
    private static final String CODE = "REQUISITION_HAS_ACTIVE_CANDIDATES";
    private static final String FILL_MESSAGE = "Yêu cầu tuyển dụng còn 3 ứng viên đang trong quy trình tuyển dụng. Vui "
            + "lòng xử lý các ứng viên này trước, hoặc xác nhận vẫn đóng yêu cầu vì đã tuyển đủ.";
    private static final String CANCEL_MESSAGE = "Yêu cầu tuyển dụng còn 3 ứng viên đang trong quy trình tuyển dụng. Vui "
            + "lòng xử lý các ứng viên này trước, hoặc xác nhận vẫn hủy yêu cầu.";
    private static final String HOLD_REQUISITION =
            "SELECT pg_backend_pid() FROM recruitment_requisitions WHERE id = ? FOR SHARE";
    private static final ActivePipeline THREE = new ActivePipeline(3, List.of(
            new ActivePipeline.Stage("SCREENING", "Sàng lọc", 1), new ActivePipeline.Stage("INTERVIEW", "Phỏng vấn", 2)));

    // What the fake source answers per requisition, and what it does when asked; reset after each test.
    private static final Map<UUID, ActivePipeline> PIPELINES = new ConcurrentHashMap<>();
    private static final Consumer<UUID> NOTHING = id -> { };
    private static final AtomicReference<Consumer<UUID>> PROBE = new AtomicReference<>(NOTHING);
    private static final AtomicInteger CALLS = new AtomicInteger();

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;

    private RequisitionStatusTestSupport status;
    private JobPostingTestSupport postings;
    private ApprovalTestSupport api;
    private List<Map<String, Object>> permissionSeed;
    private UUID adminId;
    private String adminToken;
    private String hrToken;
    private String headToken;
    private UUID it;
    private UUID position;
    private UUID workLocation;
    private UUID employmentType;

    @TestConfiguration(proxyBeanMethods = false)
    static class PipelineConfiguration {

        // Override the production application source for deterministic status-change probes.
        @Bean
        @Primary
        RequisitionPipelineSource fakePipelineSource() {
            return requisitionId -> {
                CALLS.incrementAndGet();
                PROBE.get().accept(requisitionId);
                return PIPELINES.getOrDefault(requisitionId, ActivePipeline.NONE);
            };
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void resetFixture() throws Exception {
        permissionSeed = jdbc.queryForList("SELECT role_code, permission_code FROM role_permissions");
        clock.set(START);
        status = new RequisitionStatusTestSupport(jdbc, json, transactions,
                Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        postings = status.postings;
        api = status.api;
        status.reset();
        bootstrap.run(new DefaultApplicationArguments());
        adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        String passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        adminToken = api.token("admin@example.test");
        UUID hrId = accounts.saveAndFlush(new Account("hr@example.test", "Nguyễn Thị Hoa", passwordHash,
                Set.of(Role.HR_MANAGER), START)).getId();
        hrToken = api.token("hr@example.test");
        UUID headId = accounts.saveAndFlush(new Account("head@example.test", "Trưởng phòng IT", passwordHash,
                Set.of(Role.HIRING_MANAGER), START)).getId();
        headToken = api.token("head@example.test");
        // IT's only level is HR_MANAGER.
        it = api.department("IT", headId, null, START);
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"));
        position = api.position(START);
        workLocation = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, START);
        employmentType = postings.catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", true, START);
        CALLS.set(0);
    }

    @AfterEach
    void resetSourceAndPermissions() {
        PIPELINES.clear();
        PROBE.set(NOTHING);
        CALLS.set(0);
        if (permissionSeed == null) { return; }
        jdbc.update("DELETE FROM role_permissions");
        jdbc.batchUpdate("INSERT INTO role_permissions (role_code, permission_code) VALUES (?, ?)", permissionSeed
                .stream().map(row -> new Object[] {row.get("role_code"), row.get("permission_code")}).toList());
    }

    // S3-07 AC3: closing as filled or cancelling while candidates are still in the pipeline is refused with the
    // pipeline until the caller confirms; the refusal writes nothing, so the confirmation is the same body with
    // confirmActiveCandidates: true and the same expectedRevision. Every requisition is reached through the API.
    @ParameterizedTest(name = "{0} to {1}")
    @CsvSource({"APPROVED,FILLED", "PAUSED,FILLED", "APPROVED,CANCELLED", "PAUSED,CANCELLED", "DRAFT,CANCELLED",
            "PENDING_APPROVAL,CANCELLED", "RETURNED,CANCELLED"})
    void closingWithCandidatesLeftNeedsConfirmation(RequisitionStatus from, RequisitionStatus target) throws Exception {
        UUID id = reach(from);
        boolean posted = from == RequisitionStatus.APPROVED || from == RequisitionStatus.PAUSED;
        UUID posting = posted ? postingOf(id) : null;
        int revision = api.lastRevision(id);
        Map<String, Object> rowBefore = status.row(id);
        List<Map<String, Object>> eventsBefore = status.events(id);
        Map<String, Object> postingBefore = posted ? postings.row(posting) : null;
        PIPELINES.put(id, THREE);
        String reason = "Lý do " + target;
        Map<String, Object> explicitNull = new HashMap<>(Map.of("status", target.name(), "reason", reason,
                "expectedRevision", revision));
        explicitNull.put("confirmActiveCandidates", null);

        List<HttpResponse<String>> refused = List.of(
                status.change(id, target.name(), reason, revision, null, hrToken),
                status.change(id, target.name(), reason, revision, false, hrToken),
                status.change(id, explicitNull, hrToken));

        for (var response : refused) {
            assertRefused(response, target == RequisitionStatus.FILLED ? FILL_MESSAGE : CANCEL_MESSAGE);
        }
        assertThat(status.row(id)).isEqualTo(rowBefore);
        assertThat(status.events(id)).isEqualTo(eventsBefore);
        if (posted) {
            assertThat(postings.row(posting)).isEqualTo(postingBefore);
        }
        assertThat(CALLS.get()).isEqualTo(3);

        JsonNode closed = status.ok(status.change(id, target.name(), reason, revision, true, hrToken));

        assertThat(closed.path("status").asText()).isEqualTo(target.name());
        List<Map<String, Object>> events = status.events(id);
        assertThat(events).hasSize(eventsBefore.size() + 1);
        assertThat(events.getLast().get("event_type")).isEqualTo(target.name());
        assertThat(events.getLast().get("revision")).isEqualTo(revision + 1);
        assertThat(events.getLast().get("comment")).isEqualTo(reason);
        if (posted) {
            // Task 292 still closes the posting of a confirmed closure.
            assertThat(postings.row(posting).get("status")).isEqualTo("CLOSED");
        }
        // A confirmed closure does not ask the source again.
        assertThat(CALLS.get()).isEqualTo(3);
    }

    // Pausing keeps the requisition, its headcount and its team and can be undone, so it never asks; resuming
    // reopens. The source is not even asked.
    @Test
    void pausingAndResumingNeverAsk() throws Exception {
        UUID id = status.approved(position, it, headToken, hrToken);
        PIPELINES.put(id, THREE);

        assertThat(status.ok(status.change(id, "PAUSED", "Chờ ngân sách", 2, hrToken)).path("status").asText())
                .isEqualTo("PAUSED");
        assertThat(status.ok(status.change(id, "APPROVED", null, 3, hrToken)).path("status").asText())
                .isEqualTo("APPROVED");
        // A confirmation sent anyway is ignored.
        assertThat(status.ok(status.change(id, "PAUSED", "Chờ ngân sách", 4, true, hrToken)).path("status").asText())
                .isEqualTo("PAUSED");

        assertThat(CALLS.get()).isZero();
        assertThat(status.events(id)).hasSize(5);
    }

    @Test
    void anEmptyPipelineNeedsNoConfirmation() throws Exception {
        UUID filled = status.approved(position, it, headToken, hrToken);
        UUID cancelled = status.approved(position, it, headToken, hrToken);

        assertThat(status.ok(status.change(filled, "FILLED", "Đã tuyển đủ", 2, hrToken)).path("status").asText())
                .isEqualTo("FILLED");
        assertThat(CALLS.get()).isOne();
        // A confirmation that turns out not to be needed is accepted as well.
        assertThat(status.ok(status.change(cancelled, "CANCELLED", "Không còn nhu cầu", 2, true, hrToken))
                .path("status").asText()).isEqualTo("CANCELLED");
        assertThat(CALLS.get()).isOne();
    }

    // Step 11 comes after every other check, so nobody is asked to confirm a closure that would fail anyway, and the
    // source is never asked for a requisition the caller may not close or may not read.
    @Test
    void everyOtherCheckComesFirst() throws Exception {
        UUID approved = status.approved(position, it, headToken, hrToken);
        UUID filled = postings.requisition(position, it, adminId, RequisitionStatus.FILLED, START);
        UUID unknown = UUID.randomUUID();
        for (UUID id : List.of(approved, filled, unknown)) {
            PIPELINES.put(id, THREE);
        }
        Map<String, Object> rowBefore = status.row(approved);

        postings.businessError(status.change(approved, "FILLED", null, 2, hrToken),
                "REQUISITION_STATUS_REASON_REQUIRED", "reason",
                "Vui lòng nhập lý do khi tạm dừng, đóng vì đã tuyển đủ hoặc hủy yêu cầu tuyển dụng.");
        postings.error(status.change(filled, "CANCELLED", "Hủy", 0, hrToken), 409,
                "REQUISITION_STATUS_CHANGE_NOT_ALLOWED");
        for (int stale : List.of(1, 3)) {
            postings.error(status.change(approved, "FILLED", "Đã tuyển đủ", stale, hrToken), 409,
                    "REQUISITION_STATE_CHANGED");
        }
        postings.error(status.change(unknown, "FILLED", "Đã tuyển đủ", 0, hrToken), 404, "REQUISITION_NOT_FOUND");
        postings.error(status.activePipeline(unknown, hrToken), 404, "REQUISITION_NOT_FOUND");
        // The head of IT is stopped by the matcher.
        postings.forbidden(status.change(approved, "CANCELLED", "Hủy", 2, headToken));
        postings.forbidden(status.activePipeline(approved, headToken));

        // HR keeps REQUISITIONS_WRITE_ALL but reads only the departments it manages (none): the service's department
        // read scope (step 8) answers before the pipeline.
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' "
                + "AND permission_code = 'REQUISITIONS_READ_ALL'");
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                + "VALUES ('HR_MANAGER', 'REQUISITIONS_READ_SCOPED')");
        postings.forbidden(status.change(approved, "FILLED", "Đã tuyển đủ", 2, hrToken));
        postings.forbidden(status.activePipeline(approved, hrToken));
        // Without any REQUISITIONS read permission: 403 before the lookup, for a known and an unknown requisition.
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' "
                + "AND permission_code = 'REQUISITIONS_READ_SCOPED'");
        for (UUID id : List.of(approved, unknown)) {
            postings.forbidden(status.change(id, "FILLED", "Đã tuyển đủ", 2, hrToken));
            postings.forbidden(status.activePipeline(id, hrToken));
        }

        assertThat(CALLS.get()).isZero();
        assertThat(status.row(approved)).isEqualTo(rowBefore);
        assertThat(status.events(approved)).hasSize(2);
    }

    // The source runs inside the change's transaction while the requisition row is locked, after every check and
    // before the history row and the new status are written: a second connection cannot lock the row, and the
    // change's own connection still sees the old status and the old history.
    @Test
    void theSourceIsAskedUnderTheLockBeforeAnythingIsWritten() throws Exception {
        List<Object> seen = new CopyOnWriteArrayList<>();
        PROBE.set(requisitionId -> {
            seen.add(lockFromAnotherConnection(requisitionId));
            seen.add(jdbc.queryForObject("SELECT status FROM recruitment_requisitions WHERE id = ?", String.class,
                    requisitionId));
            seen.add(jdbc.queryForObject("SELECT count(*) FROM requisition_workflow_events WHERE requisition_id = ?",
                    Integer.class, requisitionId));
        });

        UUID refused = status.approved(position, it, headToken, hrToken);
        PIPELINES.put(refused, THREE);
        postings.error(status.change(refused, "FILLED", "Đã tuyển đủ", 2, hrToken), 409, CODE);
        assertThat(seen).containsExactly("55P03", "APPROVED", 2);

        // Nobody in the pipeline: the source is asked, then the change writes.
        seen.clear();
        UUID cancelled = status.approved(position, it, headToken, hrToken);
        status.ok(status.change(cancelled, "CANCELLED", "Không còn nhu cầu", 2, hrToken));
        assertThat(seen).containsExactly("55P03", "APPROVED", 2);
        assertThat(status.events(cancelled)).hasSize(3);
        assertThat(CALLS.get()).isEqualTo(2);
    }

    // With an in-memory source this proves when the source is asked, not what a database source would see: the
    // change waits for the requisition lock without asking, and the answer given once the lock is granted decides.
    // That a real source then counts applications committed during the wait follows from READ COMMITTED and the
    // writer contract of RequisitionPipelineSource; the candidate module tests it against its own tables.
    @Test
    void theSourceIsAskedOnlyAfterTheLockIsGranted() throws Exception {
        UUID arriving = status.approved(position, it, headToken, hrToken);
        postings.error(whileHeld(arriving, () -> PIPELINES.put(arriving, THREE)), 409, CODE);
        assertThat(status.events(arriving)).hasSize(2);

        UUID leaving = status.approved(position, it, headToken, hrToken);
        PIPELINES.put(leaving, THREE);
        status.ok(whileHeld(leaving, () -> PIPELINES.remove(leaving)));
        assertThat(status.row(leaving).get("status")).isEqualTo("FILLED");
        assertThat(CALLS.get()).isEqualTo(2);
    }

    @Test
    void theGetShowsTheSourcesPipeline() throws Exception {
        UUID id = status.approved(position, it, headToken, hrToken);
        PIPELINES.put(id, THREE);
        JsonNode expected = expectedPipeline();

        for (String token : List.of(hrToken, adminToken)) {
            assertThat(status.ok(status.activePipeline(id, token))).isEqualTo(expected);
        }
        status.ok(status.change(id, "FILLED", "Đã tuyển đủ", 2, true, hrToken));
        // Any status: what is left after a confirmed closure.
        assertThat(status.ok(status.activePipeline(id, hrToken))).isEqualTo(expected);
        assertThat(CALLS.get()).isEqualTo(3);
    }

    // A 409 with the three keys of every error and the pipeline, nothing else.
    private void assertRefused(HttpResponse<String> response, String message) {
        JsonNode body = postings.error(response, 409, CODE);
        assertThat(new ArrayList<>(body.propertyNames()))
                .containsExactlyInAnyOrder("code", "message", "fieldErrors", "activePipeline");
        assertThat(body.path("message").asText()).isEqualTo(message);
        assertThat(body.path("fieldErrors").isObject()).isTrue();
        assertThat(body.path("fieldErrors").size()).isZero();
        assertThat(body.path("activePipeline")).isEqualTo(expectedPipeline());
    }

    // THREE as the JSON every client reads: count, then the stages in pipeline order with stage, name and count.
    private JsonNode expectedPipeline() {
        return json.readTree("""
                {"count": 3, "stages": [{"stage": "SCREENING", "name": "Sàng lọc", "count": 1},
                                        {"stage": "INTERVIEW", "name": "Phỏng vấn", "count": 2}]}
                """);
    }

    // A requisition of IT in this status, reached through the API (created and submitted by the head of IT, decided
    // by HR). An approved one gets a published posting first, so a paused one has one too.
    private UUID reach(RequisitionStatus from) throws Exception {
        return switch (from) {
            case DRAFT -> api.draft(position, it, 22_000_000L, headToken);
            case PENDING_APPROVAL -> api.submitted(position, it, 22_000_000L, headToken, headToken);
            case RETURNED -> {
                UUID id = api.submitted(position, it, 22_000_000L, headToken, headToken);
                api.expect(api.decide(id, "REQUEST_INFO", "Bổ sung mô tả công việc", 1, hrToken), 200);
                yield id;
            }
            case APPROVED -> {
                UUID id = status.approved(position, it, headToken, hrToken);
                status.publishedPosting(id, workLocation, employmentType, hrToken, adminToken);
                yield id;
            }
            case PAUSED -> {
                UUID id = reach(RequisitionStatus.APPROVED);
                status.ok(status.change(id, "PAUSED", "Chờ ngân sách", 2, hrToken));
                yield id;
            }
            case REJECTED, FILLED, CANCELLED -> throw new IllegalArgumentException(from + " cannot be closed");
        };
    }

    private UUID postingOf(UUID requisition) {
        return jdbc.queryForObject("SELECT id FROM job_postings WHERE requisition_id = ?", UUID.class, requisition);
    }

    // The SQLState of locking the requisition row from a connection of its own without waiting, or "granted".
    private String lockFromAnotherConnection(UUID requisition) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT id FROM recruitment_requisitions WHERE id = ? FOR NO KEY UPDATE NOWAIT")) {
            statement.setObject(1, requisition);
            statement.executeQuery().close();
            return "granted";
        } catch (SQLException exception) {
            return exception.getSQLState();
        }
    }

    // Holds the requisition row, sends FILL without confirmation and waits until it waits for the row, checks that
    // the source was not asked yet, runs meanwhile, then releases the row and returns the answer.
    private HttpResponse<String> whileHeld(UUID requisition, Runnable meanwhile) throws Exception {
        int asked = CALLS.get();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, requisition);
            try (var executor = Executors.newSingleThreadExecutor()) {
                Future<HttpResponse<String>> response = executor.submit(() ->
                        status.change(requisition, "FILLED", "Đã tuyển đủ", 2, hrToken));
                try {
                    awaitWaiters(blocker);
                    assertThat(CALLS.get()).as("asked while waiting for the requisition lock").isEqualTo(asked);
                    meanwhile.run();
                } finally {
                    connection.commit();
                }
                return response.get(20, TimeUnit.SECONDS);
            }
        }
    }

    private int lock(Connection connection, UUID id) throws Exception {
        try (var statement = connection.prepareStatement(HOLD_REQUISITION)) {
            statement.setObject(1, id);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); return row.getInt(1); }
        }
    }

    private void awaitWaiters(int blockerPid) throws Exception {
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
            if (observed >= 1) { return; }
            Thread.sleep(20);
        }
        assertThat(observed).as("the HTTP request must reach the requisition lock before release").isOne();
    }
}
