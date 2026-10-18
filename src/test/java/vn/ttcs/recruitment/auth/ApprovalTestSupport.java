package vn.ttcs.recruitment.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Tasks 262-267: the HTTP and SQL helpers the approval workflow tests share. Flows are written with SQL (a version is
// inserted, given its steps and sealed in one transaction, as V18 requires).
final class ApprovalTestSupport {
    static final String PASSWORD = "TestingOnly123!";

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String deviceId = UUID.randomUUID().toString();
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PlatformTransactionManager transactions;
    private final int port;

    ApprovalTestSupport(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactions, int port) {
        this.jdbc = jdbc;
        this.json = json;
        this.transactions = transactions;
        this.port = port;
    }

    record Step(int position, Long threshold, UUID user, String role) { }

    static Step step(int position, Long threshold, UUID user, String role) {
        return new Step(position, threshold, user, role);
    }

    // Every table the approval tests write, in an order the foreign keys accept. Flow versions and the approval
    // history cannot be deleted row by row (triggers), but TRUNCATE does not fire them.
    void reset() {
        jdbc.execute("TRUNCATE requisition_workflow_events, approval_flow_steps, approval_flow_versions, approval_flows");
        jdbc.update("DELETE FROM requisition_recruiter_changes");
        jdbc.update("DELETE FROM requisition_recruiters");
        jdbc.update("DELETE FROM requisition_headcount_overrides");
        jdbc.update("DELETE FROM headcount_plans");
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET department_id = NULL, admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("UPDATE departments SET parent_id = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM user_accounts");
    }

    UUID flow(UUID department, UUID createdBy, Instant at, Step... steps) {
        UUID id = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO approval_flows (id, department_id, current_version) VALUES (?, ?, 1)", id, department);
            version(id, 1, createdBy, at, steps);
        });
        return id;
    }

    void publish(UUID flow, int version, UUID createdBy, Instant at, Step... steps) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            version(flow, version, createdBy, at, steps);
            jdbc.update("UPDATE approval_flows SET current_version = ? WHERE id = ?", version, flow);
        });
    }

    private void version(UUID flow, int version, UUID createdBy, Instant at, Step... steps) {
        jdbc.update("INSERT INTO approval_flow_versions (flow_id, version, name, created_by, created_at) VALUES (?, ?, ?, ?, ?)",
                flow, version, "Duyệt tuyển dụng " + version, createdBy, Timestamp.from(at));
        for (Step step : steps) {
            jdbc.update("""
                    INSERT INTO approval_flow_steps (flow_id, version, position, salary_threshold, approver_user_id, approver_role)
                    VALUES (?, ?, ?, ?, ?, ?)""", flow, version, step.position(), step.threshold(), step.user(), step.role());
        }
        jdbc.update("UPDATE approval_flow_versions SET sealed = TRUE WHERE flow_id = ? AND version = ?", flow, version);
    }

    UUID department(String code, UUID manager, UUID parent, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, code, name, manager_user_id, parent_id, active, created_at) VALUES (?, ?, ?, ?, ?, TRUE, ?)",
                id, code, "Phòng " + code, manager, parent, Timestamp.from(at));
        return id;
    }

    UUID position(Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id, code, name, level, salary_min, salary_max, active, created_at, updated_at)
                VALUES (?, 'DEV', 'Lập trình viên', 'Junior', 15000000, 70000000, TRUE, ?, ?)
                """, id, Timestamp.from(at), Timestamp.from(at));
        return id;
    }

    // A complete draft (needed by 1 Dec 2026) with the given maximum salary, created by the token's account.
    UUID draft(UUID position, UUID department, long maxSalary, String token) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("positionId", position);
        body.put("departmentId", department);
        body.put("headcount", 1);
        body.put("reason", "NEW_HEADCOUNT");
        body.put("proposedSalaryMin", 20_000_000L);
        body.put("proposedSalaryMax", maxSalary);
        body.put("neededBy", "2026-12-01");
        body.put("jobDescription", "Phát triển dịch vụ tuyển dụng.");
        body.put("candidateRequirements", "Ba năm kinh nghiệm Java.");
        return UUID.fromString(expect(request("POST", "/api/v1/requisitions", json.writeValueAsString(body), token), 201)
                .path("id").asText());
    }

    // A draft submitted by the token's account; returns its id.
    UUID submitted(UUID position, UUID department, long maxSalary, String creatorToken, String submitterToken)
            throws Exception {
        UUID id = draft(position, department, maxSalary, creatorToken);
        expect(request("POST", "/api/v1/requisitions/" + id + "/submit", null, submitterToken), 200);
        return id;
    }

    HttpResponse<String> decide(UUID requisition, String action, String comment, Integer expectedRevision, String token)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        if (comment != null) {
            body.put("comment", comment);
        }
        if (expectedRevision != null) {
            body.put("expectedRevision", expectedRevision);
        }
        return request("POST", "/api/v1/requisitions/" + requisition + "/approvals", json.writeValueAsString(body), token);
    }

    int lastRevision(UUID requisition) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(revision), 0) FROM requisition_workflow_events WHERE requisition_id = ?",
                Integer.class, requisition);
    }

    int events(UUID requisition) {
        return jdbc.queryForObject("SELECT count(*) FROM requisition_workflow_events WHERE requisition_id = ?",
                Integer.class, requisition);
    }

    String status(UUID requisition) {
        return jdbc.queryForObject("SELECT status FROM recruitment_requisitions WHERE id = ?", String.class, requisition);
    }

    String token(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200)
                .path("accessToken").asText();
    }

    HttpResponse<String> request(String method, String path, String payload, String token) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + path);
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        if (method.equals("POST") && path.equals("/api/v1/auth/login")) {
            builder.header("X-Device-Id", deviceId);
        }
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode expect(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return json.readTree(response.body());
    }

    String code(HttpResponse<String> response, int expected) {
        return expect(response, expected).path("code").asText();
    }

    void forbidden(HttpResponse<String> response) {
        assertThat(code(response, 403)).isEqualTo("FORBIDDEN");
    }
}
