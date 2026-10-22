package vn.ttcs.recruitment.cvsuggestion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import static vn.ttcs.recruitment.cvextraction.CvExtractionResult.VERSION;

/** All locks are short lived. Never call the sandbox inside a transaction. */
@Repository
public class CvSuggestionRepository {
    static final long QUEUE_LOCK=74433401L;
    private static final long WORKER_LOCK=74433402L;
    private final JdbcTemplate jdbc;
    public CvSuggestionRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    Instant now() { return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant(); }
    Optional<CvSuggestionView> find(UUID application, byte[] hash) {
        return jdbc.query("SELECT * FROM cv_extraction_jobs WHERE application_id=? AND cv_sha256=? AND extractor_version=?",
                this::view,application,hash,VERSION).stream().findFirst();
    }
    CvSuggestionView get(UUID id) {
        return jdbc.queryForObject("SELECT * FROM cv_extraction_jobs WHERE id=?",this::view,id);
    }
    CvSuggestionView enqueue(UUID app, byte[] hash, UUID actor, CvSuggestionView previous, boolean pdf) {
        Instant now=now();
        UUID id=previous==null ? UUID.randomUUID() : previous.id();
        int attempt=previous==null ? 1 : previous.attempt()+1;
        String status=pdf ? "QUEUED" : "SUCCEEDED", result=pdf ? null : CvSuggestionJson.unsupported();
        if (previous==null) jdbc.update("""
                INSERT INTO cv_extraction_jobs(id,application_id,cv_sha256,extractor_version,status,attempt,result,created_at,updated_at)
                VALUES (?,?,?,?,?,?,CAST(? AS jsonb),?,?)
                """,id,app,hash,VERSION,status,attempt,result,Timestamp.from(now),Timestamp.from(now));
        else jdbc.update("""
                UPDATE cv_extraction_jobs SET status=?,attempt=?,result=CAST(? AS jsonb),error_code=NULL,updated_at=? WHERE id=?
                """,status,attempt,result,Timestamp.from(now),id);
        jdbc.update("""
                INSERT INTO cv_extraction_attempts(job_id,attempt,is_retry,requested_by,requested_at,status,finished_at)
                VALUES (?,?,?,?,?,?,?)
                """,id,attempt,previous!=null,actor,Timestamp.from(now),status,pdf ? null : Timestamp.from(now));
        return get(id);
    }
    /** Expiration never requeues: retry always requires a new authorized POST. */
    public void expire(TransactionTemplate tx) {
        expire(tx,null);
    }
    void expire(TransactionTemplate tx,UUID onlyId) {
        tx.executeWithoutResult(ignored -> {
            var ids=jdbc.queryForList("""
                    SELECT j.id FROM cv_extraction_jobs j JOIN cv_extraction_attempts a
                    ON a.job_id=j.id AND a.attempt=j.attempt
                    WHERE ((j.status='RUNNING' AND a.lease_until<=clock_timestamp())
                    OR (j.status='QUEUED' AND a.requested_at<=clock_timestamp()-interval '10 minutes'))
                    AND (CAST(? AS uuid) IS NULL OR j.id=?)
                    ORDER BY j.updated_at LIMIT 100 FOR UPDATE OF j SKIP LOCKED
                    """,UUID.class,onlyId,onlyId);
            for (UUID id : ids) {
                String code="JOB_EXPIRED";
                jdbc.update("""
                        UPDATE cv_extraction_attempts a SET status='FAILED',lease_token=NULL,lease_until=NULL,
                        finished_at=clock_timestamp(),error_code=? FROM cv_extraction_jobs j
                        WHERE j.id=? AND a.job_id=j.id AND a.attempt=j.attempt
                        """,code,id);
                jdbc.update("UPDATE cv_extraction_jobs SET status='FAILED',error_code=?,updated_at=clock_timestamp() WHERE id=?",code,id);
            }
        });
    }
    public record Claim(UUID id, UUID applicationId, int attempt, UUID token, byte[] hash) {
        public Claim { hash=hash.clone(); }
        @Override public byte[] hash() { return hash.clone(); }
        @Override public String toString() { return "Claim[id="+id+"]"; }
    }
    public Optional<Claim> claim(TransactionTemplate tx, int maxRunning) {
        return tx.execute(ignored -> {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)",Boolean.class,WORKER_LOCK)))
                return Optional.empty();
            if (jdbc.queryForObject("SELECT count(*) FROM cv_extraction_jobs WHERE status='RUNNING'",Integer.class)>=maxRunning)
                return Optional.empty();
            var rows=jdbc.query("""
                    SELECT * FROM cv_extraction_jobs j WHERE status='QUEUED' AND extractor_version=?
                    AND EXISTS (SELECT 1 FROM cv_extraction_attempts a WHERE a.job_id=j.id AND a.attempt=j.attempt
                        AND a.requested_at>clock_timestamp()-interval '10 minutes')
                    ORDER BY updated_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, (rs,n)->new Claim(rs.getObject("id",UUID.class),rs.getObject("application_id",UUID.class),
                    rs.getInt("attempt"),UUID.randomUUID(),rs.getBytes("cv_sha256")),VERSION);
            if (rows.isEmpty()) return Optional.empty();
            var claim=rows.getFirst();
            jdbc.update("UPDATE cv_extraction_jobs SET status='RUNNING',updated_at=clock_timestamp() WHERE id=?",claim.id());
            jdbc.update("""
                    UPDATE cv_extraction_attempts SET status='RUNNING',lease_token=?,lease_until=clock_timestamp()+interval '180 seconds'
                    WHERE job_id=? AND attempt=?
                    """,claim.token(),claim.id(),claim.attempt());
            return Optional.of(claim);
        });
    }
    public boolean renew(Claim claim) {
        return jdbc.update("""
                UPDATE cv_extraction_attempts SET lease_until=clock_timestamp()+interval '180 seconds'
                WHERE job_id=? AND attempt=? AND lease_token=? AND status='RUNNING' AND lease_until>clock_timestamp()
                """,claim.id(),claim.attempt(),claim.token())==1;
    }
    public Optional<byte[]> content(Claim claim) {
        return jdbc.query("SELECT content FROM application_cvs WHERE application_id=? AND sha256=? AND content_type='application/pdf'",
                (rs,n)->rs.getBytes(1),claim.applicationId(),claim.hash()).stream().findFirst();
    }
    public boolean finish(TransactionTemplate tx, Claim claim, String result, String error) {
        if (error==null) {
            var parsed=CvSuggestionJson.read(result);
            if (parsed.outcome()==vn.ttcs.recruitment.cvextraction.CvExtractionResult.Outcome.FAILED)
                throw new IllegalArgumentException("Failed extraction cannot be a successful job");
        } else if (result!=null || !java.util.Set.of("EXTRACTION_FAILED","CV_CHANGED","WORKER_INTERRUPTED").contains(error)) {
            throw new IllegalArgumentException("Invalid worker failure");
        }
        return Boolean.TRUE.equals(tx.execute(ignored -> {
            // Lock order matches expiration: job, then attempt. Fence by both token and unexpired lease.
            jdbc.queryForList("SELECT id FROM cv_extraction_jobs WHERE id=? FOR UPDATE",UUID.class,claim.id());
            String status=error==null ? "SUCCEEDED" : "FAILED";
            int changed=jdbc.update("""
                    UPDATE cv_extraction_attempts SET status=?,lease_token=NULL,lease_until=NULL,
                    finished_at=clock_timestamp(),error_code=? WHERE job_id=? AND attempt=? AND lease_token=?
                    AND status='RUNNING' AND lease_until>clock_timestamp()
                    """,status,error,claim.id(),claim.attempt(),claim.token());
            if (changed==0) return false;
            jdbc.update("""
                    UPDATE cv_extraction_jobs SET status=?,result=CAST(? AS jsonb),error_code=?,updated_at=clock_timestamp()
                    WHERE id=? AND attempt=? AND status='RUNNING'
                    """,status,result,error,claim.id(),claim.attempt());
            return true;
        }));
    }
    private CvSuggestionView view(ResultSet rs,int row) throws SQLException {
        String result=rs.getString("result");
        return new CvSuggestionView(rs.getObject("id",UUID.class),rs.getObject("application_id",UUID.class),
                rs.getString("extractor_version"),rs.getString("status"),rs.getInt("attempt"),
                rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),
                result==null ? null : CvSuggestionJson.read(result),rs.getString("error_code"));
    }
}
