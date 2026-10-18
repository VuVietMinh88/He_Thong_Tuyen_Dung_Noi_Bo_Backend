package vn.ttcs.recruitment.jobposting;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.ttcs.recruitment.requisition.RequisitionStatus;
import vn.ttcs.recruitment.security.AccessScope;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

// Task 303: every read of postings goes through SELECT_ROW, so the detail, the list and the answer of every write
// show the same data. Names (position, department, catalog values, creator) are read now, not copied: a renamed
// value shows its new name.
// Task 308: the two LATERAL joins read the posting's latest submission and latest review from job_posting_events
// (newest revision first). The decision of APPROVE reads the latest submitter from this same query under the posting
// lock, so actions.canApprove and the server's answer use one rule on one row.
// Task 309: the preview and the public portal read the same SELECT_ROW (the portal only adds a filter), so at any
// one moment the preview and the public posting show the same content, live names included.
// Task 310: the publication trail (publisher and time, V23) and the publisher's current name.
@Repository
class JobPostingReadRepository {
    static final String SELECT_ROW = """
            SELECT p.id, p.requisition_id, r.status AS requisition_status, p.status, p.revision, p.title,
                   r.position_id, pos.name AS position_name, r.department_id, d.name AS department_name, r.headcount,
                   r.proposed_salary_min, r.proposed_salary_max, p.job_description, p.candidate_requirements,
                   p.benefits, p.work_location_id, wl.name AS work_location_name, p.employment_type_id,
                   et.name AS employment_type_name, p.application_deadline, p.show_salary, p.created_by,
                   c.full_name AS created_by_name, p.created_at, p.updated_at,
                   submission.actor_id AS latest_submitter_id, review.event_type AS review_action,
                   review.comment AS review_comment, review.actor_id AS review_actor_id,
                   review.actor_name AS review_actor_name, review.occurred_at AS review_at,
                   p.published_by, pb.full_name AS published_by_name, p.published_at
            FROM job_postings p
            JOIN recruitment_requisitions r ON r.id = p.requisition_id
            JOIN positions pos ON pos.id = r.position_id
            JOIN departments d ON d.id = r.department_id
            JOIN user_accounts c ON c.id = p.created_by
            LEFT JOIN recruitment_catalog_items wl ON wl.id = p.work_location_id
            LEFT JOIN recruitment_catalog_items et ON et.id = p.employment_type_id
            LEFT JOIN LATERAL (SELECT e.actor_id FROM job_posting_events e
                WHERE e.job_posting_id = p.id AND e.event_type = 'SUBMITTED'
                ORDER BY e.revision DESC LIMIT 1) submission ON TRUE
            LEFT JOIN LATERAL (SELECT e.event_type, e.comment, e.actor_id, a.full_name AS actor_name, e.occurred_at
                FROM job_posting_events e JOIN user_accounts a ON a.id = e.actor_id
                WHERE e.job_posting_id = p.id AND e.event_type IN ('APPROVED', 'REVISION_REQUESTED')
                ORDER BY e.revision DESC LIMIT 1) review ON TRUE
            LEFT JOIN user_accounts pb ON pb.id = p.published_by
            """;

    // Task 309: public means a status in JobPostingService.PUBLIC_STATUSES, an application deadline that is today or
    // later (today: the business date, read by the caller) and a requisition still in SOURCE_STATUSES (task 290
    // pauses, fills and cancels requisitions: a paused one hides its postings until it is resumed; task 292 closes the
    // postings of a filled or cancelled one). Statuses are bound by name: PgJDBC cannot bind a Java enum.
    private static final String PUBLIC_WHERE = """
             WHERE p.status IN (:public) AND p.application_deadline >= :today
               AND r.status IN (:sourceStatuses)
            """;
    private final NamedParameterJdbcTemplate jdbc;

    JobPostingReadRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<JobPostingRow> findRow(UUID id) {
        return jdbc.query(SELECT_ROW + " WHERE p.id = :id", new MapSqlParameterSource("id", id),
                (row, number) -> map(row)).stream().findFirst();
    }

