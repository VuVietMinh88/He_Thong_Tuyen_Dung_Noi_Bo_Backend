package vn.ttcs.recruitment.interview;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class InterviewRepository {
    private static final String SELECT = """
            SELECT i.*, ARRAY(SELECT p.user_id FROM interview_participants p
                WHERE p.interview_id=i.id AND p.active ORDER BY p.user_id) AS participant_ids
            FROM interview_sessions i
            """;
    private final NamedParameterJdbcTemplate jdbc;

    public InterviewRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<InterviewView> find(UUID id) {
        return jdbc.query(SELECT + " WHERE i.id=:id", params("id", id), InterviewRepository::view)
                .stream().findFirst();
    }

    Optional<InterviewView> lock(UUID id) {
        // FOR UPDATE is also the evaluation submission lock; ever_submitted_at is examined afterwards.
        return jdbc.query(SELECT + " WHERE i.id=:id FOR UPDATE OF i", params("id", id), InterviewRepository::view)
                .stream().findFirst();
    }

    UUID applicationOf(UUID id) {
        return jdbc.query("SELECT application_id FROM interview_sessions WHERE id=:id", params("id", id),
                (row, n) -> row.getObject(1, UUID.class)).stream().findFirst().orElse(null);
    }

    UUID requisitionOf(UUID application) {
        return jdbc.query("SELECT requisition_id FROM job_applications WHERE id=:id", params("id", application),
                (row, n) -> row.getObject(1, UUID.class)).stream().findFirst().orElse(null);
    }

    String lockRequisition(UUID id) {
        return jdbc.query("SELECT status FROM recruitment_requisitions WHERE id=:id FOR NO KEY UPDATE",
                params("id", id), (row, n) -> row.getString(1)).stream().findFirst().orElse(null);
    }

    String lockApplication(UUID id) {
        return jdbc.query("SELECT stage FROM job_applications WHERE id=:id FOR NO KEY UPDATE",
                params("id", id), (row, n) -> row.getString(1)).stream().findFirst().orElse(null);
    }

    boolean participant(UUID id, UUID user) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM interview_participants
                    WHERE interview_id=:id AND user_id=:user AND active)
                """, params("id", id).addValue("user", user), Boolean.class));
    }

    InterviewPage page(UUID application, UUID participant, int page, int size) {
        String where = application != null ? " WHERE i.application_id=:application"
                : " WHERE EXISTS (SELECT 1 FROM interview_participants p WHERE p.interview_id=i.id"
                    + " AND p.user_id=:participant AND p.active)";
        var parameters = params("application", application).addValue("participant", participant)
                .addValue("size", size).addValue("offset", (long) page * size);
        Long count = jdbc.queryForObject("SELECT count(*) FROM interview_sessions i" + where, parameters, Long.class);
        List<InterviewView> items = jdbc.query(SELECT + where + " ORDER BY i.starts_at DESC,i.id LIMIT :size OFFSET :offset",
                parameters, InterviewRepository::view);
        return new InterviewPage(items, count == null ? 0 : count, page, size);
    }

    // One global transaction lock deliberately trades scheduling throughput for a simple, correct first version:
    // overlaps across different applications/rooms/panels must serialize, not just edits of the same appointment.
    void serializeConflictCheck() {
        jdbc.getJdbcTemplate().execute("SELECT pg_advisory_xact_lock(743882, 1)");
    }

    List<InterviewConflict> conflicts(UUID excluded, Instant start, Instant end, List<UUID> participants, String room) {
        var parameters = params("excluded", excluded).addValue("start", Timestamp.from(start))
                .addValue("end", Timestamp.from(end)).addValue("participants", participants).addValue("room", room);
        return jdbc.query("""
                SELECT i.id,i.starts_at,i.ends_at,'INTERVIEWER' AS resource_type,p.user_id,
                    CAST(NULL AS varchar) AS room
                FROM interview_sessions i JOIN interview_participants p ON p.interview_id=i.id AND p.active
                WHERE i.status='SCHEDULED' AND (CAST(:excluded AS uuid) IS NULL OR i.id<>:excluded)
                    AND i.starts_at<:end AND i.ends_at>:start AND p.user_id IN (:participants)
                UNION ALL
                SELECT i.id,i.starts_at,i.ends_at,'ROOM',CAST(NULL AS uuid),i.room
                FROM interview_sessions i
                WHERE i.status='SCHEDULED' AND (CAST(:excluded AS uuid) IS NULL OR i.id<>:excluded)
                    AND i.starts_at<:end AND i.ends_at>:start AND i.normalized_room=:room
                ORDER BY starts_at,id,resource_type,user_id
                """, parameters, (row, n) -> new InterviewConflict(row.getObject("id", UUID.class),
                row.getTimestamp("starts_at").toInstant(), row.getTimestamp("ends_at").toInstant(),
                row.getString("resource_type"), row.getObject("user_id", UUID.class), row.getString("room")));
    }

    void insert(UUID id, UUID application, InterviewScheduleRequest request, String normalizedRoom, UUID actor,
                Instant now, Instant end) {
        jdbc.update("""
                INSERT INTO interview_sessions(id,application_id,round_name,starts_at,ends_at,mode,room,normalized_room,
                    meeting_url,created_by,created_at,updated_at)
                VALUES (:id,:application,:round,:start,:end,:mode,:room,:normalizedRoom,:url,:actor,:now,:now)
                """, scheduleParams(id, request, normalizedRoom, end).addValue("application", application)
                .addValue("actor", actor).addValue("now", Timestamp.from(now)));
        participants(id, request.participantIds());
    }

    void update(UUID id, InterviewScheduleRequest request, String normalizedRoom, Instant now, Instant end) {
        jdbc.update("""
                UPDATE interview_sessions SET round_name=:round,starts_at=:start,ends_at=:end,mode=:mode,room=:room,
                    normalized_room=:normalizedRoom,meeting_url=:url,updated_at=:now,version=version+1 WHERE id=:id
                """, scheduleParams(id, request, normalizedRoom, end).addValue("now", Timestamp.from(now)));
        participants(id, request.participantIds());
    }

    void cancel(UUID id, Instant now) {
        jdbc.update("UPDATE interview_sessions SET status='CANCELLED',updated_at=:now,version=version+1 WHERE id=:id",
                params("id", id).addValue("now", Timestamp.from(now)));
    }

    void event(InterviewView view, String type, UUID actor, Instant now, String reason, boolean override,
               String overrideReason) {
        jdbc.update("""
                INSERT INTO interview_events(id,interview_id,application_id,event_type,actor_id,occurred_at,
                    starts_at,ends_at,round_name,reason,conflict_override,override_reason)
                VALUES (:id,:interview,:application,:type,:actor,:now,:start,:end,:round,:reason,:override,:overrideReason)
                """, params("id", UUID.randomUUID()).addValue("interview", view.id())
                .addValue("application", view.applicationId()).addValue("type", type).addValue("actor", actor)
                .addValue("now", Timestamp.from(now)).addValue("start", Timestamp.from(view.startsAt()))
                .addValue("end", Timestamp.from(view.endsAt())).addValue("round", view.roundName())
                .addValue("reason", reason).addValue("override", override).addValue("overrideReason", overrideReason));
    }

    private void participants(UUID id, List<UUID> users) {
        jdbc.update("UPDATE interview_participants SET active=false WHERE interview_id=:id", params("id", id));
        for (UUID user : users) {
            jdbc.update("""
                    INSERT INTO interview_participants(interview_id,user_id,active) VALUES (:id,:user,true)
                    ON CONFLICT (interview_id,user_id) DO UPDATE SET active=true
                    """, params("id", id).addValue("user", user));
        }
    }

    private static MapSqlParameterSource scheduleParams(UUID id, InterviewScheduleRequest request, String normalized,
                                                         Instant end) {
        return params("id", id).addValue("round", request.roundName()).addValue("start", Timestamp.from(request.startsAt()))
                .addValue("end", Timestamp.from(end)).addValue("mode", request.mode().name()).addValue("room", request.room())
                .addValue("normalizedRoom", normalized).addValue("url", request.meetingUrl());
    }

    private static MapSqlParameterSource params(String key, Object value) { return new MapSqlParameterSource(key, value); }

    private static InterviewView view(ResultSet row, int number) throws SQLException {
        var submitted = row.getTimestamp("ever_submitted_at");
        var participantIds = Arrays.stream((Object[]) row.getArray("participant_ids").getArray())
                .map(value -> (UUID) value).toList();
        return new InterviewView(row.getObject("id", UUID.class), row.getObject("application_id", UUID.class),
                row.getString("round_name"), row.getTimestamp("starts_at").toInstant(), row.getTimestamp("ends_at").toInstant(),
                InterviewMode.valueOf(row.getString("mode")), row.getString("room"), row.getString("meeting_url"),
                row.getString("status"), row.getLong("version"), participantIds, row.getObject("created_by", UUID.class),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                submitted == null ? null : submitted.toInstant());
    }
}
