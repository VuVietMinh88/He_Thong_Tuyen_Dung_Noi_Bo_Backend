package vn.ttcs.recruitment.auth;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
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
import vn.ttcs.recruitment.competency.CompetencyCriterionView;
import vn.ttcs.recruitment.competency.EvaluationCriteria;
import vn.ttcs.recruitment.competency.EvaluationCriteriaService;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

// Jira 215: the weighted criteria of the competency framework a position uses. Interviewers read them over HTTP;
// the later evaluation form module (Sprint 6) calls EvaluationCriteriaService.forPosition directly.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PositionEvaluationCriteriaIntegrationTest {
    private static final String POSITIONS = "/api/v1/positions";
    private static final String FRAMEWORKS = "/api/v1/competency-frameworks";
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-07T00:00:00Z");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JwtDecoder jwtDecoder;
    @Autowired private EvaluationCriteriaService evaluationCriteria;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    // Only HR_MANAGER may create positions (salary band permission), so the HR manager writes the fixtures.
    private String hrToken;
    // The interviewer is the main reader: interviewers score candidates with these criteria.
    private String interviewerToken;
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
        // Positions first: a framework still used by a position cannot be deleted (ON DELETE RESTRICT).
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM competency_frameworks");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        JsonNode login = login("admin@example.test");
        UUID adminId = UUID.fromString(login.path("user").path("id").asText());
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        account("hr@example.test", Set.of(Role.HR_MANAGER));
        hrToken = login("hr@example.test").path("accessToken").asText();
        account("interviewer@example.test", Set.of(Role.INTERVIEWER));
        interviewerToken = login("interviewer@example.test").path("accessToken").asText();
    }

    @Test
    void everyPositionUsingAFrameworkGetsItsOrderedCriteriaAndWeightsWithoutTheSalaryBand() throws Exception {
        JsonNode framework = activeFramework("DEV_CORE",
                criterion(null, "Kỹ năng lập trình", "Viết mã đúng và dễ đọc", new BigDecimal("33.33")),
                criterion(null, "Thiết kế hệ thống", null, new BigDecimal("33.33")),
                criterion(null, "Làm việc nhóm", null, new BigDecimal("33.34")));
        UUID frameworkId = id(framework);
        UUID junior = position("DEV_JUNIOR", "Lập trình viên", "Junior");
        UUID senior = position("DEV_SENIOR", "Lập trình viên", "Senior");
        UUID tester = position("QA", "Kiểm thử viên", "Junior");
        expect(assign(junior, frameworkId), 200);
        expect(assign(senior, frameworkId), 200);
        UUID testerFramework = id(activeFramework("QA_CORE", criterion(null, "Tỉ mỉ", null, 100)));
        expect(assign(tester, testerFramework), 200);

        var response = get(criteriaPath(junior), interviewerToken);
        JsonNode result = expect(response, 200);
        noStore(response);
        assertThat(result.size()).isEqualTo(3);

        JsonNode position = result.path("position");
        assertThat(position.size()).isEqualTo(5);
        assertThat(position.path("id").asText()).isEqualTo(junior.toString());
        assertThat(position.path("code").asText()).isEqualTo("DEV_JUNIOR");
        assertThat(position.path("name").asText()).isEqualTo("Lập trình viên");
        assertThat(position.path("level").asText()).isEqualTo("Junior");
        assertThat(position.path("active").asBoolean()).isTrue();

        JsonNode frameworkView = result.path("framework");
        assertThat(frameworkView.size()).isEqualTo(3);
        assertThat(frameworkView.path("id").asText()).isEqualTo(frameworkId.toString());
        assertThat(frameworkView.path("code").asText()).isEqualTo("DEV_CORE");
        assertThat(frameworkView.path("name").asText()).isEqualTo("Khung DEV_CORE");

        JsonNode criteria = result.path("criteria");
        assertThat(ids(criteria)).containsExactlyElementsOf(ids(framework.path("criteria")));
        assertThat(texts(criteria, "name")).containsExactly("Kỹ năng lập trình", "Thiết kế hệ thống", "Làm việc nhóm");
        assertThat(texts(criteria, "sortOrder")).containsExactly("1", "2", "3");
        assertThat(criteria.path(0).size()).isEqualTo(5);
        assertThat(criteria.path(0).path("description").asText()).isEqualTo("Viết mã đúng và dễ đọc");
        assertThat(criteria.path(1).path("description").isNull()).isTrue();
        // Two decimals, exactly as stored, and the weights of the complete framework total 100%.
        assertThat(response.body()).contains("\"weight\":33.33", "\"weight\":33.34").doesNotContain("salary");
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode criterion : criteria) {
            total = total.add(criterion.path("weight").decimalValue());
        }
        assertThat(total).isEqualByComparingTo("100");
        // The same criterion rows as the framework detail shows: nothing was copied for the position.
        assertThat(criteria).isEqualTo(expect(get(FRAMEWORKS + "/" + frameworkId, hrToken), 200).path("criteria"));

        // The other position sharing the framework gets the very same criteria.
        JsonNode seniorResult = expect(get(criteriaPath(senior), interviewerToken), 200);
        assertThat(seniorResult.path("position").path("level").asText()).isEqualTo("Senior");
        assertThat(seniorResult.path("framework")).isEqualTo(frameworkView);
        assertThat(seniorResult.path("criteria")).isEqualTo(criteria);

        // A position with another framework gets only the criteria of that framework.
        var testerResponse = get(criteriaPath(tester), interviewerToken);
        JsonNode testerResult = expect(testerResponse, 200);
        assertThat(testerResult.path("framework").path("id").asText()).isEqualTo(testerFramework.toString());
        assertThat(texts(testerResult.path("criteria"), "name")).containsExactly("Tỉ mỉ");
        assertThat(testerResponse.body()).contains("\"weight\":100.00");

        // The HR manager may see salaries elsewhere (GET /positions/{id}) but gets none here either.
        var hrResponse = get(criteriaPath(junior), hrToken);
        assertThat(expect(hrResponse, 200)).isEqualTo(result);
        assertThat(hrResponse.body()).doesNotContain("salary");
    }

    @Test
    void aFrameworkEditReachesTheEvaluationCriteriaOfItsPositionsAtOnce() throws Exception {
        JsonNode framework = activeFramework("CORE", criterion(null, "Giao tiếp", null, 60),
                criterion(null, "Tư duy", null, 40));
        UUID frameworkId = id(framework);
        UUID position = position("DEV", "Developer", "Junior");
        expect(assign(position, frameworkId), 200);
        List<String> criterionIds = ids(framework.path("criteria"));

        // "Tư duy" is renamed and moved first, "Giao tiếp" is dropped and "Học hỏi" is new; still 100% in total.
        expect(request("PUT", FRAMEWORKS + "/" + frameworkId, json.writeValueAsString(payload("CORE", List.of(
                criterion(UUID.fromString(criterionIds.get(1)), "Tư duy logic", null, 50),
                criterion(null, "Học hỏi", null, 50)), null)), hrToken), 200);

        var response = get(criteriaPath(position), interviewerToken);
        JsonNode criteria = expect(response, 200).path("criteria");
        assertThat(texts(criteria, "name")).containsExactly("Tư duy logic", "Học hỏi");
        assertThat(texts(criteria, "sortOrder")).containsExactly("1", "2");
        assertThat(response.body()).contains("\"weight\":50.00").doesNotContain("\"weight\":40.00");
        // The kept criterion keeps its id, so whatever refers to it (later evaluation forms) still finds it.
        assertThat(ids(criteria).getFirst()).isEqualTo(criterionIds.get(1));
        assertThat(ids(criteria)).doesNotContain(criterionIds.get(0));
    }

    @Test
    void aPositionWithoutAFrameworkHasNoEvaluationCriteriaYet() throws Exception {
        UUID position = position("DEV", "Developer", "Junior");

        var response = get(criteriaPath(position), interviewerToken);
        JsonNode error = expect(response, 409);
        noStore(response);
        assertThat(error.path("code").asText()).isEqualTo("POSITION_COMPETENCY_FRAMEWORK_NOT_ASSIGNED");
        assertThat(error.path("message").asText())
                .isEqualTo("Chức danh chưa được gán khung năng lực nên chưa có tiêu chí đánh giá.");
        assertThat(error.path("fieldErrors").isEmpty()).isTrue();

        // Once HR assigns a framework the same request succeeds...
        UUID frameworkId = id(activeFramework("CORE", criterion(null, "Giao tiếp", null, 100)));
        expect(assign(position, frameworkId), 200);
        assertThat(expect(get(criteriaPath(position), interviewerToken), 200).path("framework").path("id").asText())
                .isEqualTo(frameworkId.toString());

        // ...and after HR removes it again the position has no criteria, while the framework itself stays.
        expect(request("DELETE", POSITIONS + "/" + position + "/competency-framework", null, hrToken), 200);
        error(get(criteriaPath(position), interviewerToken), 409, "POSITION_COMPETENCY_FRAMEWORK_NOT_ASSIGNED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_criteria WHERE framework_id = ?",
                Integer.class, frameworkId)).isEqualTo(1);
    }

    @Test
    void unknownPositionsAndOtherMethodsAreRejectedWithoutChangingAnything() throws Exception {
        UUID frameworkId = id(activeFramework("CORE", criterion(null, "Giao tiếp", null, 100)));
        UUID position = position("DEV", "Developer", "Junior");
        expect(assign(position, frameworkId), 200);
        var before = state();

        var missing = get(criteriaPath(UUID.randomUUID()), interviewerToken);
        JsonNode notFound = expect(missing, 404);
        noStore(missing);
        assertThat(notFound.path("code").asText()).isEqualTo("POSITION_NOT_FOUND");
        assertThat(notFound.path("message").asText()).isEqualTo("Không tìm thấy chức danh.");
        error(get(POSITIONS + "/not-a-uuid/evaluation-criteria", interviewerToken), 400, "VALIDATION_ERROR");

        // The criteria are edited on the framework; this URL is read only, even for the HR manager.
        for (String method : List.of("POST", "PUT", "DELETE")) {
            error(request(method, criteriaPath(position), "{}", hrToken), 403, "FORBIDDEN");
        }
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void aPositionHrStoppedUsingKeepsItsEvaluationCriteria() throws Exception {
        UUID frameworkId = id(activeFramework("CORE", criterion(null, "Giao tiếp", null, 100)));
        UUID position = position("DEV", "Developer", "Junior");
        expect(assign(position, frameworkId), 200);
        Map<String, Object> inactive = positionPayload("DEV", "Developer", "Junior");
        inactive.put("active", false);
        expect(request("PUT", POSITIONS + "/" + position, json.writeValueAsString(inactive), hrToken), 200);

        // Interviews already running for the position still need their form; the caller sees active=false.
        JsonNode result = expect(get(criteriaPath(position), interviewerToken), 200);
        assertThat(result.path("position").path("active").asBoolean()).isFalse();
        assertThat(texts(result.path("criteria"), "name")).containsExactly("Giao tiếp");
        assertThat(evaluationCriteria.forPosition(position).position().active()).isFalse();
    }

    @Test
    void neverHandsOutTheCriteriaOfAFrameworkThatIsNotComplete() throws Exception {
        UUID frameworkId = id(activeFramework("CORE", criterion(null, "Giao tiếp", null, 100)));
        UUID position = position("DEV", "Developer", "Junior");
        expect(assign(position, frameworkId), 200);
        // Only a direct SQL change can do this: the API never turns an ACTIVE framework back into a DRAFT.
        jdbc.update("UPDATE competency_frameworks SET status = 'DRAFT' WHERE id = ?", frameworkId);

        var response = get(criteriaPath(position), interviewerToken);
        JsonNode error = expect(response, 409);
        noStore(response);
        assertThat(error.path("code").asText()).isEqualTo("COMPETENCY_FRAMEWORK_NOT_ACTIVE");
        assertThat(error.path("message").asText())
                .isEqualTo("Khung năng lực của chức danh chưa hoàn chỉnh (ACTIVE) nên chưa dùng để đánh giá được.");
        apiError(() -> evaluationCriteria.forPosition(position), HttpStatus.CONFLICT, "COMPETENCY_FRAMEWORK_NOT_ACTIVE");
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void everyInternalRoleReadsTheSameEvaluationCriteria(Role role) throws Exception {
        account("role@example.test", Set.of(role));
        String token = login("role@example.test").path("accessToken").asText();
        UUID frameworkId = id(activeFramework("CORE", criterion(null, "Giao tiếp", null, 60),
                criterion(null, "Tư duy", null, 40)));
        UUID position = position("DEV", "Developer", "Junior");
        expect(assign(position, frameworkId), 200);
        JsonNode expected = expect(get(criteriaPath(position), hrToken), 200);

        var response = get(criteriaPath(position), token);
        assertThat(expect(response, 200)).isEqualTo(expected);
        assertThat(response.body()).doesNotContain("salary");
    }

    @Test
    void callersWithoutOrganizationReadAreRefusedByTheUrlRuleAndByTheServiceItself() throws Exception {
        UUID frameworkId = id(activeFramework("CORE", criterion(null, "Giao tiếp", null, 100)));
        UUID position = position("DEV", "Developer", "Junior");
        expect(assign(position, frameworkId), 200);
        account("no-role@example.test", Set.of());
        String noRoleToken = login("no-role@example.test").path("accessToken").asText();

        error(get(criteriaPath(position), noRoleToken), 403, "FORBIDDEN");
        // Called past SecurityConfiguration, the service still checks ORGANIZATION_READ_ALL on its own.
        Jwt noRole = jwtDecoder.decode(noRoleToken);
        Jwt interviewer = jwtDecoder.decode(interviewerToken);
        assertThatThrownBy(() -> evaluationCriteria.get(noRole, position)).isInstanceOf(AccessDeniedException.class);
        assertThat(evaluationCriteria.get(interviewer, position).framework().id()).isEqualTo(frameworkId);

        // A revoked permission applies to the next request made with the same access token.
        jdbc.update("DELETE FROM role_permissions "
                + "WHERE role_code = 'INTERVIEWER' AND permission_code = 'ORGANIZATION_READ_ALL'");
        try {
            error(get(criteriaPath(position), interviewerToken), 403, "FORBIDDEN");
            assertThatThrownBy(() -> evaluationCriteria.get(interviewer, position))
                    .isInstanceOf(AccessDeniedException.class);
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('INTERVIEWER', "
                    + "'ORGANIZATION_READ_ALL') ON CONFLICT DO NOTHING");
        }
        expect(get(criteriaPath(position), interviewerToken), 200);

        // The service also refuses a token that has expired since it was checked.
        clock.set(START.plus(TokenService.ACCESS_TOKEN_TTL));
        assertThatThrownBy(() -> evaluationCriteria.get(interviewer, position))
                .isInstanceOf(AuthenticationFailureException.class);
    }

    @Test
    void otherModulesGetTheSameDataFromTheServiceWithoutAToken() throws Exception {
        UUID frameworkId = id(activeFramework("CORE", criterion(null, "Giao tiếp", "Trình bày rõ ràng", 60),
                criterion(null, "Tư duy", null, new BigDecimal("40.0"))));
        UUID position = position("DEV", "Developer", "Junior");
        UUID withoutFramework = position("QA", "Tester", "Junior");
        expect(assign(position, frameworkId), 200);

        EvaluationCriteria direct = evaluationCriteria.forPosition(position);
        assertThat(json.readTree(json.writeValueAsString(direct)))
                .isEqualTo(expect(get(criteriaPath(position), interviewerToken), 200));
        assertThat(direct.position().id()).isEqualTo(position);
        assertThat(direct.framework().id()).isEqualTo(frameworkId);
        assertThat(direct.criteria()).extracting(CompetencyCriterionView::name).containsExactly("Giao tiếp", "Tư duy");
        // BigDecimal with two decimals, exactly as NUMERIC(5,2) stores it (equals also compares the scale).
        assertThat(direct.criteria()).extracting(CompetencyCriterionView::weight)
                .containsExactly(new BigDecimal("60.00"), new BigDecimal("40.00"));

        apiError(() -> evaluationCriteria.forPosition(UUID.randomUUID()), HttpStatus.NOT_FOUND, "POSITION_NOT_FOUND");
        apiError(() -> evaluationCriteria.forPosition(withoutFramework), HttpStatus.CONFLICT,
                "POSITION_COMPETENCY_FRAMEWORK_NOT_ASSIGNED");
        assertThatThrownBy(() -> evaluationCriteria.forPosition(null)).isInstanceOf(NullPointerException.class);

        var readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        EvaluationCriteria inReadOnly = readOnly.execute(status -> {
            // PostgreSQL really runs this transaction as READ ONLY; the service must skip FOR SHARE here, which
            // PostgreSQL would reject (SQLSTATE 25006).
            assertThat(jdbc.queryForObject("SHOW transaction_read_only", String.class)).isEqualTo("on");
            return evaluationCriteria.forPosition(position);
        });
        assertThat(inReadOnly).isEqualTo(direct);
    }

    @ParameterizedTest
    @ValueSource(strings = {"framework-edit", "link-removal"})
    void aChangeWaitsUntilTheTransactionThatReadTheCriteriaEnds(String change) throws Exception {
        JsonNode framework = activeFramework("CORE", criterion(null, "Giao tiếp", null, 60),
                criterion(null, "Tư duy", null, 40));
        UUID frameworkId = id(framework);
        List<String> criterionIds = ids(framework.path("criteria"));
        UUID position = position("DEV", "Developer", "Junior");
        expect(assign(position, frameworkId), 200);
        EvaluationCriteria before = evaluationCriteria.forPosition(position);
        boolean edit = change.equals("framework-edit");
        var criteriaRead = new CountDownLatch(1);
        var finishForm = new CountDownLatch(1);
        var readerPid = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            // Plays the later evaluation form module: it reads the criteria inside its own write transaction and
            // commits later.
            Future<EvaluationCriteria> form = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .execute(status -> {
                        EvaluationCriteria read = evaluationCriteria.forPosition(position);
                        readerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        criteriaRead.countDown();
                        await(finishForm);
                        return read;
                    }));
            try {
                assertThat(criteriaRead.await(10, TimeUnit.SECONDS)).as("the form must read the criteria").isTrue();
                // Shared locks: another service and the HTTP API read the same position without waiting.
                assertThat(executor.submit(() -> evaluationCriteria.forPosition(position)).get(10, TimeUnit.SECONDS))
                        .isEqualTo(before);
                expect(get(criteriaPath(position), interviewerToken), 200);

                var hrChange = executor.submit(() -> edit
                        ? request("PUT", FRAMEWORKS + "/" + frameworkId, json.writeValueAsString(payload("CORE",
                                List.of(criterion(UUID.fromString(criterionIds.get(0)), "Giao tiếp hiệu quả", null, 70),
                                        criterion(UUID.fromString(criterionIds.get(1)), "Tư duy", null, 30)),
                                null)), hrToken)
                        : request("DELETE", POSITIONS + "/" + position + "/competency-framework", null, hrToken));
                awaitWaiters(readerPid.get());
                assertThat(hrChange.isDone()).isFalse();

                finishForm.countDown();
                // The form saw the criteria as they were, and they stayed so until it committed.
                assertThat(form.get(10, TimeUnit.SECONDS)).isEqualTo(before);
                expect(hrChange.get(10, TimeUnit.SECONDS), 200);
            } finally {
                // Release the form transaction even if an assertion above fails.
                finishForm.countDown();
            }
        }
        if (edit) {
            assertThat(evaluationCriteria.forPosition(position).criteria())
                    .extracting(CompetencyCriterionView::name, CompetencyCriterionView::weight)
                    .containsExactly(tuple("Giao tiếp hiệu quả", new BigDecimal("70.00")),
                            tuple("Tư duy", new BigDecimal("30.00")));
        } else {
            apiError(() -> evaluationCriteria.forPosition(position), HttpStatus.CONFLICT,
                    "POSITION_COMPETENCY_FRAMEWORK_NOT_ASSIGNED");
        }
    }

    @Test
    void aReadThatMeetsAFrameworkEditInProgressWaitsAndReturnsTheCommittedCriteria() throws Exception {
        UUID frameworkId = id(activeFramework("CORE", criterion(null, "Giao tiếp", null, 60),
                criterion(null, "Tư duy", null, 40)));
        UUID position = position("DEV", "Developer", "Junior");
        expect(assign(position, frameworkId), 200);
        JsonNode committed = expect(get(criteriaPath(position), interviewerToken), 200);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            int editorPid = backendPid(connection);
            // Plays a framework edit in progress: it holds the framework lock and has renamed a criterion.
            lockRow(connection, "SELECT id FROM competency_frameworks WHERE id = ? FOR UPDATE", frameworkId);
            execute(connection, "UPDATE competency_criteria SET name = 'Giao tiếp hiệu quả' "
                    + "WHERE framework_id = ? AND sort_order = 1", frameworkId);
            try (var executor = Executors.newSingleThreadExecutor()) {
                Future<EvaluationCriteria> read = executor.submit(() -> evaluationCriteria.forPosition(position));
                try {
                    awaitWaiters(editorPid);
                    assertThat(read.isDone()).isFalse();
                    // The HTTP API reads without locks, so it does not wait and shows the last committed criteria.
                    assertThat(expect(get(criteriaPath(position), interviewerToken), 200)).isEqualTo(committed);
                    connection.commit();
                    assertThat(read.get(10, TimeUnit.SECONDS).criteria()).extracting(CompetencyCriterionView::name)
                            .containsExactly("Giao tiếp hiệu quả", "Tư duy");
                } finally {
                    connection.rollback();
                }
            }
        }
    }

    private void account(String email, Set<Role> roles) {
        accounts.saveAndFlush(new Account(email, "Evaluation criteria test", fixturePasswordHash, roles, START));
    }

    @SafeVarargs
    private JsonNode activeFramework(String code, Map<String, Object>... criteria) throws Exception {
        JsonNode created = expect(request("POST", FRAMEWORKS,
                json.writeValueAsString(payload(code, List.of(criteria), "ACTIVE")), hrToken), 201);
        assertThat(created.path("status").asText()).isEqualTo("ACTIVE");
        return created;
    }

    // status null leaves the field out, so PUT keeps the current status.
    private static Map<String, Object> payload(String code, List<Map<String, Object>> criteria, String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("name", "Khung " + code);
        if (status != null) {
            result.put("status", status);
        }
        result.put("criteria", criteria);
        return result;
    }

    // The id is left out for a new criterion, the description when it is null.
    private static Map<String, Object> criterion(UUID id, String name, String description, Object weight) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (id != null) {
            result.put("id", id);
        }
        result.put("name", name);
        if (description != null) {
            result.put("description", description);
        }
        result.put("weight", weight);
        return result;
    }

    private UUID position(String code, String name, String level) throws Exception {
        return id(expect(request("POST", POSITIONS, json.writeValueAsString(positionPayload(code, name, level)),
                hrToken), 201));
    }

    private static Map<String, Object> positionPayload(String code, String name, String level) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("name", name);
        result.put("level", level);
        result.put("salaryMin", 15_000_000L);
        result.put("salaryMax", 25_000_000L);
        result.put("active", true);
        return result;
    }

    private HttpResponse<String> assign(UUID position, UUID frameworkId) throws Exception {
        return request("PUT", POSITIONS + "/" + position + "/competency-framework",
                json.writeValueAsString(Map.of("frameworkId", frameworkId)), hrToken);
    }

    private static String criteriaPath(UUID position) {
        return POSITIONS + "/" + position + "/evaluation-criteria";
    }

    private Map<String, List<Map<String, Object>>> state() {
        Map<String, List<Map<String, Object>>> state = new LinkedHashMap<>();
        for (String table : List.of("positions", "competency_frameworks", "competency_criteria")) {
            state.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        }
        return state;
    }

    private JsonNode login(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200);
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

    private void noStore(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private static void apiError(ThrowingCallable call, HttpStatus status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatus()).isEqualTo(status);
            assertThat(error.getCode()).isEqualTo(code);
        });
    }

    private static UUID id(JsonNode node) { return UUID.fromString(node.path("id").asText()); }

    private List<String> ids(JsonNode array) { return texts(array, "id"); }

    private List<String> texts(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.path(field).asText()));
        return values;
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

    private void lockRow(Connection connection, String sql, UUID id) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (var row = statement.executeQuery()) { assertThat(row.next()).isTrue(); }
        }
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

    // Polls from a separate connection until another transaction waits for a lock held by the blocker.
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
        assertThat(waiting).as("another transaction must wait for the blocker's row lock").isTrue();
    }
}
