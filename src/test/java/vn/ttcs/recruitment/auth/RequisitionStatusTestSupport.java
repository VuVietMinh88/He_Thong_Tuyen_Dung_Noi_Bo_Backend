package vn.ttcs.recruitment.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Tasks 290/291/292/293: the HTTP and SQL helpers the status change tests share. It composes JobPostingTestSupport, so
// accounts, departments, flows, requisitions and postings come from there.
final class RequisitionStatusTestSupport {
    static final String REQUISITIONS = "/api/v1/requisitions/";

    final JobPostingTestSupport postings;
    final ApprovalTestSupport api;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    RequisitionStatusTestSupport(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactions,
                                 int port) {
        this.postings = new JobPostingTestSupport(jdbc, json, transactions, port);
        this.api = postings.api;
        this.jdbc = jdbc;
        this.json = json;
    }

    void reset() {
        postings.reset();
    }

    // reason and expectedRevision are left out of the body when null.
    HttpResponse<String> change(UUID requisition, String status, String reason, Integer expectedRevision,
                                String token) throws Exception {
        return change(requisition, status, reason, expectedRevision, null, token);
    }

    // Task 291: the same with confirmActiveCandidates, which is left out of the body when null.
    HttpResponse<String> change(UUID requisition, String status, String reason, Integer expectedRevision,
                                Boolean confirmActiveCandidates, String token) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        if (reason != null) {
            body.put("reason", reason);
        }
        if (expectedRevision != null) {
            body.put("expectedRevision", expectedRevision);
        }
        if (confirmActiveCandidates != null) {
            body.put("confirmActiveCandidates", confirmActiveCandidates);
        }
        return change(requisition, body, token);
    }

    // Task 291: GET /requisitions/{id}/active-pipeline.
    HttpResponse<String> activePipeline(UUID requisition, String token) throws Exception {
        return api.request("GET", REQUISITIONS + requisition + "/active-pipeline", null, token);
    }

    // Any body, null values included.
    HttpResponse<String> change(UUID requisition, Map<String, Object> body, String token) throws Exception {
        return post(REQUISITIONS + requisition + "/status-changes", json.writeValueAsString(body), token);
    }

    HttpResponse<String> post(String path, String payload, String token) throws Exception {
        return api.request("POST", path, payload, token);
    }

    // A requisition approved through the API by hrToken, the only level of its department's flow: revision 2.
    UUID approved(UUID position, UUID department, String creatorToken, String hrToken) throws Exception {
        return postings.approvedRequisition(position, department, creatorToken, hrToken);
    }

    // Task 292: a complete posting of the approved requisition, published through the API: hrToken writes and sends
    // it (1, 2), adminToken approves it (3; an author never approves) and hrToken publishes it (4).
    UUID publishedPosting(UUID requisition, UUID workLocation, UUID employmentType, String hrToken,
                          String adminToken) throws Exception {
        UUID id = UUID.fromString(postings.created(postings.body(requisition, workLocation, employmentType), hrToken)
                .path("id").asText());
        api.expect(postings.submit(id, 1, null, hrToken), 200);
        api.expect(postings.decide(id, "APPROVE", null, 2, adminToken), 200);
        api.expect(postings.publish(id, 3, hrToken), 200);
        return id;
    }

    // GET /requisitions/{id}/approvals: the progress, with the history newest first.
    JsonNode progress(UUID requisition, String token) throws Exception {
        return api.expect(api.request("GET", REQUISITIONS + requisition + "/approvals", null, token), 200);
    }

    JsonNode history(UUID requisition, String token) throws Exception {
        return progress(requisition, token).path("history");
    }

    // The requisition's workflow history, oldest first.
    List<Map<String, Object>> events(UUID requisition) {
        return jdbc.queryForList("SELECT * FROM requisition_workflow_events WHERE requisition_id = ? ORDER BY revision",
                requisition);
    }

    Map<String, Object> row(UUID requisition) {
        return postings.requisitionRow(requisition);
    }

    // A 200 answer with no-store; returns the requisition view.
    JsonNode ok(HttpResponse<String> response) {
        JsonNode body = api.expect(response, 200);
        postings.noStore(response);
        return body;
    }
}