    // One page of the postings the caller may see, newest first; the id keeps the order stable. status = null means
    // every status. SCOPED callers only get postings of the requisitions they are assigned to: the filter is part of
    // the SQL query, so other postings never leave the database. The status is bound by name: PgJDBC cannot bind a
    // Java enum.
    JobPostingPage page(AccessScope scope, UUID me, JobPostingStatus status, int page, int size) {
        var parameters = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        if (status != null) {
            parameters.addValue("status", status.name());
            where.append(" AND p.status = :status ");
        }
        if (scope != AccessScope.ALL) {
            parameters.addValue("me", me);
            where.append(" AND p.requisition_id IN (SELECT requisition_id FROM requisition_recruiters"
                    + " WHERE recruiter_id = :me) ");
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM job_postings p" + where, parameters, Long.class);
        parameters.addValue("size", size).addValue("offset", (long) page * size);
        var items = jdbc.query(SELECT_ROW + where + " ORDER BY p.created_at DESC, p.id DESC LIMIT :size OFFSET :offset",
                parameters, (row, number) -> JobPostingSummary.from(map(row)));
        return new JobPostingPage(items, page, size, total, (total + size - 1) / size);
    }

    // Task 309: the posting candidates may see, by id. The same SELECT_ROW as the preview, so both are built from
    // the same row; the filter only decides whether the row is public.
    Optional<JobPostingRow> findPublic(UUID id, LocalDate today) {
        return jdbc.query(SELECT_ROW + PUBLIC_WHERE + " AND p.id = :id", publicParameters(today).addValue("id", id),
                (row, number) -> map(row)).stream().findFirst();
    }

    // Task 309: one page of the public postings, newest publication first; the id keeps the order stable. Task 310:
    // the publication time is the posting's own published_at (V23 job_postings_published_at_idx), no longer read from
    // the PUBLISHED event.
    PublicJobPostingPage publicPage(LocalDate today, int page, int size) {
        var parameters = publicParameters(today);
        long total = jdbc.queryForObject("SELECT count(*) FROM job_postings p"
                + " JOIN recruitment_requisitions r ON r.id = p.requisition_id" + PUBLIC_WHERE, parameters, Long.class);
        parameters.addValue("size", size).addValue("offset", (long) page * size);
        var items = jdbc.query(SELECT_ROW + PUBLIC_WHERE
                        + " ORDER BY p.published_at DESC, p.id DESC LIMIT :size OFFSET :offset", parameters,
                (row, number) -> PublicJobPostingSummary.of(PublicJobPostingView.of(map(row))));
        return new PublicJobPostingPage(items, page, size, total, (total + size - 1) / size);
    }

    private static MapSqlParameterSource publicParameters(LocalDate today) {
        return new MapSqlParameterSource("public", JobPostingService.PUBLIC_STATUS_NAMES)
                .addValue("today", today).addValue("sourceStatuses", JobPostingService.SOURCE_STATUS_NAMES);
    }

    private static JobPostingRow map(ResultSet row) throws SQLException {
        String reviewAction = row.getString("review_action");
        OffsetDateTime reviewAt = row.getObject("review_at", OffsetDateTime.class);
        OffsetDateTime publishedAt = row.getObject("published_at", OffsetDateTime.class);
        return new JobPostingRow(row.getObject("id", UUID.class), row.getObject("requisition_id", UUID.class),
                RequisitionStatus.valueOf(row.getString("requisition_status")),
                JobPostingStatus.valueOf(row.getString("status")), row.getInt("revision"), row.getString("title"),
                row.getObject("position_id", UUID.class), row.getString("position_name"),
                row.getObject("department_id", UUID.class), row.getString("department_name"),
                row.getInt("headcount"), row.getObject("proposed_salary_min", Long.class),
                row.getObject("proposed_salary_max", Long.class), row.getString("job_description"),
                row.getString("candidate_requirements"), row.getString("benefits"),
                row.getObject("work_location_id", UUID.class), row.getString("work_location_name"),
                row.getObject("employment_type_id", UUID.class), row.getString("employment_type_name"),
                row.getObject("application_deadline", LocalDate.class), row.getBoolean("show_salary"),
                row.getObject("created_by", UUID.class), row.getString("created_by_name"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant(),
                row.getObject("latest_submitter_id", UUID.class),
                reviewAction == null ? null : JobPostingEventType.valueOf(reviewAction),
                row.getString("review_comment"), row.getObject("review_actor_id", UUID.class),
                row.getString("review_actor_name"), reviewAt == null ? null : reviewAt.toInstant(),
                row.getObject("published_by", UUID.class), row.getString("published_by_name"),
                publishedAt == null ? null : publishedAt.toInstant());
    }
}
