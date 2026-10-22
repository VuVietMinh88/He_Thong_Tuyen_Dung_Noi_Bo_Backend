package vn.ttcs.recruitment.cvsuggestion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import vn.ttcs.recruitment.common.ApiException;
import static vn.ttcs.recruitment.cvsuggestion.ConfirmedContactView.*;

@Repository
public class ConfirmedContactRepository {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    public ConfirmedContactRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    record Application(UUID id,UUID requisition,ContactValues submitted,String hash) { }
    Application application(UUID id,boolean lock) {
        var rows=jdbc.query("SELECT requisition_id FROM job_applications WHERE id=?",(rs,n)->rs.getObject(1,UUID.class),id);
        if (rows.isEmpty()) throw missing();
        if (lock) {
            // Explicit lock order shared by confirmation and future mail outbox creation.
            jdbc.queryForList("SELECT id FROM recruitment_requisitions WHERE id=? FOR UPDATE",UUID.class,rows.getFirst());
            jdbc.queryForList("SELECT id FROM job_applications WHERE id=? FOR UPDATE",UUID.class,id);
        }
        var apps=jdbc.query("""
                SELECT a.id,a.requisition_id,a.full_name,a.email,a.phone,c.sha256 FROM job_applications a
                JOIN application_cvs c ON c.application_id=a.id WHERE a.id=?
                """+(lock ? " FOR UPDATE OF c" : ""),(rs,n)->new Application(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                new ContactValues(rs.getString(3),rs.getString(4),rs.getString(5)),HexFormat.of().formatHex(rs.getBytes(6))),id);
        if (apps.isEmpty()) throw missing();return apps.getFirst();
    }
    Optional<Confirmation> current(UUID id) {
        return jdbc.query("SELECT * FROM application_confirmed_contacts WHERE application_id=?",this::confirmation,id).stream().findFirst();
    }
    ConfirmedContactView view(Application app) {
        var current=current(app.id()).orElse(null);
        return new ConfirmedContactView(app.id(),app.submitted(),current,current==null ? app.submitted() : current.contact(),
                current==null ? 0 : current.revision(),app.hash(),current!=null && !current.cvHash().equals(app.hash()));
    }
    void save(Application app,ContactConfirmationRequest request,Confirmation old,UUID actor,String actorName) {
        Instant now=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
        long revision=old==null ? 1 : Math.addExact(old.revision(),1);
        var confirmed=new Confirmation(request.values(),revision,app.hash(),request.nameSource(),request.emailSource(),
                request.phoneSource(),request.extractionId(),actor,actorName,now);
        jdbc.update("""
                INSERT INTO application_confirmed_contacts(application_id,revision,full_name,email,normalized_email,phone,
                cv_sha256,name_source,email_source,phone_source,extraction_id,confirmed_by,confirmed_by_name,confirmed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT (application_id) DO UPDATE SET
                revision=EXCLUDED.revision,full_name=EXCLUDED.full_name,email=EXCLUDED.email,
                normalized_email=EXCLUDED.normalized_email,phone=EXCLUDED.phone,cv_sha256=EXCLUDED.cv_sha256,
                name_source=EXCLUDED.name_source,email_source=EXCLUDED.email_source,phone_source=EXCLUDED.phone_source,
                extraction_id=EXCLUDED.extraction_id,confirmed_by=EXCLUDED.confirmed_by,
                confirmed_by_name=EXCLUDED.confirmed_by_name,confirmed_at=EXCLUDED.confirmed_at
                """,app.id(),revision,request.fullName(),request.email(),request.email().toLowerCase(Locale.ROOT),request.phone(),
                HexFormat.of().parseHex(app.hash()),request.nameSource().name(),request.emailSource().name(),request.phoneSource().name(),
                request.extractionId(),actor,actorName,Timestamp.from(now));
        jdbc.update("""
                INSERT INTO application_contact_history(application_id,revision,before_contact,after_contact,actor_id,actor_name,confirmed_at)
                VALUES (?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?,?)
                """,app.id(),revision,JSON.writeValueAsString(old==null ? app.submitted() : old.contact()),
                JSON.writeValueAsString(confirmed),actor,actorName,Timestamp.from(now));
    }
    HistoryPage history(UUID app,int page,int size) {
        var rows=jdbc.query("""
                SELECT * FROM application_contact_history WHERE application_id=? ORDER BY revision DESC LIMIT ? OFFSET ?
                """,(rs,n)->new HistoryItem(rs.getLong("revision"),JSON.readValue(rs.getString("before_contact"),ContactValues.class),
                JSON.readValue(rs.getString("after_contact"),Confirmation.class),rs.getObject("actor_id",UUID.class),
                rs.getString("actor_name"),rs.getTimestamp("confirmed_at").toInstant()),app,size,(long)page*size);
        return new HistoryPage(rows,page,size,jdbc.queryForObject("SELECT count(*) FROM application_contact_history WHERE application_id=?",Long.class,app));
    }
    private Confirmation confirmation(ResultSet rs,int row) throws SQLException {
        return new Confirmation(new ContactValues(rs.getString("full_name"),rs.getString("email"),rs.getString("phone")),
                rs.getLong("revision"),HexFormat.of().formatHex(rs.getBytes("cv_sha256")),ContactSource.valueOf(rs.getString("name_source")),
                ContactSource.valueOf(rs.getString("email_source")),ContactSource.valueOf(rs.getString("phone_source")),
                rs.getObject("extraction_id",UUID.class),rs.getObject("confirmed_by",UUID.class),rs.getString("confirmed_by_name"),
                rs.getTimestamp("confirmed_at").toInstant());
    }
    static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"APPLICATION_CONTACT_NOT_FOUND","Không tìm thấy hồ sơ ứng tuyển."); }
}
