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
import java.math.BigDecimal;
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
import java.util.Arrays;
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
class CompetencyFrameworkManagementIntegrationTest {
    private static final String BASE = "/api/v1/competency-frameworks";
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-07T00:00:00Z");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private UUID adminId;
    // HR_MANAGER is the role of the story ("Trưởng phòng Nhân sự"), so the HR manager writes most fixtures.
    private UUID hrId;
    private String hrToken;
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
        // ON DELETE CASCADE removes the criteria too.
        jdbc.update("DELETE FROM competency_frameworks");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        JsonNode login = login("admin@example.test");
        adminId = UUID.fromString(login.path("user").path("id").asText());
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = account("hr@example.test", Set.of(Role.HR_MANAGER));
        hrToken = login("hr@example.test").path("accessToken").asText();
    }

    @Test
    void createsTrimmedDraftFrameworkWithOrderedCriteriaAndReadsItBack() throws Exception {
        JsonNode emptyPage = expect(get(BASE, hrToken), 200);
        assertThat(emptyPage.path("items").isEmpty()).isTrue();
        assertThat(emptyPage.path("size").asInt()).isEqualTo(20);
        assertThat(emptyPage.path("totalElements").asLong()).isZero();
        assertThat(emptyPage.path("totalPages").asLong()).isZero();

        var response = create(payload("  DEV_CORE  ", "  Năng lực lập trình viên ", "\n Dùng cho mọi cấp lập trình viên \t",
                List.of(criterion(null, "  Kỹ năng lập trình  ", " Viết mã đúng\nvà dễ đọc ", 40),
                        criterion(null, "Thiết kế hệ thống", "   ", new BigDecimal("35.5")),
                        criterion(null, "Làm việc nhóm", null, new BigDecimal("24.50")))), hrToken);
        JsonNode result = expect(response, 201);
        noStore(response);
        UUID id = UUID.fromString(result.path("id").asText());
        assertThat(result.size()).isEqualTo(9);
        assertThat(result.path("code").asText()).isEqualTo("DEV_CORE");
        assertThat(result.path("name").asText()).isEqualTo("Năng lực lập trình viên");
        assertThat(result.path("description").asText()).isEqualTo("Dùng cho mọi cấp lập trình viên");
        // Without a status in the request, a new framework is a DRAFT.
        assertThat(result.path("status").asText()).isEqualTo("DRAFT");
        // Jira 214: no position uses a framework that was just created.
        assertThat(result.path("positions").isArray()).isTrue();
        assertThat(result.path("positions").isEmpty()).isTrue();
        assertThat(Instant.parse(result.path("createdAt").asText())).isEqualTo(START);
        assertThat(Instant.parse(result.path("updatedAt").asText())).isEqualTo(START);

        JsonNode criteria = result.path("criteria");
        assertThat(names(criteria)).containsExactly("Kỹ năng lập trình", "Thiết kế hệ thống", "Làm việc nhóm");
        assertThat(sortOrders(criteria)).containsExactly(1, 2, 3);
        assertThat(criteria.path(0).size()).isEqualTo(5);
        // The inner line break stays; only the padding around the text is removed.
        assertThat(criteria.path(0).path("description").asText()).isEqualTo("Viết mã đúng\nvà dễ đọc");
        // A blank description is stored as NULL, like a missing one.
        assertThat(criteria.path(1).path("description").isNull()).isTrue();
        assertThat(criteria.path(2).path("description").isNull()).isTrue();
        // Weights are sent back with exactly two decimals, as NUMERIC(5,2) stores them.
        assertThat(response.body()).contains("\"weight\":40.00", "\"weight\":35.50", "\"weight\":24.50");

        var detail = get(BASE + "/" + id, hrToken);
        assertThat(expect(detail, 200)).isEqualTo(result);
        noStore(detail);
        assertThat(detail.body()).contains("\"weight\":40.00");

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM competency_frameworks WHERE id = ?", id);
        assertThat(row.get("code")).isEqualTo("DEV_CORE");
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("description")).isEqualTo("Dùng cho mọi cấp lập trình viên");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM competency_criteria WHERE framework_id = ? ORDER BY sort_order", id);
        assertThat(rows).extracting(criterion -> criterion.get("id").toString())
                .containsExactlyElementsOf(ids(criteria));
        assertThat(rows).extracting(criterion -> criterion.get("weight"))
                .containsExactly(new BigDecimal("40.00"), new BigDecimal("35.50"), new BigDecimal("24.50"));
        assertThat(rows.get(1).get("description")).isNull();

        // The list shows the framework without its criteria, only how many it has.
        JsonNode item = expect(get(BASE, hrToken), 200).path("items").path(0);
        assertThat(item.size()).isEqualTo(8);
        assertThat(item.has("criteria")).isFalse();
        assertThat(item.path("id").asText()).isEqualTo(id.toString());
        assertThat(item.path("criterionCount").asLong()).isEqualTo(3);
        assertThat(item.path("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void updateReplacesTheCriteriaListAndKeepsTheIdOfEveryCriterionSentBack() throws Exception {
        JsonNode created = framework("DEV_CORE", "Năng lực lập trình viên", criterion(null, "Giao tiếp", null, 30),
                criterion(null, "Tư duy", null, 30), criterion(null, "Làm việc nhóm", null, 40));
        UUID id = UUID.fromString(created.path("id").asText());
        List<String> before = ids(created.path("criteria"));
        UUID communication = UUID.fromString(before.get(0));
        UUID thinking = UUID.fromString(before.get(1));
        UUID teamwork = UUID.fromString(before.get(2));

        // The clock has nanoseconds; the response must show the microseconds PostgreSQL actually stores.
        clock.set(START.plusSeconds(60).plusNanos(123_456_789));
        // Moves "Tư duy" first and edits it, adds a new criterion, keeps "Làm việc nhóm" and drops "Giao tiếp".
        var response = update(id, payload("DEV_CORE_V2", "Năng lực lập trình viên (bản 2)", "Bản sửa",
                List.of(criterion(thinking, "Tư duy phản biện", "Đặt câu hỏi đúng", new BigDecimal("45.25")),
                        criterion(null, "Học hỏi", null, new BigDecimal("14.75")),
                        criterion(teamwork, "Làm việc nhóm", null, 40))), hrToken);
        JsonNode result = expect(response, 200);
        noStore(response);
        assertThat(result.path("id").asText()).isEqualTo(id.toString());
        assertThat(result.path("code").asText()).isEqualTo("DEV_CORE_V2");
        assertThat(result.path("name").asText()).isEqualTo("Năng lực lập trình viên (bản 2)");
        assertThat(result.path("description").asText()).isEqualTo("Bản sửa");
        assertThat(result.path("status").asText()).isEqualTo("DRAFT");
        assertThat(Instant.parse(result.path("createdAt").asText())).isEqualTo(START);
        assertThat(Instant.parse(result.path("updatedAt").asText()))
                .isEqualTo(START.plusSeconds(60).plusNanos(123_456_000));

        JsonNode criteria = result.path("criteria");
        List<String> after = ids(criteria);
        assertThat(after.get(0)).isEqualTo(thinking.toString());
        assertThat(after.get(2)).isEqualTo(teamwork.toString());
        assertThat(after.get(1)).isNotIn(before);
        assertThat(names(criteria)).containsExactly("Tư duy phản biện", "Học hỏi", "Làm việc nhóm");
        assertThat(sortOrders(criteria)).containsExactly(1, 2, 3);
        assertThat(criteria.path(0).path("description").asText()).isEqualTo("Đặt câu hỏi đúng");
        assertThat(response.body()).contains("\"weight\":45.25", "\"weight\":14.75", "\"weight\":40.00");
        assertThat(expect(get(BASE + "/" + id, hrToken), 200)).isEqualTo(result);

        // The dropped criterion is deleted; the kept ones are the same rows, now in the new order.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_criteria WHERE id = ?", Integer.class,
                communication)).isZero();
        assertThat(jdbc.queryForMap("SELECT name, weight, sort_order FROM competency_criteria WHERE id = ?", thinking))
                .isEqualTo(Map.of("name", "Tư duy phản biện", "weight", new BigDecimal("45.25"), "sort_order", 1));
        assertThat(jdbc.queryForObject("SELECT sort_order FROM competency_criteria WHERE id = ?", Integer.class,
                teamwork)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_criteria", Integer.class)).isEqualTo(3);

        // An empty list removes every criterion; a DRAFT framework may have none.
        JsonNode emptied = expect(update(id, payload("DEV_CORE_V2", "Năng lực lập trình viên (bản 2)", null,
                List.of()), hrToken), 200);
        assertThat(emptied.path("criteria").isEmpty()).isTrue();
        assertThat(emptied.path("description").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_criteria", Integer.class)).isZero();
        assertThat(expect(get(BASE, hrToken), 200).path("items").path(0).path("criterionCount").asLong()).isZero();
    }

    @Test
    void oneEditMaySwapTheNamesOfTwoCriteriaAndNamesAreCaseSensitive() throws Exception {
        JsonNode created = framework("SWAP", "Swap", criterion(null, "Giao tiếp", null, 60),
                criterion(null, "Tư duy", null, 40));
        UUID id = UUID.fromString(created.path("id").asText());
        UUID first = UUID.fromString(ids(created.path("criteria")).get(0));
        UUID second = UUID.fromString(ids(created.path("criteria")).get(1));

        // Between the two row updates both rows have the same name and sort order for a moment; V8 checks
        // uniqueness only for the final state, so the swap succeeds.
        JsonNode swapped = expect(update(id, payload("SWAP", "Swap", null, List.of(
                criterion(second, "Giao tiếp", null, 40), criterion(first, "Tư duy", null, 60))), hrToken), 200);
        assertThat(ids(swapped.path("criteria"))).containsExactly(second.toString(), first.toString());
        assertThat(names(swapped.path("criteria"))).containsExactly("Giao tiếp", "Tư duy");
        assertThat(jdbc.queryForMap("SELECT name, sort_order FROM competency_criteria WHERE id = ?", first))
                .isEqualTo(Map.of("name", "Tư duy", "sort_order", 2));

        // The V8 UNIQUE constraint compares names exactly, so names that differ only in case are both kept.
        JsonNode cased = expect(update(id, payload("SWAP", "Swap", null, List.of(
                criterion(second, "Giao tiếp", null, 40), criterion(first, "giao tiếp", null, 60))), hrToken), 200);
        assertThat(names(cased.path("criteria"))).containsExactly("Giao tiếp", "giao tiếp");
    }

    @Test
    void rejectsCriterionIdsThatAreUnknownBelongToAnotherFrameworkOrRepeatWithoutChangingRows() throws Exception {
        JsonNode target = framework("TARGET", "Target", criterion(null, "Giao tiếp", null, 50),
                criterion(null, "Tư duy", null, 50));
        UUID id = UUID.fromString(target.path("id").asText());
        UUID communication = UUID.fromString(ids(target.path("criteria")).get(0));
        UUID foreign = UUID.fromString(ids(framework("OTHER", "Other", criterion(null, "Giao tiếp", null, 100))
                .path("criteria")).get(0));
        var before = competencyRows();

        var fromOtherFramework = update(id, payload("TARGET", "Changed", null, List.of(
                criterion(communication, "Giao tiếp", null, 50), criterion(foreign, "Tư duy", null, 50))), hrToken);
        JsonNode body = expect(fromOtherFramework, 400);
        noStore(fromOtherFramework);
        assertThat(body.path("code").asText()).isEqualTo("INVALID_COMPETENCY_CRITERION");
        assertThat(body.path("fieldErrors").size()).isEqualTo(1);
        assertThat(body.path("fieldErrors").path("criteria[1].id").asText())
                .isEqualTo("Tiêu chí không thuộc khung năng lực này.");

        JsonNode unknown = expect(update(id, payload("TARGET", "Changed", null, List.of(
                criterion(UUID.randomUUID(), "Giao tiếp", null, 100))), hrToken), 400);
        assertThat(unknown.path("code").asText()).isEqualTo("INVALID_COMPETENCY_CRITERION");
        assertThat(unknown.path("fieldErrors").has("criteria[0].id")).isTrue();

        JsonNode repeated = expect(update(id, payload("TARGET", "Changed", null, List.of(
                criterion(communication, "Giao tiếp", null, 50), criterion(communication, "Tư duy", null, 50))),
                hrToken), 400);
        assertThat(repeated.path("code").asText()).isEqualTo("INVALID_COMPETENCY_CRITERION");
        assertThat(repeated.path("fieldErrors").size()).isEqualTo(1);
        assertThat(repeated.path("fieldErrors").path("criteria[1].id").asText())
                .isEqualTo("Mỗi tiêu chí chỉ được gửi một lần.");

        // A new framework has no criteria yet, so POST accepts no criterion id at all.
        JsonNode reused = expect(create(payload("COPY", "Copy", null, List.of(
                criterion(communication, "Giao tiếp", null, 100))), hrToken), 400);
        assertThat(reused.path("code").asText()).isEqualTo("INVALID_COMPETENCY_CRITERION");
        assertThat(reused.path("fieldErrors").has("criteria[0].id")).isTrue();
        assertThat(competencyRows()).isEqualTo(before);
    }

    @Test
    void rejectsRepeatedCriterionNamesWithAConflictThatPointsAtTheRepeatedRow() throws Exception {
        List<Map<String, Object>> repeated = List.of(criterion(null, "Giao tiếp", null, 30),
                criterion(null, "Tư duy", null, 30), criterion(null, "  Giao tiếp ", null, 20),
                criterion(null, "Tư duy", null, 20));
        var response = create(payload("REPEAT", "Repeat", null, repeated), hrToken);
        JsonNode body = expect(response, 409);
        noStore(response);
        assertThat(body.path("code").asText()).isEqualTo("COMPETENCY_CRITERION_NAME_DUPLICATE");
        assertThat(body.path("message").asText()).isEqualTo("Tên tiêu chí trong một khung năng lực không được trùng nhau.");
        // Names are trimmed first, so the padded name repeats row 0; each repeated row is named.
        assertThat(body.path("fieldErrors").size()).isEqualTo(2);
        assertThat(body.path("fieldErrors").path("criteria[2].name").asText())
                .isEqualTo("Tên tiêu chí đã có ở dòng khác trong khung.");
        assertThat(body.path("fieldErrors").has("criteria[3].name")).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_frameworks", Integer.class)).isZero();

        UUID id = UUID.fromString(framework("KEEP", "Keep", criterion(null, "Giao tiếp", null, 100)).path("id").asText());
        var before = competencyRows();
        error(update(id, payload("KEEP", "Changed", null, repeated), hrToken), 409, "COMPETENCY_CRITERION_NAME_DUPLICATE");
        assertThat(competencyRows()).isEqualTo(before);
    }

    @Test
    void rejectsMissingBlankOversizedAndUnknownFrameworkFieldsButAcceptsLengthBoundaries() throws Exception {
        Map<String, Object> valid = payload("VALID", "Valid", null, List.of(criterion(null, "Giao tiếp", null, 100)));
        for (String field : List.of("code", "name", "criteria")) {
            Map<String, Object> missing = new LinkedHashMap<>(valid);
            missing.remove(field);
            fieldErrors(create(missing, hrToken), field);
            Map<String, Object> nullValue = new LinkedHashMap<>(valid);
            nullValue.put(field, null);
            fieldErrors(create(nullValue, hrToken), field);
        }
        for (String field : List.of("code", "name")) {
            int limit = field.equals("code") ? 50 : 255;
            for (String value : List.of("", " \t\n ", "x".repeat(limit + 1), "  " + "x".repeat(limit + 1) + "  ")) {
                Map<String, Object> invalid = new LinkedHashMap<>(valid);
                invalid.put(field, value);
                fieldErrors(create(invalid, hrToken), field);
            }
        }
        Map<String, Object> longDescription = new LinkedHashMap<>(valid);
        longDescription.put("description", "d".repeat(1001));
        assertThat(fieldErrors(create(longDescription, hrToken), "description").path("description").asText())
                .isEqualTo("Mô tả khung năng lực tối đa 1000 ký tự.");
        // Several broken fields are reported together, so a form can mark all of them in one round trip.
        fieldErrors(create(payload(" ", "", "d".repeat(1001), List.of()), hrToken), "code", "name", "description");

        // The id and timestamps of a framework are set by the server, so they are fields outside the contract.
        for (String field : List.of("id", "createdAt")) {
            Map<String, Object> unknown = new LinkedHashMap<>(valid);
            unknown.put(field, "x");
            error(create(unknown, hrToken), 400, "INVALID_JSON");
        }
        Map<String, Object> notAList = new LinkedHashMap<>(valid);
        notAList.put("criteria", "Giao tiếp");
        error(create(notAList, hrToken), 400, "INVALID_JSON");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_frameworks", Integer.class)).isZero();

        // Values are trimmed before the length check, so padding around a value at the limit is accepted.
        String description = "d".repeat(498) + "\n" + "d".repeat(501);
        JsonNode boundary = expect(create(payload("  " + "c".repeat(50) + "\t", " " + "n".repeat(255) + " ",
                " \n" + description + "\t ", List.of()), hrToken), 201);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM competency_frameworks WHERE id = ?",
                UUID.fromString(boundary.path("id").asText()));
        assertThat(row.get("code")).isEqualTo("c".repeat(50));
        assertThat(row.get("name")).isEqualTo("n".repeat(255));
        assertThat(row.get("description")).isEqualTo(description);
    }

    @Test
    void reportsEveryInvalidCriterionRowWithItsIndexAndLimitsTheListToFiftyRows() throws Exception {
        Map<String, Object> missingWeight = new LinkedHashMap<>();
        missingWeight.put("name", "Không có trọng số");
        List<Map<String, Object>> rows = new ArrayList<>(Arrays.asList(
                criterion(null, "Hợp lệ", null, 10),
                criterion(null, " \t ", null, 10),
                criterion(null, "x".repeat(256), null, 10),
                criterion(null, "Mô tả dài", "d".repeat(1001), 10),
                null,
                missingWeight));
        JsonNode errors = fieldErrors(create(payload("ROWS", "Rows", null, rows), hrToken),
                "criteria[1].name", "criteria[2].name", "criteria[3].description", "criteria[4]", "criteria[5].weight");
        assertThat(errors.path("criteria[1].name").asText()).isEqualTo("Tên tiêu chí không được để trống.");
        assertThat(errors.path("criteria[2].name").asText()).isEqualTo("Tên tiêu chí tối đa 255 ký tự.");
        assertThat(errors.path("criteria[3].description").asText()).isEqualTo("Mô tả tiêu chí tối đa 1000 ký tự.");
        assertThat(errors.path("criteria[4]").asText()).isEqualTo("Tiêu chí không được để trống.");
        assertThat(errors.path("criteria[5].weight").asText()).isEqualTo("Trọng số không được để trống.");

        // sortOrder is not a request field: the order of the list is the order of the criteria.
        Map<String, Object> withSortOrder = criterion(null, "Giao tiếp", null, 100);
        withSortOrder.put("sortOrder", 1);
        error(create(payload("ORDER", "Order", null, List.of(withSortOrder)), hrToken), 400, "INVALID_JSON");

        List<Map<String, Object>> fifty = new ArrayList<>();
        for (int index = 1; index <= 50; index++) {
            fifty.add(criterion(null, "Tiêu chí " + index, null, 2));
        }
        List<Map<String, Object>> fiftyOne = new ArrayList<>(fifty);
        fiftyOne.add(criterion(null, "Tiêu chí 51", null, 2));
        assertThat(fieldErrors(create(payload("TOO_MANY", "Too many", null, fiftyOne), hrToken), "criteria")
                .path("criteria").asText()).isEqualTo("Một khung năng lực có tối đa 50 tiêu chí.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_frameworks", Integer.class)).isZero();

        JsonNode full = expect(create(payload("FULL", "Full", null, fifty), hrToken), 201);
        assertThat(full.path("criteria").size()).isEqualTo(50);
        assertThat(full.path("criteria").path(49).path("sortOrder").asInt()).isEqualTo(50);
    }

    @Test
    void acceptsWeightsFromOneHundredthToOneHundredWithAtMostTwoDecimals() throws Exception {
        // Raw JSON text, so the test controls the exact number form the client sends.
        Map<String, String> messages = Map.of(
                "0", "Trọng số phải lớn hơn 0.",
                "-1", "Trọng số phải lớn hơn 0.",
                "100.01", "Trọng số không được vượt quá 100.",
                "33.335", "Trọng số là phần trăm có tối đa 2 chữ số thập phân.",
                "0.001", "Trọng số là phần trăm có tối đa 2 chữ số thập phân.",
                "null", "Trọng số không được để trống.");
        for (var entry : messages.entrySet()) {
            assertThat(fieldErrors(request("POST", BASE, weightJson(entry.getKey()), hrToken), "criteria[0].weight")
                    .path("criteria[0].weight").asText()).as(entry.getKey()).isEqualTo(entry.getValue());
        }
        // Far above the limit: rejected on the field (more than one rule fails, so the message is not fixed).
        fieldErrors(request("POST", BASE, weightJson("1000"), hrToken), "criteria[0].weight");
        // A weight must be a JSON number: text, booleans, objects and arrays are not converted.
        for (String notANumber : List.of("\"40\"", "true", "{}", "[40]")) {
            error(request("POST", BASE, weightJson(notANumber), hrToken), 400, "INVALID_JSON");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_frameworks", Integer.class)).isZero();

        // The smallest and largest weights, extra zeros and exponent notation are accepted and stored exactly.
        var response = request("POST", BASE, """
                {"code":"WEIGHTS","name":"Weights","criteria":[
                  {"name":"A","weight":0.01},{"name":"B","weight":100},{"name":"C","weight":40.000},
                  {"name":"D","weight":1e1},{"name":"E","weight":33.3}]}
                """, hrToken);
        JsonNode created = expect(response, 201);
        assertThat(response.body()).contains("\"weight\":0.01", "\"weight\":100.00", "\"weight\":40.00",
                "\"weight\":10.00", "\"weight\":33.30");
        assertThat(jdbc.queryForList("SELECT weight FROM competency_criteria WHERE framework_id = ? ORDER BY sort_order",
                BigDecimal.class, UUID.fromString(created.path("id").asText())))
                .containsExactly(new BigDecimal("0.01"), new BigDecimal("100.00"), new BigDecimal("40.00"),
                        new BigDecimal("10.00"), new BigDecimal("33.30"));
    }

    @Test
    void completeFrameworkNeedsWeightsThatTotalExactlyOneHundredWhileADraftMayBeIncomplete() throws Exception {
        // Three weights rounded to two decimals that add up to exactly 100.00 with BigDecimal.
        var thirds = create(withStatus(payload("THIRDS", "Ba tiêu chí", null, List.of(
                criterion(null, "Kỹ năng", null, new BigDecimal("33.33")),
                criterion(null, "Thái độ", null, new BigDecimal("33.33")),
                criterion(null, "Kinh nghiệm", null, new BigDecimal("33.34")))), "ACTIVE"), hrToken);
        JsonNode active = expect(thirds, 201);
        noStore(thirds);
        UUID id = UUID.fromString(active.path("id").asText());
        assertThat(active.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(thirds.body()).contains("\"weight\":33.33", "\"weight\":33.34");
        assertThat(jdbc.queryForObject("SELECT status FROM competency_frameworks WHERE id = ?", String.class, id))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT sum(weight) FROM competency_criteria WHERE framework_id = ?",
                BigDecimal.class, id)).isEqualTo(new BigDecimal("100.00"));
        assertThat(expect(get(BASE + "/" + id, hrToken), 200)).isEqualTo(active);
        // A single criterion of 100% is a complete framework too.
        assertThat(expect(create(withStatus(payload("SINGLE", "Single", null,
                List.of(criterion(null, "Giao tiếp", null, 100))), "ACTIVE"), hrToken), 201).path("status").asText())
                .isEqualTo("ACTIVE");

        var before = competencyRows();
        // One hundredth short, one hundredth over, and no criteria at all: each is refused and nothing is saved.
        weightTotalError(create(withStatus(payload("SHORT", "Short", null, List.of(
                criterion(null, "Kỹ năng", null, new BigDecimal("33.33")),
                criterion(null, "Thái độ", null, new BigDecimal("33.33")),
                criterion(null, "Kinh nghiệm", null, new BigDecimal("33.33")))), "ACTIVE"), hrToken), "99.99");
        weightTotalError(create(withStatus(payload("OVER", "Over", null, List.of(
                criterion(null, "Kỹ năng", null, 60), criterion(null, "Thái độ", null, new BigDecimal("40.01")))),
                "ACTIVE"), hrToken), "100.01");
        weightTotalError(create(withStatus(payload("EMPTY", "Empty", null, List.of()), "ACTIVE"), hrToken), "0.00");
        // Exponent and extra zeros count with their real value: 1e1 + 50.000 is 60.
        weightTotalError(request("POST", BASE, """
                {"code":"RAW","name":"Raw","status":"ACTIVE","criteria":[
                  {"name":"A","weight":1e1},{"name":"B","weight":50.000}]}
                """, hrToken), "60.00");
        // A third decimal is refused on its own row first, so 33.335 + 66.665 is never rounded into 100.
        fieldErrors(request("POST", BASE, """
                {"code":"ROUND","name":"Round","status":"ACTIVE","criteria":[
                  {"name":"A","weight":33.335},{"name":"B","weight":66.665}]}
                """, hrToken), "criteria[0].weight", "criteria[1].weight");
        assertThat(competencyRows()).isEqualTo(before);
        expect(request("POST", BASE, """
                {"code":"RAW","name":"Raw","status":"ACTIVE","criteria":[
                  {"name":"A","weight":5e1},{"name":"B","weight":50.000}]}
                """, hrToken), 201);

        // A DRAFT may be incomplete or over 100%, whether the status is sent or left out.
        JsonNode draft = expect(create(withStatus(payload("DRAFT_SHORT", "Draft short", null, List.of(
                criterion(null, "Kỹ năng", null, new BigDecimal("33.33")))), "DRAFT"), hrToken), 201);
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        JsonNode nullStatus = expect(create(withStatus(payload("DRAFT_OVER", "Draft over", null, List.of(
                criterion(null, "Kỹ năng", null, 80), criterion(null, "Thái độ", null, 70))), null), hrToken), 201);
        assertThat(nullStatus.path("status").asText()).isEqualTo("DRAFT");
        assertThat(expect(create(payload("DRAFT_EMPTY", "Draft empty", null, List.of()), hrToken), 201)
                .path("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void draftBecomesActiveOnlyWithCompleteWeightsAndAnActiveFrameworkMustStayComplete() throws Exception {
        JsonNode created = framework("DEV_CORE", "Năng lực lập trình viên", criterion(null, "Giao tiếp", null, 30),
                criterion(null, "Tư duy", null, 30));
        UUID id = UUID.fromString(created.path("id").asText());
        UUID communication = UUID.fromString(ids(created.path("criteria")).get(0));
        UUID thinking = UUID.fromString(ids(created.path("criteria")).get(1));
        clock.set(START.plusSeconds(60));

        // Activating with the current 60% is refused, and the whole edit (here the new name) is left out.
        var before = competencyRows();
        weightTotalError(update(id, withStatus(payload("DEV_CORE", "Đổi tên", null, List.of(
                criterion(communication, "Giao tiếp", null, 30), criterion(thinking, "Tư duy", null, 30))),
                "ACTIVE"), hrToken), "60.00");
        assertThat(competencyRows()).isEqualTo(before);

        // The same edit with weights that total 100 activates the framework and keeps both criterion ids.
        var activation = update(id, withStatus(payload("DEV_CORE", "Năng lực lập trình viên", null, List.of(
                criterion(communication, "Giao tiếp", null, new BigDecimal("50.5")),
                criterion(thinking, "Tư duy", null, new BigDecimal("49.5")))), "ACTIVE"), hrToken);
        JsonNode activated = expect(activation, 200);
        noStore(activation);
        assertThat(activated.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(ids(activated.path("criteria"))).containsExactly(communication.toString(), thinking.toString());
        assertThat(Instant.parse(activated.path("updatedAt").asText())).isEqualTo(START.plusSeconds(60));
        assertThat(expect(get(BASE + "/" + id, hrToken), 200)).isEqualTo(activated);
        assertThat(ids(expect(get(BASE + "?status=ACTIVE", hrToken), 200).path("items")))
                .containsExactly(id.toString());

        // Without a status an edit keeps ACTIVE, so it must keep the total at 100 as well.
        before = competencyRows();
        weightTotalError(update(id, payload("DEV_CORE", "Năng lực lập trình viên", null, List.of(
                criterion(communication, "Giao tiếp", null, 50), criterion(thinking, "Tư duy", null, 40))),
                hrToken), "90.00");
        weightTotalError(update(id, payload("DEV_CORE", "Năng lực lập trình viên", null, List.of(
                criterion(communication, "Giao tiếp", null, 50), criterion(thinking, "Tư duy", null, 50),
                criterion(null, "Học hỏi", null, 10))), hrToken), "110.00");
        weightTotalError(update(id, payload("DEV_CORE", "Năng lực lập trình viên", null, List.of()), hrToken), "0.00");
        // ACTIVE never goes back to DRAFT, even with weights that total 100.
        for (int weight : List.of(50, 40)) {
            var demotion = update(id, withStatus(payload("DEV_CORE", "Năng lực lập trình viên", null, List.of(
                    criterion(communication, "Giao tiếp", null, 50), criterion(thinking, "Tư duy", null, weight))),
                    "DRAFT"), hrToken);
            JsonNode body = expect(demotion, 409);
            noStore(demotion);
            assertThat(body.path("code").asText()).isEqualTo("COMPETENCY_FRAMEWORK_ALREADY_ACTIVE");
            assertThat(body.path("message").asText())
                    .isEqualTo("Khung năng lực đã hoàn chỉnh (ACTIVE) không thể chuyển lại thành bản nháp (DRAFT).");
            assertThat(body.path("fieldErrors").path("status").asText()).isNotBlank();
        }
        assertThat(competencyRows()).isEqualTo(before);

        // Rebalancing in one edit (a new criterion, a removed one) is fine while the total stays 100.
        JsonNode rebalanced = expect(update(id, payload("DEV_CORE", "Năng lực lập trình viên", null, List.of(
                criterion(thinking, "Tư duy", null, new BigDecimal("33.34")),
                criterion(null, "Học hỏi", null, new BigDecimal("33.33")),
                criterion(null, "Làm việc nhóm", null, new BigDecimal("33.33")))), hrToken), 200);
        assertThat(rebalanced.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(names(rebalanced.path("criteria"))).containsExactly("Tư duy", "Học hỏi", "Làm việc nhóm");
        // Sending ACTIVE again is the same as leaving the status out.
        assertThat(expect(update(id, withStatus(payload("DEV_CORE", "Năng lực lập trình viên", null, List.of(
                criterion(thinking, "Tư duy", null, 100))), "ACTIVE"), hrToken), 200).path("status").asText())
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForMap("SELECT status, (SELECT sum(weight) FROM competency_criteria "
                + "WHERE framework_id = ?) AS total FROM competency_frameworks WHERE id = ?", id, id))
                .isEqualTo(Map.of("status", "ACTIVE", "total", new BigDecimal("100.00")));
    }

    @Test
    void acceptsOnlyTheExactStatusNamesAndTreatsNullStatusAsLeftOut() throws Exception {
        Map<String, Object> valid = payload("STATUS", "Status", null, List.of(criterion(null, "Giao tiếp", null, 40)));
        // Only the exact names are read. A number is refused too: Jackson alone would read 1 as ACTIVE.
        for (Object status : List.of("active", "Draft", " ACTIVE", "UNKNOWN", "", 1, 0, true, List.of("ACTIVE"),
                Map.of())) {
            assertThat(expect(create(withStatus(valid, status), hrToken), 400).path("code").asText())
                    .as(String.valueOf(status)).isEqualTo("INVALID_JSON");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_frameworks", Integer.class)).isZero();

        // JSON null means "no status": DRAFT for a new framework, the current status for an edit.
        UUID id = UUID.fromString(expect(create(withStatus(valid, null), hrToken), 201).path("id").asText());
        assertThat(expect(update(id, withStatus(payload("STATUS", "Status", null, List.of(
                criterion(null, "Giao tiếp", null, 100))), "ACTIVE"), hrToken), 200).path("status").asText())
                .isEqualTo("ACTIVE");
        JsonNode kept = expect(update(id, withStatus(payload("STATUS", "Renamed", null, List.of(
                criterion(null, "Tư duy", null, 100))), null), hrToken), 200);
        assertThat(kept.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(kept.path("name").asText()).isEqualTo("Renamed");
    }

    @Test
    void codesAreCaseSensitiveUniqueAndADuplicateUpdateLeavesTheRowsUnchanged() throws Exception {
        UUID upper = UUID.fromString(framework("HR", "HR", criterion(null, "Giao tiếp", null, 100)).path("id").asText());
        UUID lower = UUID.fromString(framework("hr", "Lowercase", criterion(null, "Giao tiếp", null, 100))
                .path("id").asText());
        var duplicate = create(payload("HR", "Duplicate", null, List.of()), hrToken);
        error(duplicate, 409, "COMPETENCY_FRAMEWORK_CODE_EXISTS");
        noStore(duplicate);
        error(create(payload("  HR  ", "Padded duplicate", null, List.of()), hrToken), 409,
                "COMPETENCY_FRAMEWORK_CODE_EXISTS");

        var before = competencyRows();
        error(update(lower, payload("HR", "Duplicate update", null, List.of()), hrToken), 409,
                "COMPETENCY_FRAMEWORK_CODE_EXISTS");
        // The whole edit is refused, including the criteria list it would have emptied.
        assertThat(competencyRows()).isEqualTo(before);

        // Keeping its own code is not a conflict.
        assertThat(expect(update(upper, payload("HR", "Human resources", null, List.of()), hrToken), 200)
                .path("name").asText()).isEqualTo("Human resources");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_frameworks", Integer.class)).isEqualTo(2);
    }

    @Test
    void searchesCodeAndNameCaseInsensitivelyTreatingSqlWildcardsLiterally() throws Exception {
        String exact = framework("PCT_%!", "Unique wording").path("id").asText();
        String decoy = framework("PCTAB", "Decoy").path("id").asText();
        String other = framework("NORMAL", "Other name").path("id").asText();
        jdbc.update("UPDATE competency_frameworks SET description = 'pct_ unique' WHERE id = ?", UUID.fromString(other));
        for (String query : List.of("pct_%!", "UNIQUE WORD", "%", "_", "!", "pct_")) {
            JsonNode page = expect(get(BASE + "?q=" + encode(query), hrToken), 200);
            assertThat(page.path("totalElements").asInt()).as(query).isEqualTo(1);
            assertThat(ids(page.path("items"))).as(query).containsExactly(exact);
        }
        assertThat(ids(expect(get(BASE + "?q=" + encode("decoy"), hrToken), 200).path("items"))).containsExactly(decoy);
        // The description is not searched, and a quote cannot change the SQL.
        assertThat(expect(get(BASE + "?q=" + encode("' OR 1=1 --"), hrToken), 200).path("totalElements").asInt()).isZero();
        assertThat(expect(get(BASE + "?q=" + encode("   "), hrToken), 200).path("totalElements").asInt()).isEqualTo(3);

        // The decoy is activated through the API, which needs a criteria list that totals 100%.
        expect(update(decoy, withStatus(payload("PCTAB", "Decoy", null, List.of(criterion(null, "Giao tiếp", null, 100))),
                "ACTIVE"), hrToken), 200);
        JsonNode active = expect(get(BASE + "?status=ACTIVE", hrToken), 200);
        assertThat(ids(active.path("items"))).containsExactly(decoy);
        assertThat(active.path("items").path(0).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(ids(expect(get(BASE + "?status=DRAFT", hrToken), 200).path("items"))).containsExactly(other, exact);
        assertThat(ids(expect(get(BASE + "?q=pct&status=DRAFT", hrToken), 200).path("items"))).containsExactly(exact);
    }

    @Test
    void paginationReturnsStablePagesOrderedByCodeWithCriterionCounts() throws Exception {
        List<String> expected = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int number = 1; number <= index; number++) {
                rows.add(criterion(null, "Tiêu chí " + number, null, 10));
            }
            // Created in reverse code order, so the list order must come from the code, not the insert order.
            expected.add(0, expect(create(payload("F" + (4 - index), "Framework", null, rows), hrToken), 201)
                    .path("id").asText());
        }
        List<String> seen = new ArrayList<>();
        List<Long> counts = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            var response = get(BASE + "?page=" + page + "&size=2", hrToken);
            JsonNode result = expect(response, 200);
            noStore(response);
            assertThat(result.size()).isEqualTo(5);
            assertThat(result.path("page").asInt()).isEqualTo(page);
            assertThat(result.path("size").asInt()).isEqualTo(2);
            assertThat(result.path("totalElements").asInt()).isEqualTo(5);
            assertThat(result.path("totalPages").asInt()).isEqualTo(3);
            seen.addAll(ids(result.path("items")));
            result.path("items").forEach(item -> counts.add(item.path("criterionCount").asLong()));
        }
        assertThat(seen).containsExactlyElementsOf(expected);
        // F0 was created last with four criteria, F4 first with none.
        assertThat(counts).containsExactly(4L, 3L, 2L, 1L, 0L);
        JsonNode beyond = expect(get(BASE + "?page=10&size=2", hrToken), 200);
        assertThat(beyond.path("items").isEmpty()).isTrue();
        assertThat(beyond.path("totalElements").asInt()).isEqualTo(5);
    }

    @Test
    void validatesListArgumentsAndReturnsNotFoundForUnknownFrameworks() throws Exception {
        for (String query : List.of("page=-1", "size=0", "size=101", "status=UNKNOWN", "status=draft", "page=abc",
                "q=" + "x".repeat(256))) {
            error(get(BASE + "?" + query, hrToken), 400, "VALIDATION_ERROR");
        }
        expect(get(BASE + "?size=100&q=" + "x".repeat(255), hrToken), 200);

        var missing = get(BASE + "/" + UUID.randomUUID(), hrToken);
        error(missing, 404, "COMPETENCY_FRAMEWORK_NOT_FOUND");
        noStore(missing);
        error(update(UUID.randomUUID(), payload("NONE", "None", null, List.of(criterion(null, "Giao tiếp", null, 100))),
                hrToken), 404, "COMPETENCY_FRAMEWORK_NOT_FOUND");
        error(get(BASE + "/not-a-uuid", hrToken), 400, "VALIDATION_ERROR");
        error(update("not-a-uuid", payload("NONE", "None", null, List.of()), hrToken), 400, "VALIDATION_ERROR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_frameworks", Integer.class)).isZero();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void everyInternalRoleReadsFrameworksButOnlyOrganizationWritersChangeThem(Role role) throws Exception {
        account("role@example.test", Set.of(role));
        String token = login("role@example.test").path("accessToken").asText();
        JsonNode target = framework("READ", "Read", criterion(null, "Giao tiếp", null, 100));
        UUID id = UUID.fromString(target.path("id").asText());
        // V3 grants ORGANIZATION_WRITE_ALL to ADMIN and HR_MANAGER only.
        boolean writer = role == Role.ADMIN || role == Role.HR_MANAGER;

        // Interviewers read the criteria too: they score candidates with them.
        assertThat(ids(expect(get(BASE, token), 200).path("items"))).containsExactly(id.toString());
        assertThat(expect(get(BASE + "/" + id, token), 200)).isEqualTo(target);

        var before = competencyRows();
        UUID kept = UUID.fromString(ids(target.path("criteria")).get(0));
        var created = create(payload("NEW", "New", null, List.of(criterion(null, "Tư duy", null, 100))), token);
        var updated = update(id, payload("READ", "Updated", null, List.of(criterion(kept, "Giao tiếp", null, 50))), token);
        // Without write permission the data rules are never reached: the answer is 403, not 400 or 409.
        var invalid = update(id, payload("READ", "Invalid", null, List.of(criterion(UUID.randomUUID(), "X", null, 1))),
                token);
        var incomplete = create(withStatus(payload("INCOMPLETE", "Incomplete", null,
                List.of(criterion(null, "Tư duy", null, 50))), "ACTIVE"), token);
        if (writer) {
            assertThat(expect(created, 201).path("code").asText()).isEqualTo("NEW");
            JsonNode result = expect(updated, 200);
            assertThat(result.path("name").asText()).isEqualTo("Updated");
            assertThat(ids(result.path("criteria"))).containsExactly(kept.toString());
            error(invalid, 400, "INVALID_COMPETENCY_CRITERION");
            weightTotalError(incomplete, "50.00");
        } else {
            error(created, 403, "FORBIDDEN");
            error(updated, 403, "FORBIDDEN");
            error(invalid, 403, "FORBIDDEN");
            error(incomplete, 403, "FORBIDDEN");
            assertThat(competencyRows()).isEqualTo(before);
        }
    }

    @Test
    void accountWithoutRolesCannotReadFrameworksAndNobodyCanDeleteThem() throws Exception {
        UUID id = UUID.fromString(framework("READ", "Read", criterion(null, "Giao tiếp", null, 100)).path("id").asText());
        account("none@example.test", Set.of());
        String token = login("none@example.test").path("accessToken").asText();
        error(get(BASE, token), 403, "FORBIDDEN");
        error(get(BASE + "/" + id, token), 403, "FORBIDDEN");

        // There is no DELETE API, so the default deny rule refuses it even for the HR manager.
        var before = competencyRows();
        error(request("DELETE", BASE + "/" + id, null, hrToken), 403, "FORBIDDEN");
        assertThat(competencyRows()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update"})
    void codeCommittedByAnotherTransactionWhileTheWriteWaitsReturnsConflict(String operation) throws Exception {
        UUID existing = UUID.fromString(framework("OLD", "Old", criterion(null, "Giao tiếp", null, 100))
                .path("id").asText());
        var before = jdbc.queryForList("SELECT * FROM competency_criteria ORDER BY id");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Uncommitted, so the API's existence check misses it and only the unique constraint can stop the write.
            execute(connection, """
                    INSERT INTO competency_frameworks (id, code, name, status, created_at, updated_at)
                    VALUES (?, 'SAME', 'Other transaction', 'DRAFT', ?, ?)
                    """, UUID.randomUUID(), Timestamp.from(START), Timestamp.from(START));
            int blockerPid = backendPid(connection);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> operation.equals("create")
                        ? create(payload("SAME", "Second", null, List.of(criterion(null, "Tư duy", null, 100))), hrToken)
                        : update(existing, payload("SAME", "Renamed", null, List.of()), hrToken));
                try {
                    awaitWaiters(blockerPid);
                    connection.commit();
                    error(response.get(10, TimeUnit.SECONDS), 409, "COMPETENCY_FRAMEWORK_CODE_EXISTS");
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_frameworks WHERE code = 'SAME'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT code FROM competency_frameworks WHERE id = ?", String.class, existing))
                .isEqualTo("OLD");
        // The refused edit did not empty the criteria list, and the refused create left no criteria behind.
        assertThat(jdbc.queryForList("SELECT * FROM competency_criteria ORDER BY id")).isEqualTo(before);
    }

    @Test
    void anEditThatWaitsForTheFrameworkLockSeesTheCriteriaCommittedMeanwhile() throws Exception {
        JsonNode created = framework("LOCKED", "Locked", criterion(null, "Giao tiếp", null, 50),
                criterion(null, "Tư duy", null, 50));
        UUID id = UUID.fromString(created.path("id").asText());
        UUID communication = UUID.fromString(ids(created.path("criteria")).get(0));
        UUID thinking = UUID.fromString(ids(created.path("criteria")).get(1));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Plays another edit of the same framework: it holds the framework lock and removes "Tư duy".
            try (var statement = connection.prepareStatement(
                    "SELECT id FROM competency_frameworks WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, id);
                try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); }
            }
            execute(connection, "DELETE FROM competency_criteria WHERE id = ?", thinking);
            int blockerPid = backendPid(connection);
            try (var executor = Executors.newSingleThreadExecutor()) {
                // This edit was prepared from the old page and still sends "Tư duy" by id.
                var response = executor.submit(() -> update(id, payload("LOCKED", "Changed", null, List.of(
                        criterion(communication, "Giao tiếp", null, 40), criterion(thinking, "Tư duy", null, 60))),
                        hrToken));
                try {
                    awaitWaiters(blockerPid);
                    assertThat(response.isDone()).isFalse();
                    connection.commit();
                    // The criteria are read after the lock, so the edit sees the deletion and refuses the stale id
                    // instead of failing with a 500 on a row that no longer exists.
                    JsonNode body = expect(response.get(10, TimeUnit.SECONDS), 400);
                    assertThat(body.path("code").asText()).isEqualTo("INVALID_COMPETENCY_CRITERION");
                    assertThat(body.path("fieldErrors").has("criteria[1].id")).isTrue();
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(jdbc.queryForObject("SELECT name FROM competency_frameworks WHERE id = ?", String.class, id))
                .isEqualTo("Locked");
        assertThat(jdbc.queryForList("SELECT id FROM competency_criteria WHERE framework_id = ?", UUID.class, id))
                .containsExactly(communication);
    }

    @Test
    void anEditThatWaitsForTheFrameworkLockSeesAnActivationCommittedMeanwhileAndMustKeepOneHundred() throws Exception {
        JsonNode created = framework("RACE", "Race", criterion(null, "Giao tiếp", null, 50),
                criterion(null, "Tư duy", null, 50));
        UUID id = UUID.fromString(created.path("id").asText());
        UUID communication = UUID.fromString(ids(created.path("criteria")).get(0));
        UUID thinking = UUID.fromString(ids(created.path("criteria")).get(1));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Plays another edit that holds the framework lock and activates the framework.
            try (var statement = connection.prepareStatement(
                    "SELECT id FROM competency_frameworks WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, id);
                try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); }
            }
            execute(connection, "UPDATE competency_frameworks SET status = 'ACTIVE' WHERE id = ?", id);
            int blockerPid = backendPid(connection);
            try (var executor = Executors.newSingleThreadExecutor()) {
                // This edit was prepared while the framework was a DRAFT: no status and only 90% in total.
                var response = executor.submit(() -> update(id, payload("RACE", "Changed", null, List.of(
                        criterion(communication, "Giao tiếp", null, 50), criterion(thinking, "Tư duy", null, 40))),
                        hrToken));
                try {
                    awaitWaiters(blockerPid);
                    assertThat(response.isDone()).isFalse();
                    connection.commit();
                    // The status is read after the lock, so the edit now keeps ACTIVE and needs 100%.
                    weightTotalError(response.get(10, TimeUnit.SECONDS), "90.00");
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(jdbc.queryForMap("SELECT name, status FROM competency_frameworks WHERE id = ?", id))
                .isEqualTo(Map.of("name", "Race", "status", "ACTIVE"));
        assertThat(jdbc.queryForList("SELECT weight FROM competency_criteria WHERE framework_id = ? ORDER BY sort_order",
                BigDecimal.class, id)).containsExactly(new BigDecimal("50.00"), new BigDecimal("50.00"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lost-organization-permission", "expired-jwt", "locked-actor", "revoked-session"})
    void rechecksAccessAfterWaitingForTheActorAccountLock(String change) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM user_accounts WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, hrId);
                try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); }
            }
            int blockerPid = backendPid(connection);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> create(payload("DENIED", "Denied", null,
                        List.of(criterion(null, "Giao tiếp", null, 100))), hrToken));
                try {
                    awaitWaiters(blockerPid);
                    switch (change) {
                        case "lost-organization-permission" -> jdbc.update("DELETE FROM role_permissions "
                                + "WHERE role_code = 'HR_MANAGER' AND permission_code = 'ORGANIZATION_WRITE_ALL'");
                        case "expired-jwt" -> clock.set(START.plus(Duration.ofMinutes(15)));
                        // The bootstrap admin is outside the request, so referencing it causes no lock interference.
                        case "locked-actor" -> execute(connection, "UPDATE user_accounts SET admin_locked_at = ?, "
                                + "admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                                Timestamp.from(START), adminId, hrId);
                        default -> execute(connection, "UPDATE auth_sessions SET revoked_at = ? WHERE user_id = ?",
                                Timestamp.from(START), hrId);
                    }
                    connection.commit();
                    var result = response.get(10, TimeUnit.SECONDS);
                    if (change.startsWith("lost-")) {
                        error(result, 403, "FORBIDDEN");
                    } else {
                        error(result, 401, "SESSION_INVALID");
                    }
                } finally {
                    connection.rollback();
                }
            }
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', "
                    + "'ORGANIZATION_WRITE_ALL') ON CONFLICT DO NOTHING");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_frameworks", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_criteria", Integer.class)).isZero();
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Competency test", fixturePasswordHash, roles, START)).getId();
    }

    @SafeVarargs
    private JsonNode framework(String code, String name, Map<String, Object>... criteria) throws Exception {
        return expect(create(payload(code, name, null, List.of(criteria)), hrToken), 201);
    }

    private static Map<String, Object> payload(String code, String name, String description,
                                               List<Map<String, Object>> criteria) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("name", name);
        result.put("description", description);
        result.put("criteria", criteria);
        return result;
    }

    // payload(...) leaves the status out; this copy sends one, which may also be null or a wrong JSON value.
    private static Map<String, Object> withStatus(Map<String, Object> payload, Object status) {
        Map<String, Object> result = new LinkedHashMap<>(payload);
        result.put("status", status);
        return result;
    }

    // The id is left out for a new criterion; null description and weight are sent as JSON null.
    private static Map<String, Object> criterion(UUID id, String name, String description, Object weight) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (id != null) {
            result.put("id", id);
        }
        result.put("name", name);
        result.put("description", description);
        result.put("weight", weight);
        return result;
    }

    private static String weightJson(String weight) {
        return """
                {"code":"WEIGHT","name":"Weight","criteria":[{"name":"Giao tiếp","weight":%s}]}
                """.formatted(weight);
    }

    private Map<String, List<Map<String, Object>>> competencyRows() {
        return Map.of("frameworks", jdbc.queryForList("SELECT * FROM competency_frameworks ORDER BY id"),
                "criteria", jdbc.queryForList("SELECT * FROM competency_criteria ORDER BY id"));
    }

    private JsonNode login(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200);
    }

    private HttpResponse<String> create(Map<String, Object> payload, String token) throws Exception {
        return request("POST", BASE, json.writeValueAsString(payload), token);
    }

    private HttpResponse<String> update(Object id, Map<String, Object> payload, String token) throws Exception {
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

    // Jira 213: the refusal of a complete framework whose weights do not total 100; total is shown like "99.99".
    private void weightTotalError(HttpResponse<String> response, String total) {
        JsonNode body = expect(response, 400);
        noStore(response);
        assertThat(body.path("code").asText()).isEqualTo("COMPETENCY_FRAMEWORK_WEIGHT_TOTAL_INVALID");
        assertThat(body.path("message").asText()).isEqualTo("Khung năng lực hoàn chỉnh (ACTIVE) cần tổng trọng số "
                + "các tiêu chí đúng 100%; tổng hiện tại là " + total + "%.");
        assertThat(body.path("fieldErrors").size()).isEqualTo(1);
        assertThat(body.path("fieldErrors").path("criteria").asText())
                .isEqualTo("Tổng trọng số hiện tại là " + total + "%, cần đúng 100%.");
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

    private List<String> names(JsonNode array) {
        List<String> names = new ArrayList<>();
        array.forEach(item -> names.add(item.path("name").asText()));
        return names;
    }

    private List<Integer> sortOrders(JsonNode array) {
        List<Integer> orders = new ArrayList<>();
        array.forEach(item -> orders.add(item.path("sortOrder").asInt()));
        return orders;
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

    // Polls from a separate connection until an HTTP request waits for a lock held by the blocker.
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
        assertThat(waiting).as("the HTTP request must wait for the blocker's lock").isTrue();
    }
}
