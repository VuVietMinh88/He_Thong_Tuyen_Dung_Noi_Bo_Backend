package vn.ttcs.recruitment.candidatemerge;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateReader;

import java.util.List;
import java.util.UUID;

/**
 * Task 367 (story S5-01, "Phát hiện trùng theo email và số điện thoại khi có hồ sơ mới"): the reads behind the
 * duplicate suggestions of one candidate. All run in the caller's read transaction (MANDATORY,
 * CandidateDuplicateService: REPEATABLE READ) and take no lock.
 * <p>
 * Two applications are the same person's when they share the <em>effective</em> normalized email or the effective
 * phone in its national form. Effective means the contact a recruiter confirmed for the application (task 335,
 * application_confirmed_contacts, V68_1) when there is one, otherwise the application's own snapshot (V26): a
 * confirmed contact replaces the submitted one for every later use, so a corrected email or phone is what matches,
 * and the submitted value no longer does. The email is normalized like the storage's (ApplicationSubmission:
 * invisible edges trimmed, Locale.ROOT lowercase; the confirmation stores its normalized_email with the same rule).
 * The phone is compared in its national form, "+84..." read as "0...", the expression of the candidate search and of
 * the manual duplicate check (CandidateSearchRepository.NATIONAL_PHONE), here over the effective phone.
 * <p>
 * Applications with the same snapshot email are already one candidate (CandidateLinks, task 360), so this report adds
 * what the automatic link cannot see: the same phone under another email, and a confirmed email that belongs to
 * another candidate's applications. It is computed when it is read, so an application received a moment ago is
 * compared on the next request; nothing is stored and nothing waits for a job.
 * <p>
 * The scope is applied on both sides with CandidateAccess.READABLE: only the applications of the candidate that the
 * reader may read provide the email and phone compared, and only readable applications of other candidates are
 * reported, so a caller learns nothing about an application it could not already open.
 */
@Repository
class CandidateDuplicateRepository {
    // At most this many candidates, the most recent matching application first, and this many of each candidate's
    // matching applications; one more row of each is read only to report that the list was cut.
    static final int MAX_CANDIDATES = 20;
    static final int MAX_APPLICATIONS = 10;
    // The effective contact of a (job_applications) with its confirmed contact cc (LEFT JOIN, V68_1).
    static final String EFFECTIVE_EMAIL = "COALESCE(cc.normalized_email, a.normalized_email)";
    static final String EFFECTIVE_PHONE = "COALESCE(cc.phone, a.phone)";
    // The national form of the effective phone, as CandidateSearchRepository.NATIONAL_PHONE reads the snapshot one.
    static final String EFFECTIVE_NATIONAL_PHONE = "(CASE WHEN " + EFFECTIVE_PHONE + " LIKE '+84%' THEN '0' || substr("
            + EFFECTIVE_PHONE + ", 4) ELSE " + EFFECTIVE_PHONE + " END)";
    // The readable applications of :candidate with their effective contact; the keys every other application is
    // compared with.
    private static final String KEYS = """
            SELECT %s AS email, %s AS phone
            FROM candidate_applications ca
            JOIN job_applications a ON a.id = ca.application_id
            JOIN recruitment_requisitions r ON r.id = a.requisition_id
            LEFT JOIN application_confirmed_contacts cc ON cc.application_id = a.id
            WHERE ca.candidate_id = :candidate AND %s
            """.formatted(EFFECTIVE_EMAIL, EFFECTIVE_NATIONAL_PHONE, CandidateAccess.READABLE);
    // The readable applications of other candidates that share an effective email or phone with the keys. The windows
    // say how each candidate matched over all its matching applications (not only the ones shown), rank the candidates
    // by their newest matching application and number each candidate's applications, newest first.
    private static final String DUPLICATES = """
            WITH keys AS (%s),
            matches AS (
                SELECT a.id, ca.candidate_id, a.requisition_id, a.posting_title, COALESCE(cc.full_name, a.full_name) AS full_name,
                       a.stage, a.received_at,
                       %s IN (SELECT email FROM keys) AS by_email,
                       %s IN (SELECT phone FROM keys) AS by_phone
                FROM candidate_applications ca
                JOIN job_applications a ON a.id = ca.application_id
                JOIN recruitment_requisitions r ON r.id = a.requisition_id
                LEFT JOIN application_confirmed_contacts cc ON cc.application_id = a.id
                WHERE ca.candidate_id <> :candidate AND %s
            ),
            ranked AS (
                SELECT m.*, bool_or(m.by_email) OVER candidate AS candidate_by_email,
                       bool_or(m.by_phone) OVER candidate AS candidate_by_phone,
                       max(m.received_at) OVER candidate AS newest,
                       row_number() OVER (PARTITION BY m.candidate_id ORDER BY m.received_at DESC, m.id DESC) AS application_rank
                FROM matches m
                WHERE m.by_email OR m.by_phone
                WINDOW candidate AS (PARTITION BY m.candidate_id)
            ),
            numbered AS (
                SELECT ranked.*, dense_rank() OVER (ORDER BY newest DESC, candidate_id DESC) AS candidate_rank FROM ranked
            )
            SELECT * FROM numbered WHERE candidate_rank <= :maxCandidates + 1 AND application_rank <= :maxApplications + 1
            ORDER BY candidate_rank, application_rank
            """.formatted(KEYS, EFFECTIVE_EMAIL, EFFECTIVE_NATIONAL_PHONE, CandidateAccess.READABLE);

