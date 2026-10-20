package vn.ttcs.recruitment.candidatesearch;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.department.DepartmentRepository;
import vn.ttcs.recruitment.requisition.RecruiterAssignmentAccess;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 392 (story S5-05): the SQL of the candidate search. The WHERE clause is assembled only from constant fragments;
 * every value, the keyword included, is a bound parameter. The scope is CandidateAccess.READABLE on the same row the
 * filters test, so a page and its count can never disagree with the single-record checks.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class CandidateSearchRepository {
    // A requisition whose candidates the reader may read: the READABLE rule on r alone, for a requisition filter that
    // must be checked before (or without) any application, like the board's opening filter.
    public static final String READABLE_OPENING = "(CAST(:" + CandidateAccess.ALL_CANDIDATES + " AS boolean) OR r.id IN ("
            + RecruiterAssignmentAccess.ASSIGNED_REQUISITION_IDS + ") OR r.department_id IN ("
            + DepartmentRepository.MANAGED_DEPARTMENT_IDS + "))";
    // The national form of the stored phone of a (job_applications): "+84..." read as "0...". Phones are stored as
    // typed (digits with an optional +), so a phone search and a duplicate check compare this form.
    public static final String NATIONAL_PHONE = "(CASE WHEN a.phone LIKE '+84%' THEN '0' || substr(a.phone, 4)"
            + " ELSE a.phone END)";
    private static final String NAME = CandidateSearchText.folded("a.full_name") + " LIKE :namePattern ESCAPE '!'";
    private static final String EMAIL = "a.normalized_email LIKE :emailPattern ESCAPE '!'";
    private static final String PHONE = "(a.phone LIKE :phonePattern ESCAPE '!' OR " + NATIONAL_PHONE
            + " LIKE :nationalPhonePattern ESCAPE '!')";
    private static final String FROM = """
             FROM job_applications a
             JOIN recruitment_requisitions r ON r.id = a.requisition_id
             JOIN positions p ON p.id = r.position_id
             JOIN candidate_applications ca ON ca.application_id = a.id
             LEFT JOIN application_screening_ratings sr ON sr.application_id = a.id
             LEFT JOIN recruitment_catalog_items src ON src.id = a.source_id
            """;
    private static final String COLUMNS = """
            SELECT a.id, ca.candidate_id, a.requisition_id, r.position_id, p.code AS position_code,
                   p.name AS position_name, a.posting_title, a.full_name, a.email, a.phone, a.stage,
                   src.name AS source_name, sr.rating, a.received_at,
                   ARRAY(SELECT c.name FROM application_screening_tags t
                         JOIN recruitment_catalog_items c ON c.id = t.tag_id
                         WHERE t.application_id = a.id ORDER BY c.sort_order, c.name, c.id) AS tag_names
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final CandidateAccess access;
    private final BusinessCalendar calendar;

    CandidateSearchRepository(NamedParameterJdbcTemplate jdbc, CandidateAccess access, BusinessCalendar calendar) {
        this.jdbc = jdbc;
        this.access = access;
        this.calendar = calendar;
    }

    // Empty when the requisition does not exist (404), false when it is outside the reader's scope (403).
    Optional<Boolean> readableRequisition(CandidateReader reader, UUID requisitionId) {
        return jdbc.query("SELECT " + READABLE_OPENING + " AS readable FROM recruitment_requisitions r WHERE r.id = :id",
                access.parameters(reader).addValue("id", requisitionId), (row, index) -> row.getBoolean("readable"))
                .stream().findFirst();
    }

    boolean positionExists(UUID positionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM positions WHERE id = :id)",
                new MapSqlParameterSource("id", positionId), Boolean.class));
    }

    // Has the position at least one requisition whose candidates the reader may read?
    boolean hasReadablePosition(CandidateReader reader, UUID positionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM recruitment_requisitions r"
                        + " WHERE r.position_id = :id AND " + READABLE_OPENING + ")",
                access.parameters(reader).addValue("id", positionId), Boolean.class));
    }

    // How many of these distinct ids are catalog values of this type (inactive values included: old applications keep
    // the source or tag they were given).
    int catalogValuesOfType(List<UUID> ids, String type) {
        if (ids.isEmpty()) return 0;
        Integer found = jdbc.queryForObject("SELECT count(*) FROM recruitment_catalog_items"
                        + " WHERE id IN (:ids) AND catalog_type = :type",
                new MapSqlParameterSource("ids", ids).addValue("type", type), Integer.class);
        return found == null ? 0 : found;
    }

    long count(CandidateReader reader, CandidateSearchCriteria criteria) {
        Query query = query(reader, criteria);
        Long total = jdbc.queryForObject("SELECT count(*)" + FROM + query.where(), query.parameters(), Long.class);
        return total == null ? 0 : total;
    }

    List<CandidateSearchView.Item> page(CandidateReader reader, CandidateSearchCriteria criteria, long offset, int size) {
        Query query = query(reader, criteria);
        String sql = COLUMNS + ", " + query.matchColumns() + FROM + query.where()
                + " ORDER BY a.received_at DESC, a.id DESC LIMIT :limit OFFSET :offset";
        return jdbc.query(sql, query.parameters().addValue("limit", size).addValue("offset", offset),
                (row, index) -> item(row));
    }

    private Query query(CandidateReader reader, CandidateSearchCriteria criteria) {
        MapSqlParameterSource parameters = access.parameters(reader).addValue("stages", criteria.effectiveStages());
        StringBuilder where = new StringBuilder(" WHERE ").append(CandidateAccess.READABLE)
                .append(" AND a.stage IN (:stages)");
        if (criteria.requisitionId() != null) {
            where.append(" AND a.requisition_id = :requisition");
            parameters.addValue("requisition", criteria.requisitionId());
        }
        if (criteria.positionId() != null) {
            where.append(" AND r.position_id = :position");
            parameters.addValue("position", criteria.positionId());
        }
        if (!criteria.sourceIds().isEmpty()) {
            where.append(" AND a.source_id IN (:sources)");
            parameters.addValue("sources", criteria.distinctSources());
        }
        if (!criteria.tagIds().isEmpty()) {
            List<UUID> tags = criteria.distinctTags();
            parameters.addValue("tags", tags).addValue("tagCount", tags.size());
            where.append(switch (criteria.tagMatch()) {
                case ANY -> " AND EXISTS (SELECT 1 FROM application_screening_tags ft"
                        + " WHERE ft.application_id = a.id AND ft.tag_id IN (:tags))";
                case ALL -> " AND (SELECT count(*) FROM application_screening_tags ft"
                        + " WHERE ft.application_id = a.id AND ft.tag_id IN (:tags)) = :tagCount";
            });
        }
        if (criteria.submittedFrom() != null) {
            where.append(" AND a.received_at >= :from");
            parameters.addValue("from", Timestamp.from(calendar.startOf(criteria.submittedFrom())));
        }
        if (criteria.submittedTo() != null) {
            where.append(" AND a.received_at < :to");
            parameters.addValue("to", Timestamp.from(calendar.startOf(criteria.submittedTo().plusDays(1))));
        }
        if (criteria.minRating() != null) {
            where.append(" AND sr.rating >= :minRating");
            parameters.addValue("minRating", criteria.minRating());
        }
        if (criteria.maxRating() != null) {
            where.append(" AND sr.rating <= :maxRating");
            parameters.addValue("maxRating", criteria.maxRating());
        }
        boolean phone = false;
        if (criteria.keyword() != null) {
            String digits = CandidateSearchText.phoneDigits(criteria.keyword());
            phone = digits != null;
            if (phone) {
                parameters.addValue("nationalPhonePattern",
                        CandidateSearchText.contains(CandidateSearchText.nationalPhone(criteria.keyword())));
            }
            parameters.addValue(CandidateSearchText.FROM_PARAMETER, CandidateSearchText.FOLD_FROM)
                    .addValue(CandidateSearchText.TO_PARAMETER, CandidateSearchText.FOLD_TO)
                    .addValue("namePattern", CandidateSearchText.contains(CandidateSearchText.fold(criteria.keyword())))
                    .addValue("emailPattern", CandidateSearchText.contains(
                            criteria.keyword().toLowerCase(java.util.Locale.ROOT)));
            where.append(" AND (").append(NAME).append(" OR ").append(EMAIL);
            if (phone) {
                parameters.addValue("phonePattern", CandidateSearchText.contains(digits));
                where.append(" OR ").append(PHONE);
            }
            where.append(")");
        }
        return new Query(where.toString(), parameters, criteria.keyword() != null, phone);
    }

    private static CandidateSearchView.Item item(ResultSet row) throws SQLException {
        List<String> matched = new ArrayList<>();
        if (row.getBoolean("match_name")) matched.add("NAME");
        if (row.getBoolean("match_email")) matched.add("EMAIL");
        if (row.getBoolean("match_phone")) matched.add("PHONE");
        ApplicationStage stage = ApplicationStage.valueOf(row.getString("stage"));
        return new CandidateSearchView.Item(row.getObject("id", UUID.class), row.getObject("candidate_id", UUID.class),
                row.getObject("requisition_id", UUID.class), row.getObject("position_id", UUID.class),
                row.getString("position_code"), row.getString("position_name"), row.getString("posting_title"),
                row.getString("full_name"), row.getString("email"), row.getString("phone"), stage,
                stage.displayName(), row.getString("source_name"), strings(row.getArray("tag_names")),
                row.getObject("rating", Integer.class), row.getTimestamp("received_at").toInstant(),
                List.copyOf(matched));
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) return List.of();
        try {
            return List.copyOf(Arrays.asList((String[]) array.getArray()));
        } finally {
            array.free();
        }
    }

    private record Query(String where, MapSqlParameterSource parameters, boolean keyword, boolean phone) {
        // Which of the keyword conditions held for the row; all false without a keyword.
        String matchColumns() {
            return (keyword ? NAME : "false") + " AS match_name, "
                    + (keyword ? EMAIL : "false") + " AS match_email, "
                    + (phone ? PHONE : "false") + " AS match_phone";
        }
    }
}
