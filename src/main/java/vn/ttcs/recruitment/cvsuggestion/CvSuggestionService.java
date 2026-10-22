package vn.ttcs.recruitment.cvsuggestion;

import java.util.Arrays;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.common.ApiException;

@Service
public class CvSuggestionService {
    private final JdbcTemplate jdbc;
    private final CvSuggestionRepository jobs;
    private final CvSuggestionAccess access;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final int maxPending;
    public CvSuggestionService(JdbcTemplate jdbc,CvSuggestionRepository jobs,CvSuggestionAccess access,
            PlatformTransactionManager manager,@Value("${app.cv-extraction.enabled:false}") boolean enabled,
            @Value("${app.cv-extraction.max-pending:100}") int maxPending) {
        if (maxPending<1 || maxPending>10000) throw new IllegalArgumentException("Invalid CV queue capacity");
        this.jdbc=jdbc;this.jobs=jobs;this.access=access;this.enabled=enabled;this.maxPending=maxPending;
        tx=new TransactionTemplate(manager);tx.setTimeout(5);
    }
    public CvSuggestionView request(Jwt jwt,UUID application) {
        return database(() -> tx.execute(ignored -> {
            jdbc.execute("SET LOCAL lock_timeout='2s'");
            jdbc.execute("SET LOCAL statement_timeout='3s'");
            var writer=access.lock(jwt);
            access.writer(jwt,writer);
            var app=application(application,true);
            access.scope(access.writer(jwt,writer),app.requisition());
            requireEnabled();
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)",Boolean.class,CvSuggestionRepository.QUEUE_LOCK)))
                throw unavailable();
            // Expire in the same transaction; never run the worker or parser here.
            jobs.expire(tx);
            var existing=jobs.find(application,app.hash()).orElse(null);
            if (existing!=null && !existing.status().equals("FAILED")) return existing;
            if (existing!=null && jdbc.queryForObject("""
                    SELECT count(*) FROM cv_extraction_attempts a JOIN cv_extraction_jobs j ON j.id=a.job_id
                    WHERE j.application_id=? AND a.is_retry AND a.requested_at>clock_timestamp()-interval '1 hour'
                    """,Integer.class,application)>=3)
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"CV_RETRY_LIMIT","Đã hết lượt thử lại. Vui lòng thử sau.");
            if (app.pdf() && jdbc.queryForObject("SELECT count(*) FROM cv_extraction_jobs WHERE status IN ('QUEUED','RUNNING')",Integer.class)>=maxPending)
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"CV_QUEUE_FULL","Hàng đợi đang đầy. Vui lòng thử sau.");
            access.scope(access.writer(jwt,writer),app.requisition());
            return jobs.enqueue(application,app.hash(),writer.id(),existing,app.pdf());
        }));
    }
    public CvSuggestionView get(Jwt jwt,UUID application,UUID id) {
        return database(() -> tx.execute(ignored -> {
            jdbc.execute("SET LOCAL statement_timeout='3s'");
            var caller=access.reader(jwt);
            var app=application(application,false);
            access.scope(caller,app.requisition());
            var rows=jdbc.query("SELECT cv_sha256,extractor_version FROM cv_extraction_jobs WHERE id=? AND application_id=?",
                    (rs,n)->new Identity(rs.getBytes(1),rs.getString(2)),id,application);
            if (rows.isEmpty()) throw notFound();
            if (!Arrays.equals(rows.getFirst().hash(),app.hash()))
                throw new ApiException(HttpStatus.CONFLICT,"CV_CHANGED","CV đã thay đổi. Vui lòng yêu cầu trích lại.");
            if (!vn.ttcs.recruitment.cvextraction.CvExtractionResult.VERSION.equals(rows.getFirst().version()))
                throw new ApiException(HttpStatus.CONFLICT,"CV_EXTRACTION_STALE","Bộ trích đã thay đổi. Vui lòng yêu cầu trích lại.");
            jobs.expire(tx,id);
            return jobs.get(id);
        }));
    }
    private App application(UUID id,boolean lock) {
        var rows=jdbc.query("""
                SELECT a.requisition_id,c.sha256,c.content_type FROM job_applications a
                JOIN recruitment_requisitions r ON r.id=a.requisition_id
                JOIN application_cvs c ON c.application_id=a.id WHERE a.id=?
                """+(lock ? " FOR UPDATE OF r,a,c" : ""),(rs,n)->new App(rs.getObject(1,UUID.class),rs.getBytes(2),
                "application/pdf".equals(rs.getString(3))),id);
        if (rows.isEmpty()) throw notFound();
        return rows.getFirst();
    }
    private record App(UUID requisition,byte[] hash,boolean pdf) { }
    private record Identity(byte[] hash,String version) { }
    private void requireEnabled() { if (!enabled) throw unavailable(); }
    private <T> T database(java.util.function.Supplier<T> work) {
        try { return work.get(); } catch (DataAccessException | TransactionException exception) { throw unavailable(); }
    }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND,"CV_EXTRACTION_NOT_FOUND","Không tìm thấy hồ sơ hoặc yêu cầu trích."); }
    private static ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"CV_EXTRACTION_UNAVAILABLE","Chức năng trích CV tạm chưa sẵn sàng."); }
}
