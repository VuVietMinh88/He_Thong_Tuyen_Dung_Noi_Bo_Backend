package vn.ttcs.recruitment.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.catalog.RecruitmentCatalogType;
import vn.ttcs.recruitment.requisition.RequisitionRecruiterRole;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Tasks 303-310: the HTTP and SQL helpers the job posting tests share. It composes ApprovalTestSupport: accounts,
// departments, positions, approval flows, logins and the requisition workflow come from there.
final class JobPostingTestSupport {
    static final String POSTINGS = "/api/v1/job-postings";
    static final String PUBLIC_POSTINGS = "/api/v1/public/job-postings";
    // The texts of every requisition made here; the leading spaces, the empty line and the last line break must
    // reach the posting unchanged.
    static final String JOB_DESCRIPTION = "  Phát triển API tuyển dụng.\n\n- Spring Boot\n- PostgreSQL\n";
    static final String REQUIREMENTS = "Tối thiểu 2 năm kinh nghiệm Java.\nĐọc hiểu tài liệu tiếng Anh.";
    static final String FORM_MESSAGE = "Vui lòng kiểm tra dữ liệu đã nhập.";
    static final String DEADLINE = "2026-11-30";

    final ApprovalTestSupport api;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    JobPostingTestSupport(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager transactions, int port) {
        this.api = new ApprovalTestSupport(jdbc, json, transactions, port);
        this.jdbc = jdbc;
        this.json = json;
    }

    // Postings reference requisitions, accounts and catalog values (RESTRICT), so they go first, after their history
    // and their authors. The history is emptied with TRUNCATE, which stays possible once V23 makes it append-only.
    void reset() {
        jdbc.execute("TRUNCATE job_posting_events");
        jdbc.update("DELETE FROM job_posting_authors");
        jdbc.update("DELETE FROM job_postings");
        jdbc.update("DELETE FROM recruitment_catalog_items");
        api.reset();
    }

    // A requisition approved through the API: the creator's complete requisition (20-22 million, needed by 1 Dec
    // 2026, JOB_DESCRIPTION and REQUIREMENTS) is submitted by the creator and approved by hrToken. The department needs
    // a flow whose only level is HR_MANAGER (api.flow), and the creator must never be the HR manager who decides.
    UUID approvedRequisition(UUID position, UUID department, String creatorToken, String hrToken) throws Exception {
        return approvedRequisition(position, department, creatorToken, hrToken, JOB_DESCRIPTION, REQUIREMENTS);
    }

