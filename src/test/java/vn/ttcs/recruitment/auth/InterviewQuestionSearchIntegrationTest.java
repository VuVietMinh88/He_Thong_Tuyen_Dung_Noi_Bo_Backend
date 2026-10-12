package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.interviewquestion.InterviewQuestionService;

import javax.sql.DataSource;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Jira 223: search and filter the interview question bank (story S2-07) by text, position, criterion, difficulty
// and active, page by page.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InterviewQuestionSearchIntegrationTest {
    private static final String BASE = "/api/v1/interview-questions";
    private static final String FRAMEWORKS = "/api/v1/competency-frameworks";
    private static final String POSITIONS = "/api/v1/positions";
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-07T00:00:00Z");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private JwtDecoder jwtDecoder;
    @Autowired private InterviewQuestionService service;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    // HR_MANAGER writes the fixtures: only that role may create positions (salary band permission).
    private String hrToken;
    // Interviewers are the main readers: they pick the questions to ask.
    private String interviewerToken;
    private String fixturePasswordHash;
    // Every test starts with two complete frameworks shared by positions:
    // DEV_CORE (Giao tiếp, Tư duy) used by DEV_JUNIOR and DEV_SENIOR, QA_CORE (Tỉ mỉ) used by QA,
    // and the position NEW that has no framework yet.
    private UUID devCore;
    private UUID communication;
    private UUID thinking;
    private UUID qaCore;
    private UUID meticulous;
    private UUID junior;
    private UUID senior;
    private UUID tester;
    private UUID newcomer;

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
        // V9 and V8 use ON DELETE RESTRICT: questions before their criteria, positions before their framework.
        jdbc.update("DELETE FROM interview_questions");
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

        JsonNode dev = framework("DEV_CORE", "Giao tiếp", "Tư duy");
        devCore = id(dev);
        communication = criterionIds(dev).get(0);
        thinking = criterionIds(dev).get(1);
        JsonNode qa = framework("QA_CORE", "Tỉ mỉ");
        qaCore = id(qa);
        meticulous = criterionIds(qa).get(0);
        junior = position("DEV_JUNIOR");
        senior = position("DEV_SENIOR");
        tester = position("QA");
        newcomer = position("NEW");
        expect(assign(junior, devCore), 200);
        expect(assign(senior, devCore), 200);
        expect(assign(tester, qaCore), 200);
    }

    @Test
    void aPositionFindsTheQuestionsOfTheCriteriaOfTheFrameworkItUses() throws Exception {
        JsonNode talk = created(communication, "Kể về một lần bạn bất đồng với đồng nghiệp.", "MEDIUM");
        JsonNode estimate = created(thinking, "Ước lượng số quán cà phê ở Hà Nội.", "HARD");
        JsonNode check = created(meticulous, "Bạn kiểm tra lại công việc của mình thế nào?", "EASY");

        var response = search("positionId=" + junior, interviewerToken);
        JsonNode page = expect(response, 200);
        noStore(response);
        assertThat(page.size()).isEqualTo(5);
        assertThat(page.path("page").asInt()).isZero();
        assertThat(page.path("size").asInt()).isEqualTo(20);
        assertThat(page.path("totalElements").asInt()).isEqualTo(2);
        assertThat(page.path("totalPages").asInt()).isEqualTo(1);
        // Each item is exactly what GET /{id} returns, criterion and framework included.
        assertThat(page.path("items").get(0)).isEqualTo(expect(get(BASE + "/" + id(talk), interviewerToken), 200));
        assertThat(page.path("items").get(1)).isEqualTo(expect(get(BASE + "/" + id(estimate), interviewerToken), 200));
        // Positions that share a framework find the same questions: nothing was copied per position.
        assertThat(expect(search("positionId=" + senior, interviewerToken), 200).path("items"))
                .isEqualTo(page.path("items"));
        assertThat(ids(expect(search("positionId=" + tester, interviewerToken), 200))).containsExactly(id(check));
        // A position without a framework has no criteria, so it has no questions; that is not an error.
        JsonNode none = expect(search("positionId=" + newcomer, interviewerToken), 200);
        assertThat(none.path("items").isEmpty()).isTrue();
        assertThat(none.path("totalElements").asInt()).isZero();
        assertThat(none.path("totalPages").asInt()).isZero();

        assertThat(ids(expect(search("criterionId=" + thinking, interviewerToken), 200))).containsExactly(id(estimate));
        // Both filters must match: the criterion of another framework gives an empty page, not an error.
        assertThat(ids(expect(search("positionId=" + junior + "&criterionId=" + communication, interviewerToken),
                200))).containsExactly(id(talk));
        assertThat(ids(expect(search("positionId=" + junior + "&criterionId=" + meticulous, interviewerToken),
                200))).isEmpty();

        // The position follows its framework link, so moving QA to DEV_CORE changes its questions at once.
        expect(assign(tester, devCore), 200);
        assertThat(ids(expect(search("positionId=" + tester, interviewerToken), 200)))
                .containsExactly(id(talk), id(estimate));
        // A question moved to another criterion is found under its new criterion only.
        expect(request("PUT", BASE + "/" + id(check), json.writeValueAsString(question(thinking,
                "Bạn kiểm tra lại công việc của mình thế nào?", "EASY")), hrToken), 200);
        assertThat(ids(expect(search("criterionId=" + meticulous, interviewerToken), 200))).isEmpty();
        assertThat(ids(expect(search("criterionId=" + thinking, interviewerToken), 200)))
                .containsExactlyInAnyOrder(id(estimate), id(check));
    }

    @Test
    void searchesTheContentIgnoringCaseLineBreaksAndHowVietnameseLettersAreEncoded() throws Exception {
        JsonNode conflict = created(communication, "Bạn đã xử lý\nxung đột trong nhóm thế nào?", "MEDIUM",
                "Nêu rõ cách lắng nghe.");
        JsonNode percent = created(thinking, "Tỉ lệ hoàn thành 100% công việc đúng hạn của bạn?", "HARD", null);
        JsonNode thousand = created(thinking, "Tỉ lệ hoàn thành 1000 công việc của bạn?", "HARD", null);
        JsonNode snake = created(meticulous, "Bạn đặt tên biến theo snake_case khi nào!", "EASY", null);

        // Upper case Vietnamese letters, a line break where the search has a space, and several spaces.
        for (String query : List.of("BẠN ĐÃ XỬ LÝ XUNG ĐỘT", "xử lý xung đột", "xử   lý \t xung\nđột", "  NHÓM THẾ  ")) {
            assertThat(ids(expect(search("q=" + encode(query), interviewerToken), 200))).as(query)
                    .containsExactly(id(conflict));
        }
        // The same letters sent as base letters plus combining marks (Unicode NFD) find the stored NFC text.
        String decomposed = Normalizer.normalize("xung đột trong nhóm", Normalizer.Form.NFD);
        assertThat(decomposed).isNotEqualTo("xung đột trong nhóm");
        assertThat(ids(expect(search("q=" + encode(decomposed), interviewerToken), 200))).containsExactly(id(conflict));

        // %, _ and ! are searched as themselves, not as LIKE wildcards or the escape character.
        assertThat(ids(expect(search("q=" + encode("100%"), interviewerToken), 200))).containsExactly(id(percent));
        assertThat(ids(expect(search("q=" + encode("_"), interviewerToken), 200))).containsExactly(id(snake));
        assertThat(ids(expect(search("q=" + encode("!"), interviewerToken), 200))).containsExactly(id(snake));
        assertThat(ids(expect(search("q=" + encode("tỉ lệ hoàn thành"), interviewerToken), 200)))
                .containsExactlyInAnyOrder(id(percent), id(thousand));
        // Only the content is searched: not the answer hint, the criterion or the framework.
        for (String query : List.of("lắng nghe", "Giao tiếp", "DEV_CORE")) {
            assertThat(expect(search("q=" + encode(query), interviewerToken), 200).path("totalElements").asInt())
                    .as(query).isZero();
        }
        // A quote cannot change the SQL.
        assertThat(expect(search("q=" + encode("' OR 1=1 --"), interviewerToken), 200).path("totalElements").asInt())
                .isZero();
        // A blank search text is no search.
        assertThat(expect(search("q=" + encode(" \t\n "), interviewerToken), 200).path("totalElements").asInt())
                .isEqualTo(4);
        // The text works together with the other filters.
        assertThat(ids(expect(search("q=" + encode("tỉ lệ") + "&positionId=" + tester, interviewerToken), 200)))
                .isEmpty();
        assertThat(ids(expect(search("q=" + encode("tỉ lệ") + "&positionId=" + junior + "&criterionId=" + thinking,
                interviewerToken), 200))).containsExactlyInAnyOrder(id(percent), id(thousand));
    }

    @Test
    void filtersByDifficultyAndActiveAndCombinesEveryFilter() throws Exception {
        JsonNode easy = created(communication, "Giới thiệu về bản thân.", "EASY");
        JsonNode medium = created(communication, "Bạn thuyết phục khách hàng thế nào?", "MEDIUM");
        JsonNode hardInUse = created(thinking, "Thiết kế hệ thống đặt vé cho một triệu người.", "HARD");
        Map<String, Object> outOfUse = question(thinking, "Thiết kế hệ thống nhắn tin.", "HARD");
        outOfUse.put("active", false);
        UUID hardOutOfUse = id(expect(request("POST", BASE, json.writeValueAsString(outOfUse), hrToken), 201));
        JsonNode qaHard = created(meticulous, "Thiết kế bộ kiểm thử cho hệ thống thanh toán.", "HARD");

        assertThat(ids(expect(search("difficulty=EASY", interviewerToken), 200))).containsExactly(id(easy));
        assertThat(ids(expect(search("difficulty=MEDIUM", interviewerToken), 200))).containsExactly(id(medium));
        assertThat(ids(expect(search("difficulty=HARD", interviewerToken), 200)))
                .containsExactlyInAnyOrder(id(hardInUse), hardOutOfUse, id(qaHard));
        // Questions out of use are listed unless active=true is asked for, like the other lists.
        assertThat(ids(expect(search("active=false", interviewerToken), 200))).containsExactly(hardOutOfUse);
        assertThat(expect(search("active=true", interviewerToken), 200).path("totalElements").asInt()).isEqualTo(4);
        assertThat(expect(search("", interviewerToken), 200).path("totalElements").asInt()).isEqualTo(5);
        // Every filter at once.
        assertThat(ids(expect(search("q=" + encode("thiết kế") + "&positionId=" + junior + "&criterionId=" + thinking
                + "&difficulty=HARD&active=true", interviewerToken), 200))).containsExactly(id(hardInUse));
        // Empty values count as not given.
        assertThat(expect(search("q=&positionId=&criterionId=&difficulty=&active=&page=&size=", interviewerToken),
                200).path("totalElements").asInt()).isEqualTo(5);
    }

    @Test
    void pagesAreStableAndFollowTheFrameworkCodeTheCriterionOrderAndTheQuestionAge() throws Exception {
        // Created in another order than the list order, so the order cannot come from the insert order.
        UUID qa = id(created(meticulous, "Câu hỏi QA", "EASY"));
        clock.set(START.plus(Duration.ofMinutes(1)));
        UUID think = id(created(thinking, "Câu hỏi tư duy", "EASY"));
        clock.set(START.plus(Duration.ofMinutes(2)));
        // Two questions created at the same moment are ordered by id.
        List<UUID> sameMoment = new ArrayList<>(List.of(id(created(communication, "Câu hỏi giao tiếp 1", "EASY")),
                id(created(communication, "Câu hỏi giao tiếp 2", "EASY"))));
        // PostgreSQL compares UUIDs byte by byte, which is the order of their text form (not UUID.compareTo).
        sameMoment.sort((left, right) -> left.toString().compareTo(right.toString()));
        clock.set(START.plus(Duration.ofMinutes(3)));
        UUID latest = id(created(communication, "Câu hỏi giao tiếp 3", "EASY"));

        List<UUID> expected = List.of(sameMoment.get(0), sameMoment.get(1), latest, think, qa);
        assertThat(allPages(3)).containsExactlyElementsOf(expected);

        // HR moves "Tư duy" before "Giao tiếp" in DEV_CORE: the list follows the criterion order HR set, not the
        // criterion names.
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("code", "DEV_CORE");
        reordered.put("name", "Khung DEV_CORE");
        reordered.put("criteria", List.of(criterionBody(thinking, "Tư duy", 50),
                criterionBody(communication, "Giao tiếp", 50)));
        expect(request("PUT", FRAMEWORKS + "/" + devCore, json.writeValueAsString(reordered), hrToken), 200);
        assertThat(allPages(3)).containsExactly(think, sameMoment.get(0), sameMoment.get(1), latest, qa);

        // A page past the end is empty but still reports the totals.
        JsonNode beyond = expect(search("page=7&size=2", interviewerToken), 200);
        assertThat(beyond.path("items").isEmpty()).isTrue();
        assertThat(beyond.path("page").asInt()).isEqualTo(7);
        assertThat(beyond.path("totalElements").asInt()).isEqualTo(5);
        assertThat(beyond.path("totalPages").asInt()).isEqualTo(3);
        JsonNode one = expect(search("size=100", interviewerToken), 200);
        assertThat(one.path("items").size()).isEqualTo(5);
        assertThat(one.path("totalPages").asInt()).isEqualTo(1);
    }

    @Test
    void validatesTheParametersAndRefusesUnknownPositionsAndCriteria() throws Exception {
        UUID existing = id(created(communication, "Câu hỏi", "EASY"));
        fieldErrors(search("page=-1", hrToken), Map.of("page", "Số trang phải từ 0 trở lên."));
        fieldErrors(search("size=0", hrToken), Map.of("size", "Số câu hỏi mỗi trang phải từ 1 đến 100."));
        fieldErrors(search("size=101", hrToken), Map.of("size", "Số câu hỏi mỗi trang phải từ 1 đến 100."));
        // Every wrong parameter is reported at once.
        fieldErrors(search("page=-1&size=101&q=" + "x".repeat(256), hrToken), Map.of(
                "q", "Từ khóa tìm kiếm tối đa 255 ký tự.",
                "page", "Số trang phải từ 0 trở lên.",
                "size", "Số câu hỏi mỗi trang phải từ 1 đến 100."));
        // The limit counts letters as people see them, after the spaces between words are reduced to one.
        expect(search("size=100&q=" + "x".repeat(255), hrToken), 200);
        expect(search("q=" + encode(Normalizer.normalize("ệ".repeat(255), Normalizer.Form.NFD)), hrToken), 200);
        expect(search("q=" + encode("x" + " ".repeat(300) + "y"), hrToken), 200);
        // Control characters are refused (U+0000 would otherwise fail in PostgreSQL); tabs and line breaks are
        // spaces.
        for (String control : List.of("\u0000", "\u0007", "\u001B", "\u007F", "\u009F")) {
            fieldErrors(search("q=" + encode("câu" + control + "hỏi"), hrToken),
                    Map.of("q", "Từ khóa tìm kiếm không được chứa ký tự điều khiển."));
        }
        assertThat(ids(expect(search("q=" + encode("Câu\thỏi"), hrToken), 200))).containsExactly(existing);

        // Values Spring cannot convert are refused before the service runs.
        for (String query : List.of("positionId=not-a-uuid", "criterionId=123", "difficulty=easy",
                "difficulty=VERY_HARD", "active=maybe", "page=abc", "size=1.5")) {
            var refused = search(query, hrToken);
            JsonNode body = expect(refused, 400);
            noStore(refused);
            assertThat(body.path("code").asText()).as(query).isEqualTo("VALIDATION_ERROR");
            assertThat(body.path("message").asText()).as(query).isEqualTo("Tham số đường dẫn hoặc bộ lọc không hợp lệ.");
            // The key is still sent, as an empty object, so a client can always read fieldErrors.
            assertThat(body.path("fieldErrors")).as(query).isEqualTo(json.createObjectNode());
        }

        // An id that is not a position (here a criterion id) or not a criterion (here a framework id).
        for (UUID unknown : List.of(UUID.randomUUID(), communication)) {
            var response = search("positionId=" + unknown, hrToken);
            JsonNode body = expect(response, 400);
            noStore(response);
            assertThat(body.path("code").asText()).isEqualTo("INVALID_POSITION");
            assertThat(body.path("message").asText()).isEqualTo("Chức danh không tồn tại.");
            assertThat(body.path("fieldErrors")).isEqualTo(json.valueToTree(Map.of(
                    "positionId", "Không tìm thấy chức danh này.")));
        }
        for (UUID unknown : List.of(UUID.randomUUID(), devCore)) {
            var response = search("criterionId=" + unknown, hrToken);
            JsonNode body = expect(response, 400);
            noStore(response);
            assertThat(body.path("code").asText()).isEqualTo("INVALID_COMPETENCY_CRITERION");
            assertThat(body.path("fieldErrors")).isEqualTo(json.valueToTree(Map.of(
                    "criterionId", "Không tìm thấy tiêu chí này trong khung năng lực nào.")));
        }
        // The text and paging are checked before the ids, and the position before the criterion.
        error(search("page=-1&positionId=" + UUID.randomUUID(), hrToken), 400, "VALIDATION_ERROR");
        error(search("positionId=" + UUID.randomUUID() + "&criterionId=" + UUID.randomUUID(), hrToken), 400,
                "INVALID_POSITION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_questions", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void everyInternalRoleSearchesTheQuestionBank(Role role) throws Exception {
        JsonNode question = created(communication, "Câu hỏi", "EASY");
        account("role@example.test", Set.of(role));
        String token = login("role@example.test").path("accessToken").asText();
        // V3 grants ORGANIZATION_READ_ALL to the six internal roles.
        JsonNode page = expect(search("positionId=" + junior, token), 200);
        assertThat(page.path("items").size()).isEqualTo(1);
        assertThat(page.path("items").get(0)).isEqualTo(question);
    }

    @Test
    void callersWithoutOrganizationReadAreRefusedByTheUrlRuleAndByTheServiceItself() throws Exception {
        created(communication, "Câu hỏi", "EASY");
        account("no-role@example.test", Set.of());
        String noRoleToken = login("no-role@example.test").path("accessToken").asText();
        error(search("", noRoleToken), 403, "FORBIDDEN");
        // Permission comes before the parameters: no hint about which ids exist.
        error(search("page=-1&positionId=" + UUID.randomUUID(), noRoleToken), 403, "FORBIDDEN");
        error(search("positionId=not-a-uuid", noRoleToken), 403, "FORBIDDEN");
        error(search("", null), 401, "UNAUTHORIZED");

        // Called past SecurityConfiguration, the service still checks the permission on its own.
        Jwt noRole = jwtDecoder.decode(noRoleToken);
        Jwt interviewer = jwtDecoder.decode(interviewerToken);
        assertThatThrownBy(() -> service.list(noRole, "", null, null, null, null, 0, 20))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(service.list(interviewer, "câu", junior, null, null, null, 0, 20).totalElements()).isEqualTo(1);

        // A revoked permission applies to the next request made with the same access token.
        jdbc.update("DELETE FROM role_permissions "
                + "WHERE role_code = 'INTERVIEWER' AND permission_code = 'ORGANIZATION_READ_ALL'");
        try {
            error(search("", interviewerToken), 403, "FORBIDDEN");
            assertThatThrownBy(() -> service.list(interviewer, "", null, null, null, null, 0, 20))
                    .isInstanceOf(AccessDeniedException.class);
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('INTERVIEWER', "
                    + "'ORGANIZATION_READ_ALL') ON CONFLICT DO NOTHING");
        }
        expect(search("", interviewerToken), 200);
    }

    @Test
    void aSearchNeitherWaitsForAnEditInProgressNorShowsWhatItHasNotSavedYet() throws Exception {
        UUID question = id(created(communication, "Câu hỏi đã lưu", "EASY"));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                // Another transaction holds the framework, the position and the question rows and has changed the
                // question without committing yet, as an edit in progress would.
                try (var statement = connection.prepareStatement(
                        "SELECT id FROM competency_frameworks WHERE id = ? FOR UPDATE")) {
                    statement.setObject(1, devCore);
                    statement.executeQuery().close();
                }
                try (var statement = connection.prepareStatement(
                        "UPDATE positions SET competency_framework_id = ? WHERE id = ?")) {
                    statement.setObject(1, qaCore);
                    statement.setObject(2, junior);
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }
                try (var statement = connection.prepareStatement("UPDATE interview_questions"
                        + " SET content = 'Nội dung chưa lưu', difficulty = 'HARD' WHERE id = ?")) {
                    statement.setObject(1, question);
                    assertThat(statement.executeUpdate()).isEqualTo(1);
                }

                // The search reads the last committed rows at once (request timeout: 20 seconds).
                JsonNode page = expect(search("positionId=" + junior + "&difficulty=EASY", interviewerToken), 200);
                assertThat(ids(page)).containsExactly(question);
                assertThat(page.path("items").get(0).path("content").asText()).isEqualTo("Câu hỏi đã lưu");
                assertThat(expect(search("q=" + encode("chưa lưu"), interviewerToken), 200)
                        .path("totalElements").asInt()).isZero();
            } finally {
                connection.rollback();
            }
        }
    }

    // Reads every page of the whole bank with two questions per page and checks that the pages fit together.
    private List<UUID> allPages(int expectedPages) throws Exception {
        List<UUID> seen = new ArrayList<>();
        for (int page = 0; page < expectedPages; page++) {
            JsonNode result = expect(search("page=" + page + "&size=2", interviewerToken), 200);
            assertThat(result.path("page").asInt()).isEqualTo(page);
            assertThat(result.path("size").asInt()).isEqualTo(2);
            assertThat(result.path("totalPages").asInt()).isEqualTo(expectedPages);
            seen.addAll(ids(result));
        }
        assertThat(seen).doesNotHaveDuplicates();
        return seen;
    }

    private void account(String email, Set<Role> roles) {
        accounts.saveAndFlush(new Account(email, "Interview question search test", fixturePasswordHash, roles, START));
    }

    // A complete (ACTIVE) framework created through its API; the weights total 100.
    private JsonNode framework(String code, String... criterionNames) throws Exception {
        List<Map<String, Object>> criteria = new ArrayList<>();
        int weight = 100 / criterionNames.length;
        for (int index = 0; index < criterionNames.length; index++) {
            int extra = index == 0 ? 100 % criterionNames.length : 0;
            criteria.add(criterionBody(null, criterionNames[index], weight + extra));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("name", "Khung " + code);
        payload.put("status", "ACTIVE");
        payload.put("criteria", criteria);
        return expect(request("POST", FRAMEWORKS, json.writeValueAsString(payload), hrToken), 201);
    }

    // The id is left out for a new criterion.
    private static Map<String, Object> criterionBody(UUID id, String name, int weight) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (id != null) {
            result.put("id", id);
        }
        result.put("name", name);
        result.put("weight", weight);
        return result;
    }

    private UUID position(String code) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("name", "Chức danh " + code);
        payload.put("level", "Junior");
        payload.put("salaryMin", 15_000_000L);
        payload.put("salaryMax", 25_000_000L);
        payload.put("active", true);
        return id(expect(request("POST", POSITIONS, json.writeValueAsString(payload), hrToken), 201));
    }

    private HttpResponse<String> assign(UUID position, UUID frameworkId) throws Exception {
        return request("PUT", POSITIONS + "/" + position + "/competency-framework",
                json.writeValueAsString(Map.of("frameworkId", frameworkId)), hrToken);
    }

    private JsonNode created(UUID criterionId, String content, String difficulty) throws Exception {
        return created(criterionId, content, difficulty, null);
    }

    // A question created through the API by the HR manager.
    private JsonNode created(UUID criterionId, String content, String difficulty, String answerHint) throws Exception {
        Map<String, Object> payload = question(criterionId, content, difficulty);
        payload.put("answerHint", answerHint);
        return expect(request("POST", BASE, json.writeValueAsString(payload), hrToken), 201);
    }

    private static Map<String, Object> question(UUID criterionId, String content, String difficulty) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("criterionId", criterionId);
        result.put("content", content);
        result.put("difficulty", difficulty);
        return result;
    }

    private List<UUID> criterionIds(JsonNode framework) {
        List<UUID> ids = new ArrayList<>();
        framework.path("criteria").forEach(criterion -> ids.add(id(criterion)));
        return ids;
    }

    // The ids of the items of one page, in the order returned.
    private List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(id(item)));
        return ids;
    }

    private void fieldErrors(HttpResponse<String> response, Map<String, String> expected) {
        JsonNode body = expect(response, 400);
        noStore(response);
        assertThat(body.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.path("message").asText())
                .isEqualTo("Trang, số lượng hoặc từ khóa tìm kiếm câu hỏi phỏng vấn không hợp lệ.");
        assertThat(body.path("fieldErrors")).isEqualTo(json.valueToTree(expected));
    }

    private JsonNode login(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200);
    }

    // query is the part after "?", without it; "" lists everything with the default paging.
    private HttpResponse<String> search(String query, String token) throws Exception {
        return get(query.isEmpty() ? BASE : BASE + "?" + query, token);
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

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static UUID id(JsonNode node) { return UUID.fromString(node.path("id").asText()); }
}
