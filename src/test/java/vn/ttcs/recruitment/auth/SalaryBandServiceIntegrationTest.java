package vn.ttcs.recruitment.auth;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.position.Position;
import vn.ttcs.recruitment.position.PositionRepository;
import vn.ttcs.recruitment.position.SalaryBand;
import vn.ttcs.recruitment.position.SalaryBandService;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.ttcs.recruitment.position.SalaryBandComparison.ABOVE;
import static vn.ttcs.recruitment.position.SalaryBandComparison.BELOW;
import static vn.ttcs.recruitment.position.SalaryBandComparison.WITHIN;

// Jira 206: SalaryBandService has no endpoint, so the test calls it directly, as the later offer and requisition
// services will. HTTP is only used for the HR manager's PUT /positions that competes with a band check.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SalaryBandServiceIntegrationTest {
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-06T00:00:00Z");
    // Larger than Integer.MAX_VALUE, so the band must stay a whole-dong long from the database to the caller.
    private static final long THREE_BILLION_VND = 3_000_000_000L;

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private PositionRepository positions;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SalaryBandService salaryBands;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    // Only HR_MANAGER may change positions, so the HR manager plays the user who edits a band during a check.
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
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        // Reuse the bootstrap admin's hash of PASSWORD instead of hashing it again for the HR account.
        String passwordHash = jdbc.queryForObject(
                "SELECT password_hash FROM user_accounts WHERE email = 'admin@example.test'", String.class);
        accounts.saveAndFlush(new Account("hr@example.test", "Salary band test", passwordHash,
                Set.of(Role.HR_MANAGER), START));
        hrToken = login("hr@example.test").path("accessToken").asText();
    }

    @Test
    void returnsTheStoredBandOfAnActivePositionInWholeVnd() {
        UUID director = position("CEO", 1_500_000_000L, THREE_BILLION_VND, true);
        UUID intern = position("INTERN", 5_000_000L, 5_000_000L, true);

        assertThat(salaryBands.standardBand(director))
                .isEqualTo(new SalaryBand(director, 1_500_000_000L, THREE_BILLION_VND));
        assertThat(salaryBands.standardBand(intern)).isEqualTo(new SalaryBand(intern, 5_000_000L, 5_000_000L));
    }

    @Test
    void comparesProposedSalariesWithTheStoredBandIncludingBothEnds() {
        UUID id = position("DEV_JUNIOR", 15_000_000L, 25_000_000L, true);

        assertThat(salaryBands.compare(id, 14_999_999L)).isEqualTo(BELOW);
        assertThat(salaryBands.compare(id, 15_000_000L)).isEqualTo(WITHIN);
        assertThat(salaryBands.compare(id, 25_000_000L)).isEqualTo(WITHIN);
        assertThat(salaryBands.compare(id, 25_000_001L)).isEqualTo(ABOVE);
        assertThat(salaryBands.compare(position("CEO", 1_500_000_000L, THREE_BILLION_VND, true),
                THREE_BILLION_VND + 1)).isEqualTo(ABOVE);
    }

    @Test
    void unknownPositionIsNotFoundAndInactivePositionHasNoBandUntilHrTurnsItBackOn() throws Exception {
        UUID retired = position("RETIRED", 10_000_000L, 20_000_000L, false);
        Map<String, Object> before = row(retired);

        apiError(() -> salaryBands.standardBand(UUID.randomUUID()), HttpStatus.NOT_FOUND, "POSITION_NOT_FOUND");
        apiError(() -> salaryBands.compare(UUID.randomUUID(), 1L), HttpStatus.NOT_FOUND, "POSITION_NOT_FOUND");
        apiError(() -> salaryBands.standardBand(retired), HttpStatus.CONFLICT, "POSITION_INACTIVE");
        apiError(() -> salaryBands.compare(retired, 15_000_000L), HttpStatus.CONFLICT, "POSITION_INACTIVE");
        assertThatThrownBy(() -> salaryBands.standardBand(null)).isInstanceOf(NullPointerException.class);
        assertThat(row(retired)).isEqualTo(before);

        expect(update(retired, payload("RETIRED", 10_000_000L, 20_000_000L, true), hrToken), 200);
        assertThat(salaryBands.standardBand(retired)).isEqualTo(new SalaryBand(retired, 10_000_000L, 20_000_000L));
        assertThat(salaryBands.compare(retired, 15_000_000L)).isEqualTo(WITHIN);
    }

    @Test
    void worksInsideAReadOnlyTransaction() {
        UUID id = position("DEV", 15_000_000L, 25_000_000L, true);
        var readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);

        SalaryBand band = readOnly.execute(status -> {
            // PostgreSQL really runs this transaction as READ ONLY; the service must skip FOR SHARE here, which
            // PostgreSQL would reject (SQLSTATE 25006).
            assertThat(jdbc.queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("on");
            return salaryBands.standardBand(id);
        });

        assertThat(band).isEqualTo(new SalaryBand(id, 15_000_000L, 25_000_000L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"band-change", "deactivation"})
    void hrChangeWaitsUntilTheTransactionThatCheckedTheBandEnds(String change) throws Exception {
        UUID id = position("DEV", 15_000_000L, 25_000_000L, true);
        boolean stillActive = change.equals("band-change");
        var bandChecked = new CountDownLatch(1);
        var finishCheck = new CountDownLatch(1);
        var checkerPid = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            // Plays the later offer approval: it checks the band inside its own write transaction and commits later.
            Future<SalaryBand> check = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .execute(status -> {
                        SalaryBand band = salaryBands.standardBand(id);
                        checkerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        bandChecked.countDown();
                        await(finishCheck);
                        return band;
                    }));
            try {
                assertThat(bandChecked.await(10, TimeUnit.SECONDS)).as("the check must read the band").isTrue();
                // FOR SHARE does not block other checks of the same position.
                assertThat(executor.submit(() -> salaryBands.compare(id, 26_000_000L)).get(10, TimeUnit.SECONDS))
                        .isEqualTo(ABOVE);

                var hrChange = executor.submit(() -> update(id,
                        payload("DEV", 15_000_000L, 30_000_000L, stillActive), hrToken));
                awaitWaiters(checkerPid.get());
                assertThat(hrChange.isDone()).isFalse();
                assertThat(row(id).get("salary_max")).isEqualTo(25_000_000L);
                assertThat(row(id).get("active")).isEqualTo(true);

                finishCheck.countDown();
                assertThat(check.get(10, TimeUnit.SECONDS)).isEqualTo(new SalaryBand(id, 15_000_000L, 25_000_000L));
                assertThat(expect(hrChange.get(10, TimeUnit.SECONDS), 200).path("salaryMax").asLong())
                        .isEqualTo(30_000_000L);
            } finally {
                // Release the checking transaction even if an assertion above fails.
                finishCheck.countDown();
            }
        }
        if (stillActive) {
            assertThat(salaryBands.standardBand(id)).isEqualTo(new SalaryBand(id, 15_000_000L, 30_000_000L));
        } else {
            apiError(() -> salaryBands.standardBand(id), HttpStatus.CONFLICT, "POSITION_INACTIVE");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"band-change", "deactivation"})
    void checkWaitingForAnUncommittedChangeUsesTheCommittedValues(String change) throws Exception {
        UUID id = position("DEV", 15_000_000L, 25_000_000L, true);
        boolean stillActive = change.equals("band-change");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int writerPid = backendPid(connection);
            execute(connection, "UPDATE positions SET salary_max = ?, active = ? WHERE id = ?",
                    30_000_000L, stillActive, id);
            try (var executor = Executors.newSingleThreadExecutor()) {
                Future<SalaryBand> check = executor.submit(() -> salaryBands.standardBand(id));
                try {
                    awaitWaiters(writerPid);
                    assertThat(check.isDone()).isFalse();
                    connection.commit();
                    if (stillActive) {
                        assertThat(check.get(10, TimeUnit.SECONDS))
                                .isEqualTo(new SalaryBand(id, 15_000_000L, 30_000_000L));
                    } else {
                        apiError(() -> resultOf(check), HttpStatus.CONFLICT, "POSITION_INACTIVE");
                    }
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    private UUID position(String code, long salaryMin, long salaryMax, boolean active) {
        return positions.saveAndFlush(new Position(code, "Chức danh " + code, "Junior",
                salaryMin, salaryMax, active, START)).getId();
    }

    private Map<String, Object> payload(String code, long salaryMin, long salaryMax, boolean active) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("name", "Chức danh " + code);
        result.put("level", "Junior");
        result.put("salaryMin", salaryMin);
        result.put("salaryMax", salaryMax);
        result.put("active", active);
        return result;
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM positions WHERE id = ?", id);
    }

    private static void apiError(ThrowingCallable call, HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(status);
            assertThat(error.getCode()).isEqualTo(code);
        });
    }

    // Rethrows what the background call threw, so apiError can inspect the ApiException itself.
    private static SalaryBand resultOf(Future<SalaryBand> future) throws Throwable {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            throw exception.getCause();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The test did not release the latch in time");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    // Polls from a separate connection: inside one transaction PostgreSQL would keep showing the same snapshot.
    private void awaitWaiters(int blockerPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean waiting = false;
        while (!waiting && System.nanoTime() < deadline) {
            waiting = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity
                        WHERE ? = ANY(pg_blocking_pids(pid)) AND wait_event_type = 'Lock'
                          AND datname = current_database()
                    )
                    """, Boolean.class, blockerPid));
            if (!waiting) {
                Thread.sleep(20);
            }
        }
        assertThat(waiting).as("another transaction must wait for the positions row lock").isTrue();
    }

    private int backendPid(Connection connection) throws Exception {
        try (var statement = connection.prepareStatement("SELECT pg_backend_pid()");
             var row = statement.executeQuery()) {
            assertThat(row.next()).isTrue();
            return row.getInt(1);
        }
    }

    private void execute(Connection connection, String sql, Object... values) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private JsonNode login(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200);
    }

    private HttpResponse<String> update(UUID id, Map<String, Object> payload, String token) throws Exception {
        return request("PUT", "/api/v1/positions/" + id, json.writeValueAsString(payload), token);
    }

    private HttpResponse<String> request(String method, String path, String payload, String token) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path);
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(payload));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode expect(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return json.readTree(response.body());
    }
}