    private final NamedParameterJdbcTemplate jdbc;
    private final CandidateAccess access;

    CandidateDuplicateRepository(NamedParameterJdbcTemplate jdbc, CandidateAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    // One matching application, as read; CandidateDuplicateService groups the rows per candidate.
    record Row(UUID applicationId, UUID candidateId, UUID requisitionId, String postingTitle, String fullName,
               ApplicationStage stage, java.time.Instant receivedAt, boolean byEmail, boolean byPhone,
               boolean candidateByEmail, boolean candidateByPhone, int candidateRank, int applicationRank) {
        @Override public String toString() { return "Row[" + applicationId + ", personal data redacted]"; }
    }

    // Does the candidate exist with at least one application? Checked before the scope, like the unified profile
    // (CandidateProfileRepository.hasApplications), so the answer depends on the record only.
    @Transactional(propagation = Propagation.MANDATORY)
    boolean hasApplications(UUID candidateId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM candidate_applications WHERE candidate_id = :candidate)",
                new MapSqlParameterSource("candidate", candidateId), Boolean.class));
    }

    // May the reader read at least one of the candidate's applications?
    @Transactional(propagation = Propagation.MANDATORY)
    boolean hasReadableApplication(CandidateReader reader, UUID candidateId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (" + KEYS + ")",
                access.parameters(reader).addValue("candidate", candidateId), Boolean.class));
    }

    // The matching applications of at most MAX_CANDIDATES + 1 other candidates, at most MAX_APPLICATIONS + 1 each.
    @Transactional(propagation = Propagation.MANDATORY)
    List<Row> duplicates(CandidateReader reader, UUID candidateId) {
        return jdbc.query(DUPLICATES, access.parameters(reader).addValue("candidate", candidateId)
                        .addValue("maxCandidates", MAX_CANDIDATES).addValue("maxApplications", MAX_APPLICATIONS),
                (row, number) -> new Row(row.getObject("id", UUID.class), row.getObject("candidate_id", UUID.class),
                        row.getObject("requisition_id", UUID.class), row.getString("posting_title"),
                        row.getString("full_name"), ApplicationStage.valueOf(row.getString("stage")),
                        row.getTimestamp("received_at").toInstant(), row.getBoolean("by_email"),
                        row.getBoolean("by_phone"), row.getBoolean("candidate_by_email"),
                        row.getBoolean("candidate_by_phone"), row.getInt("candidate_rank"), row.getInt("application_rank")));
    }
}
