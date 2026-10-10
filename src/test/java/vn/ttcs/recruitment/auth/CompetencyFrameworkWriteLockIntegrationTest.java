package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// PUT /competency-frameworks/{id} checks the caller before it locks the framework row and, since this fix, again
// after the lock: another edit of the same framework can hold the row for a while, and meanwhile the access token
// may expire or ORGANIZATION_WRITE_ALL may be removed from the caller's role. Such a write must be refused and must
// change nothing (the same rule as DepartmentService, task 198).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CompetencyFrameworkWriteLockIntegrationTest {
    private static final String BASE = "/api/v1/competency-frameworks";
    private static final String PASSWORD = "TestingOnly123!";
    // Access tokens last 15 minutes from START; only the token expiry case moves the clock.
    private static final Instant START = Instant.parse("2026-10-10T00:00:00Z");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String adminToken;
    private String hrToken;

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
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'ORGANIZATION_WRITE_ALL') ON CONFLICT DO NOTHING");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM competency_frameworks");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        JsonNode adminLogin = login("admin@example.test");
        adminToken = adminLogin.path("accessToken").asText();
        String hash = accounts.findById(UUID.fromString(adminLogin.path("user").path("id").asText())).orElseThrow()
                .getPasswordHash();
        accounts.saveAndFlush(new Account("hr@example.test", "HR", hash, Set.of(Role.HR_MANAGER), START));
        hrToken = login("hr@example.test").path("accessToken").asText();
    }

    // HR's PUT passed the checks at the start and then waits for the framework row, which another transaction holds.
    // With nothing changed it goes on when that transaction ends. When the token expired or the permission was
    // removed during the wait, it is refused (401 or 403) and the framework and its criteria stay as they were.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"nothing", "token-expiry", "permission-lost"})
    void aPutWaitingForTheFrameworkRowChecksTheCallerAgain(String change) throws Exception {
        UUID framework = UUID.fromString(expect(request("POST", BASE, body("FW", "Original"), adminToken), 201)
                .path("id").asText());
        List<Map<String, Object>> before = rows();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid;
            try (var statement = connection.prepareStatement(
                    "SELECT pg_backend_pid() FROM competency_frameworks WHERE id = ? FOR SHARE")) {
                statement.setObject(1, framework);
                try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); blockerPid = row.getInt(1); }
            }
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> request("PUT", BASE + "/" + framework, body("FW", "Renamed"), hrToken));
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
                        case "nothing" -> assertThat(expect(result, 200).path("name").asText()).isEqualTo("Renamed");
                        case "token-expiry" -> assertThat(expect(result, 401).path("code").asText()).isEqualTo("SESSION_INVALID");
                        default -> assertThat(expect(result, 403).path("code").asText()).isEqualTo("FORBIDDEN");
                    }
                } finally {
                    connection.rollback();
                }
            }
        }
        if (change.equals("nothing")) {
            assertThat(jdbc.queryForObject("SELECT name FROM competency_frameworks WHERE id = ?", String.class, framework))
                    .isEqualTo("Renamed");
        } else {
            assertThat(rows()).isEqualTo(before);
        }
    }

    private List<Map<String, Object>> rows() {
        List<Map<String, Object>> result = jdbc.queryForList("SELECT * FROM competency_frameworks ORDER BY id");
        result.addAll(jdbc.queryForList("SELECT * FROM competency_criteria ORDER BY id"));
        return result;
    }

    private String body(String code, String name) throws Exception {
        return json.writeValueAsString(Map.of("code", code, "name", name,
                "criteria", List.of(Map.of("name", "Kỹ năng chuyên môn", "weight", 100))));
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
