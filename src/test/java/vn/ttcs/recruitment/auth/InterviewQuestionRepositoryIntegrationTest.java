package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import vn.ttcs.recruitment.competency.CompetencyCriterion;
import vn.ttcs.recruitment.competency.CompetencyCriterionRepository;
import vn.ttcs.recruitment.competency.CompetencyFramework;
import vn.ttcs.recruitment.competency.CompetencyFrameworkRepository;
import vn.ttcs.recruitment.interviewquestion.InterviewQuestion;
import vn.ttcs.recruitment.interviewquestion.InterviewQuestionDifficulty;
import vn.ttcs.recruitment.interviewquestion.InterviewQuestionRepository;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Hibernate runs with ddl-auto=validate, so this context only starts when InterviewQuestion matches V9.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InterviewQuestionRepositoryIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-07T00:00:00Z");

    @Autowired private InterviewQuestionRepository questions;
    @Autowired private CompetencyFrameworkRepository frameworks;
    @Autowired private CompetencyCriterionRepository criteria;
    @Autowired private JdbcTemplate jdbc;

    private CompetencyCriterion communication;
    private CompetencyCriterion thinking;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void resetQuestionBank() {
        // Questions first: V9 does not let the criteria below be deleted while questions point to them.
        jdbc.update("DELETE FROM interview_questions");
        // ON DELETE CASCADE removes the criteria too.
        jdbc.update("DELETE FROM competency_frameworks");
        var framework = frameworks.saveAndFlush(new CompetencyFramework("DEV_CORE", "Năng lực lập trình viên",
                null, CREATED_AT));
        communication = criteria.saveAndFlush(new CompetencyCriterion(framework.getId(), "Giao tiếp", null,
                new BigDecimal("50"), 1));
        thinking = criteria.saveAndFlush(new CompetencyCriterion(framework.getId(), "Tư duy", null,
                new BigDecimal("50"), 2));
    }

    @Test
    void savesQuestionsAndReadsTheQuestionsOfACriterionOldestFirst() {
        // Saved out of creation order on purpose: the repository must sort by createdAt.
        var later = questions.saveAndFlush(new InterviewQuestion(communication.getId(),
                "Thiết kế lớp cho giỏ hàng.\n- Thêm sản phẩm\n- Tính tổng tiền", InterviewQuestionDifficulty.HARD,
                null, CREATED_AT.plusSeconds(60)));
        var first = questions.saveAndFlush(new InterviewQuestion(communication.getId(),
                "Giới thiệu bản thân trong một phút.", InterviewQuestionDifficulty.EASY,
                "Ngắn gọn, đúng trọng tâm\nvà có ví dụ.", CREATED_AT));
        var other = questions.saveAndFlush(new InterviewQuestion(thinking.getId(),
                "Ước lượng số quán cà phê ở Hà Nội.", InterviewQuestionDifficulty.MEDIUM, null, CREATED_AT));

        var row = jdbc.queryForMap("SELECT * FROM interview_questions WHERE id=?", first.getId());
        assertThat(row.get("criterion_id")).isEqualTo(communication.getId());
        assertThat(row.get("content")).isEqualTo("Giới thiệu bản thân trong một phút.");
        // The enum is stored by name, which the V9 CHECK expects.
        assertThat(row.get("difficulty")).isEqualTo("EASY");
        assertThat(row.get("answer_hint")).isEqualTo("Ngắn gọn, đúng trọng tâm\nvà có ví dụ.");
        assertThat(row.get("active")).isEqualTo(true);
        assertThat(row.get("created_at")).isEqualTo(Timestamp.from(CREATED_AT));
        assertThat(row.get("updated_at")).isEqualTo(Timestamp.from(CREATED_AT));

        var loaded = questions.findByCriterionIdOrderByCreatedAtAscIdAsc(communication.getId());
        assertThat(loaded).extracting(InterviewQuestion::getId).containsExactly(first.getId(), later.getId());
        assertThat(loaded).extracting(InterviewQuestion::getDifficulty)
                .containsExactly(InterviewQuestionDifficulty.EASY, InterviewQuestionDifficulty.HARD);
        var reloaded = loaded.get(1);
        assertThat(reloaded.getCriterionId()).isEqualTo(communication.getId());
        assertThat(reloaded.getContent()).isEqualTo("Thiết kế lớp cho giỏ hàng.\n- Thêm sản phẩm\n- Tính tổng tiền");
        assertThat(reloaded.getAnswerHint()).isNull();
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.getCreatedAt()).isEqualTo(CREATED_AT.plusSeconds(60));
        assertThat(reloaded.getUpdatedAt()).isEqualTo(CREATED_AT.plusSeconds(60));
        assertThat(questions.findByCriterionIdOrderByCreatedAtAscIdAsc(thinking.getId()))
                .extracting(InterviewQuestion::getId).containsExactly(other.getId());
        assertThat(questions.findByCriterionIdOrderByCreatedAtAscIdAsc(UUID.randomUUID())).isEmpty();

        // An inactive question is still a question of its criterion (it is kept as history).
        jdbc.update("UPDATE interview_questions SET active=FALSE WHERE id=?", later.getId());
        assertThat(questions.findByCriterionIdOrderByCreatedAtAscIdAsc(communication.getId()))
                .extracting(InterviewQuestion::isActive).containsExactly(true, false);
    }

    @Test
    void savesLongTextBecauseContentAndAnswerHintAreText() {
        // Longer than any VARCHAR limit of the schema (at most 1000 characters); TEXT keeps it whole.
        String content = "Câu hỏi dài. ".repeat(400).trim();
        String hint = "Gợi ý dài. ".repeat(400).trim();
        var saved = questions.saveAndFlush(new InterviewQuestion(thinking.getId(), content,
                InterviewQuestionDifficulty.MEDIUM, hint, CREATED_AT));

        var reloaded = questions.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getContent()).hasSize(content.length()).isEqualTo(content);
        assertThat(reloaded.getAnswerHint()).isEqualTo(hint);
    }

    @Test
    void databaseConstraintsRejectInvalidQuestionsSavedThroughJpa() {
        var kept = questions.saveAndFlush(new InterviewQuestion(communication.getId(), "Giới thiệu bản thân.",
                InterviewQuestionDifficulty.EASY, null, CREATED_AT));

        assertThatThrownBy(() -> questions.saveAndFlush(new InterviewQuestion(UUID.randomUUID(), "Không có tiêu chí",
                InterviewQuestionDifficulty.EASY, null, CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("interview_questions_criterion_id_fkey");
        assertThatThrownBy(() -> questions.saveAndFlush(new InterviewQuestion(communication.getId(), " Câu hỏi",
                InterviewQuestionDifficulty.EASY, null, CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("valid_interview_question_content");
        assertThatThrownBy(() -> questions.saveAndFlush(new InterviewQuestion(communication.getId(), "Câu hỏi",
                InterviewQuestionDifficulty.EASY, "", CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("valid_interview_question_answer_hint");
        assertThatThrownBy(() -> questions.saveAndFlush(new InterviewQuestion(communication.getId(), "Câu hỏi",
                null, null, CREATED_AT)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("difficulty");

        // A criterion with a question cannot be deleted through JPA either.
        assertThatThrownBy(() -> criteria.delete(communication))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("interview_questions_criterion_id_fkey");
        assertThat(criteria.existsById(communication.getId())).isTrue();
        assertThat(questions.findAll()).extracting(InterviewQuestion::getId).containsExactly(kept.getId());

        // A criterion without questions is deleted normally.
        criteria.delete(thinking);
        assertThat(criteria.existsById(thinking.getId())).isFalse();
    }
}
