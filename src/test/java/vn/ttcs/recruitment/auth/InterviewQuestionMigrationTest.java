package vn.ttcs.recruitment.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InterviewQuestionMigrationTest {
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-07T00:00:00Z"));

    @Test
    void upgradesV8WithoutChangingExistingDataOrPermissions() throws Exception {
        try (var postgres = startPostgres()) {
            var dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("8").load().migrate();
            var jdbc = new JdbcTemplate(dataSource);
            UUID managerId = UUID.randomUUID();
            jdbc.update("INSERT INTO user_accounts (id,email,full_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                    managerId, "manager@example.test", "Tài khoản kiểm thử", "unchanged-password-hash", CREATED_AT);
            jdbc.update("INSERT INTO user_roles (user_id,role) VALUES (?, 'HR_MANAGER')", managerId);
            jdbc.update("INSERT INTO departments (id,code,name,manager_user_id) VALUES (?,?,?,?)",
                    UUID.randomUUID(), "HR", "Nhân sự", managerId);
            UUID frameworkId = insertFramework(jdbc, "DEV_CORE");
            insertCriterion(jdbc, frameworkId, "Kỹ năng lập trình", 1);
            insertCriterion(jdbc, frameworkId, "Làm việc nhóm", 2);
            UUID positionId = insertPosition(jdbc, "DEV_JUNIOR");
            jdbc.update("UPDATE positions SET competency_framework_id=? WHERE id=?", frameworkId, positionId);
            var before = snapshot(jdbc);

            var flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                    .target("9").load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
            flyway.validate();

            // V9 only adds a table: accounts, organization data, frameworks, criteria and permissions are unchanged.
            assertThat(snapshot(jdbc)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_questions", Integer.class)).isZero();
        }
    }

    @Test
    void storesQuestionsWithTheirCriterionDifficultyAndAnswerHint() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            UUID devId = insertFramework(jdbc, "DEV_CORE");
            UUID codingId = insertCriterion(jdbc, devId, "Kỹ năng lập trình", 1);
            UUID teamworkId = insertCriterion(jdbc, devId, "Làm việc nhóm", 2);
            UUID testerId = insertFramework(jdbc, "TEST_CORE");
            UUID testingId = insertCriterion(jdbc, testerId, "Thiết kế ca kiểm thử", 1);
            UUID juniorId = insertPosition(jdbc, "DEV_JUNIOR");
            UUID seniorId = insertPosition(jdbc, "DEV_SENIOR");
            UUID testerPositionId = insertPosition(jdbc, "TESTER");
            jdbc.update("UPDATE positions SET competency_framework_id=? WHERE id IN (?,?)", devId, juniorId, seniorId);
            jdbc.update("UPDATE positions SET competency_framework_id=? WHERE id=?", testerId, testerPositionId);

            UUID easyId = insertQuestion(jdbc, codingId, "Biến và hằng khác nhau thế nào?", "EASY",
                    "Nêu được hằng không đổi giá trị sau khi gán.");
            // The content may span several lines; only padding at the start or end is refused.
            String multiLine = "Thiết kế lớp cho giỏ hàng.\n- Thêm sản phẩm\n- Tính tổng tiền";
            UUID hardId = insertQuestion(jdbc, codingId, multiLine, "HARD", null);
            UUID mediumId = insertQuestion(jdbc, teamworkId, "Kể về một lần bạn bất đồng với đồng đội.", "MEDIUM",
                    "Tập trung vào cách lắng nghe\nvà kết quả chung.");
            UUID testingQuestionId = insertQuestion(jdbc, testingId, "Biến và hằng khác nhau thế nào?", "EASY", null);

            var easy = jdbc.queryForMap("SELECT * FROM interview_questions WHERE id=?", easyId);
            assertThat(easy.get("criterion_id")).isEqualTo(codingId);
            assertThat(easy.get("content")).isEqualTo("Biến và hằng khác nhau thế nào?");
            assertThat(easy.get("difficulty")).isEqualTo("EASY");
            assertThat(easy.get("answer_hint")).isEqualTo("Nêu được hằng không đổi giá trị sau khi gán.");
            // A question inserted without active is in use.
            assertThat(easy.get("active")).isEqualTo(true);
            assertThat(easy.get("created_at")).isEqualTo(CREATED_AT);
            assertThat(easy.get("updated_at")).isEqualTo(CREATED_AT);
            var hard = jdbc.queryForMap("SELECT content, difficulty, answer_hint FROM interview_questions WHERE id=?",
                    hardId);
            assertThat(hard.get("content")).isEqualTo(multiLine);
            assertThat(hard.get("difficulty")).isEqualTo("HARD");
            assertThat(hard.get("answer_hint")).isNull();

            // TEXT has no 255 or 1000 character limit like the other columns; the API will set one.
            String longContent = "Câu hỏi dài. ".repeat(400).trim();
            UUID longId = insertQuestion(jdbc, teamworkId, longContent, "MEDIUM", "Gợi ý dài. ".repeat(400).trim());
            assertThat(jdbc.queryForObject("SELECT length(content) FROM interview_questions WHERE id=?",
                    Integer.class, longId)).isEqualTo(longContent.length());

            // The story filters questions by position and by criterion: both are joins from the position.
            // Both positions share DEV_CORE, so they reach the same questions; nothing is copied per position.
            String byPosition = """
                    SELECT q.id FROM positions p
                    JOIN competency_criteria c ON c.framework_id = p.competency_framework_id
                    JOIN interview_questions q ON q.criterion_id = c.id
                    WHERE p.id = ?
                    """;
            String byPositionAndCriterion = byPosition + " AND c.id = ?";
            assertThat(jdbc.queryForList(byPosition, UUID.class, juniorId))
                    .containsExactlyInAnyOrder(easyId, hardId, mediumId, longId);
            assertThat(jdbc.queryForList(byPosition, UUID.class, seniorId))
                    .containsExactlyInAnyOrder(easyId, hardId, mediumId, longId);
            assertThat(jdbc.queryForList(byPosition, UUID.class, testerPositionId)).containsExactly(testingQuestionId);
            assertThat(jdbc.queryForList(byPositionAndCriterion, UUID.class, juniorId, teamworkId))
                    .containsExactlyInAnyOrder(mediumId, longId);
            // A criterion of another framework finds nothing for this position.
            assertThat(jdbc.queryForList(byPositionAndCriterion, UUID.class, juniorId, testingId)).isEmpty();

            // A question that is no longer used is kept and only marked inactive.
            jdbc.update("UPDATE interview_questions SET active=FALSE WHERE id=?", hardId);
            assertThat(jdbc.queryForList("SELECT id FROM interview_questions WHERE criterion_id=? AND active",
                    UUID.class, codingId)).containsExactly(easyId);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_questions", Integer.class)).isEqualTo(5);
            assertThat(jdbc.queryForObject("""
                    SELECT indexdef FROM pg_indexes
                    WHERE tablename = 'interview_questions' AND indexname = 'interview_questions_criterion_id_idx'
                    """, String.class)).contains("(criterion_id)");
        }
    }

    @Test
    void rejectsInvalidQuestions() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            UUID frameworkId = insertFramework(jdbc, "DEV_CORE");
            UUID criterionId = insertCriterion(jdbc, frameworkId, "Giao tiếp", 1);
            UUID questionId = insertQuestion(jdbc, criterionId, "Giới thiệu bản thân trong một phút.", "EASY", null);

            for (String invalid : new String[]{"VERY_HARD", "easy", "", " EASY"}) {
                assertThatThrownBy(() -> jdbc.update("UPDATE interview_questions SET difficulty=? WHERE id=?",
                        invalid, questionId))
                        .as(invalid).isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("valid_interview_question_difficulty");
            }
            for (String column : new String[]{"criterion_id", "content", "difficulty", "active", "created_at",
                    "updated_at"}) {
                String sql = "UPDATE interview_questions SET " + column + "=NULL WHERE id=?";
                assertThatThrownBy(() -> jdbc.update(sql, questionId))
                        .as(column).isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("null value in column \"" + column + "\"");
            }
            for (String invalid : new String[]{"", " ", "\nCâu hỏi", "Câu hỏi\t"}) {
                assertThatThrownBy(() -> insertQuestion(jdbc, criterionId, invalid, "EASY", null))
                        .as("content '%s'", invalid).isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("valid_interview_question_content");
                assertThatThrownBy(() -> insertQuestion(jdbc, criterionId, "Câu hỏi", "EASY", invalid))
                        .as("answer hint '%s'", invalid).isInstanceOf(DataIntegrityViolationException.class)
                        .hasMessageContaining("valid_interview_question_answer_hint");
            }
            assertThatThrownBy(() -> insertQuestion(jdbc, UUID.randomUUID(), "Không có tiêu chí", "EASY", null))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("interview_questions_criterion_id_fkey");
            assertThatThrownBy(() -> jdbc.update("UPDATE interview_questions SET criterion_id=? WHERE id=?",
                    UUID.randomUUID(), questionId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("interview_questions_criterion_id_fkey");

            var question = jdbc.queryForMap("SELECT * FROM interview_questions WHERE id=?", questionId);
            assertThat(question.get("criterion_id")).isEqualTo(criterionId);
            assertThat(question.get("content")).isEqualTo("Giới thiệu bản thân trong một phút.");
            assertThat(question.get("difficulty")).isEqualTo("EASY");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_questions", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    void criterionWithQuestionsCannotBeDeletedButMayBeEditedInPlace() throws Exception {
        try (var postgres = startPostgres()) {
            var jdbc = migrateAll(postgres);
            UUID frameworkId = insertFramework(jdbc, "DEV_CORE");
            UUID usedId = insertCriterion(jdbc, frameworkId, "Giao tiếp", 1);
            UUID unusedId = insertCriterion(jdbc, frameworkId, "Tư duy", 2);
            UUID activeQuestionId = insertQuestion(jdbc, usedId, "Giới thiệu bản thân.", "EASY", null);
            UUID inactiveQuestionId = insertQuestion(jdbc, usedId, "Câu hỏi cũ.", "MEDIUM", null);
            jdbc.update("UPDATE interview_questions SET active=FALSE WHERE id=?", inactiveQuestionId);

            assertThatThrownBy(() -> jdbc.update("DELETE FROM competency_criteria WHERE id=?", usedId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("interview_questions_criterion_id_fkey");
            // Deleting the framework would delete its criteria (V8 CASCADE), so it is refused too.
            assertThatThrownBy(() -> jdbc.update("DELETE FROM competency_frameworks WHERE id=?", frameworkId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("interview_questions_criterion_id_fkey");
            // A criterion without questions is still deleted normally.
            assertThat(jdbc.update("DELETE FROM competency_criteria WHERE id=?", unusedId)).isEqualTo(1);

            // Editing the criterion row keeps its id, so its questions follow the new name, weight and order.
            jdbc.update("UPDATE competency_criteria SET name='Giao tiếp rõ ràng', weight=100, sort_order=2 WHERE id=?",
                    usedId);
            assertThat(jdbc.queryForList("""
                    SELECT q.id FROM interview_questions q JOIN competency_criteria c ON c.id = q.criterion_id
                    WHERE c.name = 'Giao tiếp rõ ràng' ORDER BY q.content
                    """, UUID.class)).containsExactly(inactiveQuestionId, activeQuestionId);

            // The inactive question still blocks the deletion; only removing every question frees the criterion.
            jdbc.update("DELETE FROM interview_questions WHERE id=?", activeQuestionId);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM competency_criteria WHERE id=?", usedId))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("interview_questions_criterion_id_fkey");
            jdbc.update("DELETE FROM interview_questions WHERE id=?", inactiveQuestionId);
            assertThat(jdbc.update("DELETE FROM competency_frameworks WHERE id=?", frameworkId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM competency_criteria", Integer.class)).isZero();
        }
    }

    private static EmbeddedPostgres startPostgres() throws Exception {
        return EmbeddedPostgres.builder().setPort(0)
                .setServerConfig("listen_addresses", "127.0.0.1").start();
    }

    private static JdbcTemplate migrateAll(EmbeddedPostgres postgres) {
        var dataSource = postgres.getPostgresDatabase();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        return new JdbcTemplate(dataSource);
    }

    // Every table that existed before V9, so the upgrade can be compared row by row.
    private static Map<String, Object> snapshot(JdbcTemplate jdbc) {
        return Map.of(
                "accounts", jdbc.queryForList("SELECT * FROM user_accounts ORDER BY id"),
                "roles", jdbc.queryForList("SELECT * FROM user_roles ORDER BY user_id,role"),
                "departments", jdbc.queryForList("SELECT * FROM departments ORDER BY id"),
                "positions", jdbc.queryForList("SELECT * FROM positions ORDER BY id"),
                "frameworks", jdbc.queryForList("SELECT * FROM competency_frameworks ORDER BY id"),
                "criteria", jdbc.queryForList("SELECT * FROM competency_criteria ORDER BY id"),
                "permissions", jdbc.queryForList("SELECT * FROM permissions ORDER BY code"),
                "grants", jdbc.queryForList("SELECT * FROM role_permissions ORDER BY role_code,permission_code"));
    }

    private static UUID insertPosition(JdbcTemplate jdbc, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id,code,name,level,salary_min,salary_max,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, code, "Chức danh " + code, "Junior", 15_000_000L, 25_000_000L, CREATED_AT, CREATED_AT);
        return id;
    }

    private static UUID insertFramework(JdbcTemplate jdbc, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO competency_frameworks (id,code,name,created_at,updated_at) VALUES (?,?,?,?,?)",
                id, code, "Khung " + code, CREATED_AT, CREATED_AT);
        return id;
    }

    private static UUID insertCriterion(JdbcTemplate jdbc, UUID frameworkId, String name, int sortOrder) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO competency_criteria (id,framework_id,name,weight,sort_order)
                VALUES (?,?,?,?,?)
                """, id, frameworkId, name, new BigDecimal("50"), sortOrder);
        return id;
    }

    // active is left out on purpose, so the column default is used.
    private static UUID insertQuestion(JdbcTemplate jdbc, UUID criterionId, String content, String difficulty,
                                       String answerHint) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO interview_questions (id,criterion_id,content,difficulty,answer_hint,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?)
                """, id, criterionId, content, difficulty, answerHint, CREATED_AT, CREATED_AT);
        return id;
    }
}
