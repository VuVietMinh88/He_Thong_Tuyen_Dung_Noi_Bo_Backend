package vn.ttcs.recruitment.candidatemerge;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static vn.ttcs.recruitment.candidatemerge.CandidateMergeService.*;
import static vn.ttcs.recruitment.candidatemerge.CandidateMergeApi.*;

/** Restores identity links only. Application/contact/CV/pipeline/email data is never restored from snapshots. */
@Repository
@Transactional(propagation=Propagation.MANDATORY)
class CandidateMergeUndo {
    private final CandidateMergeJournal journal;
    private final CandidateMergeAccess access;
    private final JdbcTemplate jdbc;
    CandidateMergeUndo(CandidateMergeJournal journal,CandidateMergeAccess access,JdbcTemplate jdbc) {
        this.journal=journal;this.access=access;this.jdbc=jdbc;
    }

    UndoResult undo(Jwt jwt,UUID actor,UUID id,UUID requestKey) {
        byte[] hash=CandidateMergeSnapshot.hash("candidate-merge-undo-v1\n"+actor+'\n'+id);
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))","candidate-undo:"+actor+':'+requestKey);
        var replay=jdbc.query("SELECT id,merge_operation_id,payload_hash,recorded_at FROM candidate_merge_undo_events WHERE actor_id=? AND request_key=?",
                (rs,n)->new Replay(new UndoResult(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getTimestamp(4).toInstant()),rs.getBytes(3)),actor,requestKey);
        if (!replay.isEmpty()) {
            var old=replay.getFirst();var operation=journal.find(old.result().mergeOperationId()).orElseThrow(CandidateMergeService::missing);
            access.require(jwt,operation.before().members(),true);
            if (!java.security.MessageDigest.isEqual(hash,old.hash())) throw conflict("IDEMPOTENCY_KEY_REUSED");
            return old.result();
        }
        var operation=journal.find(id).orElseThrow(CandidateMergeService::missing);
        var groups=operation.before().groups();UUID left=groups.getFirst().id(),right=groups.getLast().id();
        journal.lockSources(left,right);
        access.require(jwt,operation.before().members(),true);
        var current=jdbc.query("SELECT id,revision,representative_application_id,merge_head_id FROM candidate_profiles WHERE id IN (?,?) ORDER BY id",
                (rs,n)->new CandidateMergeSnapshot.Group(rs.getObject(1,UUID.class),rs.getLong(2),rs.getObject(3,UUID.class),rs.getObject(4,UUID.class)),left,right);
        boolean undone=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM candidate_merge_undo_events WHERE merge_operation_id=?)",Boolean.class,id);
        if (undone || current.size()!=2 || current.stream().anyMatch(g->!id.equals(g.mergeHeadId())))
            throw conflict("CANDIDATE_UNDO_DEPENDENCY");
        var actual=jdbc.query("SELECT application_id,candidate_id FROM candidate_memberships WHERE candidate_id IN (?,?) ORDER BY application_id",
                (rs,n)->new CandidateMergeSnapshot.Member(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),0),left,right);
        var expected=operation.before().members();
        if (actual.size()!=expected.size() || actual.stream().anyMatch(m->!m.candidateId().equals(operation.resultGroupId()))
                || !actual.stream().map(CandidateMergeSnapshot.Member::applicationId).toList()
                        .equals(expected.stream().map(CandidateMergeSnapshot.Member::applicationId).toList()))
            throw conflict("CANDIDATE_UNDO_MEMBERS_CHANGED");
        if (!jdbc.queryForObject("SELECT candidate_merge_undo_open(undo_deadline,clock_timestamp()) FROM candidate_merge_operations WHERE id=?",Boolean.class,id))
            throw conflict("CANDIDATE_UNDO_EXPIRED");
        for (var group:groups) {
            // Revisions are monotonic; historical revisions must never be reinstated (ABA protection).
            jdbc.update("UPDATE candidate_profiles SET revision=revision+1,representative_application_id=?,merge_head_id=? WHERE id=?",
                    group.representativeApplicationId(),group.mergeHeadId(),group.id());
        }
        var named=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc);
        for (var group:groups) named.update("UPDATE candidate_memberships SET candidate_id=:candidate WHERE application_id IN (:members)",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("candidate",group.id())
                        .addValue("members",expected.stream().filter(m->m.candidateId().equals(group.id()))
                                .map(CandidateMergeSnapshot.Member::applicationId).toList()));
        String name=jdbc.queryForObject("SELECT full_name FROM user_accounts WHERE id=?",String.class,actor);
        UUID undoId=UUID.randomUUID();
        // Check the database clock again at the final write: crossing the deadline rolls back every preceding update.
        var result=jdbc.query("""
                INSERT INTO candidate_merge_undo_events(id,merge_operation_id,actor_id,actor_name,request_key,payload_hash,
                    left_revision_before,right_revision_before,left_revision_after,right_revision_after)
                SELECT ?,?,?,?,?,?,?,?, ?,? FROM candidate_merge_operations WHERE id=? AND candidate_merge_undo_open(undo_deadline,clock_timestamp())
                RETURNING id,merge_operation_id,recorded_at
                """,(rs,n)->new UndoResult(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getTimestamp(3).toInstant()),
                undoId,id,actor,name,requestKey,hash,current.getFirst().revision(),current.getLast().revision(),
                Math.addExact(current.getFirst().revision(),1),Math.addExact(current.getLast().revision(),1),id);
        if (result.isEmpty()) throw conflict("CANDIDATE_UNDO_EXPIRED");
        return result.getFirst();
    }
    private record Replay(UndoResult result,byte[] hash) { }
}
