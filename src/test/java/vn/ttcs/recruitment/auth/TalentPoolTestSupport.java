package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story S5-06 (tasks 398-400): the screening fixture plus SQL arrangements of the three final-stage rejections that
 * qualify an application for the talent pool, and of an early rejection that does not.
 */
abstract class TalentPoolTestSupport extends ScreeningTestSupport {
    static final String POOL = "/api/v1/talent-pool";
    UUID rejectionReason;
    private long tick;

    @BeforeEach
    void poolFixture() {
        rejectionReason = catalog("REJECTION_REASON", "Chưa phù hợp", true);
        tick = 0;
    }

    private Timestamp next() {
        return Timestamp.from(ApplicationTestSupport.NOW.plusSeconds(++tick));
    }

    // A staff rejection from this stage, as the stage change API records it.
    UUID rejectedFrom(UUID application, String before) {
        UUID event = UUID.randomUUID();
        jdbc.update("UPDATE job_applications SET stage = 'REJECTED' WHERE id = ?", application);
        jdbc.update("""
                INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,actor_id,reason,
                    occurred_at,rejection_reason_id,rejection_reason_name)
                VALUES (?,?,?,'REJECTED','STAFF',?,'Kết luận vòng cuối',?,?,'Chưa phù hợp')
                """, event, application, before, hr, next(), rejectionReason);
        return event;
    }

    // A REJECT hiring decision after an interview, with its stage event.
    void rejectedByHiringDecision(UUID application) {
        UUID interview = UUID.randomUUID();
        Timestamp at = Timestamp.from(ApplicationTestSupport.NOW);
        jdbc.update("""
                INSERT INTO interview_sessions(id,application_id,round_name,starts_at,ends_at,mode,room,normalized_room,
                    status,created_by,created_at,updated_at) VALUES (?,?,'Vòng cuối',?,?,'ONSITE','Phòng 1','phòng 1','SCHEDULED',?,?,?)
                """, interview, application, Timestamp.from(ApplicationTestSupport.NOW.minusSeconds(7200)),
                Timestamp.from(ApplicationTestSupport.NOW.minusSeconds(3600)), hr, at, at);
        UUID event = rejectedFrom(application, "INTERVIEW");
        jdbc.update("""
                INSERT INTO hiring_decisions(id,application_id,interview_id,stage_event_id,decision,actor_id,actor_name,
                    decided_at,rejection_reason_id,rejection_reason_name,evidence_hash,evidence_snapshot)
                VALUES (?,?,?,?,'REJECT',?,'HR Nội Bộ',?,?,'Chưa phù hợp',?,'[{"score":2}]'::jsonb)
                """, UUID.randomUUID(), application, interview, event, hr, at, rejectionReason, "a".repeat(64));
    }

    // The candidate declined a sent offer: the offer and the candidate's stage event naming it.
    void declinedOffer(UUID application, UUID candidate) {
        UUID offer = UUID.randomUUID();
        UUID position = jdbc.queryForObject("SELECT r.position_id FROM job_applications a JOIN recruitment_requisitions r"
                + " ON r.id = a.requisition_id WHERE a.id = ?", UUID.class, application);
        Timestamp at = Timestamp.from(ApplicationTestSupport.NOW);
        jdbc.update("""
                INSERT INTO offers(id,application_id,candidate_id,position_id,candidate_name,email,position_title,salary,
                    allowance,start_date,response_deadline,band_min,band_max,outside_band,status,created_by,created_at,
                    updated_at,sent_at,responded_at,decline_reason)
                VALUES (?,?,?,?,'Ứng viên','offer@example.test','Developer',20000000,0,?,?,0,30000000,false,'DECLINED',?,?,?,?,?,
                    'SALARY')
                """, offer, application, candidate, position, LocalDate.of(2026, 11, 2),
                Timestamp.from(ApplicationTestSupport.NOW.plusSeconds(86400)), hr, at, at, at, at);
        jdbc.update("UPDATE job_applications SET stage = 'REJECTED' WHERE id = ?", application);
        jdbc.update("""
                INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,reason,
                    occurred_at,offer_id)
                VALUES (?,?,'OFFER','REJECTED','CANDIDATE','Ứng viên từ chối offer',?,?)
                """, UUID.randomUUID(), application, next(), offer);
    }

    String addBody(UUID application, String reason, List<String> titles, List<String> skills) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("applicationId", application == null ? null : application.toString());
        body.put("reason", reason);
        body.put("titleInterests", titles);
        body.put("skills", skills);
        return json.writeValueAsString(body);
    }

    HttpResponse<String> add(String token, UUID application, List<String> titles, List<String> skills) throws Exception {
        return call("POST", POOL, addBody(application, "Phỏng vấn tốt, thiếu kinh nghiệm quản lý", titles, skills), token);
    }

    JsonNode added(String token, UUID application, List<String> titles, List<String> skills) throws Exception {
        var response = add(token, application, titles, skills);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json.readTree(response.body());
    }

    void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }

    Instant now() { return ApplicationTestSupport.NOW; }
}