    // Task 304: the same with other texts, for texts the requisition API accepts and a posting does not (HTML,
    // Windows line breaks).
    UUID approvedRequisition(UUID position, UUID department, String creatorToken, String hrToken,
                             String jobDescription, String candidateRequirements) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("positionId", position);
        body.put("departmentId", department);
        body.put("headcount", 2);
        body.put("reason", "NEW_HEADCOUNT");
        body.put("proposedSalaryMin", 20_000_000L);
        body.put("proposedSalaryMax", 22_000_000L);
        body.put("neededBy", "2026-12-01");
        body.put("jobDescription", jobDescription);
        body.put("candidateRequirements", candidateRequirements);
        UUID id = UUID.fromString(api.expect(api.request("POST", "/api/v1/requisitions", json.writeValueAsString(body),
                creatorToken), 201).path("id").asText());
        api.expect(api.request("POST", "/api/v1/requisitions/" + id + "/submit", null, creatorToken), 200);
        api.expect(api.decide(id, "APPROVE", null, api.lastRevision(id), hrToken), 200);
        assertThat(api.status(id)).isEqualTo("APPROVED");
        return id;
    }

    // A complete requisition in any status written with SQL, without workflow events, for sweeps over the statuses.
    UUID requisition(UUID position, UUID department, UUID createdBy, RequisitionStatus status, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_requisitions (id, position_id, department_id, headcount, reason,
                    proposed_salary_min, proposed_salary_max, needed_by, job_description, candidate_requirements,
                    status, created_by, created_at, updated_at)
                VALUES (?, ?, ?, 2, 'NEW_HEADCOUNT', 20000000, 22000000, ?, ?, ?, ?, ?, ?, ?)
                """, id, position, department, LocalDate.of(2026, 12, 1), JOB_DESCRIPTION, REQUIREMENTS,
                status.name(), createdBy, Timestamp.from(at), Timestamp.from(at));
        return id;
    }

    /**
     * Makes the recruiter a member of the requisition's team with SQL. A SUPPORTING row needs a PRIMARY row of the
     * same requisition: RequisitionAssignment.of throws on supporting-only teams, so every API that reads the team
     * (/assign, /unassign, /assignment) would answer 500. Insert the PRIMARY first. The scope of job postings is read
     * in SQL, not through RequisitionAssignment, so a lone SUPPORTING row is only safe in tests that never call those
     * APIs.
     */
    void assign(UUID requisition, UUID recruiter, RequisitionRecruiterRole role, UUID by, Instant at) {
        jdbc.update("""
                INSERT INTO requisition_recruiters (requisition_id, recruiter_id, assignment_role, assigned_by,
                    assigned_at)
                VALUES (?, ?, ?, ?, ?)
                """, requisition, recruiter, role.name(), by, Timestamp.from(at));
    }

    Set<UUID> authorsOf(UUID posting) {
        return new HashSet<>(jdbc.queryForList("SELECT account_id FROM job_posting_authors WHERE job_posting_id = ?",
                UUID.class, posting));
    }

    UUID catalogValue(RecruitmentCatalogType type, String code, String name, boolean active, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_catalog_items (id, catalog_type, code, name, sort_order, active, created_at,
                    updated_at)
                VALUES (?, ?, ?, ?, 0, ?, ?, ?)
                """, id, type.name(), code, name, active, Timestamp.from(at), Timestamp.from(at));
        return id;
    }

    // A create body with the public details filled in (deadline 30 Nov 2026, salary hidden); the texts are inherited.
    Map<String, Object> body(UUID requisition, UUID workLocation, UUID employmentType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requisitionId", requisition);
        body.put("workLocationId", workLocation);
        body.put("employmentTypeId", employmentType);
        body.put("applicationDeadline", DEADLINE);
        body.put("showSalary", false);
        return body;
    }

    // A PUT body that saves the posting as the view shows it, on the view's revision.
    Map<String, Object> putBody(JsonNode view) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedRevision", view.path("revision").asInt());
        for (String field : new String[] {"title", "jobDescription", "candidateRequirements", "benefits",
                "workLocationId", "employmentTypeId", "applicationDeadline"}) {
            body.put(field, view.path(field).isNull() ? null : view.path(field).asText());
        }
        body.put("showSalary", view.path("showSalary").asBoolean());
        return body;
    }

    HttpResponse<String> create(Map<String, Object> body, String token) throws Exception {
        return api.request("POST", POSTINGS, json.writeValueAsString(body), token);
    }

    HttpResponse<String> update(UUID posting, Map<String, Object> body, String token) throws Exception {
        return api.request("PUT", POSTINGS + "/" + posting, json.writeValueAsString(body), token);
    }

    HttpResponse<String> get(UUID posting, String token) throws Exception {
        return api.request("GET", POSTINGS + "/" + posting, null, token);
    }

    // query: "" or a query string starting with "?".
    HttpResponse<String> list(String query, String token) throws Exception {
        return api.request("GET", POSTINGS + query, null, token);
    }

    HttpResponse<String> sources(String query, String token) throws Exception {
        return api.request("GET", POSTINGS + "/source-requisitions" + query, null, token);
    }

    // Task 308: comment and expectedRevision are left out of the body when null.
    HttpResponse<String> submit(UUID posting, Integer expectedRevision, String comment, String token)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (expectedRevision != null) {
            body.put("expectedRevision", expectedRevision);
        }
        if (comment != null) {
            body.put("comment", comment);
        }
        return api.request("POST", POSTINGS + "/" + posting + "/submit", json.writeValueAsString(body), token);
    }

    HttpResponse<String> decide(UUID posting, String action, String comment, Integer expectedRevision, String token)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        if (comment != null) {
            body.put("comment", comment);
        }
        if (expectedRevision != null) {
            body.put("expectedRevision", expectedRevision);
        }
        return api.request("POST", POSTINGS + "/" + posting + "/approvals", json.writeValueAsString(body), token);
    }

    HttpResponse<String> publish(UUID posting, Integer expectedRevision, String token) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (expectedRevision != null) {
            body.put("expectedRevision", expectedRevision);
        }
        return api.request("POST", POSTINGS + "/" + posting + "/publish", json.writeValueAsString(body), token);
    }

    // Task 309: the authors' preview, and the public portal called the way candidates call it, without a token.
    HttpResponse<String> preview(UUID posting, String token) throws Exception {
        return api.request("GET", POSTINGS + "/" + posting + "/preview", null, token);
    }

    HttpResponse<String> publicDetail(UUID posting) throws Exception {
        return api.request("GET", PUBLIC_POSTINGS + "/" + posting, null, null);
    }

    HttpResponse<String> publicList(int page, int size) throws Exception {
        return api.request("GET", PUBLIC_POSTINGS + "?page=" + page + "&size=" + size, null, null);
    }

    // Creates a posting through the API and returns its view.
    JsonNode created(Map<String, Object> body, String token) throws Exception {
        var response = create(body, token);
        noStore(response);
        return api.expect(response, 201);
    }

    Map<String, Object> row(UUID posting) {
        return jdbc.queryForMap("SELECT * FROM job_postings WHERE id = ?", posting);
    }

    Map<String, Object> requisitionRow(UUID requisition) {
        return jdbc.queryForMap("SELECT * FROM recruitment_requisitions WHERE id = ?", requisition);
    }

    int revision(UUID posting) {
        return (Integer) row(posting).get("revision");
    }

    // Task 308: the review and publication history of the posting, oldest first.
    List<Map<String, Object>> events(UUID posting) {
        return jdbc.queryForList("SELECT * FROM job_posting_events WHERE job_posting_id = ? ORDER BY revision",
                posting);
    }

    int count() {
        return jdbc.queryForObject("SELECT count(*) FROM job_postings", Integer.class);
    }

    // An error answer with this status and code (and no-store); returns its body.
    JsonNode error(HttpResponse<String> response, int status, String code) {
        JsonNode body = api.expect(response, status);
        assertThat(body.path("code").asText()).as(body.toString()).isEqualTo(code);
        noStore(response);
        return body;
    }

    // A 400 VALIDATION_ERROR whose fieldErrors names exactly these request fields.
    JsonNode fieldErrors(HttpResponse<String> response, String... fields) {
        JsonNode body = error(response, 400, "VALIDATION_ERROR");
        assertThat(body.path("message").asText()).isEqualTo(FORM_MESSAGE);
        assertThat(body.path("fieldErrors").size()).as(body.toString()).isEqualTo(fields.length);
        for (String field : fields) {
            assertThat(body.path("fieldErrors").path(field).asText()).as(field).isNotBlank();
        }
        return body.path("fieldErrors");
    }

    // A 400 business error about one field: the same text is the message and the field's form error.
    void businessError(HttpResponse<String> response, String code, String field, String message) {
        JsonNode body = error(response, 400, code);
        assertThat(body.path("message").asText()).isEqualTo(message);
        assertThat(body.path("fieldErrors").size()).as(body.toString()).isEqualTo(1);
        assertThat(body.path("fieldErrors").path(field).asText()).isEqualTo(message);
    }

    void forbidden(HttpResponse<String> response) {
        JsonNode body = error(response, 403, "FORBIDDEN");
        assertThat(body.path("message").asText()).isEqualTo("Bạn không có quyền thực hiện thao tác này.");
    }

    void noStore(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }
}
