package vn.ttcs.recruitment.candidate;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.application.ApplicationStage;

import java.util.List;
import java.util.UUID;

/**
 * Task 361 (story S4-09): the reads behind the unified profile. Both run in the caller's read transaction (MANDATORY,
 * CandidateProfileService: REPEATABLE READ), take no lock and select ids, snapshots and names only.
 * <p>
 * The scope is filtered in SQL with CandidateAccess.READABLE, the same fragment findApplication and every later list
 * use, so an application outside the reader's scope is never read into Java at all, and a list and a single record
 * can never disagree.
 */
@Repository
class CandidateProfileRepository {
    // Every application of the profile the reader may read, newest first. The recruiter is the requisition's current
    // PRIMARY only (a SUPPORTING recruiter is never shown as the recruiter); the hiring manager is the direct manager
    // of the requisition's department (manager_user_id is NOT NULL, V5). The posting title is the snapshot taken when the
    // application was received; the department name is the current one. Never cv.content, cv.sha256,
    // a.tracking_code_hash, a.normalized_email, a.requisition_id in the output, or any user_accounts.email.
    // Task 345: the time and the optional reason of the candidate's withdrawal come from the application's one WITHDRAWN
    // stage event (V34 unique index), so the join never adds a row.
    // Task 350: the referral (V36, at most one per application: its key is the application id) with the referrer's
    // current full name; never the referrer's email. The referrer key is NOT NULL and RESTRICT, so a referral always
    // has its account.
    private static final String READABLE_APPLICATIONS = """
            SELECT a.id, a.job_posting_id, a.posting_title, a.received_at, a.stage, a.full_name, a.email, a.phone,
                   a.cover_letter, d.name AS department_name, d.manager_user_id, m.full_name AS manager_name,
                   p.recruiter_id, pu.full_name AS recruiter_name, src.name AS source_name,
                   cv.filename, cv.content_type, cv.size_bytes,
                   w.occurred_at AS withdrawn_at, w.reason AS withdrawal_reason,
                   ref.referrer_id, ru.full_name AS referrer_name, ref.note AS referral_note
            FROM candidate_applications ca
            JOIN job_applications a ON a.id = ca.application_id
            JOIN recruitment_requisitions r ON r.id = a.requisition_id
            JOIN departments d ON d.id = r.department_id
            JOIN user_accounts m ON m.id = d.manager_user_id
            LEFT JOIN requisition_recruiters p ON p.requisition_id = a.requisition_id AND p.assignment_role = 'PRIMARY'
            LEFT JOIN user_accounts pu ON pu.id = p.recruiter_id
            LEFT JOIN recruitment_catalog_items src ON src.id = a.source_id
            LEFT JOIN application_cvs cv ON cv.application_id = a.id
            LEFT JOIN application_stage_events w ON w.application_id = a.id AND w.stage_after = 'WITHDRAWN'
            LEFT JOIN application_referrals ref ON ref.application_id = a.id
            LEFT JOIN user_accounts ru ON ru.id = ref.referrer_id
            WHERE ca.candidate_id = :candidate
            """ + " AND " + CandidateAccess.READABLE + " ORDER BY a.received_at DESC, a.id DESC";

    private final NamedParameterJdbcTemplate jdbc;
    private final CandidateAccess access;

    CandidateProfileRepository(NamedParameterJdbcTemplate jdbc, CandidateAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    // Does the profile exist with at least one application? A profile whose applications were all deleted is unknown
    // too (404). Checked before the scope, so the answer depends on the record only, never on the reader.
    @Transactional(propagation = Propagation.MANDATORY)
    boolean hasApplications(UUID candidateId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM candidate_applications WHERE candidate_id = :candidate)",
                new MapSqlParameterSource("candidate", candidateId), Boolean.class));
    }

    // The applications of the profile the reader may read, newest first; empty when none is in the scope.
    @Transactional(propagation = Propagation.MANDATORY)
    List<ReadableApplication> readableApplications(CandidateReader reader, UUID candidateId) {
        return jdbc.query(READABLE_APPLICATIONS, access.parameters(reader).addValue("candidate", candidateId),
                (row, number) -> new ReadableApplication(
                        row.getObject("id", UUID.class),
                        row.getObject("job_posting_id", UUID.class),
                        row.getString("posting_title"),
                        row.getTimestamp("received_at").toInstant(),
                        ApplicationStage.valueOf(row.getString("stage")),
                        row.getString("full_name"),
                        row.getString("email"),
                        row.getString("phone"),
                        row.getString("cover_letter"),
                        row.getString("department_name"),
                        row.getObject("manager_user_id", UUID.class),
                        row.getString("manager_name"),
                        row.getObject("recruiter_id", UUID.class),
                        row.getString("recruiter_name"),
                        row.getString("source_name"),
                        row.getString("filename"),
                        row.getString("content_type"),
                        row.getObject("size_bytes", Integer.class),
                        row.getTimestamp("withdrawn_at") == null ? null : row.getTimestamp("withdrawn_at").toInstant(),
                        row.getString("withdrawal_reason"),
                        row.getObject("referrer_id", UUID.class),
                        row.getString("referrer_name"),
                        row.getString("referral_note")));
    }
}
