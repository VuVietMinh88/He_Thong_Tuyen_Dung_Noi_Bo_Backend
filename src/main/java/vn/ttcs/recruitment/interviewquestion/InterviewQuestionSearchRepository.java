package vn.ttcs.recruitment.interviewquestion;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

// Jira 223: the search behind GET /api/v1/interview-questions. Plain SQL like AccountSearchRepository and
// DepartmentRepository: every filter adds its condition only when it is used, and every value is a bound parameter,
// never pasted into the SQL, so a search text cannot change the query (no SQL injection).
// A position finds its questions through positions.competency_framework_id -> competency_criteria.framework_id ->
// interview_questions.criterion_id. Nothing is copied per position, so positions that share a framework find the
// same questions.
@Repository
public class InterviewQuestionSearchRepository {
    private static final String FROM = " FROM interview_questions q"
            + " JOIN competency_criteria c ON c.id = q.criterion_id"
            + " JOIN competency_frameworks f ON f.id = c.framework_id ";
    private static final String SELECT = "SELECT q.id, q.content, q.difficulty, q.answer_hint, q.active,"
            + " q.created_at, q.updated_at, c.id AS criterion_id, c.name AS criterion_name,"
            + " f.id AS framework_id, f.code AS framework_code, f.name AS framework_name" + FROM;
    // The questions of one framework stay together, criterion after criterion in the order HR set (sort_order),
    // oldest question first. The id breaks ties, so the pages never overlap or skip a question while nothing changes.
    private static final String ORDER = " ORDER BY f.code, c.sort_order, q.created_at, q.id ";

    private final NamedParameterJdbcTemplate jdbc;

    public InterviewQuestionSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // text is already prepared by InterviewQuestionService: Unicode NFC, one space between words, "" for no search.
    // Every other filter is skipped when it is null.
    public InterviewQuestionPage search(String text, UUID positionId, UUID criterionId,
                                        InterviewQuestionDifficulty difficulty, Boolean active, int page, int size) {
        var parameters = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        if (!text.isEmpty()) {
            // Escape the LIKE wildcards, so %, _ and ! typed by the user are searched as themselves.
            String literal = text.replace("!", "!!").replace("%", "!%").replace("_", "!_");
            parameters.addValue("text", "%" + literal + "%");
            // Compared like the text: each run of spaces, tabs and line breaks in the content counts as one space,
            // so "xử lý thế nào" also finds a question that breaks the line between "xử lý" and "thế nào".
            // lower() on both sides ignores upper/lower case, Vietnamese letters included (docs/api).
            where.append(" AND lower(regexp_replace(q.content, '[[:space:]]+', ' ', 'g'))"
                    + " LIKE lower(:text) ESCAPE '!' ");
        }
        if (positionId != null) {
            // The criteria of the framework the position uses. A position without a framework has no criteria:
            // comparing with NULL is never true, so it finds no question.
            parameters.addValue("positionId", positionId);
            where.append(" AND c.framework_id = (SELECT p.competency_framework_id FROM positions p"
                    + " WHERE p.id = :positionId) ");
        }
        if (criterionId != null) {
            parameters.addValue("criterionId", criterionId);
            where.append(" AND q.criterion_id = :criterionId ");
        }
        if (difficulty != null) {
            parameters.addValue("difficulty", difficulty.name());
            where.append(" AND q.difficulty = :difficulty ");
        }
        if (active != null) {
            parameters.addValue("active", active);
            where.append(" AND q.active = :active ");
        }
        long total = jdbc.queryForObject("SELECT count(*)" + FROM + where, parameters, Long.class);
        // long: page * size could pass the int limit for a very large page number.
        parameters.addValue("size", size).addValue("offset", (long) page * size);
        var items = jdbc.query(SELECT + where + ORDER + " LIMIT :size OFFSET :offset",
                parameters, (row, number) -> map(row));
        return new InterviewQuestionPage(items, page, size, total, (total + size - 1) / size);
    }

    // Plain SQL on the positions table, like EvaluationCriteriaService: this package needs no position entity.
    public boolean positionExists(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM positions WHERE id = :id)",
                new MapSqlParameterSource("id", id), Boolean.class));
    }

    // The same fields as InterviewQuestionView.from, so a list item equals what GET /{id} returns.
    private static InterviewQuestionView map(ResultSet row) throws SQLException {
        return new InterviewQuestionView(row.getObject("id", UUID.class),
                new InterviewQuestionView.Criterion(row.getObject("criterion_id", UUID.class),
                        row.getString("criterion_name")),
                new InterviewQuestionView.Framework(row.getObject("framework_id", UUID.class),
                        row.getString("framework_code"), row.getString("framework_name")),
                row.getString("content"), InterviewQuestionDifficulty.valueOf(row.getString("difficulty")),
                row.getString("answer_hint"), row.getBoolean("active"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }
}
