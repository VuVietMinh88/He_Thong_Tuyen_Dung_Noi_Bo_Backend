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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// The writes of InterviewQuestionService (POST /interview-questions and PUT /interview-questions/{id}) check the
// caller after the account and session locks and, since this fix, again after every row lock they wait for: the
// question row (update only), the framework row of the criterion (locked FOR SHARE, so it waits while HR edits that
// framework) and the criterion row (locked FOR UPDATE, so it waits for another question write on the same criterion).
// Meanwhile the access token may expire or a permission may be removed. Such a write must be refused and must change
// nothing (the same rule as DepartmentService, task 198).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InterviewQuestionWriteLockIntegrationTest {
    private static final String QUESTIONS = "/api/v1/interview-questions";
    private static final String FRAMEWORKS = "/api/v1/competency-frameworks";
    private static final String PASSWORD = "TestingOnly123!";
    // Access tokens last 15 minutes from START; only the token expiry case moves the clock.
    private static final Instant START = Instant.parse("2026-10-10T00:00:00Z");
    private static final String CONTENT_BEFORE = "Câu hỏi gốc";
    private static final String CONTENT_AFTER = "Câu hỏi mới";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    // HR_MANAGER writes the questions, like in the story ("Trưởng phòng Nhân sự").
    private String hrToken;
    private UUID frameworkId;
    private UUID criterionId;

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
        grantWrite();
        // V9: a criterion that still has questions cannot be deleted, so the questions go first.
        jdbc.update("DELETE FROM interview_questions");
        // ON DELETE CASCADE removes the criteria too.
        jdbc.update("DELETE FROM competency_frameworks");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        UUID adminId = UUID.fromString(login("admin@example.test").path("user").path("id").asText());
        String hash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        accounts.saveAndFlush(new Account("hr@example.test", "HR", hash, Set.of(Role.HR_MANAGER), START));
        hrToken = login("hr@example.test").path("accessToken").asText();
        JsonNode framework = expect(request("POST", FRAMEWORKS, json.writeValueAsString(Map.of("code", "DEV_CORE",
                "name", "Khung DEV_CORE", "status", "ACTIVE", "criteria", List.of(
                        Map.of("name", "Giao tiếp", "weight", 50), Map.of("name", "Tư duy", "weight", 50)))),
                hrToken), 201);
        frameworkId = UUID.fromString(framework.path("id").asText());
        criterionId = UUID.fromString(framework.path("criteria").get(0).path("id").asText());
    }

    // HR's write passed the checks at the start and then waits for a row that another transaction holds: the question
    // row (update), the framework row that HR is editing, or the criterion row that another question write holds. With
    // nothing changed it goes on when that transaction ends. When the token expired or the permission was removed
    // during the wait, it is refused (401 or 403) and no question changes.
    @ParameterizedTest(name = "{0} waiting for the {1} row, then {2}")
    @CsvSource({
            "create, framework, nothing", "create, framework, token-expiry", "create, framework, permission-lost",
            "create, criterion, nothing", "create, criterion, token-expiry", "create, criterion, permission-lost",
            "update, question, nothing", "update, question, token-expiry", "update, question, permission-lost",
            "update, framework, nothing", "update, framework, token-expiry", "update, framework, permission-lost",
            "update, criterion, nothing", "update, criterion, token-expiry", "update, criterion, permission-lost"
    })
    void aWriteWaitingForARowChecksTheCallerAgain(String operation, String lock, String change) throws Exception {
        boolean create = operation.equals("create");
        UUID question = create ? null
                : UUID.fromString(expect(request("POST", QUESTIONS, questionBody(CONTENT_BEFORE), hrToken), 201)
                        .path("id").asText());
        List<Map<String, Object>> before = questionRows();
        String table = switch (lock) {
            case "question" -> "interview_questions";
            case "framework" -> "competency_frameworks";
            default -> "competency_criteria";
        };
        UUID row = switch (lock) {
            case "question" -> question;
            case "framework" -> frameworkId;
            default -> criterionId;
        };
        // The question and the criterion are read FOR UPDATE, so a FOR SHARE blocks them; the framework is read FOR
        // SHARE, so only HR's FOR UPDATE (the edit of that framework) blocks it.
        String mode = lock.equals("framework") ? "FOR UPDATE" : "FOR SHARE";
        String lockSql = "SELECT pg_backend_pid() FROM " + table + " WHERE id = ? " + mode;
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid;
            try (var statement = connection.prepareStatement(lockSql)) {
                statement.setObject(1, row);
                try (var result = statement.executeQuery()) { assertThat(result.next()).isTrue(); blockerPid = result.getInt(1); }
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> create
                        ? request("POST", QUESTIONS, questionBody(CONTENT_AFTER), hrToken)
                        : request("PUT", QUESTIONS + "/" + question, questionBody(CONTENT_AFTER), hrToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    switch (change) {
                        case "token-expiry" -> clock.set(START.plus(Duration.ofMinutes(15)));
                        // A migration that removes the grant from the role locks no account.
                        case "permission-lost" -> jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' AND permission_code = 'ORGANIZATION_WRITE_ALL'");
                        default -> { }
                    }
                    connection.commit();
                    var result = response.get(10, TimeUnit.SECONDS);
                    switch (change) {
                        case "nothing" -> assertThat(expect(result, create ? 201 : 200).path("content").asText())
                                .isEqualTo(CONTENT_AFTER);
                        case "token-expiry" -> assertThat(expect(result, 401).path("code").asText()).isEqualTo("SESSION_INVALID");
                        default -> assertThat(expect(result, 403).path("code").asText()).isEqualTo("FORBIDDEN");
                    }
                } finally {
                    connection.rollback();
                    grantWrite();
                }
            }
        }
        if (change.equals("nothing")) {
            assertThat(questionRows()).isNotEqualTo(before);
        } else {
            assertThat(questionRows()).isEqualTo(before);
        }
    }

    private List<Map<String, Object>> questionRows() {
        return jdbc.queryForList("SELECT * FROM interview_questions ORDER BY id");
    }

    private String questionBody(String content) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("criterionId", criterionId);
        body.put("content", content);
        body.put("difficulty", "MEDIUM");
        body.put("answerHint", null);
        return json.writeValueAsString(body);
    }

    private void grantWrite() {
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'ORGANIZATION_WRITE_ALL') ON CONFLICT DO NOTHING");
    }

    private JsonNode login(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200);
    }

    private HttpResponse<String> request(String method, String path, String payload, String token) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path);
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode expect(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return json.readTree(response.body());
    }

    // Counts the requests waiting, directly or through another waiting request, for the other connection's locks.
    private void awaitWaiters(int blockerPid, int expected) throws SQLException, InterruptedException {
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
        assertThat(observed).as("the HTTP request must reach the PostgreSQL lock before it is released").isGreaterThanOrEqualTo(expected);
    }
}
