package vn.ttcs.recruitment.application;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateReader;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * Task 408 (story S5-08, "Ứng viên thêm tay vẫn đi qua đúng luồng phát hiện trùng"): the duplicate flow of a manual
 * entry. The rules themselves are the public form's and are not repeated here: the same normalized email in the same
 * requisition is refused by the storage's arbiter (409), and an email already known elsewhere links the new
 * application to the same candidate profile (CandidateLinks). This report adds what a staff member needs to compare
 * before or after entering: the applications that share the email (the profile's, as the unified profile groups them)
 * and other profiles with an application on the same phone number. Both lists respect CandidateAccess.READABLE, so a
 * caller only learns about applications it could already open, and both use the storage's own normalization
 * (ApplicationSubmission). A phone is compared in its national form: "+84" and a leading "0" are the same number,
 * read by the expression of the candidate search (CandidateSearchRepository.NATIONAL_PHONE). Whether the email is
 * already in the requisition is its own EXISTS, never read from the capped lists.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class ManualApplicationDuplicates {
    static final int MAX_MATCHES = 20;
    // The national form of a stored phone: digits with an optional + (V26), "+84..." read as "0..." (index V65_2).
    static final String NATIONAL_PHONE = vn.ttcs.recruitment.candidatesearch.CandidateSearchRepository.NATIONAL_PHONE;
    private static final String COLUMNS = """
            SELECT a.id, ca.candidate_id, a.requisition_id, a.posting_title, a.full_name, a.stage, a.received_at
            FROM job_applications a
            JOIN recruitment_requisitions r ON r.id = a.requisition_id
            JOIN candidate_applications ca ON ca.application_id = a.id
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final CandidateAccess access;

    public ManualApplicationDuplicates(NamedParameterJdbcTemplate jdbc, CandidateAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public record Match(UUID applicationId, UUID candidateId, UUID requisitionId, String postingTitle, String fullName,
                        ApplicationStage stage, String stageName, java.time.Instant receivedAt, boolean sameRequisition) {
        @Override public String toString() { return "Match[" + applicationId + ", personal data redacted]"; }
    }

    public record Report(boolean emailInRequisition, List<Match> sameEmail, List<Match> samePhone) {
        public boolean empty() { return sameEmail.isEmpty() && samePhone.isEmpty(); }
    }

    // Whether this normalized email already has an application in the requisition (the entry would meet the storage's
    // 409), then the readable applications with this email and the readable applications of other emails with this
    // phone, newest first, at most MAX_MATCHES each; exceptApplication (the entry itself) is left out everywhere.
    // requisitionId marks the matches of the requisition being entered; the caller may write to it, so its own
    // duplicate is no secret to the caller.
    public Report report(CandidateReader reader, UUID requisitionId, String normalizedEmail, String phone,
                         UUID exceptApplication) {
        var parameters = access.parameters(reader).addValue("email", normalizedEmail)
                .addValue("phone", nationalPhone(phone)).addValue("requisition", requisitionId)
                .addValue("except", exceptApplication).addValue("limit", MAX_MATCHES);
        List<Match> sameEmail = jdbc.query(COLUMNS + " WHERE a.normalized_email = :email"
                        + " AND (CAST(:except AS uuid) IS NULL OR a.id <> :except) AND " + CandidateAccess.READABLE
                        + " ORDER BY a.received_at DESC, a.id DESC LIMIT :limit", parameters,
                (row, index) -> match(row, requisitionId));
        List<Match> samePhone = jdbc.query(COLUMNS + " WHERE " + NATIONAL_PHONE + " = :phone"
                        + " AND a.normalized_email <> :email"
                        + " AND (CAST(:except AS uuid) IS NULL OR a.id <> :except) AND " + CandidateAccess.READABLE
                        + " ORDER BY a.received_at DESC, a.id DESC LIMIT :limit", parameters,
                (row, index) -> match(row, requisitionId));
        boolean inRequisition = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM job_applications WHERE requisition_id = :requisition
                    AND normalized_email = :email AND (CAST(:except AS uuid) IS NULL OR id <> :except))
                """, parameters, Boolean.class));
        return new Report(inRequisition, sameEmail, samePhone);
    }

    // The storage's phone (separators already removed by ApplicationSubmission) in its national form.
    static String nationalPhone(String phone) {
        return phone.startsWith("+84") ? "0" + phone.substring(3) : phone;
    }

    private static Match match(ResultSet row, UUID requisitionId) throws SQLException {
        ApplicationStage stage = ApplicationStage.valueOf(row.getString("stage"));
        UUID requisition = row.getObject("requisition_id", UUID.class);
        return new Match(row.getObject("id", UUID.class), row.getObject("candidate_id", UUID.class), requisition,
                row.getString("posting_title"), row.getString("full_name"), stage, stage.displayName(),
                row.getTimestamp("received_at").toInstant(), requisition.equals(requisitionId));
    }
}
