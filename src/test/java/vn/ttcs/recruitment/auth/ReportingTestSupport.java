package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.JsonNode;
import vn.ttcs.recruitment.account.Role;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stories S8-05..S8-09: SQL fixtures for the report tests, on top of ScreeningTestSupport (HR, two recruiters each
 * assigned to one requisition, the manager of the first requisition's department, and one application on each
 * requisition, both received at ApplicationTestSupport.NOW = Thursday 15 Oct 2026, 10:00 in Vietnam).
 * Applications, stage history, interviews, offers and workflow events are written directly, so a test controls every
 * timestamp a report reads.
 */
abstract class ReportingTestSupport extends ScreeningTestSupport {
    static final Instant NOW = ApplicationTestSupport.NOW;
    UUID department, otherDepartment, position, otherPosition, approver, interviewer;
    String managerToken, outsiderToken, approverToken, interviewerToken;
    private int sequence;

    private java.util.List<java.util.Map<String, Object>> permissionSeed;

    @BeforeEach
    void reportingFixture() {
        permissionSeed = jdbc.queryForList("SELECT role_code, permission_code FROM role_permissions");
        department = departmentOf(requisition);
        otherDepartment = departmentOf(otherRequisition);
        position = jdbc.queryForObject("SELECT position_id FROM recruitment_requisitions WHERE id = ?", UUID.class, requisition);
        otherPosition = jdbc.queryForObject("SELECT position_id FROM recruitment_requisitions WHERE id = ?", UUID.class,
                otherRequisition);
        approver = applications.account("Approver", "approver@example.test", Role.APPROVER);
        interviewer = applications.account("Interviewer", "interviewer@example.test", Role.INTERVIEWER);
        managerToken = token(manager);
        outsiderToken = token(outsider);
        approverToken = token(approver);
        interviewerToken = token(interviewer);
    }

    UUID departmentOf(UUID requisitionId) {
        return jdbc.queryForObject("SELECT department_id FROM recruitment_requisitions WHERE id = ?", UUID.class, requisitionId);
    }

    HttpResponse<String> get(String path, String token) throws Exception {
        return call("GET", path, null, token);
    }

    JsonNode okJson(String path, String token) throws Exception {
        return ok(get(path, token));
    }

    void status(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }

