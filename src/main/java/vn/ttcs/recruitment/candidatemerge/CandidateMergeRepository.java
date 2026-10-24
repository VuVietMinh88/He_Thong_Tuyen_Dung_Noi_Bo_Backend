package vn.ttcs.recruitment.candidatemerge;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.requisition.RecruiterAssignmentAccess;
import vn.ttcs.recruitment.security.AccessScope;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Task 368 (story S5-01, "Hiển thị so sánh cạnh nhau trước khi gộp; gộp giữ lại toàn bộ lịch sử ứng tuyển"): the
 * candidate rows, links and locks behind the merge preview and the merge. Every method runs in
 * the caller's transaction (MANDATORY): the preview's REPEATABLE READ snapshot, or the write's READ COMMITTED
 * transaction with the house limits of CandidateWriteGuard.lockWriter.
 * <p>
 * Lock order of a write about whole candidates, after the caller's account and session: the requisitions of every
 * application involved, then the applications, each set FOR NO KEY UPDATE in id order (the house order of every write
 * about one application, so a stage change, an offer or a talent pool entry of one of them waits for the merge or the
 * merge for it), then the candidate rows FOR UPDATE in id order. A FOR UPDATE candidate row also makes the foreign key
 * of a new link, offer or talent pool entry naming it wait, and the FOR SHARE lock of CandidateLinks.
 * <p>
 * Task 370 (story S5-01, "Gộp nhầm có thể hoàn tác trong 24 giờ"): the reads and writes of an undo, which takes the
 * same locks (the target's applications, then the target, the source and the candidates the merge re-pointed) and puts
 * the links back as the trail of task 369 says.
 */
@Repository
class CandidateMergeRepository {
    // May the caller write this application? The rule of CandidateWriteGuard.requireWritable in one fragment over the
    // aliases a and r: READABLE with the read scope, and a SCOPED writer must be one of the requisition's recruiters
    // now. Bound with CandidateAccess.parameters plus :writeAll.
    private static final String WRITABLE = "(" + CandidateAccess.READABLE + " AND (CAST(:writeAll AS boolean) OR "
            + "a.requisition_id IN (" + RecruiterAssignmentAccess.ASSIGNED_REQUISITION_IDS + ")))";
    private static final String APPLICATIONS = """
            FROM candidate_applications ca
            JOIN job_applications a ON a.id = ca.application_id
            JOIN recruitment_requisitions r ON r.id = a.requisition_id
            """;
    // The statuses of an offer that still holds the candidate's one slot (V61 offers_one_active_candidate).
    private static final String ACTIVE_OFFER = "('DRAFT','CHANGES_REQUESTED','PENDING_HR','PENDING_HIGHER','APPROVED',"
            + "'SENT','ACCEPTED')";

    private final NamedParameterJdbcTemplate jdbc;
    private final CandidateAccess access;

    CandidateMergeRepository(NamedParameterJdbcTemplate jdbc, CandidateAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    // A candidate row: its email (the link key) and where it was merged, if it was.
    record Candidate(UUID id, String normalizedEmail, UUID mergedInto, Instant mergedAt) {
        boolean merged() { return mergedInto != null; }

        // The candidate whose applications this profile's are now: itself, or the one it was merged into.
        UUID profileId() { return merged() ? mergedInto : id; }

        @Override public String toString() { return "Candidate[" + id + ", email redacted]"; }
    }

    // One link of a candidate: the application, its requisition and when it was linked.
    record Link(UUID candidateId, UUID applicationId, UUID requisitionId, Instant linkedAt) { }

    // One readable application of a candidate as the preview compares it, with its effective contact
    // (CandidateDuplicateRepository) and the forms that are compared.
    record PreviewApplication(UUID applicationId, UUID requisitionId, String postingTitle, String departmentName,
                              String fullName, String email, String phone, String normalizedEmail,
                              String nationalPhone, ApplicationStage stage, Instant receivedAt) {
        @Override public String toString() { return "PreviewApplication[" + applicationId + ", personal data redacted]"; }
    }

    // A candidate the APIs know: merged into another one, or linked to at least one application. A row left without
    // applications (they were deleted, V32 cascades the link) is unknown, like in the unified profile.
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<Candidate> profile(UUID candidateId) {
        return jdbc.query("""
                SELECT id, normalized_email, merged_into, merged_at FROM candidates c WHERE id = :id
                AND (merged_into IS NOT NULL OR EXISTS (SELECT 1 FROM candidate_applications WHERE candidate_id = c.id))
                """, new MapSqlParameterSource("id", candidateId), (row, number) -> candidate(row)).stream().findFirst();
    }

    // May the reader read at least one of the candidate's applications?
    @Transactional(propagation = Propagation.MANDATORY)
    boolean hasReadableApplication(CandidateReader reader, UUID candidateId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 " + APPLICATIONS
                        + " WHERE ca.candidate_id = :candidate AND " + CandidateAccess.READABLE + ")",
                access.parameters(reader).addValue("candidate", candidateId), Boolean.class));
    }

    // The rows FOR UPDATE, in id order; an unknown id is simply missing from the result.
    @Transactional(propagation = Propagation.MANDATORY)
    List<Candidate> lockCandidates(Collection<UUID> candidateIds) {
        if (candidateIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT id, normalized_email, merged_into, merged_at FROM candidates WHERE id IN (:ids)
                ORDER BY id FOR UPDATE
                """, new MapSqlParameterSource("ids", Set.copyOf(candidateIds)), (row, number) -> candidate(row));
    }

    // The candidates merged into this one (they point at it, V68_2), with the time of their merge.
    @Transactional(propagation = Propagation.MANDATORY)
    List<Candidate> mergedInto(UUID candidateId) {
        return jdbc.query("""
                SELECT id, normalized_email, merged_into, merged_at FROM candidates WHERE merged_into = :id ORDER BY id
                """, new MapSqlParameterSource("id", candidateId), (row, number) -> candidate(row));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    List<Link> links(UUID candidateId) {
        return jdbc.query("""
                SELECT ca.candidate_id, ca.application_id, a.requisition_id, ca.linked_at FROM candidate_applications ca
                JOIN job_applications a ON a.id = ca.application_id WHERE ca.candidate_id = :candidate
                ORDER BY ca.application_id
                """, new MapSqlParameterSource("candidate", candidateId), (row, number) -> new Link(
                row.getObject("candidate_id", UUID.class), row.getObject("application_id", UUID.class),
                row.getObject("requisition_id", UUID.class),
                row.getTimestamp("linked_at").toInstant()));
    }

    // The requisitions of these applications, then the applications, FOR NO KEY UPDATE in id order. An application
    // deleted in between is simply not locked; the caller reads the links again under the candidate locks.
    @Transactional(propagation = Propagation.MANDATORY)
    void lockApplications(Collection<Link> links) {
        if (links.isEmpty()) return;
        jdbc.queryForList("""
                SELECT id FROM recruitment_requisitions WHERE id IN (:ids) ORDER BY id FOR NO KEY UPDATE
                """, new MapSqlParameterSource("ids", Set.copyOf(links.stream().map(Link::requisitionId).toList())),
                UUID.class);
        jdbc.queryForList("""
                SELECT id FROM job_applications WHERE id IN (:ids) ORDER BY id FOR NO KEY UPDATE
                """, new MapSqlParameterSource("ids", Set.copyOf(links.stream().map(Link::applicationId).toList())),
                UUID.class);
    }

    // May the caller write every application now linked to these candidates? True when none is linked. NONE never
    // writes anything (the write APIs answer 403 before this; the preview reports it).
    @Transactional(propagation = Propagation.MANDATORY)
    boolean allWritable(CandidateReader reader, AccessScope write, Collection<UUID> candidateIds) {
        if (write == AccessScope.NONE) return false;
        if (candidateIds.isEmpty()) return true;
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT coalesce(bool_and(" + WRITABLE + "), true) "
                        + APPLICATIONS + " WHERE ca.candidate_id IN (:candidates)",
                access.parameters(reader).addValue("writeAll", write == AccessScope.ALL)
                        .addValue("candidates", Set.copyOf(candidateIds)), Boolean.class));
    }

    // Does the candidate hold the one active offer slot (V61)? The offer names the candidate for good (its
    // candidate_id never changes), so its applications cannot move away while it is active.
    @Transactional(propagation = Propagation.MANDATORY)
    boolean hasActiveOffer(UUID candidateId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM offers WHERE candidate_id = :id"
                + " AND status IN " + ACTIVE_OFFER + ")", new MapSqlParameterSource("id", candidateId), Boolean.class));
    }

    // Does the candidate have an active talent pool entry (V65_5)?
    @Transactional(propagation = Propagation.MANDATORY)
    boolean hasActiveTalentPoolEntry(UUID candidateId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM talent_pool_entries WHERE candidate_id = :id AND removed_at IS NULL)
                """, new MapSqlParameterSource("id", candidateId), Boolean.class));
    }

    // The readable applications of the candidate for the preview, newest first.
    @Transactional(propagation = Propagation.MANDATORY)
    List<PreviewApplication> readableApplications(CandidateReader reader, UUID candidateId) {
        return jdbc.query("SELECT a.id, a.requisition_id, a.posting_title, d.name AS department_name,"
                        + " COALESCE(cc.full_name, a.full_name) AS full_name, COALESCE(cc.email, a.email) AS email,"
                        + " " + CandidateDuplicateRepository.EFFECTIVE_PHONE + " AS phone,"
                        + " " + CandidateDuplicateRepository.EFFECTIVE_EMAIL + " AS normalized_email,"
                        + " " + CandidateDuplicateRepository.EFFECTIVE_NATIONAL_PHONE + " AS national_phone,"
                        + " a.stage, a.received_at " + APPLICATIONS
                        + " JOIN departments d ON d.id = r.department_id"
                        + " LEFT JOIN application_confirmed_contacts cc ON cc.application_id = a.id"
                        + " WHERE ca.candidate_id = :candidate AND " + CandidateAccess.READABLE
                        + " ORDER BY a.received_at DESC, a.id DESC",
                access.parameters(reader).addValue("candidate", candidateId), (row, number) -> new PreviewApplication(
                        row.getObject("id", UUID.class), row.getObject("requisition_id", UUID.class),
                        row.getString("posting_title"), row.getString("department_name"), row.getString("full_name"),
                        row.getString("email"), row.getString("phone"), row.getString("normalized_email"),
                        row.getString("national_phone"), ApplicationStage.valueOf(row.getString("stage")),
                        row.getTimestamp("received_at").toInstant()));
    }

    // Moves every link of the source to the target, linked now (the old times are in the merge trail first).
    @Transactional(propagation = Propagation.MANDATORY)
    int moveLinks(UUID sourceId, UUID targetId, Instant at) {
        return jdbc.update("""
                UPDATE candidate_applications SET candidate_id = :target, linked_at = :at WHERE candidate_id = :source
                """, new MapSqlParameterSource("source", sourceId).addValue("target", targetId)
                .addValue("at", Timestamp.from(at)));
    }

    // The source points at the target from now on, and so do the candidates that pointed at the source (their own
    // merge time stays: they were merged then, only their destination moved).
    @Transactional(propagation = Propagation.MANDATORY)
    void markMerged(UUID sourceId, UUID targetId, Instant at) {
        var parameters = new MapSqlParameterSource("source", sourceId).addValue("target", targetId)
                .addValue("at", Timestamp.from(at));
        jdbc.update("UPDATE candidates SET merged_into = :target WHERE merged_into = :source", parameters);
        jdbc.update("UPDATE candidates SET merged_into = :target, merged_at = :at WHERE id = :source", parameters);
    }

    // The candidate each of these applications is linked to now; a deleted application (its link cascades) is missing.
    @Transactional(propagation = Propagation.MANDATORY)
    Map<UUID, UUID> linkedCandidates(Collection<UUID> applicationIds) {
        Map<UUID, UUID> linked = new HashMap<>();
        if (applicationIds.isEmpty()) return linked;
        jdbc.query("SELECT application_id, candidate_id FROM candidate_applications WHERE application_id IN (:ids)",
                new MapSqlParameterSource("ids", Set.copyOf(applicationIds)), row -> {
                    linked.put(row.getObject("application_id", UUID.class), row.getObject("candidate_id", UUID.class));
                });
        return linked;
    }

    // The candidate's links made at or after this instant by an application whose submitted email is one of these:
    // what CandidateLinks sent to the candidate because an email led there through merged_into.
    @Transactional(propagation = Propagation.MANDATORY)
    List<Link> linkedSince(UUID candidateId, Instant since, Collection<String> emails) {
        if (emails.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT ca.candidate_id, ca.application_id, a.requisition_id, ca.linked_at FROM candidate_applications ca
                JOIN job_applications a ON a.id = ca.application_id
                WHERE ca.candidate_id = :candidate AND ca.linked_at >= :since AND a.normalized_email IN (:emails)
                ORDER BY ca.application_id
                """, new MapSqlParameterSource("candidate", candidateId).addValue("since", Timestamp.from(since))
                .addValue("emails", Set.copyOf(emails)), (row, number) -> new Link(
                row.getObject("candidate_id", UUID.class), row.getObject("application_id", UUID.class),
                row.getObject("requisition_id", UUID.class), row.getTimestamp("linked_at").toInstant()));
    }

    // Was an offer created at or after this instant for one of these applications? It names the candidate the
    // application belonged to then (offers.candidate_id never changes, V61).
    @Transactional(propagation = Propagation.MANDATORY)
    boolean hasOfferSince(Collection<UUID> applicationIds, Instant since) {
        if (applicationIds.isEmpty()) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM offers WHERE application_id IN (:ids) AND created_at >= :since)
                """, new MapSqlParameterSource("ids", Set.copyOf(applicationIds)).addValue("since", Timestamp.from(since)),
                Boolean.class));
    }

    // Was one of these applications added to the talent pool at or after this instant (for the candidate it belonged
    // to then)?
    @Transactional(propagation = Propagation.MANDATORY)
    boolean hasTalentPoolEntrySince(Collection<UUID> applicationIds, Instant since) {
        if (applicationIds.isEmpty()) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM talent_pool_entries WHERE source_application_id IN (:ids) AND added_at >= :since)
                """, new MapSqlParameterSource("ids", Set.copyOf(applicationIds)).addValue("since", Timestamp.from(since)),
                Boolean.class));
    }

    // Links each application to the candidate again, with this link time (the one kept in the trail, or its own).
    @Transactional(propagation = Propagation.MANDATORY)
    void relink(UUID candidateId, List<Link> links) {
        jdbc.batchUpdate("""
                UPDATE candidate_applications SET candidate_id = :candidate, linked_at = :linkedAt
                WHERE application_id = :application
                """, links.stream().map(link -> new MapSqlParameterSource("candidate", candidateId)
                .addValue("application", link.applicationId()).addValue("linkedAt", Timestamp.from(link.linkedAt())))
                .toArray(MapSqlParameterSource[]::new));
    }

    // The source is a profile of its own again, and the candidates the merge re-pointed point at it as before, with
    // the time of their own merge.
    @Transactional(propagation = Propagation.MANDATORY)
    void unmarkMerged(UUID sourceId, Map<UUID, Instant> redirects) {
        jdbc.batchUpdate("UPDATE candidates SET merged_into = :source, merged_at = :at WHERE id = :id",
                redirects.entrySet().stream().map(redirect -> new MapSqlParameterSource("source", sourceId)
                        .addValue("id", redirect.getKey()).addValue("at", Timestamp.from(redirect.getValue())))
                        .toArray(MapSqlParameterSource[]::new));
        jdbc.update("UPDATE candidates SET merged_into = NULL, merged_at = NULL WHERE id = :source",
                new MapSqlParameterSource("source", sourceId));
    }

    // The full names of these staff accounts, for the merge history.
    @Transactional(propagation = Propagation.MANDATORY)
    Map<UUID, String> staffNames(Collection<UUID> accountIds) {
        Map<UUID, String> names = new HashMap<>();
        if (accountIds.isEmpty()) return names;
        jdbc.query("SELECT id, full_name FROM user_accounts WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", Set.copyOf(accountIds)),
                row -> { names.put(row.getObject("id", UUID.class), row.getString("full_name")); });
        return names;
    }

    // How many applications each of these merges moved (task 369's details).
    @Transactional(propagation = Propagation.MANDATORY)
    Map<UUID, Integer> movedCounts(Collection<UUID> mergeIds) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (mergeIds.isEmpty()) return counts;
        jdbc.query("""
                SELECT merge_id, count(*) AS moved FROM candidate_merge_applications WHERE merge_id IN (:ids)
                GROUP BY merge_id
                """, new MapSqlParameterSource("ids", Set.copyOf(mergeIds)),
                row -> { counts.put(row.getObject("merge_id", UUID.class), row.getInt("moved")); });
        return counts;
    }

    private static Candidate candidate(java.sql.ResultSet row) throws java.sql.SQLException {
        Timestamp mergedAt = row.getTimestamp("merged_at");
        return new Candidate(row.getObject("id", UUID.class), row.getString("normalized_email"),
                row.getObject("merged_into", UUID.class), mergedAt == null ? null : mergedAt.toInstant());
    }
}
