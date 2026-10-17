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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
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

// Task 292: a posting write and the closure of its requisition at the same moment. Both lock the requisition row
// first and the posting after it, and waiters on a row lock are served in arrival order, so each race is staged by
// holding the requisition row, sending the first request, then the second, and releasing the row. Whatever order they
// run in, the posting ends CLOSED exactly once and never stays on the portal.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobPostingClosureLockIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");
    private static final String HOLD_REQUISITION =
            "SELECT pg_backend_pid() FROM recruitment_requisitions WHERE id = ? FOR SHARE";

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
    private String passwordHash;
    private UUID adminId;
    private String adminToken;
    private UUID hrId;
    private String hrToken;
    private UUID recruiterId;
    private String recruiterToken;
    private UUID department;
    private UUID position;
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
        status = new RequisitionStatusTestSupport(jdbc, json, transactions,
                Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        postings = status.postings;
        api = status.api;
        status.reset();
        bootstrap.run(new DefaultApplicationArguments());
        adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        adminToken = api.token("admin@example.test");
        hrId = account("hr@example.test", Role.HR_MANAGER);
        hrToken = api.token("hr@example.test");
        UUID headId = account("head@example.test", Role.HIRING_MANAGER);
        recruiterId = account("recruiter@example.test", Role.RECRUITER);
        recruiterToken = api.token("recruiter@example.test");
        department = api.department("IT", headId, null, START);
        position = api.position(START);
        workLocation = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, START);
        employmentType = postings.catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", true, START);
    }

    // first, second: the step of the posting (the recruiter saves, creates or sends it; HR approves or publishes it)
    // and ADMIN's closure of the requisition (fill, or cancel for create), in the order they reach the requisition
    // lock. secondOutcome: OK when the second request succeeds too, otherwise its 409 code. closedFrom: the posting's
    // status when it was closed, empty when no posting exists.
    @ParameterizedTest(name = "{0} then {1}")
    @CsvSource({
            "publish, fill, OK, PUBLISHED", "fill, publish, JOB_POSTING_CLOSED, APPROVED",
            "approve, fill, OK, APPROVED", "fill, approve, JOB_POSTING_CLOSED, PENDING_APPROVAL",
            "submit, fill, OK, PENDING_APPROVAL", "fill, submit, JOB_POSTING_CLOSED, DRAFT",
            "save, fill, OK, DRAFT", "fill, save, JOB_POSTING_CLOSED, DRAFT",
            "create, cancel, OK, DRAFT", "cancel, create, JOB_POSTING_REQUISITION_NOT_APPROVED, ''"
    })
    void aPostingStepAndTheClosureOfItsRequisition(String first, String second, String secondOutcome,
                                                   String closedFrom) throws Exception {
        boolean closureFirst = first.equals("fill") || first.equals("cancel");
        String step = closureFirst ? second : first;
        UUID requisition = requisition();
        UUID posting = postingBefore(requisition, step);
        Callable<HttpResponse<String>> closure = () -> status.change(requisition, step.equals("create") ? "CANCELLED"
                : "FILLED", "Đóng yêu cầu", 0, adminToken);
        Callable<HttpResponse<String>> write = () -> write(step, requisition, posting);

        var responses = staged(requisition, closureFirst ? closure : write, closureFirst ? write : closure);

        var firstResponse = responses.getFirst();
        assertThat(firstResponse.statusCode()).as(firstResponse.body()).isEqualTo(step.equals("create")
                && !closureFirst ? 201 : 200);
        var secondResponse = responses.get(1);
        if (secondOutcome.equals("OK")) {
            assertThat(secondResponse.statusCode()).as(secondResponse.body()).isEqualTo(200);
        } else {
            assertThat(secondResponse.statusCode()).as(secondResponse.body()).isEqualTo(409);
            assertThat(json.readTree(secondResponse.body()).path("code").asText()).isEqualTo(secondOutcome);
        }
        assertThat(status.row(requisition).get("status")).isEqualTo(step.equals("create") ? "CANCELLED" : "FILLED");
        UUID closed = posting != null ? posting : jdbc.queryForList(
                "SELECT id FROM job_postings WHERE requisition_id = ?", UUID.class, requisition).stream().findFirst()
                .orElse(null);
        if (closedFrom.isEmpty()) {
            // The cancelled requisition got no posting.
            assertThat(closed).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM job_posting_events", Integer.class)).isZero();
            assertThat(ok(postings.publicList(0, 20)).path("totalElements").asLong()).isZero();
            return;
        }
        List<Map<String, Object>> events = postings.events(closed);
        assertThat(events.stream().filter(event -> event.get("event_type").equals("CLOSED")).toList()).hasSize(1);
        assertThat(events.getLast()).containsEntry("event_type", "CLOSED").containsEntry("status_before", closedFrom)
                .containsEntry("actor_id", adminId);
        Map<String, Object> row = postings.row(closed);
        assertThat(row.get("status")).isEqualTo("CLOSED");
        postings.error(postings.publicDetail(closed), 404, "JOB_POSTING_NOT_FOUND");
        boolean stepRan = !closureFirst;
        switch (step) {
            case "publish" -> {
                // Published first: closed with its trail. Closed first: never published.
                assertThat(events).extracting(event -> event.get("event_type")).containsExactlyElementsOf(stepRan
                        ? List.of("SUBMITTED", "APPROVED", "PUBLISHED", "CLOSED")
                        : List.of("SUBMITTED", "APPROVED", "CLOSED"));
                assertThat(row.get("published_by")).isEqualTo(stepRan ? hrId : null);
                assertThat(row.get("published_at") != null).isEqualTo(stepRan);
            }
            case "approve" -> assertThat(events).extracting(event -> event.get("event_type"))
                    .containsExactlyElementsOf(stepRan ? List.of("SUBMITTED", "APPROVED", "CLOSED")
                            : List.of("SUBMITTED", "CLOSED"));
            case "submit" -> assertThat(events).extracting(event -> event.get("event_type"))
                    .containsExactlyElementsOf(stepRan ? List.of("SUBMITTED", "CLOSED") : List.of("CLOSED"));
            case "save" -> {
                assertThat(row.get("benefits")).isEqualTo(stepRan ? "Bảo hiểm đầy đủ." : null);
                assertThat(row.get("revision")).isEqualTo(stepRan ? 3 : 2);
            }
            case "create" -> assertThat(row.get("revision")).isEqualTo(2);
            default -> throw new IllegalArgumentException(step);
        }
    }

    // The posting before the race: none for create, a complete draft for save and submit, sent for approve, approved
    // for publish. The recruiter writes and sends it, HR approves it.
    private UUID postingBefore(UUID requisition, String step) throws Exception {
        if (step.equals("create")) {
            return null;
        }
        UUID id = UUID.fromString(postings.created(postings.body(requisition, workLocation, employmentType),
                recruiterToken).path("id").asText());
        if (step.equals("approve") || step.equals("publish")) {
            ok(postings.submit(id, 1, null, recruiterToken));
        }
        if (step.equals("publish")) {
            ok(postings.decide(id, "APPROVE", null, 2, hrToken));
        }
        return id;
    }

    private HttpResponse<String> write(String step, UUID requisition, UUID posting) throws Exception {
        return switch (step) {
            case "publish" -> postings.publish(posting, 3, hrToken);
            case "approve" -> postings.decide(posting, "APPROVE", null, 2, hrToken);
            case "submit" -> postings.submit(posting, 1, null, recruiterToken);
            case "save" -> {
                Map<String, Object> change = postings.putBody(ok(postings.get(posting, recruiterToken)));
                change.put("benefits", "Bảo hiểm đầy đủ.");
                yield postings.update(posting, change, recruiterToken);
            }
            case "create" -> postings.create(postings.body(requisition, workLocation, employmentType),
                    recruiterToken);
            default -> throw new IllegalArgumentException(step);
        };
    }

    // An approved requisition written with SQL, without history (revision 0), whose primary recruiter is the recruiter.
    private UUID requisition() {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        postings.assign(requisition, recruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
        return requisition;
    }

    // Holds the requisition row, sends first and waits until it waits for the row, then sends second and waits for
    // it too, and releases the row: first runs, then second.
    private List<HttpResponse<String>> staged(UUID requisition, Callable<HttpResponse<String>> first,
                                              Callable<HttpResponse<String>> second) throws Exception {
        List<HttpResponse<String>> results = new ArrayList<>();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blocker = lock(connection, HOLD_REQUISITION, requisition);
            try (var executor = Executors.newFixedThreadPool(2)) {
                Future<HttpResponse<String>> firstResponse = executor.submit(first);
                awaitWaiters(blocker, 1);
                Future<HttpResponse<String>> secondResponse = executor.submit(second);
                awaitWaiters(blocker, 2);
                connection.commit();
                results.add(firstResponse.get(20, TimeUnit.SECONDS));
                results.add(secondResponse.get(20, TimeUnit.SECONDS));
            }
        }
        return results;
    }

    private JsonNode ok(HttpResponse<String> response) {
        return api.expect(response, 200);
    }

    private UUID account(String email, Role... roles) {
        return accounts.saveAndFlush(new Account(email, "Lock test", passwordHash, Set.of(roles), START)).getId();
    }

    private int lock(Connection connection, String sql, UUID id) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); return row.getInt(1); }
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