    /** A department (child of parent when not null) managed by managerId. */
    UUID newDepartment(String name, UUID parent, UUID managerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments(id, code, name, parent_id, manager_user_id) VALUES (?,?,?,?,?)",
                id, "D-" + id, name, parent, managerId);
        return id;
    }

    UUID newPosition(String name) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(NOW);
        jdbc.update("INSERT INTO positions(id,code,name,level,salary_min,salary_max,created_at,updated_at) VALUES (?,?,?,'Junior',0,0,?,?)",
                id, "P-" + id, name, at, at);
        return id;
    }

    /** A requisition of this department and position with a published posting, created by hr. */
    UUID newRequisition(UUID departmentId, UUID positionId, String status, LocalDate neededBy, int headcount) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(NOW);
        jdbc.update("""
                INSERT INTO recruitment_requisitions(id,position_id,department_id,headcount,reason,status,needed_by,
                    created_by,created_at,updated_at)
                VALUES (?,?,?,?,'NEW_HEADCOUNT',?,?,?,?,?)
                """, id, positionId, departmentId, headcount, status, neededBy == null ? null : Date.valueOf(neededBy),
                hr, at, at);
        UUID location = catalog("WORK_LOCATION", "Hà Nội", true), employment = catalog("EMPLOYMENT_TYPE", "Toàn thời gian", true);
        jdbc.update("""
                INSERT INTO job_postings(id,requisition_id,status,revision,title,job_description,candidate_requirements,
                    work_location_id,employment_type_id,application_deadline,show_salary,created_by,created_at,updated_at,
                    published_by,published_at)
                VALUES (?,?,'PUBLISHED',1,'Tin','Mô tả','Yêu cầu',?,?,?,false,?,?,?,?,?)
                """, UUID.randomUUID(), id, location, employment, LocalDate.of(2026, 12, 31), hr, at, at, hr, at);
        return id;
    }

    void requisitionState(UUID requisitionId, String status, LocalDate neededBy) {
        jdbc.update("UPDATE recruitment_requisitions SET status = ?, needed_by = ? WHERE id = ?", status,
                neededBy == null ? null : Date.valueOf(neededBy), requisitionId);
    }

    /** An application of the requisition received at receivedAt, in this stage, linked to the candidate of email. */
    UUID application(UUID requisitionId, String email, Instant receivedAt, String stage, UUID sourceId) {
        UUID id = UUID.randomUUID();
        UUID posting = jdbc.queryForObject("SELECT id FROM job_postings WHERE requisition_id = ? ORDER BY created_at LIMIT 1",
                UUID.class, requisitionId);
        String normalized = email.toLowerCase();
        jdbc.update("""
                INSERT INTO job_applications(id,job_posting_id,requisition_id,full_name,email,normalized_email,phone,
                    source_id,stage,received_at,posting_title,tracking_code_hash)
                VALUES (?,?,?,?,?,?,'0912345678',?,'NEW',?,'Tin',?)
                """, id, posting, requisitionId, "Ứng viên " + (++sequence), email, normalized, sourceId,
                Timestamp.from(receivedAt), sha256(id.toString()));
        jdbc.update("INSERT INTO candidates(id, normalized_email, created_at) VALUES (?,?,?) ON CONFLICT (normalized_email) DO NOTHING",
                UUID.randomUUID(), normalized, Timestamp.from(receivedAt));
        jdbc.update("""
                INSERT INTO candidate_applications(application_id, candidate_id, linked_at)
                SELECT ?, id, ? FROM candidates WHERE normalized_email = ?
                """, id, Timestamp.from(receivedAt), normalized);
        if (!"NEW".equals(stage)) {
            applications.stage(id, vn.ttcs.recruitment.application.ApplicationStage.valueOf(stage));
        }
        return id;
    }

    void received(UUID applicationId, Instant receivedAt) {
        jdbc.update("UPDATE job_applications SET received_at = ? WHERE id = ?", Timestamp.from(receivedAt), applicationId);
    }

    /** One immutable stage change by hr (a candidate's own withdrawal when after is WITHDRAWN). */
    void stageEvent(UUID applicationId, String before, String after, Instant at) {
        boolean withdrawal = "WITHDRAWN".equals(after);
        UUID reason = "REJECTED".equals(after) ? catalog("REJECTION_REASON", "Không phù hợp", true) : null;
        jdbc.update("""
                INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,actor_id,
                    occurred_at,rejection_reason_id,rejection_reason_name,interview_skipped)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), applicationId, before, after, withdrawal ? "CANDIDATE" : "STAFF",
                withdrawal ? null : hr, Timestamp.from(at), reason, reason == null ? null : "Không phù hợp",
                "SCREENING".equals(before) && "OFFER".equals(after));
    }

    /** Moves the application forward one stage at a time from NEW to target, one event per step, then sets its stage. */
    void path(UUID applicationId, Instant start, String... stages) {
        String before = "NEW";
        Instant at = start;
        for (String after : stages) {
            stageEvent(applicationId, before, after, at);
            before = after;
            at = at.plusSeconds(3600);
        }
        jdbc.update("UPDATE job_applications SET stage = ? WHERE id = ?", before, applicationId);
    }

    UUID interview(UUID applicationId, Instant startsAt, String status) {
        UUID id = UUID.randomUUID();
        Timestamp at = Timestamp.from(NOW);
        jdbc.update("""
                INSERT INTO interview_sessions(id,application_id,round_name,starts_at,ends_at,mode,room,normalized_room,
                    status,created_by,created_at,updated_at)
                VALUES (?,?,'Vòng kỹ thuật',?,?,'ONSITE','Phòng 1','phòng 1',?,?,?,?)
                """, id, applicationId, Timestamp.from(startsAt), Timestamp.from(startsAt.plusSeconds(3600)), status, hr, at, at);
        return id;
    }

    void participant(UUID interviewId, UUID userId, boolean active) {
        jdbc.update("INSERT INTO interview_participants(interview_id, user_id, active) VALUES (?,?,?)", interviewId, userId, active);
    }

    /** An evaluation form of the evaluator for the interview: SUBMITTED (conclusion and score set) or DRAFT. */
    UUID evaluationForm(UUID interviewId, UUID applicationId, UUID evaluator, String status) {
        UUID id = UUID.randomUUID();
        boolean submitted = "SUBMITTED".equals(status);
        Timestamp at = Timestamp.from(NOW);
        jdbc.update("""
                INSERT INTO evaluation_forms(id,interview_id,application_id,evaluator_id,framework_id,framework_code,
                    framework_name,status,conclusion,total_score,first_submitted_at,submitted_at,created_at,updated_at)
                VALUES (?,?,?,?,?,'FW','Khung đánh giá',?,?,?,?,?,?,?)
                """, id, interviewId, applicationId, evaluator, UUID.randomUUID(), status, submitted ? "HIRE" : null,
                submitted ? new java.math.BigDecimal("4.00") : null, submitted ? at : null, submitted ? at : null, at, at);
        return id;
    }

    /** An offer of the application in this status; SENT and later statuses carry a sent time, answers a response time. */
    UUID offer(UUID applicationId, String status, Instant deadline) {
        UUID id = UUID.randomUUID();
        boolean sent = status.equals("SENT") || status.equals("ACCEPTED") || status.equals("DECLINED")
                || status.equals("EXPIRED");
        boolean answered = status.equals("ACCEPTED") || status.equals("DECLINED");
        jdbc.update("""
                INSERT INTO offers(id,application_id,candidate_id,position_id,candidate_name,email,position_title,
                    salary,allowance,start_date,response_deadline,band_min,band_max,outside_band,status,version,
                    approval_round,created_by,submitted_by,approved_at,created_at,updated_at,sent_at,responded_at)
                SELECT ?,a.id,ca.candidate_id,r.position_id,a.full_name,a.email,p.name,
                    20000000,2000000,DATE '2026-11-15',?,10000000,30000000,false,?,0,1,?,?,?,?,?,?,?
                FROM job_applications a JOIN candidate_applications ca ON ca.application_id=a.id
                JOIN recruitment_requisitions r ON r.id=a.requisition_id JOIN positions p ON p.id=r.position_id WHERE a.id=?
                """, id, Timestamp.from(deadline), status, recruiter, recruiter, Timestamp.from(NOW.minusSeconds(7200)),
                Timestamp.from(NOW.minusSeconds(86400)), Timestamp.from(NOW), sent ? Timestamp.from(NOW.minusSeconds(3600)) : null,
                answered ? Timestamp.from(NOW.minusSeconds(60)) : null, applicationId);
        return id;
    }

    /** One requisition workflow event (append-only history) by hr. */
    void workflowEvent(UUID requisitionId, String type, String before, String after, Instant at) {
        int revision = jdbc.queryForObject("SELECT coalesce(max(revision), 0) + 1 FROM requisition_workflow_events WHERE requisition_id = ?",
                Integer.class, requisitionId);
        boolean decision = type.equals("APPROVED") || type.equals("REJECTED") || type.equals("INFO_REQUESTED");
        boolean needsComment = type.equals("REJECTED") || type.equals("INFO_REQUESTED") || type.equals("PAUSED")
                || type.equals("FILLED") || type.equals("CANCELLED");
        jdbc.update("""
                INSERT INTO requisition_workflow_events(id,requisition_id,revision,event_type,status_before,status_after,
                    round_number,level_number,actor_id,occurred_at,comment)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), requisitionId, revision, type, before, after, decision ? 1 : null,
                decision ? revision : null, hr, Timestamp.from(at), needsComment ? "Ghi chú" : null);
    }

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // Grants changed by a test (revoke, grant) are put back, so the next test of the class starts from the seed.
    @org.junit.jupiter.api.AfterEach
    void restorePermissionSeed() {
        if (permissionSeed == null) {
            return;
        }
        jdbc.update("DELETE FROM role_permissions");
        jdbc.batchUpdate("INSERT INTO role_permissions(role_code, permission_code) VALUES (?, ?)", permissionSeed.stream()
                .map(row -> new Object[] {row.get("role_code"), row.get("permission_code")}).toList());
    }

    /** Removes one grant from a seeded role until the end of the test. */
    void revoke(String role, String permission) {
        jdbc.update("DELETE FROM role_permissions WHERE role_code = ? AND permission_code = ?", role, permission);
    }

    void grant(String role, String permission) {
        jdbc.update("INSERT INTO role_permissions(role_code, permission_code) VALUES (?, ?) ON CONFLICT DO NOTHING", role, permission);
    }
}
