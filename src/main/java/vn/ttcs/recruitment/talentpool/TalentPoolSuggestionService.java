package vn.ttcs.recruitment.talentpool;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateLabels;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.candidatesearch.CandidateSearchRepository;
import vn.ttcs.recruitment.common.ApiException;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 400 (story S5-06, "Khi mở vị trí mới, hệ thống gợi ý ứng viên phù hợp từ kho"): read-only suggestions of pooled
 * candidates for a requisition. The basis is the requisition's position: its name and its posting's title (a title of
 * interest matches when one contains the other, compared as CandidateLabels keys, at least 3 characters) and its
 * required skills (equal keys). An entry is suggested when at least one title or skill matches. Candidates who already
 * applied to this requisition are left out, and so is every candidate who is not AVAILABLE (TalentPoolCandidateStatus:
 * hired, or with an application still in process anywhere). Order: a title match first, then more matched skills, then the newest entry.
 * <p>
 * Checks: 401/403 before any lookup (live CANDIDATES read), 400 for the limit, 404 for an unknown requisition and 403
 * when the reader may not read its candidates; then only entries whose source application the reader may read
 * (CandidateAccess.READABLE), each logged as a PROFILE read, in one REPEATABLE READ snapshot. Nothing is written but the
 * log: no application is created, rejected or moved.
 */
@Service
public class TalentPoolSuggestionService {
    static final int MAX_LIMIT = 50;
    private static final int MIN_TITLE_KEY = 3;
    private final NamedParameterJdbcTemplate jdbc;
    private final CandidateAccess access;
    private final TalentPoolRepository pool;
    private final TransactionTemplate reads;

    TalentPoolSuggestionService(NamedParameterJdbcTemplate jdbc, CandidateAccess access, TalentPoolRepository pool,
                                PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.access = access;
        this.pool = pool;
        reads = new TransactionTemplate(transactions);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(10);
    }

    public TalentPoolSuggestionView suggest(Jwt jwt, UUID requisitionId, int limit) {
        CandidateReader reader = access.requireReader(jwt);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra điều kiện gợi ý.",
                    Map.of("limit", "Số gợi ý từ 1 đến " + MAX_LIMIT + "."));
        }
        return Objects.requireNonNull(reads.execute(status -> {
            Opening opening = opening(reader, requisitionId);
            List<String> titleKeys = opening.titles().stream().map(CandidateLabels::key)
                    .filter(key -> key.length() >= MIN_TITLE_KEY).distinct().toList();
            List<String> skillKeys = opening.skills().stream().map(CandidateLabels.Label::key).toList();
            List<TalentPoolSuggestionView.Suggestion> items = titleKeys.isEmpty() && skillKeys.isEmpty() ? List.of()
                    : suggestions(reader, requisitionId, titleKeys, skillKeys, limit);
            items.forEach(item -> access.recordGranted(reader, CandidateAccessKind.PROFILE, item.entry().candidateId(),
                    List.of(item.entry().sourceApplicationId())));
            return new TalentPoolSuggestionView(requisitionId, opening.positionId(), opening.titles(),
                    opening.skills().stream().map(CandidateLabels.Label::display).toList(),
                    TalentPoolSuggestionView.ADVICE, items);
        }));
    }

    private record Opening(UUID positionId, List<String> titles, List<CandidateLabels.Label> skills) { }

    private Opening opening(CandidateReader reader, UUID requisitionId) {
        var found = jdbc.query("""
                SELECT r.position_id, p.name AS position_name, jp.title AS posting_title,
                """ + CandidateSearchRepository.READABLE_OPENING + """
                 AS readable
                FROM recruitment_requisitions r JOIN positions p ON p.id = r.position_id
                LEFT JOIN job_postings jp ON jp.requisition_id = r.id
                WHERE r.id = :id
                """, access.parameters(reader).addValue("id", requisitionId), (row, index) -> {
                    List<String> titles = new ArrayList<>(new LinkedHashSet<>(java.util.stream.Stream.of(
                            row.getString("position_name"), row.getString("posting_title")).filter(Objects::nonNull)
                            .map(String::strip).filter(title -> !title.isEmpty()).toList()));
                    return Map.entry(new Opening(row.getObject("position_id", UUID.class), titles, List.of()),
                            row.getBoolean("readable"));
                }).stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REQUISITION_NOT_FOUND",
                        "Không tìm thấy yêu cầu tuyển dụng."));
        if (!found.getValue()) throw new AccessDeniedException("Requisition is outside candidate read scope");
        List<CandidateLabels.Label> skills = jdbc.query("SELECT display_name, skill_key FROM position_required_skills"
                        + " WHERE position_id = :id ORDER BY sort_order",
                new MapSqlParameterSource("id", found.getKey().positionId()),
                (row, index) -> new CandidateLabels.Label(row.getString("display_name"), row.getString("skill_key")));
        return new Opening(found.getKey().positionId(), found.getKey().titles(), skills);
    }

    private List<TalentPoolSuggestionView.Suggestion> suggestions(CandidateReader reader, UUID requisitionId,
                                                                  List<String> titleKeys, List<String> skillKeys,
                                                                  int limit) {
        MapSqlParameterSource parameters = access.parameters(reader).addValue("requisition", requisitionId)
                .addValue("limit", limit).addValue("minTitle", MIN_TITLE_KEY);
        StringBuilder titleMatch = new StringBuilder("false");
        for (int index = 0; index < titleKeys.size(); index++) {
            String name = "title" + index;
            parameters.addValue(name, titleKeys.get(index));
            // The interest inside the basis title (only for interests of at least 3 characters), or the other way round.
            titleMatch.append(" OR (strpos(CAST(:").append(name).append(" AS text), t.interest_key) > 0")
                    .append(" AND length(t.interest_key) >= :minTitle)")
                    .append(" OR strpos(t.interest_key, CAST(:").append(name).append(" AS text)) > 0");
        }
        String skillMatch = "false";
        if (!skillKeys.isEmpty()) {
            parameters.addValue("skills", skillKeys);
            skillMatch = "s.interest_key IN (:skills)";
        }
        String sql = "SELECT * FROM (" + TalentPoolRepository.COLUMNS + """
                , ARRAY(SELECT t.display_name FROM talent_pool_interests t WHERE t.entry_id = e.id AND t.kind = 'TITLE'
                        AND (""" + titleMatch + """
                ) ORDER BY t.sort_order) AS matched_titles,
                  ARRAY(SELECT s.display_name FROM talent_pool_interests s WHERE s.entry_id = e.id AND s.kind = 'SKILL'
                        AND """ + " " + skillMatch + """
                 ORDER BY s.sort_order) AS matched_skills
                """ + TalentPoolRepository.FROM + """
                 WHERE e.removed_at IS NULL AND """ + CandidateAccess.READABLE + """
                   AND NOT EXISTS (SELECT 1 FROM job_applications x JOIN candidate_applications xc ON xc.application_id = x.id
                                   WHERE x.requisition_id = :requisition AND xc.candidate_id = e.candidate_id)
                ) matched
                WHERE candidate_status = 'AVAILABLE' AND (cardinality(matched_titles) > 0 OR cardinality(matched_skills) > 0)
                ORDER BY cardinality(matched_titles) > 0 DESC, cardinality(matched_skills) DESC, added_at DESC, id DESC
                LIMIT :limit
                """;
        return jdbc.query(sql, parameters, (row, index) -> new TalentPoolSuggestionView.Suggestion(
                TalentPoolRepository.view(row), strings(row.getArray("matched_titles")),
                strings(row.getArray("matched_skills"))));
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) return List.of();
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }
}
