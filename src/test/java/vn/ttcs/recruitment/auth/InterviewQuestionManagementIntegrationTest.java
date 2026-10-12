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
import vn.ttcs.recruitment.interviewquestion.InterviewQuestionDifficulty;
import vn.ttcs.recruitment.interviewquestion.InterviewQuestionRequest;
import vn.ttcs.recruitment.interviewquestion.InterviewQuestionService;
import vn.ttcs.recruitment.interviewquestion.InterviewQuestionView;

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

// Jira 221: create, edit and read the questions of the interview question bank (story S2-07). Every question belongs
// to one criterion of a competency framework.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InterviewQuestionManagementIntegrationTest {
    private static final String BASE = "/api/v1/interview-questions";
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
    @Autowired private InterviewQuestionService service;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private UUID adminId;
    private String adminToken;
    // HR_MANAGER is the role of the story ("Trưởng phòng Nhân sự"), so the HR manager writes most questions.
    private UUID hrId;
    private String hrToken;
    // Interviewers only read the questions: they ask them in interviews.
    private String interviewerToken;
    private String fixturePasswordHash;
    // Every test starts with the complete framework DEV_CORE and its two criteria.
    private UUID frameworkId;
    private UUID communication;
    private UUID thinking;

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
        // V9: a criterion that still has questions cannot be deleted, so the questions go first.
        jdbc.update("DELETE FROM interview_questions");
        // ON DELETE CASCADE removes the criteria too.
        jdbc.update("DELETE FROM competency_frameworks");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        JsonNode login = login("admin@example.test");
        adminId = UUID.fromString(login.path("user").path("id").asText());
        adminToken = login.path("accessToken").asText();
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = account("hr@example.test", Set.of(Role.HR_MANAGER));
        hrToken = login("hr@example.test").path("accessToken").asText();
        account("interviewer@example.test", Set.of(Role.INTERVIEWER));
        interviewerToken = login("interviewer@example.test").path("accessToken").asText();
        JsonNode framework = framework("DEV_CORE", "ACTIVE", "Giao tiếp", "Tư duy");
        frameworkId = id(framework);
        communication = criterionIds(framework).get(0);
        thinking = criterionIds(framework).get(1);
    }

    @Test
    void createsATrimmedQuestionOnACriterionAndReadsItBack() throws Exception {
        var response = create(question(communication,
                "  Kể về một lần bạn bất đồng với đồng nghiệp.\nBạn đã xử lý thế nào? \n\t", "MEDIUM",
                "\n Nêu tình huống cụ thể,\ncách lắng nghe và kết quả.  "), hrToken);
        JsonNode result = expect(response, 201);
        noStore(response);
        UUID id = id(result);
        assertThat(result.size()).isEqualTo(9);
        assertThat(result.path("criterion")).isEqualTo(json.valueToTree(Map.of(
                "id", communication.toString(), "name", "Giao tiếp")));
        assertThat(result.path("framework")).isEqualTo(json.valueToTree(Map.of(
                "id", frameworkId.toString(), "code", "DEV_CORE", "name", "Khung DEV_CORE")));
        // The line break inside stays; only the spaces, tabs and line breaks around the text are removed.
        assertThat(result.path("content").asText())
                .isEqualTo("Kể về một lần bạn bất đồng với đồng nghiệp.\nBạn đã xử lý thế nào?");
        assertThat(result.path("difficulty").asText()).isEqualTo("MEDIUM");
        assertThat(result.path("answerHint").asText()).isEqualTo("Nêu tình huống cụ thể,\ncách lắng nghe và kết quả.");
        // Without active in the request, a new question is in use.
        assertThat(result.path("active").asBoolean()).isTrue();
        assertThat(Instant.parse(result.path("createdAt").asText())).isEqualTo(START);
        assertThat(Instant.parse(result.path("updatedAt").asText())).isEqualTo(START);

        // Interviewers read the same question.
        var read = get(BASE + "/" + id, interviewerToken);
        assertThat(expect(read, 200)).isEqualTo(result);
        noStore(read);
        var row = jdbc.queryForMap("SELECT * FROM interview_questions WHERE id = ?", id);
        assertThat(row.get("criterion_id")).isEqualTo(communication);
        assertThat(row.get("content")).isEqualTo("Kể về một lần bạn bất đồng với đồng nghiệp.\nBạn đã xử lý thế nào?");
        assertThat(row.get("difficulty")).isEqualTo("MEDIUM");
        assertThat(row.get("answer_hint")).isEqualTo("Nêu tình huống cụ thể,\ncách lắng nghe và kết quả.");
        assertThat(row.get("active")).isEqualTo(true);
        assertThat(row.get("created_at")).isEqualTo(Timestamp.from(START));
        assertThat(row.get("updated_at")).isEqualTo(Timestamp.from(START));

        // A blank answer hint is stored as NULL. A criterion of a DRAFT framework takes questions too.
        UUID draftCriterion = criterionIds(framework("DRAFT_CORE", "DRAFT", "Học hỏi")).get(0);
        JsonNode blankHint = expect(create(question(draftCriterion, "Bạn học một công nghệ mới thế nào?", "EASY",
                " \n\t "), hrToken), 201);
        assertThat(blankHint.path("answerHint").isNull()).isTrue();
        assertThat(blankHint.path("framework").path("code").asText()).isEqualTo("DRAFT_CORE");
        assertThat(jdbc.queryForObject("SELECT answer_hint FROM interview_questions WHERE id = ?", String.class,
                id(blankHint))).isNull();

        // The administrator writes questions too; the answer hint may be left out, and a question may be saved
        // without being used yet.
        Map<String, Object> notUsedYet = question(thinking, "Ước lượng số quán cà phê ở Hà Nội.", "HARD", null);
        notUsedYet.remove("answerHint");
        notUsedYet.put("active", false);
        JsonNode inactive = expect(create(notUsedYet, adminToken), 201);
        assertThat(inactive.path("answerHint").isNull()).isTrue();
        assertThat(inactive.path("active").asBoolean()).isFalse();
        assertThat(inactive.path("criterion").path("name").asText()).isEqualTo("Tư duy");
        assertThat(jdbc.queryForObject("SELECT active FROM interview_questions WHERE id = ?", Boolean.class,
                id(inactive))).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_questions", Integer.class)).isEqualTo(3);
    }

    @Test
    void updateReplacesTheQuestionMayMoveItToAnotherFrameworkAndKeepsItsIdAndCreationTime() throws Exception {
        UUID id = id(expect(create(question(communication, "Câu hỏi cũ", "EASY", "Gợi ý cũ"), hrToken), 201));
        JsonNode other = framework("QA_CORE", "ACTIVE", "Tỉ mỉ");
        UUID meticulous = criterionIds(other).get(0);
        clock.set(START.plus(Duration.ofMinutes(5)));

        var response = update(id, with(question(meticulous, " Bạn kiểm tra lại công việc của mình thế nào? ", "HARD",
                null), "active", false), hrToken);
        JsonNode result = expect(response, 200);
        noStore(response);
        assertThat(id(result)).isEqualTo(id);
        assertThat(result.path("criterion")).isEqualTo(json.valueToTree(Map.of(
                "id", meticulous.toString(), "name", "Tỉ mỉ")));
        assertThat(result.path("framework").path("id").asText()).isEqualTo(id(other).toString());
        assertThat(result.path("framework").path("code").asText()).isEqualTo("QA_CORE");
        assertThat(result.path("content").asText()).isEqualTo("Bạn kiểm tra lại công việc của mình thế nào?");
        assertThat(result.path("difficulty").asText()).isEqualTo("HARD");
        // PUT replaces the whole question, so a hint sent as null removes the old one.
        assertThat(result.path("answerHint").isNull()).isTrue();
        assertThat(result.path("active").asBoolean()).isFalse();
        assertThat(Instant.parse(result.path("createdAt").asText())).isEqualTo(START);
        assertThat(Instant.parse(result.path("updatedAt").asText())).isEqualTo(START.plus(Duration.ofMinutes(5)));
        assertThat(expect(get(BASE + "/" + id, interviewerToken), 200)).isEqualTo(result);
        var row = jdbc.queryForMap("SELECT * FROM interview_questions WHERE id = ?", id);
        assertThat(row.get("criterion_id")).isEqualTo(meticulous);
        assertThat(row.get("answer_hint")).isNull();
        assertThat(row.get("active")).isEqualTo(false);
        assertThat(row.get("created_at")).isEqualTo(Timestamp.from(START));
        assertThat(row.get("updated_at")).isEqualTo(Timestamp.from(START.plus(Duration.ofMinutes(5))));

        // Without active (left out or null) the question stays out of use; true puts it back in use.
        JsonNode kept = expect(update(id, question(meticulous, "Câu hỏi mới", "MEDIUM", "Gợi ý mới"), hrToken), 200);
        assertThat(kept.path("active").asBoolean()).isFalse();
        assertThat(kept.path("answerHint").asText()).isEqualTo("Gợi ý mới");
        JsonNode back = expect(update(id, with(question(communication, "Câu hỏi mới", "MEDIUM", null), "active", true),
                adminToken), 200);
        assertThat(back.path("active").asBoolean()).isTrue();
        assertThat(back.path("framework").path("code").asText()).isEqualTo("DEV_CORE");
        JsonNode nullActive = expect(update(id, with(question(communication, "Câu hỏi mới", "MEDIUM", null), "active",
                null), hrToken), 200);
        assertThat(nullActive.path("active").asBoolean()).isTrue();
        // Edits never create another row.
        assertThat(jdbc.queryForList("SELECT id FROM interview_questions", UUID.class)).containsExactly(id);
    }

    @Test
    void aCriterionKeepsItsQuestionsThroughFrameworkEditsUntilTheyAreMovedAway() throws Exception {
        UUID id = id(expect(create(question(thinking, "Giải một bài toán logic.", "HARD", null), hrToken), 201));
        var dropThinking = frameworkPayload("DEV_CORE", List.of(criterionBody(communication, "Giao tiếp", 100)));

        // The framework may not drop a criterion while a question points to it.
        JsonNode refused = expect(request("PUT", FRAMEWORKS + "/" + frameworkId, json.writeValueAsString(dropThinking),
                hrToken), 409);
        assertThat(refused.path("code").asText()).isEqualTo("COMPETENCY_CRITERION_IN_USE");
        assertThat(refused.path("fieldErrors").path("criteria").asText())
                .isEqualTo("Hãy giữ lại (gửi kèm id) các tiêu chí đang có câu hỏi phỏng vấn: Tư duy.");

        // A criterion renamed with its id keeps its questions, and the question shows the new name at once.
        expect(request("PUT", FRAMEWORKS + "/" + frameworkId, json.writeValueAsString(frameworkPayload("DEV_CORE",
                List.of(criterionBody(communication, "Giao tiếp", 50), criterionBody(thinking, "Tư duy logic", 50)))),
                hrToken), 200);
        assertThat(expect(get(BASE + "/" + id, interviewerToken), 200).path("criterion").path("name").asText())
                .isEqualTo("Tư duy logic");

        // Once the question moves to another criterion, the framework may drop the old one.
        expect(update(id, question(communication, "Giải một bài toán logic.", "HARD", null), hrToken), 200);
        expect(request("PUT", FRAMEWORKS + "/" + frameworkId, json.writeValueAsString(dropThinking), hrToken), 200);
        assertThat(jdbc.queryForList("SELECT id FROM competency_criteria", UUID.class)).containsExactly(communication);
        assertThat(expect(get(BASE + "/" + id, interviewerToken), 200).path("criterion").path("name").asText())
                .isEqualTo("Giao tiếp");
    }

    @Test
    void rejectsMissingBlankOversizedAndUnknownFieldsButAcceptsLengthBoundaries() throws Exception {
        JsonNode missing = expect(create(Map.of(), hrToken), 400);
        assertThat(missing.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(missing.path("fieldErrors")).isEqualTo(json.valueToTree(Map.of(
                "criterionId", "Cần chọn tiêu chí đánh giá cho câu hỏi.",
                "content", "Nội dung câu hỏi không được để trống.",
                "difficulty", "Cần chọn mức độ khó của câu hỏi.")));
        JsonNode blank = expect(create(question(communication, " \n\t ", "EASY", null), hrToken), 400);
        assertThat(blank.path("fieldErrors")).isEqualTo(json.valueToTree(Map.of(
                "content", "Nội dung câu hỏi không được để trống.")));

        // The limits count the text after the padding around it is removed.
        String longestContent = "â".repeat(2000);
        String longestHint = "ơ".repeat(4000);
        JsonNode atLimit = expect(create(question(communication, "  " + longestContent + "\n", "EASY",
                "\t" + longestHint + " "), hrToken), 201);
        assertThat(atLimit.path("content").asText()).isEqualTo(longestContent);
        assertThat(atLimit.path("answerHint").asText()).isEqualTo(longestHint);
        JsonNode tooLong = expect(create(question(communication, longestContent + "â", "EASY", longestHint + "ơ"),
                hrToken), 400);
        assertThat(tooLong.path("fieldErrors")).isEqualTo(json.valueToTree(Map.of(
                "content", "Nội dung câu hỏi tối đa 2000 ký tự.",
                "answerHint", "Gợi ý câu trả lời tối đa 4000 ký tự.")));

        // Fields outside the contract, values of the wrong kind and broken JSON are refused before any check.
        String valid = "\"criterionId\":\"" + communication + "\",\"content\":\"Câu hỏi\"";
        for (String body : List.of(
                "{" + valid + ",\"difficulty\":\"EASY\",\"id\":\"" + UUID.randomUUID() + "\"}",
                "{" + valid + ",\"difficulty\":\"EASY\",\"createdAt\":\"2026-10-07T00:00:00Z\"}",
                "{" + valid + ",\"difficulty\":\"easy\"}",
                "{" + valid + ",\"difficulty\":\"VERY_HARD\"}",
                "{" + valid + ",\"difficulty\":\"EASY\",\"active\":\"maybe\"}",
                "{\"criterionId\":\"not-a-uuid\",\"content\":\"Câu hỏi\",\"difficulty\":\"EASY\"}",
                "{" + valid + ",\"difficulty\":")) {
            error(request("POST", BASE, body, hrToken), 400, "INVALID_JSON");
        }
        UUID id = id(atLimit);
        var before = questionRows();
        assertThat(before).hasSize(1);

        // PUT checks its body the same way and leaves the question as it was.
        error(update(id, Map.of(), hrToken), 400, "VALIDATION_ERROR");
        error(update(id, question(communication, longestContent + "â", "HARD", null), hrToken), 400,
                "VALIDATION_ERROR");
        error(request("PUT", BASE + "/" + id, "{" + valid + ",\"difficulty\":\"EASY\",\"criterion\":{}}", hrToken),
                400, "INVALID_JSON");
        assertThat(questionRows()).isEqualTo(before);
    }

    @Test
    void removesEveryCharacterTheDatabaseCountsAsASpaceAroundTheTexts() throws Exception {
        // The V9 CHECKs use PostgreSQL's [[:space:]], which counts more characters than String.strip(), such as the
        // non-breaking spaces that text pasted from Word or a web page often ends with. Every character this database
        // counts as a space, plus those non-breaking spaces, stands around both texts.
        String spaces = jdbc.queryForObject("""
                SELECT string_agg(chr(code), '' ORDER BY code) FROM generate_series(1, 1114111) AS code
                WHERE code NOT BETWEEN 55296 AND 57343 AND chr(code) ~ '[[:space:]]'
                """, String.class) + "\u00A0\u2007\u202F\u0085\u180E";
        JsonNode created = expect(create(question(communication, spaces + "Bạn xử lý bất đồng thế nào?" + spaces,
                "EASY", spaces + "Lắng nghe trước,\u00A0rồi mới trả lời." + spaces), hrToken), 201);
        assertThat(created.path("content").asText()).isEqualTo("Bạn xử lý bất đồng thế nào?");
        // A non-breaking space inside the text stays.
        assertThat(created.path("answerHint").asText()).isEqualTo("Lắng nghe trước,\u00A0rồi mới trả lời.");
        UUID id = id(created);
        assertThat(jdbc.queryForMap("SELECT content, answer_hint FROM interview_questions WHERE id = ?", id))
                .containsEntry("content", "Bạn xử lý bất đồng thế nào?")
                .containsEntry("answer_hint", "Lắng nghe trước,\u00A0rồi mới trả lời.");

        // PUT removes them the same way, and a hint made only of them is stored as null.
        JsonNode updated = expect(update(id, question(thinking, "\u00A0Câu hỏi đã sửa" + spaces, "HARD", spaces),
                hrToken), 200);
        assertThat(updated.path("content").asText()).isEqualTo("Câu hỏi đã sửa");
        assertThat(updated.path("answerHint").isNull()).isTrue();
        var before = questionRows();
        assertThat(before).hasSize(1);
        assertThat(before.get(0)).containsEntry("content", "Câu hỏi đã sửa").containsEntry("answer_hint", null);

        // Content made only of them is blank, on POST and on PUT.
        JsonNode blank = expect(create(question(communication, "\u00A0\u202F\u2007", "EASY", null), hrToken), 400);
        assertThat(blank.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(blank.path("fieldErrors")).isEqualTo(json.valueToTree(Map.of(
                "content", "Nội dung câu hỏi không được để trống.")));
        error(update(id, question(thinking, spaces, "HARD", null), hrToken), 400, "VALIDATION_ERROR");
        assertThat(questionRows()).isEqualTo(before);
    }

    @Test
    void aTextTheDatabaseStillRefusesEndsAsAFieldErrorInsteadOfAServerError() throws Exception {
        UUID id = id(expect(create(question(communication, "Câu hỏi", "EASY", "Gợi ý"), hrToken), 201));
        var before = questionRows();
        // Plays a database whose locale counts a character the API keeps, the zero-width space U+200B (chr(8203)),
        // as a space: both V9 text CHECKs are replaced, for this test only, by one that refuses text ending with it.
        Map<String, String> original = new LinkedHashMap<>();
        for (String constraint : List.of("valid_interview_question_content", "valid_interview_question_answer_hint")) {
            original.put(constraint, jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conrelid = 'interview_questions'::regclass AND conname = ?", String.class, constraint));
        }
        try {
            replaceCheck("valid_interview_question_content", "CHECK (right(content, 1) <> chr(8203))");
            replaceCheck("valid_interview_question_answer_hint", "CHECK (right(answer_hint, 1) <> chr(8203))");
            var response = create(question(communication, "Câu hỏi mới\u200B", "EASY", null), hrToken);
            JsonNode content = expect(response, 400);
            noStore(response);
            assertThat(content.path("code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(content.path("fieldErrors")).isEqualTo(json.valueToTree(Map.of(
                    "content", "Nội dung câu hỏi không được bắt đầu hoặc kết thúc bằng khoảng trắng.")));
            JsonNode hint = expect(update(id, question(communication, "Câu hỏi", "EASY", "Gợi ý mới\u200B"),
                    hrToken), 400);
            assertThat(hint.path("code").asText()).isEqualTo("VALIDATION_ERROR");
            assertThat(hint.path("fieldErrors")).isEqualTo(json.valueToTree(Map.of(
                    "answerHint", "Gợi ý câu trả lời không được bắt đầu hoặc kết thúc bằng khoảng trắng.")));
        } finally {
            original.forEach(this::replaceCheck);
        }
        assertThat(questionRows()).isEqualTo(before);
    }

    @Test
    void rejectsUnknownCriteriaAndUnknownQuestionsWithoutChangingAnything() throws Exception {
        UUID unknown = UUID.randomUUID();
        var response = create(question(unknown, "Câu hỏi", "EASY", null), hrToken);
        JsonNode error = expect(response, 400);
        noStore(response);
        assertThat(error.path("code").asText()).isEqualTo("INVALID_COMPETENCY_CRITERION");
        assertThat(error.path("message").asText()).isEqualTo("Tiêu chí đánh giá không tồn tại.");
        assertThat(error.path("fieldErrors")).isEqualTo(json.valueToTree(Map.of(
                "criterionId", "Không tìm thấy tiêu chí này trong khung năng lực nào.")));
        // The id of a framework is not the id of a criterion.
        error(create(question(frameworkId, "Câu hỏi", "EASY", null), hrToken), 400, "INVALID_COMPETENCY_CRITERION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_questions", Integer.class)).isZero();

        UUID id = id(expect(create(question(communication, "Câu hỏi", "EASY", "Gợi ý"), hrToken), 201));
        var before = questionRows();
        error(update(id, question(unknown, "Câu hỏi đã sửa", "HARD", null), hrToken), 400,
                "INVALID_COMPETENCY_CRITERION");

        UUID missing = UUID.randomUUID();
        var notFound = get(BASE + "/" + missing, interviewerToken);
        JsonNode notFoundError = expect(notFound, 404);
        noStore(notFound);
        assertThat(notFoundError.path("code").asText()).isEqualTo("INTERVIEW_QUESTION_NOT_FOUND");
        assertThat(notFoundError.path("message").asText()).isEqualTo("Không tìm thấy câu hỏi phỏng vấn.");
        error(update(missing, question(communication, "Câu hỏi", "EASY", null), hrToken), 404,
                "INTERVIEW_QUESTION_NOT_FOUND");
        // The question is checked before the criterion.
        error(update(missing, question(unknown, "Câu hỏi", "EASY", null), hrToken), 404,
                "INTERVIEW_QUESTION_NOT_FOUND");
        error(get(BASE + "/not-a-uuid", interviewerToken), 400, "VALIDATION_ERROR");
        error(update("not-a-uuid", question(communication, "Câu hỏi", "EASY", null), hrToken), 400,
                "VALIDATION_ERROR");
        // There is no delete API: an unused question is kept with active=false, so the default rule refuses DELETE.
        error(request("DELETE", BASE + "/" + id, null, hrToken), 403, "FORBIDDEN");
        assertThat(questionRows()).isEqualTo(before);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void everyInternalRoleReadsQuestionsButOnlyOrganizationWritersChangeThem(Role role) throws Exception {
        account("role@example.test", Set.of(role));
        String token = login("role@example.test").path("accessToken").asText();
        JsonNode target = expect(create(question(communication, "Câu hỏi", "EASY", "Gợi ý"), hrToken), 201);
        UUID id = id(target);
        // V3 grants ORGANIZATION_WRITE_ALL to ADMIN and HR_MANAGER only.
        boolean writer = role == Role.ADMIN || role == Role.HR_MANAGER;

        assertThat(expect(get(BASE + "/" + id, token), 200)).isEqualTo(target);

        var before = questionRows();
        var created = create(question(thinking, "Câu hỏi mới", "HARD", null), token);
        var updated = update(id, question(thinking, "Câu hỏi đã sửa", "MEDIUM", null), token);
        // Without write permission the data rules are never reached: the answer is 403, not 400 or 404.
        var invalid = create(question(UUID.randomUUID(), " ", null, null), token);
        var unknown = update(UUID.randomUUID(), question(communication, "Câu hỏi", "EASY", null), token);
        if (writer) {
            assertThat(expect(created, 201).path("content").asText()).isEqualTo("Câu hỏi mới");
            JsonNode result = expect(updated, 200);
            assertThat(result.path("content").asText()).isEqualTo("Câu hỏi đã sửa");
            assertThat(result.path("criterion").path("id").asText()).isEqualTo(thinking.toString());
            error(invalid, 400, "VALIDATION_ERROR");
            error(unknown, 404, "INTERVIEW_QUESTION_NOT_FOUND");
        } else {
            error(created, 403, "FORBIDDEN");
            error(updated, 403, "FORBIDDEN");
            error(invalid, 403, "FORBIDDEN");
            error(unknown, 403, "FORBIDDEN");
            assertThat(questionRows()).isEqualTo(before);
        }
    }

    @Test
    void callersWithoutOrganizationReadAreRefusedByTheUrlRuleAndByTheServiceItself() throws Exception {
        UUID id = id(expect(create(question(communication, "Câu hỏi", "EASY", null), hrToken), 201));
        account("no-role@example.test", Set.of());
        String noRoleToken = login("no-role@example.test").path("accessToken").asText();
        error(get(BASE + "/" + id, noRoleToken), 403, "FORBIDDEN");

        // Called past SecurityConfiguration, the service still checks the permissions on its own.
        Jwt noRole = jwtDecoder.decode(noRoleToken);
        Jwt interviewer = jwtDecoder.decode(interviewerToken);
        assertThatThrownBy(() -> service.get(noRole, id)).isInstanceOf(AccessDeniedException.class);
        assertThat(service.get(interviewer, id).content()).isEqualTo("Câu hỏi");
        var request = new InterviewQuestionRequest(thinking, "Câu hỏi", InterviewQuestionDifficulty.EASY, null, null);
        assertThatThrownBy(() -> service.create(interviewer, request)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.update(interviewer, id, request)).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForList("SELECT criterion_id FROM interview_questions", UUID.class))
                .containsExactly(communication);

        // A revoked permission applies to the next request made with the same access token.
        jdbc.update("DELETE FROM role_permissions "
                + "WHERE role_code = 'INTERVIEWER' AND permission_code = 'ORGANIZATION_READ_ALL'");
        try {
            error(get(BASE + "/" + id, interviewerToken), 403, "FORBIDDEN");
            assertThatThrownBy(() -> service.get(interviewer, id)).isInstanceOf(AccessDeniedException.class);
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('INTERVIEWER', "
                    + "'ORGANIZATION_READ_ALL') ON CONFLICT DO NOTHING");
        }
        expect(get(BASE + "/" + id, interviewerToken), 200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "update"})
    void aCriterionThatAFrameworkEditDeletesWhileTheWriteWaitsIsRejected(String operation) throws Exception {
        JsonNode other = framework("QA_CORE", "DRAFT", "Tỉ mỉ", "Cẩn thận");
        UUID otherFramework = id(other);
        UUID removed = criterionIds(other).get(1);
        UUID existing = id(expect(create(question(communication, "Câu hỏi", "EASY", null), hrToken), 201));
        var before = questionRows();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Plays an edit of QA_CORE: it holds the framework lock and deletes "Cẩn thận", which has no question.
            lockRow(connection, "SELECT id FROM competency_frameworks WHERE id = ? FOR UPDATE", otherFramework);
            execute(connection, "DELETE FROM competency_criteria WHERE id = ?", removed);
            int blockerPid = backendPid(connection);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> operation.equals("create")
                        ? create(question(removed, "Câu hỏi mới", "MEDIUM", null), hrToken)
                        : update(existing, question(removed, "Câu hỏi đã chuyển", "MEDIUM", null), hrToken));
                try {
                    // The write waits for the framework lock before it reads the criterion again.
                    awaitWaiters(blockerPid);
                    assertThat(response.isDone()).isFalse();
                    connection.commit();
                    // It then sees the deletion: a 400 instead of a 500 from the foreign key.
                    JsonNode body = expect(response.get(10, TimeUnit.SECONDS), 400);
                    assertThat(body.path("code").asText()).isEqualTo("INVALID_COMPETENCY_CRITERION");
                    assertThat(body.path("fieldErrors").has("criterionId")).isTrue();
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(questionRows()).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT id FROM competency_criteria WHERE framework_id = ?", UUID.class,
                otherFramework)).containsExactly(criterionIds(other).get(0));
    }

    @Test
    void aWriteThatWaitsForAFrameworkEditAnswersWithTheCriterionNameCommittedMeanwhile() throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Plays an edit of DEV_CORE that holds the framework lock and renames "Tư duy" in place (same id).
            lockRow(connection, "SELECT id FROM competency_frameworks WHERE id = ? FOR UPDATE", frameworkId);
            execute(connection, "UPDATE competency_criteria SET name = 'Tư duy logic' WHERE id = ?", thinking);
            int blockerPid = backendPid(connection);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> create(question(thinking, "Câu hỏi", "EASY", null), hrToken));
                try {
                    awaitWaiters(blockerPid);
                    assertThat(response.isDone()).isFalse();
                    connection.commit();
                    // The criterion is read after the framework lock, so the response shows the committed name.
                    JsonNode created = expect(response.get(10, TimeUnit.SECONDS), 201);
                    assertThat(created.path("criterion")).isEqualTo(json.valueToTree(Map.of(
                            "id", thinking.toString(), "name", "Tư duy logic")));
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(jdbc.queryForList("SELECT criterion_id FROM interview_questions", UUID.class))
                .containsExactly(thinking);
    }

    @Test
    void aCriterionDeletedWithoutTheFrameworkLockWhileTheQuestionIsSavedIsStillRejected() throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Plays a direct SQL change that skips the framework lock: "Tư duy" is deleted but not committed yet,
            // so the service still finds it, and only the V9 foreign key can stop the question.
            execute(connection, "DELETE FROM competency_criteria WHERE id = ?", thinking);
            int blockerPid = backendPid(connection);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> create(question(thinking, "Câu hỏi", "EASY", null), hrToken));
                try {
                    // The foreign key check of the insert waits for the deleted row.
                    awaitWaiters(blockerPid);
                    assertThat(response.isDone()).isFalse();
                    connection.commit();
                    // The foreign key refuses the question; the service answers the same 400 instead of a 500.
                    var result = response.get(10, TimeUnit.SECONDS);
                    JsonNode body = expect(result, 400);
                    noStore(result);
                    assertThat(body.path("code").asText()).isEqualTo("INVALID_COMPETENCY_CRITERION");
                    assertThat(body.path("fieldErrors").has("criterionId")).isTrue();
                } finally {
                    connection.rollback();
                }
            }
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_questions", Integer.class)).isZero();
    }

    @Test
    void aFrameworkEditWaitsForAQuestionBeingSavedAndThenKeepsItsCriterion() throws Exception {
        Jwt hr = jwtDecoder.decode(hrToken);
        var saved = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var writerPid = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            // Plays a question write that has not committed yet: the service joins a longer transaction.
            Future<InterviewQuestionView> pending = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .execute(status -> {
                        var view = service.create(hr, new InterviewQuestionRequest(thinking, "Câu hỏi đang lưu",
                                InterviewQuestionDifficulty.HARD, null, null));
                        writerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        saved.countDown();
                        await(finish);
                        return view;
                    }));
            try {
                assertThat(saved.await(10, TimeUnit.SECONDS)).as("the question must be saved").isTrue();
                // Another question write on the same framework shares the lock and does not wait.
                expect(create(question(communication, "Câu hỏi khác", "EASY", null), adminToken), 201);

                // The administrator drops "Tư duy" from the framework: the edit waits for the question...
                var edit = executor.submit(() -> request("PUT", FRAMEWORKS + "/" + frameworkId,
                        json.writeValueAsString(frameworkPayload("DEV_CORE",
                                List.of(criterionBody(communication, "Giao tiếp", 100)))), adminToken));
                awaitWaiters(writerPid.get());
                assertThat(edit.isDone()).isFalse();

                finish.countDown();
                assertThat(pending.get(10, TimeUnit.SECONDS).criterion().id()).isEqualTo(thinking);
                // ...then sees the committed question and keeps the criterion instead of deleting it.
                JsonNode refused = expect(edit.get(10, TimeUnit.SECONDS), 409);
                assertThat(refused.path("code").asText()).isEqualTo("COMPETENCY_CRITERION_IN_USE");
                assertThat(refused.path("fieldErrors").path("criteria").asText())
                        .isEqualTo("Hãy giữ lại (gửi kèm id) các tiêu chí đang có câu hỏi phỏng vấn: Tư duy.");
            } finally {
                // Release the question transaction even if an assertion above fails.
                finish.countDown();
            }
        }
        assertThat(jdbc.queryForList("SELECT id FROM competency_criteria WHERE id = ?", UUID.class, thinking))
                .containsExactly(thinking);
        assertThat(jdbc.queryForList("SELECT content FROM interview_questions", String.class))
                .containsExactlyInAnyOrder("Câu hỏi khác", "Câu hỏi đang lưu");
    }

    @Test
    void anEditThatWaitsForAnotherEditOfTheSameQuestionStartsFromWhatThatEditSaved() throws Exception {
        UUID id = id(expect(create(question(communication, "Câu hỏi", "EASY", null), hrToken), 201));
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            // Plays another edit of the same question: it holds the question row lock.
            lockRow(connection, "SELECT id FROM interview_questions WHERE id = ? FOR UPDATE", id);
            int blockerPid = backendPid(connection);
            try (var executor = Executors.newSingleThreadExecutor()) {
                // No active in the body, so the question should keep whatever value it has when this edit runs.
                var response = executor.submit(() -> update(id, question(thinking, "Câu hỏi đã sửa", "HARD", null),
                        hrToken));
                try {
                    awaitWaiters(blockerPid);
                    assertThat(response.isDone()).isFalse();
                    // The other edit takes the question out of use and commits.
                    execute(connection, "UPDATE interview_questions SET active = FALSE WHERE id = ?", id);
                    connection.commit();
                    // The waiting edit reads the question only after the lock, so it keeps active = false instead of
                    // writing back the true it would have read before the other edit committed.
                    JsonNode updated = expect(response.get(10, TimeUnit.SECONDS), 200);
                    assertThat(updated.path("active").asBoolean()).isFalse();
                    assertThat(updated.path("content").asText()).isEqualTo("Câu hỏi đã sửa");
                    assertThat(updated.path("criterion").path("id").asText()).isEqualTo(thinking.toString());
                } finally {
                    connection.rollback();
                }
            }
        }
        var row = jdbc.queryForMap("SELECT criterion_id, content, active FROM interview_questions WHERE id = ?", id);
        assertThat(row).containsEntry("criterion_id", thinking).containsEntry("content", "Câu hỏi đã sửa")
                .containsEntry("active", false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lost-organization-permission", "expired-jwt", "locked-actor", "revoked-session"})
    void rechecksAccessAfterWaitingForTheActorAccountLock(String change) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            lockRow(connection, "SELECT id FROM user_accounts WHERE id = ? FOR UPDATE", hrId);
            int blockerPid = backendPid(connection);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var response = executor.submit(() -> create(question(communication, "Câu hỏi", "EASY", null),
                        hrToken));
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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_questions", Integer.class)).isZero();
    }

    private UUID account(String email, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, "Interview question test", fixturePasswordHash, roles, START))
                .getId();
    }

    // Creates a framework through its API. The weights total 100, so the framework may also be ACTIVE.
    private JsonNode framework(String code, String status, String... criterionNames) throws Exception {
        List<Map<String, Object>> criteria = new ArrayList<>();
        int weight = 100 / criterionNames.length;
        for (int index = 0; index < criterionNames.length; index++) {
            int extra = index == 0 ? 100 % criterionNames.length : 0;
            criteria.add(criterionBody(null, criterionNames[index], weight + extra));
        }
        Map<String, Object> payload = frameworkPayload(code, criteria);
        payload.put("status", status);
        return expect(request("POST", FRAMEWORKS, json.writeValueAsString(payload), hrToken), 201);
    }

    // No status: a PUT keeps the current one.
    private static Map<String, Object> frameworkPayload(String code, List<Map<String, Object>> criteria) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", code);
        result.put("name", "Khung " + code);
        result.put("criteria", criteria);
        return result;
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

    // null values are sent as JSON null; active is left out.
    private static Map<String, Object> question(UUID criterionId, String content, String difficulty,
                                                String answerHint) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("criterionId", criterionId);
        result.put("content", content);
        result.put("difficulty", difficulty);
        result.put("answerHint", answerHint);
        return result;
    }

    private static Map<String, Object> with(Map<String, Object> payload, String field, Object value) {
        Map<String, Object> result = new LinkedHashMap<>(payload);
        result.put(field, value);
        return result;
    }

    private List<UUID> criterionIds(JsonNode framework) {
        List<UUID> ids = new ArrayList<>();
        framework.path("criteria").forEach(criterion -> ids.add(UUID.fromString(criterion.path("id").asText())));
        return ids;
    }

    private List<Map<String, Object>> questionRows() {
        return jdbc.queryForList("SELECT * FROM interview_questions ORDER BY id");
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

    private void noStore(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private static UUID id(JsonNode node) { return UUID.fromString(node.path("id").asText()); }

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

    // Puts another definition under the name of a CHECK constraint of interview_questions.
    private void replaceCheck(String constraint, String definition) {
        jdbc.execute("ALTER TABLE interview_questions DROP CONSTRAINT " + constraint + ", ADD CONSTRAINT " + constraint
                + " " + definition);
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
