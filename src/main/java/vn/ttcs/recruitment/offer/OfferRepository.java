package vn.ttcs.recruitment.offer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.position.SalaryBand;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class OfferRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OfferRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }
    Optional<OfferView> find(UUID id, boolean lock) {
        return jdbc.query("SELECT o.* FROM offers o WHERE o.id=?"+(lock ? " FOR UPDATE" : ""), this::view, id).stream().findFirst();
    }
    List<OfferView> list(UUID application, int page, int size) {
        return jdbc.query("SELECT o.* FROM offers o WHERE application_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            this::view, application, size, (long)page*size);
    }
    long count(UUID application) {
        return jdbc.queryForObject("SELECT count(*) FROM offers WHERE application_id=?", Long.class, application);
    }
    void create(UUID id, OfferAccess.Context c, OfferRequests.Draft request, SalaryBand band, UUID actor, Instant now) {
        jdbc.update("""
                INSERT INTO offers(id,application_id,candidate_id,position_id,candidate_name,email,position_title,salary,allowance,
                    start_date,response_deadline,explanation,band_min,band_max,outside_band,created_by,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, c.applicationId(), c.candidateId(), c.positionId(), c.candidateName(), c.email(), c.positionTitle(), request.salary(), request.allowance(),
            request.startDate(), Timestamp.from(request.responseDeadline()), request.explanation(), band.salaryMin(), band.salaryMax(),
            outside(request.salary(), band), actor, Timestamp.from(now), Timestamp.from(now));
    }
    void update(UUID id, OfferAccess.Context c, OfferRequests.Draft request, SalaryBand band, Instant now) {
        jdbc.update("""
                UPDATE offers SET candidate_name=?,email=?,position_title=?,salary=?,allowance=?,start_date=?,response_deadline=?,
                    explanation=?,band_min=?,band_max=?,outside_band=?,version=version+1,updated_at=? WHERE id=?
                """, c.candidateName(), c.email(), c.positionTitle(), request.salary(), request.allowance(), request.startDate(),
            Timestamp.from(request.responseDeadline()), request.explanation(), band.salaryMin(), band.salaryMax(), outside(request.salary(), band), Timestamp.from(now), id);
    }
    void submit(OfferView offer, OfferAccess.Context c, SalaryBand band, OfferRequests.Submit request, UUID actor, Instant now) {
        jdbc.update("""
                UPDATE offers SET candidate_name=?,email=?,position_title=?,band_min=?,band_max=?,outside_band=?,
                    status='PENDING_HR',approval_round=approval_round+1,submitted_by=?,hr_approver_id=?,higher_approver_id=?,
                    approved_at=NULL,version=version+1,updated_at=? WHERE id=?
                """, c.candidateName(), c.email(), c.positionTitle(), band.salaryMin(), band.salaryMax(), outside(offer.salary(), band), actor,
            request.hrApproverId(), request.higherApproverId(), Timestamp.from(now), offer.id());
    }
    void transition(UUID id, String status, Instant now) {
        jdbc.update("UPDATE offers SET status=?,version=version+1,updated_at=?,approved_at=CASE WHEN ?='APPROVED' THEN CAST(? AS timestamptz) ELSE approved_at END WHERE id=?",
            status, Timestamp.from(now), status, Timestamp.from(now), id);
    }
    void event(OfferView offer, String event, OfferAccess.Writer actor, String comment, Instant now, boolean snapshot) {
        jdbc.update("""
                INSERT INTO offer_events(id,offer_id,approval_round,event_type,actor_id,actor_name,occurred_at,comment,terms_snapshot)
                VALUES (?,?,?,?,?,?,?,?,CAST(? AS jsonb))
                """, UUID.randomUUID(), offer.id(), offer.approvalRound(), event, actor.id(), actor.name(), Timestamp.from(now), comment,
            snapshot ? json.writeValueAsString(offer) : null);
    }
    List<OfferView.Event> history(UUID id, int page, int size) {
        return jdbc.query("SELECT * FROM offer_events WHERE offer_id=? ORDER BY occurred_at,id LIMIT ? OFFSET ?",
            (r, n) -> new OfferView.Event(r.getObject("id", UUID.class), r.getInt("approval_round"), r.getString("event_type"),
            r.getObject("actor_id", UUID.class), r.getString("actor_name"), instant(r, "occurred_at"), r.getString("comment")), id, size, (long)page*size);
    }
    long eventCount(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM offer_events WHERE offer_id=?", Long.class, id);
    }
    boolean approvedBy(UUID offer, int round, UUID actor) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM offer_events WHERE offer_id=? AND approval_round=? AND actor_id=? AND event_type IN ('HR_APPROVED','HIGHER_APPROVED'))",
            Boolean.class, offer, round, actor));
    }
    List<OfferView.Approver> approvers(String role) {
        return jdbc.query("""
                SELECT u.id,u.full_name FROM user_accounts u JOIN user_roles r ON r.user_id=u.id
                WHERE r.role=? AND u.enabled AND u.admin_locked_at IS NULL ORDER BY u.full_name,u.id LIMIT 200
                """, (r, n) -> new OfferView.Approver(r.getObject("id", UUID.class), r.getString("full_name")), role);
    }

    private OfferView view(ResultSet r, int n) throws SQLException {
        UUID id = r.getObject("id", UUID.class);
        String delivery = jdbc.query("SELECT status FROM offer_delivery_outbox WHERE offer_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
            (row, index) -> row.getString(1), id).stream().findFirst().orElse("NOT_QUEUED");
        return new OfferView(id, r.getObject("application_id", UUID.class), r.getObject("candidate_id", UUID.class), r.getObject("position_id", UUID.class),
            r.getString("candidate_name"), r.getString("email"), r.getString("position_title"), r.getLong("salary"), r.getLong("allowance"),
            r.getObject("start_date", java.time.LocalDate.class), instant(r, "response_deadline"), r.getString("explanation"), r.getBoolean("outside_band"),
            r.getLong("band_min"), r.getLong("band_max"), r.getString("status"), r.getLong("version"), r.getInt("approval_round"),
            r.getObject("created_by", UUID.class), r.getObject("submitted_by", UUID.class), r.getObject("hr_approver_id", UUID.class),
            r.getObject("higher_approver_id", UUID.class), instant(r, "approved_at"), instant(r, "created_at"), instant(r, "updated_at"), delivery,
            instant(r, "sent_at"), instant(r, "responded_at"), r.getString("response_note"));
    }

    static boolean outside(long salary, SalaryBand band) {
        return salary<band.salaryMin() || salary>band.salaryMax();
    }

    private static Instant instant(ResultSet r, String name) throws SQLException {
        var value = r.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
}
