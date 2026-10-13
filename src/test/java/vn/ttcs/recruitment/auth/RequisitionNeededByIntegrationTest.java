package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Task 248: the needed-by date of a requisition may not be before today, where "today" is the date in the business
// time zone (app.business-zone, Asia/Ho_Chi_Minh by default), not in UTC. Checked on both POST /api/v1/requisitions
// (create) and PUT /api/v1/requisitions/{id} (save the draft again).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionNeededByIntegrationTest {
    private static final String BASE = "/api/v1/requisitions";
    private static final String PASSWORD = "TestingOnly123!";
    // 23:30 on 6 Oct in Vietnam (UTC+7). Access tokens last 15 minutes, so a test that moves the clock logs in again
    // with at(...).
    private static final Instant START = Instant.parse("2026-10-06T16:30:00Z");
    // 00:00 on 7 Oct in Vietnam, while UTC is still at 17:00 on 6 Oct.
    private static final Instant VIETNAM_MIDNIGHT = Instant.parse("2026-10-06T17:00:00Z");
    private static final String OCT_5 = "2026-10-05";
    private static final String OCT_6 = "2026-10-06";
    private static final String OCT_7 = "2026-10-07";
    private static final String OCT_8 = "2026-10-08";
    private static final String PAST_CODE = "NEEDED_BY_IN_PAST";
    private static final String PAST_MESSAGE = "Ngày cần người không được trước ngày hôm nay.";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String fixturePasswordHash;
    // The department head (HIRING_MANAGER) who manages IT and writes the drafts.
    private UUID headId;
    private String headToken;
    private UUID itId;
    private UUID positionId;

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
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET department_id = NULL, admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("UPDATE departments SET parent_id = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        UUID adminId = UUID.fromString(login("admin@example.test").path("user").path("id").asText());
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        headId = account("head@example.test", Set.of(Role.HIRING_MANAGER));
        headToken = token("head@example.test");
        UUID salesHeadId = account("sales@example.test", Set.of(Role.HIRING_MANAGER));
        itId = department("IT", headId, true);
        department("SALES", salesHeadId, true);
        positionId = position("DEV_JUNIOR");
    }

    @Test
    void todayAndLaterDatesAreSavedAndEarlierDatesAreRefused() throws Exception {
        // At START it is 23:30 on 6 Oct in Vietnam, so 6 Oct is today and is allowed.
        List<String> allowed = List.of(OCT_6, OCT_7, "2027-12-31", "9999-12-31");
        for (String date : allowed) {
            JsonNode saved = expect(create(payload(date), headToken), 201, date);
            assertThat(saved.path("neededBy").asText()).isEqualTo(date);
            assertThat(neededBy(id(saved))).isEqualTo(LocalDate.parse(date));
        }
        // A draft may still leave the date empty: no key at all, or null.
        Map<String, Object> withoutKey = payload(null);
        withoutKey.remove("neededBy");
        assertThat(neededBy(id(expect(create(withoutKey, headToken), 201, "no neededBy key")))).isNull();
        JsonNode nullDate = expect(create(payload(null), headToken), 201, "neededBy null");
        assertThat(nullDate.path("neededBy").isNull()).isTrue();
        assertThat(neededBy(id(nullDate))).isNull();
        assertThat(count()).isEqualTo(allowed.size() + 2);

        // Yesterday and any earlier day are refused and nothing is stored.
        for (String date : List.of(OCT_5, "2025-10-06", "2026-01-01", "0001-01-01")) {
            neededByInPast(create(payload(date), headToken), date);
        }
        assertThat(count()).isEqualTo(allowed.size() + 2);
    }

    @Test
    void todayIsTheDateInVietnamNotInUtc() throws Exception {
        // One microsecond before midnight in Vietnam: 6 Oct is still today.
        String token = at(VIETNAM_MIDNIGHT.minusNanos(1_000));
        expect(create(payload(OCT_6), token), 201, "23:59:59.999999 on 6 Oct in Vietnam");

        // Midnight in Vietnam: UTC is still on 6 Oct (17:00), but 6 Oct is now yesterday.
        token = at(VIETNAM_MIDNIGHT);
        neededByInPast(create(payload(OCT_6), token), "00:00 on 7 Oct in Vietnam");
        expect(create(payload(OCT_7), token), 201, "00:00 on 7 Oct in Vietnam");

        // The example of the task: 17:30 UTC on 6 Oct is 00:30 on 7 Oct in Vietnam.
        token = at(Instant.parse("2026-10-06T17:30:00Z"));
        neededByInPast(create(payload(OCT_6), token), "00:30 on 7 Oct in Vietnam");
        expect(create(payload(OCT_7), token), 201, "00:30 on 7 Oct in Vietnam");

        // Midnight UTC changes nothing: it is 07:00 on 7 Oct in Vietnam.
        token = at(Instant.parse("2026-10-07T00:00:00Z"));
        neededByInPast(create(payload(OCT_6), token), "07:00 on 7 Oct in Vietnam");
        expect(create(payload(OCT_7), token), 201, "07:00 on 7 Oct in Vietnam");

        // The next day starts at 17:00 UTC again, not at the next midnight UTC.
        token = at(Instant.parse("2026-10-07T16:59:59Z"));
        expect(create(payload(OCT_7), token), 201, "23:59:59 on 7 Oct in Vietnam");
        token = at(Instant.parse("2026-10-07T17:00:00Z"));
        neededByInPast(create(payload(OCT_7), token), "00:00 on 8 Oct in Vietnam");
        expect(create(payload(OCT_8), token), 201, "00:00 on 8 Oct in Vietnam");
        assertThat(count()).isEqualTo(6);
    }

    @Test
    void savingADraftAgainChecksTheDateAgainstTheCurrentDay() throws Exception {
        UUID draft = id(expect(create(payload(OCT_6), headToken), 201, "today at START"));
        Map<String, Object> before = row(draft);

        // Midnight passes in Vietnam. Reading the draft is not a save: it still shows the date it was saved with.
        String token = at(VIETNAM_MIDNIGHT);
        assertThat(expect(get(draft, token), 200, "GET").path("neededBy").asText()).isEqualTo(OCT_6);
        // Saving the same content again is refused, and the stored draft stays as it was.
        neededByInPast(put(draft, payload(OCT_6), token), "PUT with the date that has passed");
        assertThat(row(draft)).isEqualTo(before);

        // A new date, or no date at all, is saved.
        JsonNode moved = expect(put(draft, payload(OCT_7), token), 200, "PUT with today");
        assertThat(moved.path("neededBy").asText()).isEqualTo(OCT_7);
        assertThat(neededBy(draft)).isEqualTo(LocalDate.parse(OCT_7));
        assertThat(expect(put(draft, payload(null), token), 200, "PUT without a date").path("neededBy").isNull()).isTrue();
        assertThat(neededBy(draft)).isNull();

        // Moving the date of a draft into the past is refused as well.
        Map<String, Object> cleared = row(draft);
        neededByInPast(put(draft, payload(OCT_5), token), "PUT with an earlier date");
        assertThat(row(draft)).isEqualTo(cleared);
        assertThat(count()).isEqualTo(1);
    }

    // The rule is the same for every role that may write requisitions: ALL does not skip it.
    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"HIRING_MANAGER", "ADMIN", "HR_MANAGER"})
    void everyRoleThatMayWriteFollowsTheSameRule(Role role) throws Exception {
        UUID caller = account("writer@example.test", Set.of(role));
        String token = token("writer@example.test");
        // The caller manages this department, so the department-scope rule of task 249 keeps this result valid.
        UUID managed = department("OWN", caller, true);

        Map<String, Object> past = payload(OCT_5);
        past.put("departmentId", managed);
        neededByInPast(create(past, token), role.name());
        assertThat(count()).isZero();

        Map<String, Object> today = payload(OCT_6);
        today.put("departmentId", managed);
        UUID saved = id(expect(create(today, token), 201, role.name()));
        neededByInPast(put(saved, past, token), role.name() + " PUT");
        assertThat(neededBy(saved)).isEqualTo(LocalDate.parse(OCT_6));
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void theEarlierChecksComeFirstAndThePastDateComesBeforeTheChecksOfOtherRows() throws Exception {
        UUID draft = id(expect(create(payload(OCT_7), headToken), 201, "valid draft"));
        Map<String, Object> before = row(draft);
        // Every body below has a needed-by date in the past.

        // Errors of single fields come first, all together; the date is not one of them.
        Map<String, Object> badField = payload(OCT_5);
        badField.put("headcount", 0);
        JsonNode fieldError = expect(create(badField, headToken), 400, "headcount 0");
        assertThat(fieldError.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(fieldError.path("fieldErrors").has("headcount")).isTrue();
        assertThat(fieldError.path("fieldErrors").has("neededBy")).isFalse();
        // An inverted salary proposal is reported before the date.
        Map<String, Object> inverted = payload(OCT_5);
        inverted.put("proposedSalaryMin", 30_000_000L);
        inverted.put("proposedSalaryMax", 20_000_000L);
        error(create(inverted, headToken), "REQUISITION_SALARY_RANGE_INVALID");

        // The date comes before the checks that read other rows: position, department and salary band.
        Map<String, Object> unknownPosition = payload(OCT_5);
        unknownPosition.put("positionId", UUID.randomUUID());
        neededByInPast(create(unknownPosition, headToken), "unknown position");
        Map<String, Object> closedDepartment = payload(OCT_5);
        closedDepartment.put("departmentId", department("CLOSED", headId, false));
        neededByInPast(create(closedDepartment, headToken), "inactive department");
        Map<String, Object> offBand = payload(OCT_5);
        offBand.put("proposedSalaryMin", 1L);
        offBand.put("proposedSalaryMax", 2L);
        neededByInPast(create(offBand, headToken), "salary outside the band without a justification");
        neededByInPast(put(draft, offBand, headToken), "PUT salary outside the band without a justification");

        // Callers without access get the standard 403, and an unknown requisition is a 404, before the date.
        account("interviewer@example.test", Set.of(Role.INTERVIEWER));
        forbidden(create(payload(OCT_5), token("interviewer@example.test")));
        forbidden(put(draft, payload(OCT_5), token("sales@example.test")));
        JsonNode missing = expect(put(UUID.randomUUID(), payload(OCT_5), headToken), 404, "unknown requisition");
        assertThat(missing.path("code").asText()).isEqualTo("REQUISITION_NOT_FOUND");
        assertThat(row(draft)).isEqualTo(before);
        assertThat(count()).isEqualTo(1);
    }

    // "Today" is read after the request got the account and session locks (and the requisition lock for PUT), not
    // when it arrived. A request sent at 23:59:59 in Vietnam that waits for the actor's account lock until midnight
    // is compared with the new day. (A wait on the position/department FOR SHARE locks comes after the date check,
    // so it does not move the date; docs/api/requisitions.md describes that limit.)
    @ParameterizedTest
    @CsvSource({"POST, true", "POST, false", "PUT, true", "PUT, false"})
    void aSaveThatWaitsForALockUsesTheDayWhenItSaves(String method, boolean midnightPasses) throws Exception {
        UUID draft = method.equals("PUT") ? id(expect(create(payload(null), headToken), 201, "draft")) : null;
        Map<String, Object> before = draft == null ? null : row(draft);
        String token = at(VIETNAM_MIDNIGHT.minusSeconds(1));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockAccount(connection, headId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> draft == null
                        ? create(payload(OCT_6), token) : put(draft, payload(OCT_6), token));
                try {
                    awaitWaiters(blockerPid, 1);
                    if (midnightPasses) {
                        clock.set(VIETNAM_MIDNIGHT);
                    }
                    connection.commit();
                    var result = response.get(10, TimeUnit.SECONDS);
                    if (midnightPasses) {
                        neededByInPast(result, method + " after midnight");
                    } else {
                        expect(result, draft == null ? 201 : 200, method + " before midnight");
                    }
                } finally {
                    connection.rollback();
                }
            }
        }
        if (draft == null) {
            assertThat(count()).isEqualTo(midnightPasses ? 0 : 1);
        } else if (midnightPasses) {
            assertThat(row(draft)).isEqualTo(before);
        } else {
            assertThat(neededBy(draft)).isEqualTo(LocalDate.parse(OCT_6));
        }
    }

    // A valid draft of the fixture position and IT with this needed-by date; null leaves the date empty.
    private Map<String, Object> payload(String neededBy) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("positionId", positionId);
        result.put("departmentId", itId);
        result.put("headcount", 2);
        result.put("reason", "NEW_HEADCOUNT");
        result.put("neededBy", neededBy);
        return result;
    }

    // Moves the clock and logs the department head in again, because the earlier token may have expired.
    private String at(Instant now) throws Exception {
        clock.set(now);
        return token("head@example.test");
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Requisition test", fixturePasswordHash, roles, START)).getId();
    }

    private UUID department(String code, UUID manager, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO departments (id, code, name, manager_user_id, active, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, code, "Phòng " + code, manager, active, Timestamp.from(START));
        return id;
    }

    // Standard salary band 15.000.000–25.000.000 VND.
    private UUID position(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id, code, name, level, salary_min, salary_max, active, created_at, updated_at)
                VALUES (?, ?, 'Lập trình viên', 'Junior', 15000000, 25000000, TRUE, ?, ?)
                """, id, code, Timestamp.from(START), Timestamp.from(START));
        return id;
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM recruitment_requisitions", Integer.class);
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("SELECT * FROM recruitment_requisitions WHERE id = ?", id);
    }

    private LocalDate neededBy(UUID id) {
        return jdbc.queryForObject("SELECT needed_by FROM recruitment_requisitions WHERE id = ?", LocalDate.class, id);
    }

    private static UUID id(JsonNode requisition) {
        return UUID.fromString(requisition.path("id").asText());
    }

    private JsonNode login(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200, "login " + email);
    }

    private String token(String email) throws Exception {
        return login(email).path("accessToken").asText();
    }

    private HttpResponse<String> create(Map<String, Object> body, String token) throws Exception {
        return request("POST", BASE, json.writeValueAsString(body), token);
    }

    private HttpResponse<String> put(UUID id, Map<String, Object> body, String token) throws Exception {
        return request("PUT", BASE + "/" + id, json.writeValueAsString(body), token);
    }

    private HttpResponse<String> get(UUID id, String token) throws Exception {
        return request("GET", BASE + "/" + id, null, token);
    }

    private HttpResponse<String> request(String method, String path, String payload, String token) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path);
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // The status first, with the case and the body in the failure message, then the parsed body.
    private JsonNode expect(HttpResponse<String> response, int expected, String description) {
        assertThat(response.statusCode()).as(description + ": " + response.body()).isEqualTo(expected);
        return json.readTree(response.body());
    }

    private void error(HttpResponse<String> response, String code) {
        assertThat(expect(response, 400, code).path("code").asText()).isEqualTo(code);
    }

    // 400 NEEDED_BY_IN_PAST: only code, message and one form error on neededBy, all with the same text.
    private void neededByInPast(HttpResponse<String> response, String description) {
        JsonNode body = expect(response, 400, description);
        assertThat(body.size()).as(description).isEqualTo(3);
        assertThat(body.path("code").asText()).as(description).isEqualTo(PAST_CODE);
        assertThat(body.path("message").asText()).as(description).isEqualTo(PAST_MESSAGE);
        assertThat(body.path("fieldErrors").size()).as(description).isEqualTo(1);
        assertThat(body.path("fieldErrors").path("neededBy").asText()).as(description).isEqualTo(PAST_MESSAGE);
        noStore(response);
    }

    private void forbidden(HttpResponse<String> response) {
        JsonNode body = expect(response, 403, "forbidden");
        assertThat(body.path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(body.path("message").asText()).isEqualTo("Bạn không có quyền thực hiện thao tác này.");
        noStore(response);
    }

    private void noStore(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private int lockAccount(Connection connection, UUID id) throws Exception {
        try (var statement = connection.prepareStatement("SELECT pg_backend_pid() FROM user_accounts WHERE id = ? FOR UPDATE")) {
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
