package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PositionManagementIntegrationTest {
    private static final String BASE = "/api/v1/positions";
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-06T00:00:00Z");
    // Larger than Integer.MAX_VALUE, so the API must keep salaries as whole-dong long values end to end.
    private static final long THREE_BILLION_VND = 3_000_000_000L;
    // The largest salary the API accepts: 1.000 tỷ đồng.
    private static final long SALARY_CEILING = 1_000_000_000_000L;

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private UUID adminId;
    private String adminToken;
    private String fixturePasswordHash;

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
        JsonNode login = login("admin@example.test");
        adminId = UUID.fromString(login.path("user").path("id").asText());
        adminToken = login.path("accessToken").asText();
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
    }

    @Test
    void createsTrimmedPositionAndReturnsItsWholeVndSalaryBand() throws Exception {
        JsonNode emptyPage = expect(get(BASE, adminToken), 200);
        assertThat(emptyPage.path("items").isEmpty()).isTrue();
        assertThat(emptyPage.path("size").asInt()).isEqualTo(20);
        assertThat(emptyPage.path("totalElements").asLong()).isZero();
        assertThat(emptyPage.path("totalPages").asLong()).isZero();

        var response = create(payload("  CEO  ", "  Giám đốc điều hành  ", "  Director  ",
                1_500_000_000L, THREE_BILLION_VND, true), adminToken);
        JsonNode result = expect(response, 201);
        noStore(response);
        UUID id = UUID.fromString(result.path("id").asText());
        assertThat(result.size()).isEqualTo(9);
        assertThat(result.path("code").asText()).isEqualTo("CEO");
        assertThat(result.path("name").asText()).isEqualTo("Giám đốc điều hành");
        assertThat(result.path("level").asText()).isEqualTo("Director");
        assertThat(result.path("salaryMin").isIntegralNumber()).isTrue();
        assertThat(result.path("salaryMin").asLong()).isEqualTo(1_500_000_000L);
        assertThat(result.path("salaryMax").asLong()).isEqualTo(THREE_BILLION_VND);
        assertThat(result.path("active").asBoolean()).isTrue();
        assertThat(Instant.parse(result.path("createdAt").asText())).isEqualTo(START);
        assertThat(Instant.parse(result.path("updatedAt").asText())).isEqualTo(START);

        assertThat(expect(get(BASE + "/" + id, adminToken), 200)).isEqualTo(result);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM positions WHERE id = ?", id);
        assertThat(row.get("code")).isEqualTo("CEO");
        assertThat(row.get("salary_min")).isEqualTo(1_500_000_000L);
        assertThat(row.get("salary_max")).isEqualTo(THREE_BILLION_VND);
    }

    @Test
    void createsInactivePositionAndUpdatesEveryEditableFieldKeepingCreationTime() throws Exception {
        UUID id = position("DEV", "Developer", "Junior", 15_000_000L, 25_000_000L, false);
        assertThat(expect(get(BASE + "/" + id, adminToken), 200).path("active").asBoolean()).isFalse();

        // The clock has nanoseconds; the response must show the microseconds PostgreSQL actually stores.
        clock.set(START.plusSeconds(60).plusNanos(123_456_789));
        var response = update(id, payload("DEV_SENIOR", "Senior developer", "Senior", 30_000_000L, 45_000_000L, true),
                adminToken);
        JsonNode result = expect(response, 200);
        noStore(response);
        assertThat(result.path("id").asText()).isEqualTo(id.toString());
        assertThat(result.path("code").asText()).isEqualTo("DEV_SENIOR");
        assertThat(result.path("name").asText()).isEqualTo("Senior developer");
        assertThat(result.path("level").asText()).isEqualTo("Senior");
        assertThat(result.path("salaryMin").asLong()).isEqualTo(30_000_000L);
        assertThat(result.path("salaryMax").asLong()).isEqualTo(45_000_000L);
        assertThat(result.path("active").asBoolean()).isTrue();
        assertThat(Instant.parse(result.path("createdAt").asText())).isEqualTo(START);
        assertThat(Instant.parse(result.path("updatedAt").asText())).isEqualTo(START.plusSeconds(60).plusNanos(123_456_000));
        assertThat(expect(get(BASE + "/" + id, adminToken), 200)).isEqualTo(result);
    }

    @Test
    void searchesCodeAndNameCaseInsensitivelyTreatingSqlWildcardsLiterally() throws Exception {
        UUID exact = position("PCT_%!", "Unique wording", "Staff", 1L, 2L, true);
        UUID decoy = position("PCTAB", "Decoy", "Staff", 1L, 2L, true);
        position("NORMAL", "Other name", "Staff", 1L, 2L, true);
        for (String query : List.of("pct_%!", "UNIQUE WORD", "%", "_", "!", "pct_")) {
            JsonNode page = expect(get(BASE + "?q=" + encode(query), adminToken), 200);
            assertThat(page.path("totalElements").asInt()).as(query).isEqualTo(1);
            assertThat(ids(page.path("items"))).as(query).containsExactly(exact.toString());
        }
        assertThat(ids(expect(get(BASE + "?q=" + encode("decoy"), adminToken), 200).path("items")))
                .containsExactly(decoy.toString());
        // The level is not searched, and a quote cannot change the SQL.
        assertThat(expect(get(BASE + "?q=staff", adminToken), 200).path("totalElements").asInt()).isZero();
        assertThat(expect(get(BASE + "?q=" + encode("' OR 1=1 --"), adminToken), 200).path("totalElements").asInt()).isZero();
        assertThat(expect(get(BASE + "?q=" + encode("   "), adminToken), 200).path("totalElements").asInt()).isEqualTo(3);
    }

    @Test
    void activeFilterAndPaginationReturnStablePagesOrderedByCode() throws Exception {
        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            expected.add(position("P" + index, "Position " + index, "Junior", 1L, 2L, index % 2 == 0).toString());
        }
        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            var response = get(BASE + "?page=" + page + "&size=2", adminToken);
            JsonNode result = expect(response, 200);
            noStore(response);
            assertThat(result.size()).isEqualTo(5);
            assertThat(result.path("page").asInt()).isEqualTo(page);
            assertThat(result.path("size").asInt()).isEqualTo(2);
            assertThat(result.path("totalElements").asInt()).isEqualTo(5);
            assertThat(result.path("totalPages").asInt()).isEqualTo(3);
            assertThat(result).isEqualTo(expect(get(BASE + "?page=" + page + "&size=2", adminToken), 200));
            seen.addAll(ids(result.path("items")));
        }
        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(expect(get(BASE + "?active=true", adminToken), 200).path("totalElements").asInt()).isEqualTo(3);
        assertThat(expect(get(BASE + "?active=false", adminToken), 200).path("totalElements").asInt()).isEqualTo(2);
        assertThat(ids(expect(get(BASE + "?q=p1&active=false", adminToken), 200).path("items")))
                .containsExactly(expected.get(1));
        JsonNode beyond = expect(get(BASE + "?page=10&size=2", adminToken), 200);
        assertThat(beyond.path("items").isEmpty()).isTrue();
        assertThat(beyond.path("totalElements").asInt()).isEqualTo(5);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void allInternalRolesCanReadButOnlyAdminAndHrManagerCanWriteByDefault(Role role) throws Exception {
        account("role@example.test", Set.of(role));
        String token = login("role@example.test").path("accessToken").asText();
        UUID target = position("READ", "Read position", "Junior", 1L, 2L, true);
        assertThat(ids(expect(get(BASE, token), 200).path("items"))).containsExactly(target.toString());
        assertThat(expect(get(BASE + "/" + target, token), 200).path("code").asText()).isEqualTo("READ");

        boolean writer = role == Role.ADMIN || role == Role.HR_MANAGER;
        var created = create(payload("NEW", "New position", "Junior", 1L, 2L, true), token);
        var updated = update(target, payload("READ", "Updated", "Senior", 3L, 4L, false), token);
        // Without write permission the salary band rules are never reached: the answer is 403, not 400.
        var inverted = update(target, payload("READ", "Inverted", "Senior", 5L, 4L, true), token);
        if (writer) {
            expect(created, 201);
            assertThat(expect(updated, 200).path("name").asText()).isEqualTo("Updated");
            error(inverted, 400, "POSITION_SALARY_RANGE_INVALID");
        } else {
            error(created, 403, "FORBIDDEN");
            error(updated, 403, "FORBIDDEN");
            error(inverted, 403, "FORBIDDEN");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM positions", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT name FROM positions WHERE id = ?", String.class, target))
                    .isEqualTo("Read position");
        }
    }

    @Test
    void rejectsMissingBlankOversizedAndUnknownFieldsButAcceptsLengthBoundaries() throws Exception {
        Map<String, Object> valid = payload("VALID", "Valid", "Junior", 1L, 2L, true);
        for (String field : List.of("code", "name", "level", "salaryMin", "salaryMax", "active")) {
            Map<String, Object> missing = new LinkedHashMap<>(valid);
            missing.remove(field);
            JsonNode body = expect(create(missing, adminToken), 400);
            assertThat(body.path("code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(body.path("fieldErrors").has(field)).as(field).isTrue();
        }
        for (String field : List.of("code", "name", "level")) {
            int limit = field.equals("name") ? 255 : 50;
            Map<String, Object> invalid = new LinkedHashMap<>(valid);
            for (String value : List.of("", " \t\n ", "x".repeat(limit + 1), "  " + "x".repeat(limit + 1) + "  ")) {
                invalid.put(field, value);
                fieldErrors(create(invalid, adminToken), field);
            }
        }
        Map<String, Object> unknown = new LinkedHashMap<>(valid);
        unknown.put("id", UUID.randomUUID());
        error(create(unknown, adminToken), 400, "INVALID_JSON");
        Map<String, Object> notANumber = new LinkedHashMap<>(valid);
        notANumber.put("salaryMax", "nhiều tiền");
        error(create(notANumber, adminToken), 400, "INVALID_JSON");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM positions", Integer.class)).isZero();

        // Values are trimmed before the length check, so padding around a value at the limit is accepted.
        UUID boundary = position("  " + "c".repeat(50) + "\t", " " + "n".repeat(255) + " ", "\n" + "l".repeat(50) + " ",
                0L, 0L, true);
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM positions WHERE id = ?", boundary);
        assertThat(before.get("code")).isEqualTo("c".repeat(50));
        assertThat(before.get("name")).isEqualTo("n".repeat(255));
        assertThat(before.get("level")).isEqualTo("l".repeat(50));
        Map<String, Object> blankName = new LinkedHashMap<>(valid);
        blankName.put("name", "");
        error(update(boundary, blankName, adminToken), 400, "VALIDATION_ERROR");
        assertThat(jdbc.queryForMap("SELECT * FROM positions WHERE id = ?", boundary)).isEqualTo(before);
    }

    @Test
    void rejectsNegativeAndInvertedSalaryBandsWithFieldErrorsWithoutChangingRows() throws Exception {
        UUID existing = position("KEEP", "Keep", "Junior", 10L, 20L, true);
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM positions WHERE id = ?", existing);
        Map<String, String> negativeMessages = Map.of("salaryMin", "Lương tối thiểu không được âm.",
                "salaryMax", "Lương tối đa không được âm.");
        for (String field : List.of("salaryMin", "salaryMax")) {
            Map<String, Object> negative = payload("NEGATIVE", "Negative", "Junior", 1L, 2L, true);
            negative.put(field, -1L);
            for (var response : List.of(create(negative, adminToken), update(existing, negative, adminToken))) {
                assertThat(fieldErrors(response, field).path(field).asText()).isEqualTo(negativeMessages.get(field));
            }
        }
        // The smallest possible inversion: the minimum is one dong above the maximum.
        Map<String, Object> inverted = payload("INVERTED", "Inverted", "Junior", 20_000_001L, 20_000_000L, true);
        for (var response : List.of(create(inverted, adminToken), update(existing, inverted, adminToken))) {
            JsonNode body = expect(response, 400);
            noStore(response);
            assertThat(body.path("code").asText()).isEqualTo("POSITION_SALARY_RANGE_INVALID");
            assertThat(body.path("message").asText()).isEqualTo("Lương tối thiểu không được lớn hơn lương tối đa.");
            assertThat(body.path("fieldErrors").size()).isEqualTo(1);
            assertThat(body.path("fieldErrors").path("salaryMax").asText())
                    .isEqualTo("Lương tối đa phải lớn hơn hoặc bằng lương tối thiểu.");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM positions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM positions WHERE id = ?", existing)).isEqualTo(before);

        // Equal minimum and maximum is still a valid band, on create and on update.
        JsonNode fixed = expect(create(payload("FIXED", "Fixed salary", "Junior", 20_000_000L, 20_000_000L, true),
                adminToken), 201);
        assertThat(fixed.path("salaryMin").asLong()).isEqualTo(20_000_000L);
        assertThat(fixed.path("salaryMax").asLong()).isEqualTo(20_000_000L);
        assertThat(expect(update(existing, payload("KEEP", "Keep", "Junior", 7L, 7L, true), adminToken), 200)
                .path("salaryMax").asLong()).isEqualTo(7L);
        assertThat(jdbc.queryForObject("SELECT salary_min FROM positions WHERE id = ?", Long.class, existing)).isEqualTo(7L);
    }

    @Test
    void acceptsSalariesUpToTheCeilingAndRejectsOneDongMoreOnEitherField() throws Exception {
        UUID existing = position("KEEP", "Keep", "Junior", 10L, 20L, true);
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM positions WHERE id = ?", existing);
        Map<String, String> ceilingMessages = Map.of(
                "salaryMin", "Lương tối thiểu không được vượt quá 1.000.000.000.000 đồng.",
                "salaryMax", "Lương tối đa không được vượt quá 1.000.000.000.000 đồng.");
        for (String field : List.of("salaryMin", "salaryMax")) {
            Map<String, Object> tooLarge = payload("LARGE", "Large", "Junior", SALARY_CEILING, SALARY_CEILING, true);
            tooLarge.put(field, SALARY_CEILING + 1);
            // When salaryMin is too large the band is also inverted, but only the field error is reported:
            // the band is compared after each salary passes its own checks.
            for (var response : List.of(create(tooLarge, adminToken), update(existing, tooLarge, adminToken))) {
                assertThat(fieldErrors(response, field).path(field).asText()).isEqualTo(ceilingMessages.get(field));
            }
        }
        // Several broken fields are reported together, so a form can mark all of them in one round trip.
        fieldErrors(create(payload(" ", "All wrong", "Junior", -1L, SALARY_CEILING + 1, true), adminToken),
                "code", "salaryMin", "salaryMax");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM positions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM positions WHERE id = ?", existing)).isEqualTo(before);

        // The ceiling itself is allowed, even as a fixed band, and is stored exactly.
        JsonNode top = expect(create(payload("TOP", "Top", "Executive", SALARY_CEILING, SALARY_CEILING, true),
                adminToken), 201);
        assertThat(top.path("salaryMin").asLong()).isEqualTo(SALARY_CEILING);
        assertThat(top.path("salaryMax").asLong()).isEqualTo(SALARY_CEILING);
        assertThat(jdbc.queryForObject("SELECT salary_max FROM positions WHERE id = ?", Long.class,
                UUID.fromString(top.path("id").asText()))).isEqualTo(SALARY_CEILING);
        assertThat(expect(update(existing, payload("KEEP", "Keep", "Junior", 0L, SALARY_CEILING, true), adminToken), 200)
                .path("salaryMax").asLong()).isEqualTo(SALARY_CEILING);
    }

    @Test
    void rejectsSalariesThatAreNotJsonWholeNumbersInsteadOfTruncatingThem() throws Exception {
        UUID existing = position("KEEP", "Keep", "Junior", 10L, 20L, true);
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM positions WHERE id = ?", existing);
        // Raw JSON text, so the test controls the exact number form. Jackson's default would store 1.9 as 1;
        // the last value is one more than the largest long.
        List<String> notWhole = List.of("1.9", "1.0", "1e3", "\"15000000\"", "9223372036854775808");
        for (String salary : notWhole) {
            for (String body : List.of(positionJson(salary, "2000"), positionJson("1", salary))) {
                error(request("POST", BASE, body, adminToken), 400, "INVALID_JSON");
                error(request("PUT", BASE + "/" + existing, body, adminToken), 400, "INVALID_JSON");
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM positions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM positions WHERE id = ?", existing)).isEqualTo(before);

        // The largest long is a whole number, so it parses, then fails the salary ceiling as a field error.
        fieldErrors(request("POST", BASE, positionJson("0", "9223372036854775807"), adminToken), "salaryMax");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM positions", Integer.class)).isEqualTo(1);

        // The same JSON template with whole numbers up to the ceiling is accepted and stored exactly.
        JsonNode largest = expect(request("POST", BASE, positionJson("0", "1000000000000"), adminToken), 201);
        assertThat(largest.path("salaryMax").asLong()).isEqualTo(SALARY_CEILING);
        assertThat(jdbc.queryForObject("SELECT salary_max FROM positions WHERE id = ?", Long.class,
                UUID.fromString(largest.path("id").asText()))).isEqualTo(SALARY_CEILING);
    }

    @Test
    void validatesListArgumentsAndReturnsNotFoundForUnknownPositions() throws Exception {
        for (String query : List.of("page=-1", "size=0", "size=101", "active=unknown", "page=abc", "q=" + "x".repeat(256))) {
            error(get(BASE + "?" + query, adminToken), 400, "VALIDATION_ERROR");
        }
        expect(get(BASE + "?size=100&q=" + "x".repeat(255), adminToken), 200);

        var missing = get(BASE + "/" + UUID.randomUUID(), adminToken);
        error(missing, 404, "POSITION_NOT_FOUND");
        noStore(missing);
        error(update(UUID.randomUUID(), payload("NONE", "None", "Junior", 1L, 2L, true), adminToken),
                404, "POSITION_NOT_FOUND");
        error(get(BASE + "/not-a-uuid", adminToken), 400, "VALIDATION_ERROR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM positions", Integer.class)).isZero();
    }

    @Test
    void codesAreCaseSensitiveUniqueAndADuplicateUpdateLeavesTheRowUnchanged() throws Exception {
        UUID upper = position("HR", "HR", "Manager", 1L, 2L, true);
        UUID lower = position("hr", "Lowercase", "Manager", 1L, 2L, true);
        var duplicate = create(payload("HR", "Duplicate", "Manager", 1L, 2L, true), adminToken);
        error(duplicate, 409, "POSITION_CODE_EXISTS");
        noStore(duplicate);
        error(create(payload("  HR  ", "Padded duplicate", "Manager", 1L, 2L, true), adminToken), 409, "POSITION_CODE_EXISTS");

        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM positions WHERE id = ?", lower);
        error(update(lower, payload("HR", "Duplicate update", "Manager", 1L, 2L, true), adminToken), 409, "POSITION_CODE_EXISTS");
        assertThat(jdbc.queryForMap("SELECT * FROM positions WHERE id = ?", lower)).isEqualTo(before);

        // Keeping its own code is not a conflict.
        assertThat(expect(update(upper, payload("HR", "Human resources", "Manager", 1L, 2L, true), adminToken), 200)
                .path("name").asText()).isEqualTo("Human resources");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM positions", Integer.class)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update"})
    void codeCommittedByAnotherTransactionWhileTheWriteWaitsReturnsConflict(String operation) throws Exception {
        UUID existing = position("OLD", "Old", "Junior", 1L, 2L, true);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Uncommitted, so the API's existence check misses it and only the unique constraint can stop the write.
            int blockerPid = insertUncommittedPosition(connection, "SAME");
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> operation.equals("create")
                        ? create(payload("SAME", "Second", "Junior", 1L, 2L, true), adminToken)
                        : update(existing, payload("SAME", "Renamed", "Junior", 1L, 2L, true), adminToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    connection.commit();
                    error(response.get(10, TimeUnit.SECONDS), 409, "POSITION_CODE_EXISTS");
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM positions WHERE code = 'SAME'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT code FROM positions WHERE id = ?", String.class, existing)).isEqualTo("OLD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"lost-permission", "expired-jwt", "locked-actor", "revoked-session"})
    void rechecksAccessAfterWaitingForTheActorAccountLock(String change) throws Exception {
        // This third account is outside the request, avoiding FK lock interference when it locks the actor.
        UUID lockOwner = account("lockowner@example.test", Set.of(Role.ADMIN));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int blockerPid = lockAccount(connection, adminId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> create(payload("DENIED", "Denied", "Junior", 1L, 2L, true), adminToken));
                try {
                    awaitWaiters(blockerPid, 1);
                    switch (change) {
                        case "lost-permission" -> jdbc.update("DELETE FROM role_permissions "
                                + "WHERE role_code = 'ADMIN' AND permission_code = 'ORGANIZATION_WRITE_ALL'");
                        case "expired-jwt" -> clock.set(START.plus(Duration.ofMinutes(15)));
                        case "locked-actor" -> execute(connection, "UPDATE user_accounts SET admin_locked_at = ?, "
                                + "admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                                Timestamp.from(START), lockOwner, adminId);
                        default -> execute(connection, "UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?",
                                Timestamp.from(START), adminId);
                    }
                    connection.commit();
                    var result = response.get(10, TimeUnit.SECONDS);
                    if (change.equals("lost-permission")) {
                        error(result, 403, "FORBIDDEN");
                    } else {
                        error(result, 401, "SESSION_INVALID");
                    }
                } finally {
                    connection.rollback();
                }
            }
        } finally {
            jdbc.update("""
                    INSERT INTO role_permissions (role_code, permission_code)
                    VALUES ('ADMIN', 'ORGANIZATION_WRITE_ALL')
                    ON CONFLICT DO NOTHING
                    """);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM positions", Integer.class)).isZero();
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Position test", fixturePasswordHash, roles, START)).getId();
    }

    private UUID position(String code, String name, String level, long salaryMin, long salaryMax, boolean active)
            throws Exception {
        return UUID.fromString(expect(create(payload(code, name, level, salaryMin, salaryMax, active), adminToken), 201)
                .path("id").asText());
    }

    private Map<String, Object> payload(String code, String name, String level, long salaryMin, long salaryMax,
                                        boolean active) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("name", name);
        result.put("level", level);
        result.put("salaryMin", salaryMin);
        result.put("salaryMax", salaryMax);
        result.put("active", active);
        return result;
    }

    private String positionJson(String salaryMin, String salaryMax) {
        return """
                {"code":"FRACTION","name":"Fraction","level":"Junior","salaryMin":%s,"salaryMax":%s,"active":true}
                """.formatted(salaryMin, salaryMax);
    }

    private JsonNode login(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200);
    }

    private HttpResponse<String> create(Map<String, Object> payload, String token) throws Exception {
        return request("POST", BASE, json.writeValueAsString(payload), token);
    }

    private HttpResponse<String> update(UUID id, Map<String, Object> payload, String token) throws Exception {
        return request("PUT", BASE + "/" + id, json.writeValueAsString(payload), token);
    }

    private HttpResponse<String> get(String path, String token) throws Exception { return request("GET", path, null, token); }

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

    private void error(HttpResponse<String> response, int status, String code) {
        assertThat(expect(response, status).path("code").asText()).isEqualTo(code);
    }

    // A 400 VALIDATION_ERROR whose fieldErrors names exactly these request fields.
    private JsonNode fieldErrors(HttpResponse<String> response, String... fields) {
        JsonNode body = expect(response, 400);
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.path("fieldErrors").size()).as(body.toString()).isEqualTo(fields.length);
        for (String field : fields) {
            assertThat(body.path("fieldErrors").path(field).asText()).as(field).isNotBlank();
        }
        return body.path("fieldErrors");
    }

    private void noStore(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        array.forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    private int insertUncommittedPosition(Connection connection, String code) throws Exception {
        execute(connection, """
                INSERT INTO positions (id, code, name, level, salary_min, salary_max, active, created_at, updated_at)
                VALUES (?, ?, 'Other transaction', 'Junior', 1, 2, TRUE, ?, ?)
                """, UUID.randomUUID(), code, Timestamp.from(START), Timestamp.from(START));
        return backendPid(connection);
    }

    private int lockAccount(Connection connection, UUID id) throws Exception {
        try (var statement = connection.prepareStatement("SELECT id FROM user_accounts WHERE id = ? FOR UPDATE")) {
            statement.setObject(1, id);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); }
        }
        return backendPid(connection);
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
