package vn.ttcs.recruitment.candidatemerge;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import static vn.ttcs.recruitment.candidatemerge.CandidateMergeSnapshot.*;
import static vn.ttcs.recruitment.candidatemerge.MergeAuditException.Reason.*;

/**
 * Internal atomic persistence primitive, NOT a candidate merge API/service or a permission boundary.
 * The API orchestration uses appendAuthorized for trusted reauthorization after blocking locks and before mutation,
 * including authorization of all recorded members on retries. The legacy append primitive is internal/test-only.
 * Never expose the storage record directly as an HTTP response or call append from a controller.
 * Joins a READ_COMMITTED caller transaction: links, revisions, representative, head and journal are atomic.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class CandidateMergeJournal {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;

    public CandidateMergeJournal(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public CandidateMergeSnapshot snapshot(UUID first, UUID second) {
        if (first == null || second == null || first.equals(second)) throw new MergeAuditException(INVALID_REQUEST);
        var groups = jdbc.query("""
                SELECT id,revision,representative_application_id,merge_head_id FROM candidate_profiles
                WHERE id IN (?,?) ORDER BY id
                """, (rs,n) -> new Group(rs.getObject(1,UUID.class),rs.getLong(2),
                rs.getObject(3,UUID.class),rs.getObject(4,UUID.class)), first,second);
        var members = jdbc.query("""
                SELECT m.application_id,m.candidate_id,v.contact_revision FROM candidate_memberships m
                JOIN candidate_effective_contacts v ON v.application_id=m.application_id
                WHERE m.candidate_id IN (?,?) ORDER BY m.application_id
                """, (rs,n) -> new Member(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getLong(3)),first,second);
        if (groups.size() != 2 || groups.stream().anyMatch(g -> members.stream().noneMatch(m -> m.candidateId().equals(g.id()))))
            throw new MergeAuditException(EMPTY_OR_MISSING_GROUP);
        return new CandidateMergeSnapshot(groups,members);
    }

    public CandidateMergeRecord append(CandidateMergeCommand command) {
        return appendAuthorized(command, members -> { });
    }

    CandidateMergeRecord appendAuthorized(CandidateMergeCommand command,
            java.util.function.Consumer<List<Member>> authorize) {
        if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw new MergeAuditException(READ_ONLY_TRANSACTION);
        if (!"read committed".equals(jdbc.queryForObject("SHOW transaction_isolation",String.class)))
            throw new MergeAuditException(UNSUPPORTED_ISOLATION);
        byte[] hash = command.payloadHash();
        // Serialize same-key retries before group locks, including requests touching different groups.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                "candidate-merge:" + command.actorId() + ':' + command.requestKey());
        var existing = jdbc.query("SELECT * FROM candidate_merge_operations WHERE actor_id=? AND request_key=?",
                this::record,command.actorId(),command.requestKey());
        if (!existing.isEmpty()) {
            authorize.accept(existing.getFirst().before().members());
            if (!MessageDigest.isEqual(HexFormat.of().parseHex(existing.getFirst().payloadHash()),hash))
                throw new MergeAuditException(REQUEST_KEY_REUSED);
            return existing.getFirst();
        }
        lockSources(command.leftGroupId(),command.rightGroupId());
        var before = snapshot(command.leftGroupId(),command.rightGroupId());
        authorize.accept(before.members());
        if (!before.fingerprint().equals(command.expectedFingerprint())) throw new MergeAuditException(STALE_SNAPSHOT);
        if (before.members().stream().noneMatch(m -> m.applicationId().equals(command.representativeApplicationId())))
            throw new MergeAuditException(INVALID_REQUEST);
        var names = jdbc.query("SELECT full_name FROM user_accounts WHERE id=?",(rs,n) -> rs.getString(1),command.actorId());
        if (names.isEmpty()) throw new MergeAuditException(INVALID_REQUEST);
        var left = before.groups().getFirst(); var right = before.groups().getLast();
        long leftAfter = Math.addExact(left.revision(),1); long rightAfter = Math.addExact(right.revision(),1);
        String membersJson = JSON.writeValueAsString(before.members());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                UPDATE candidate_profiles SET revision=revision+1,
                    representative_application_id=CASE WHEN id=? THEN ?::uuid ELSE NULL END,merge_head_id=?
                WHERE id IN (?,?)
                """,command.resultGroupId(),command.representativeApplicationId(),id,left.id(),right.id());
        jdbc.update("UPDATE candidate_memberships SET candidate_id=? WHERE candidate_id=?",
                command.resultGroupId(),command.resultGroupId().equals(left.id()) ? right.id() : left.id());
        // Last substantial write. The database trigger samples its clock AFTER the source locks/writes.
        return jdbc.queryForObject("""
                INSERT INTO candidate_merge_operations(id,actor_id,actor_name,request_key,payload_hash,
                    left_group_id,right_group_id,result_group_id,left_revision_before,right_revision_before,
                    left_revision_after,right_revision_after,left_representative_before,right_representative_before,
                    representative_after,left_head_before,right_head_before,members_before)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb)) RETURNING *
                """,this::record,id,command.actorId(),names.getFirst(),command.requestKey(),hash,left.id(),right.id(),
                command.resultGroupId(),left.revision(),right.revision(),leftAfter,rightAfter,
                left.representativeApplicationId(),right.representativeApplicationId(),command.representativeApplicationId(),
                left.mergeHeadId(),right.mergeHeadId(),membersJson);
    }

    public Optional<CandidateMergeRecord> find(UUID id) {
        return jdbc.query("SELECT * FROM candidate_merge_operations WHERE id=?",this::record,id).stream().findFirst();
    }

    private void lockSources(UUID left, UUID right) {
        jdbc.queryForList("SELECT id FROM candidate_profiles WHERE id IN (?,?) ORDER BY id FOR NO KEY UPDATE",UUID.class,left,right);
        // Contact confirmation locks requisition then application. Take those locks in canonical order too.
        jdbc.queryForList("""
                SELECT r.id FROM recruitment_requisitions r WHERE r.id IN
                  (SELECT a.requisition_id FROM job_applications a JOIN candidate_memberships m ON m.application_id=a.id
                   WHERE m.candidate_id IN (?,?)) ORDER BY r.id FOR UPDATE
                """,UUID.class,left,right);
        jdbc.queryForList("""
                SELECT a.id FROM job_applications a JOIN candidate_memberships m ON m.application_id=a.id
                WHERE m.candidate_id IN (?,?) ORDER BY a.id FOR UPDATE OF a,m
                """,UUID.class,left,right);
    }

    private CandidateMergeRecord record(ResultSet rs, int ignored) throws SQLException {
        var groups = List.of(new Group(rs.getObject("left_group_id",UUID.class),rs.getLong("left_revision_before"),
                        rs.getObject("left_representative_before",UUID.class),rs.getObject("left_head_before",UUID.class)),
                new Group(rs.getObject("right_group_id",UUID.class),rs.getLong("right_revision_before"),
                        rs.getObject("right_representative_before",UUID.class),rs.getObject("right_head_before",UUID.class)));
        var members = Arrays.asList(JSON.readValue(rs.getString("members_before"),Member[].class));
        return new CandidateMergeRecord(rs.getObject("id",UUID.class),rs.getObject("actor_id",UUID.class),rs.getString("actor_name"),
                rs.getObject("request_key",UUID.class),HexFormat.of().formatHex(rs.getBytes("payload_hash")),
                new CandidateMergeSnapshot(groups,members),rs.getObject("result_group_id",UUID.class),
                rs.getObject("representative_after",UUID.class),rs.getLong("left_revision_after"),rs.getLong("right_revision_after"),
                rs.getTimestamp("recorded_at").toInstant(),rs.getTimestamp("undo_deadline").toInstant());
    }
}
