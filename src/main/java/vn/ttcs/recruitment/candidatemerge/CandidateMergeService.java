package vn.ttcs.recruitment.candidatemerge;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.cvsuggestion.ContactValues;
import static vn.ttcs.recruitment.candidatemerge.CandidateMergeApi.*;

@Service
public class CandidateMergeService {
    private final CandidateMergeJournal journal;
    private final CandidateMergeAccess access;
    private final JdbcTemplate jdbc;
    private final Validator validator;
    private final TransactionTemplate read,write;
    private final boolean enabled;

    CandidateMergeService(CandidateMergeJournal journal,CandidateMergeAccess access,JdbcTemplate jdbc,
            Validator validator,PlatformTransactionManager manager,
            @Value("${app.candidate-merges.enabled:false}") boolean enabled) {
        this.journal=journal;this.access=access;this.jdbc=jdbc;this.validator=validator;this.enabled=enabled;
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setTimeout(5);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        write=new TransactionTemplate(manager);write.setTimeout(5);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Preview preview(Jwt jwt,PreviewRequest request) {
        validate(request);
        return database(()->read.execute(ignored->{
            timeout(false); access.require(jwt,List.of(),false);
            var snapshot=journal.snapshot(group(request.leftApplicationId()),group(request.rightApplicationId()));
            access.require(jwt,snapshot.members(),false); available();
            var members=jdbc.query("""
                    SELECT v.*,m.candidate_id FROM candidate_memberships m
                    JOIN candidate_effective_contacts v ON v.application_id=m.application_id
                    WHERE m.candidate_id IN (?,?) ORDER BY m.application_id
                    """,(rs,n)->new Member(rs.getObject("application_id",UUID.class),rs.getObject("candidate_id",UUID.class),
                            rs.getObject("requisition_id",UUID.class),rs.getString("posting_title"),
                            new ContactValues(rs.getString("full_name"),rs.getString("email"),rs.getString("phone")),
                            rs.getLong("contact_revision")),snapshot.groups().getFirst().id(),snapshot.groups().getLast().id());
            // Current effective contacts, never the asynchronously refreshed detector cache. Bound pair output.
            var matches=jdbc.query("""
                    SELECT l.application_id,r.application_id,l.normalized_email=r.normalized_email,l.phone=r.phone
                    FROM candidate_memberships lm JOIN candidate_effective_contacts l ON l.application_id=lm.application_id
                    JOIN candidate_memberships rm ON rm.candidate_id=?
                    JOIN candidate_effective_contacts r ON r.application_id=rm.application_id
                    WHERE lm.candidate_id=? AND (l.normalized_email=r.normalized_email OR l.phone=r.phone)
                    ORDER BY l.application_id,r.application_id LIMIT 201
                    """,(rs,n)->new Match(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getBoolean(3),rs.getBoolean(4)),
                    snapshot.groups().getLast().id(),snapshot.groups().getFirst().id());
            return new Preview(snapshot.groups(),members,matches.stream().limit(200).toList(),matches.size()>200,snapshot.fingerprint());
        }));
    }

    public Result merge(Jwt jwt,MergeRequest request) {
        validate(request);
        return database(()->write.execute(ignored->{
            timeout(true); UUID actor=access.actor(jwt,true);access.require(jwt,List.of(),true);available();
            var command=new CandidateMergeCommand(actor,request.requestKey(),request.leftGroupId(),request.rightGroupId(),
                    request.resultGroupId(),request.representativeApplicationId(),request.expectedFingerprint());
            try { return result(journal.appendAuthorized(command,members->access.require(jwt,members,true))); }
            catch (MergeAuditException changed) {
                if (changed.reason()==MergeAuditException.Reason.EMPTY_OR_MISSING_GROUP) throw conflict("CANDIDATE_MERGE_STALE");
                throw changed;
            }
        }));
    }

    public Result get(Jwt jwt,UUID id) {
        return database(()->read.execute(ignored->{
            timeout(false);access.require(jwt,List.of(),false);
            var operation=journal.find(id).orElseThrow(CandidateMergeService::missing);
            access.require(jwt,operation.before().members(),false);available();return result(operation);
        }));
    }

    private UUID group(UUID application) {
        var rows=jdbc.queryForList("SELECT candidate_id FROM candidate_memberships WHERE application_id=?",UUID.class,application);
        if (rows.isEmpty()) throw missing();return rows.getFirst();
    }

    private Result result(CandidateMergeRecord value) {
        boolean undone=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM candidate_merge_undo_events WHERE merge_operation_id=?)",Boolean.class,value.id());
        return new Result(value.id(),value.resultGroupId(),value.representativeApplicationId(),value.recordedAt(),value.undoDeadline(),undone);
    }

    private void validate(Object request) {
        if (request==null || !validator.validate(request).isEmpty())
            throw new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Thông tin gộp hồ sơ không hợp lệ.");
    }
    private void available() { if (!enabled) throw unavailable(); }
    private void timeout(boolean locks) {
        jdbc.execute("SET LOCAL statement_timeout='3s'");
        if (locks) jdbc.execute("SET LOCAL lock_timeout='2s'");
    }
    static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"CANDIDATE_MERGE_NOT_FOUND","Không tìm thấy dữ liệu gộp hồ sơ."); }
    static ApiException conflict(String code) { return new ApiException(HttpStatus.CONFLICT,code,"Dữ liệu gộp hồ sơ đã thay đổi hoặc không còn đủ điều kiện. Vui lòng tải lại."); }
    static ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"CANDIDATE_MERGE_UNAVAILABLE","Chức năng gộp hồ sơ tạm chưa sẵn sàng."); }
    static <T> T database(Supplier<T> callback) {
        try { return callback.get(); }
        catch (MergeAuditException failure) {
            throw switch (failure.reason()) {
                case INVALID_REQUEST -> new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR","Thông tin gộp hồ sơ không hợp lệ.");
                case EMPTY_OR_MISSING_GROUP -> missing();
                case STALE_SNAPSHOT -> conflict("CANDIDATE_MERGE_STALE");
                case REQUEST_KEY_REUSED -> conflict("IDEMPOTENCY_KEY_REUSED");
                default -> unavailable();
            };
        } catch (DataAccessException | TransactionException failure) {
            throw unavailable();
        }
    }
}
