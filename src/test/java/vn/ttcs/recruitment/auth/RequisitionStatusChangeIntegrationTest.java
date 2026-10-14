package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.requisition.RequisitionRecruiterRole;
import vn.ttcs.recruitment.requisition.RequisitionStatus;
import vn.ttcs.recruitment.requisition.RequisitionStatusChange;
import vn.ttcs.recruitment.requisition.RequisitionStatusService;

import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.ttcs.recruitment.auth.ApprovalTestSupport.step;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.EMPLOYMENT_TYPE;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.WORK_LOCATION;

// Task 290 (story S3-07): "Kiểm tra quyền, trạng thái và lý do phù hợp cho tuyển đủ/tạm dừng/hủy". HR pauses, resumes,
// closes as filled or cancels a requisition with POST /requisitions/{id}/status-changes; task 293 records each change
// in the history. Task 292's JobPostingRequisitionSync is the only RequisitionStatusListener in this context; what it
// does to postings is tested in JobPostingClosureIntegrationTest. Task 291: this context has the production
// RequisitionPipelineSource. These fixtures create no applications, so closure needs no confirmation;
// ApplicationStorageIntegrationTest exercises the real data, and RequisitionActivePipelineIntegrationTest probes confirmation.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionStatusChangeIntegrationTest {
    // 10:00 on 11 Oct 2026 in Vietnam. Requisitions made with SQL need people by 1 Dec 2026.
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");
    private static final List<RequisitionStatus> TARGETS = List.of(RequisitionStatus.PAUSED,
            RequisitionStatus.APPROVED, RequisitionStatus.FILLED, RequisitionStatus.CANCELLED);
    private static final String REASON_REQUIRED =
            "Vui lòng nhập lý do khi tạm dừng, đóng vì đã tuyển đủ hoặc hủy yêu cầu tuyển dụng.";
    private static final String NOT_ALLOWED = "Không thể chuyển yêu cầu tuyển dụng từ trạng thái hiện tại sang trạng "
            + "thái đã chọn. Vui lòng tải lại để xem trạng thái mới nhất.";
    private static final String STATE_CHANGED = "Yêu cầu tuyển dụng vừa có thay đổi khác (có thể người khác vừa xử "
            + "lý). Vui lòng tải lại trước khi thao tác.";
    private static final String NOT_APPROVED_REQUISITION =
            "Chỉ tạo và xử lý được tin tuyển dụng của yêu cầu tuyển dụng đã duyệt.";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JwtDecoder jwtDecoder;
    @Autowired private RequisitionStatusService statusService;

    private RequisitionStatusTestSupport status;
    private JobPostingTestSupport postings;
    private ApprovalTestSupport api;
    private String passwordHash;
    private UUID adminId;
    private String adminToken;
    private UUID hrId;
    private String hrToken;
    private UUID itHeadId;
    private String itHeadToken;
    private UUID recruiterId;
    private String recruiterToken;
    private UUID backupRecruiterId;
    private UUID approverId;
    private String approverToken;
    private String interviewerToken;
    private String noRoleToken;
    private UUID it;
    private UUID ops;
    private UUID position;
    private UUID workLocation;
    private UUID employmentType;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void resetFixture() throws Exception {
        clock.set(START);
        status = new RequisitionStatusTestSupport(jdbc, json, transactions,
                Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        postings = status.postings;
        api = status.api;
        status.reset();
        bootstrap.run(new DefaultApplicationArguments());
        adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        adminToken = api.token("admin@example.test");
        hrId = account("hr@example.test", "Nguyễn Thị Hoa", Role.HR_MANAGER);
        hrToken = api.token("hr@example.test");
        itHeadId = account("head@example.test", "Trưởng phòng IT", Role.HIRING_MANAGER);
        itHeadToken = api.token("head@example.test");
        recruiterId = account("recruiter@example.test", "Trần Văn Tuyển", Role.RECRUITER);
        recruiterToken = api.token("recruiter@example.test");
        backupRecruiterId = account("backup@example.test", "Lê Văn Hỗ", Role.RECRUITER);
        approverId = account("approver@example.test", "Giám đốc khối", Role.APPROVER);
        approverToken = api.token("approver@example.test");
        account("interviewer@example.test", "Người phỏng vấn", Role.INTERVIEWER);
        interviewerToken = api.token("interviewer@example.test");
        account("norole@example.test", "Không vai trò");
        noRoleToken = api.token("norole@example.test");
        // IT's only level is HR_MANAGER; OPS's only level is the approver, who manages no department (READ_SCOPED).
        it = api.department("IT", itHeadId, null, START);
        ops = api.department("OPS", itHeadId, null, START);
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"));
        api.flow(ops, hrId, START, step(1, null, approverId, null));
        position = api.position(START);
        workLocation = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, START);
        employmentType = postings.catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", true, START);
    }

    static Stream<Arguments> everyStatusAndTarget() {
        return Arrays.stream(RequisitionStatus.values())
                .flatMap(from -> TARGETS.stream().map(target -> Arguments.of(from, target)));
    }

    // The statuses are set with SQL and have no history, so the revision HR looks at is 0.
    @ParameterizedTest(name = "{0} to {1}")
    @MethodSource("everyStatusAndTarget")
    void everyStatusTimesEveryTarget(RequisitionStatus from, RequisitionStatus target) throws Exception {
        UUID id = requisition(it, from);
        Map<String, Object> rowBefore = status.row(id);
        clock.set(START.plusSeconds(90).plusNanos(123_456_789));

        var response = status.change(id, target.name(), "Lý do " + target, 0, hrToken);

        boolean allowed = switch (from) {
            case DRAFT, PENDING_APPROVAL, RETURNED -> target == RequisitionStatus.CANCELLED;
            case APPROVED -> target != RequisitionStatus.APPROVED;
            case PAUSED -> target != RequisitionStatus.PAUSED;
            case REJECTED, FILLED, CANCELLED -> false;
        };
        if (allowed) {
            JsonNode view = status.ok(response);
            assertThat(view.path("status").asText()).isEqualTo(target.name());
            assertThat(view.path("updatedAt").asText()).isEqualTo(START.toString());
            assertThat(status.row(id).get("status")).isEqualTo(target.name());
            assertThat(status.events(id)).hasSize(1);
            Map<String, Object> event = status.events(id).getFirst();
            assertThat(event.get("revision")).isEqualTo(1);
            assertThat(event.get("event_type")).isEqualTo(target == RequisitionStatus.APPROVED ? "RESUMED"
                    : target.name());
            assertThat(event.get("status_before")).isEqualTo(from.name());
            assertThat(event.get("status_after")).isEqualTo(target.name());
            assertThat(event.get("actor_id")).isEqualTo(hrId);
            assertThat(((Timestamp) event.get("occurred_at")).toInstant())
                    .isEqualTo(Instant.parse("2026-10-11T03:01:30.123456Z"));
            assertThat(event.get("comment")).isEqualTo("Lý do " + target);
            for (String empty : List.of("round_number", "level_number", "flow_id", "flow_version", "level_count",
                    "salary_basis")) {
                assertThat(event.get(empty)).as(empty).isNull();
            }
            assertThat(status.progress(id, hrToken).path("revision").asInt()).isEqualTo(1);
        } else {
            JsonNode error = postings.error(response, 409, "REQUISITION_STATUS_CHANGE_NOT_ALLOWED");
            assertThat(error.path("message").asText()).isEqualTo(NOT_ALLOWED);
            assertThat(status.row(id)).isEqualTo(rowBefore);
            assertThat(status.events(id)).isEmpty();
        }
    }

    // A reason without any visible character is no reason, like every workflow comment (WorkflowComment): only spaces
    // and line breaks, only non-breaking spaces PostgreSQL counts as spaces (the history CHECK refused them: 500), or
    // only zero-width characters (they were stored as a required reason that shows nothing).
    @Test
    void aReasonIsRequiredToPauseFillOrCancel() throws Exception {
        UUID approved = requisition(it, RequisitionStatus.APPROVED);
        Map<String, Object> rowBefore = status.row(approved);
        List<String> blanks = Arrays.asList(null, "", "  \n\t ", codePoints(0x00A0), codePoints(0x0085),
                codePoints(0x200B, 0xFEFF), codePoints(0x2007, 0x202F, '\n', 0x180E, 0x2060, '\t'));
        // Checked before any lock or lookup: an unknown requisition gets the same answer, not 404.
        for (UUID id : List.of(approved, UUID.randomUUID())) {
            for (String target : List.of("PAUSED", "FILLED", "CANCELLED")) {
                postings.businessError(status.change(id, target, null, 0, hrToken),
                        "REQUISITION_STATUS_REASON_REQUIRED", "reason", REASON_REQUIRED);
                for (String blank : blanks) {
                    Map<String, Object> body = new HashMap<>();
                    body.put("status", target);
                    body.put("reason", blank);
                    body.put("expectedRevision", 0);
                    postings.businessError(status.change(id, body, hrToken), "REQUISITION_STATUS_REASON_REQUIRED",
                            "reason", REASON_REQUIRED);
                }
            }
        }
        assertThat(postings.fieldErrors(status.change(approved, "PAUSED", "x".repeat(2_001), 0, hrToken), "reason")
                .path("reason").asText()).isEqualTo("Lý do tối đa 2.000 ký tự.");
        assertThat(postings.fieldErrors(status.change(approved, "PAUSED", "Chờ" + (char) 0 + "ngân sách", 0,
                hrToken), "reason").path("reason").asText()).isEqualTo("Lý do chứa ký tự không hợp lệ.");
        assertThat(status.row(approved)).isEqualTo(rowBefore);
        assertThat(status.events(approved)).isEmpty();

        // Resuming needs no reason; one without any visible character counts as none, and a reason given is kept
        // byte for byte.
        for (String blank : List.of("   ", codePoints(0x00A0), codePoints(0x0085), codePoints(0x200B, 0xFEFF))) {
            UUID quiet = requisition(it, RequisitionStatus.PAUSED);
            assertThat(status.ok(status.change(quiet, "APPROVED", blank, 0, hrToken)).path("status").asText())
                    .isEqualTo("APPROVED");
            assertThat(status.events(quiet).getFirst().get("comment"))
                    .as(blank.codePoints().boxed().toList().toString()).isNull();
        }
        UUID explained = requisition(it, RequisitionStatus.PAUSED);
        String reason = "  Đã có ngân sách quý 4.\n\nTuyển tiếp từ tuần sau.\n";
        status.ok(status.change(explained, "APPROVED", reason, 0, hrToken));
        assertThat(status.events(explained).getFirst().get("comment")).isEqualTo(reason);
        // A visible reason keeps the invisible characters around it.
        UUID filled = requisition(it, RequisitionStatus.APPROVED);
        String wrapped = codePoints(0x00A0) + "Đã tuyển đủ" + codePoints(0x200B);
        status.ok(status.change(filled, "FILLED", wrapped, 0, hrToken));
        assertThat(status.events(filled).getFirst().get("comment")).isEqualTo(wrapped);
        // 2,000 characters is still accepted.
        String longest = "y".repeat(2_000);
        status.ok(status.change(approved, "CANCELLED", longest, 0, hrToken));
        assertThat(status.events(approved).getFirst().get("comment")).isEqualTo(longest);
    }

    @Test
    void theBodyIsValidated() throws Exception {
        UUID id = requisition(it, RequisitionStatus.APPROVED);
        Map<String, Object> rowBefore = status.row(id);

        JsonNode empty = postings.fieldErrors(status.post(RequisitionStatusTestSupport.REQUISITIONS + id
                + "/status-changes", "{}", hrToken), "status", "expectedRevision");
        assertThat(empty.path("status").asText()).isEqualTo("Vui lòng chọn trạng thái mới.");
        assertThat(empty.path("expectedRevision").asText())
                .isEqualTo("Thiếu số phiên bản lịch sử của yêu cầu đang xem.");
        Map<String, Object> noStatus = new HashMap<>();
        noStatus.put("status", null);
        noStatus.put("reason", "Chờ ngân sách");
        noStatus.put("expectedRevision", 0);
        assertThat(postings.fieldErrors(status.change(id, noStatus, hrToken), "status").path("status").asText())
                .isEqualTo("Vui lòng chọn trạng thái mới.");
        // Codes are exact: the frontend's CLOSED is FILLED here, and the workflow's own statuses are not targets.
        for (String code : List.of("paused", "CLOSED", "DRAFT", "REJECTED", "PENDING_APPROVAL", "RESUMED", "")) {
            assertThat(postings.fieldErrors(status.change(id, code, "Chờ ngân sách", 0, hrToken), "status")
                    .path("status").asText()).as(code).isEqualTo(RequisitionStatusChange.TARGETS_MESSAGE);
        }
        Map<String, Object> noRevision = new HashMap<>(Map.of("status", "PAUSED", "reason", "Chờ ngân sách"));
        noRevision.put("expectedRevision", null);
        assertThat(postings.fieldErrors(status.change(id, noRevision, hrToken), "expectedRevision")
                .path("expectedRevision").asText()).isEqualTo("Thiếu số phiên bản lịch sử của yêu cầu đang xem.");
        assertThat(postings.fieldErrors(status.change(id, "PAUSED", "Chờ ngân sách", -1, hrToken),
                "expectedRevision").path("expectedRevision").asText()).isEqualTo("Số phiên bản lịch sử không hợp lệ.");

        // The actor, the time, the status before and any reason code are the server's, never the client's.
        for (var field : Map.of("comment", (Object) "Chờ ngân sách", "reasonCode", "BUDGET", "actorId",
                adminId.toString(), "occurredAt", "2026-10-11T03:00:00Z", "statusBefore", "APPROVED").entrySet()) {
            Map<String, Object> body = new LinkedHashMap<>(Map.of("status", "PAUSED", "reason", "Chờ ngân sách",
                    "expectedRevision", 0));
            body.put(field.getKey(), field.getValue());
            postings.error(status.change(id, body, hrToken), 400, "INVALID_JSON");
        }
        postings.error(status.post(RequisitionStatusTestSupport.REQUISITIONS + id + "/status-changes",
                "{\"status\":", hrToken), 400, "INVALID_JSON");
        JsonNode badId = postings.error(status.post(RequisitionStatusTestSupport.REQUISITIONS
                + "not-a-uuid/status-changes", json.writeValueAsString(Map.of("status", "PAUSED", "reason",
                "Chờ ngân sách", "expectedRevision", 0)), hrToken), 400, "VALIDATION_ERROR");
        assertThat(badId.path("message").asText()).isEqualTo("Tham số đường dẫn hoặc bộ lọc không hợp lệ.");
        // Task 291: confirmActiveCandidates is a boolean; another value, or the field under another name, is no JSON
        // this API reads.
        for (Object notABoolean : List.of("yes", List.of(), Map.of())) {
            Map<String, Object> body = new LinkedHashMap<>(Map.of("status", "FILLED", "reason", "Đã tuyển đủ",
                    "expectedRevision", 0, "confirmActiveCandidates", notABoolean));
            postings.error(status.change(id, body, hrToken), 400, "INVALID_JSON");
        }
        postings.error(status.change(id, Map.of("status", "FILLED", "reason", "Đã tuyển đủ", "expectedRevision", 0,
                "confirmed", true), hrToken), 400, "INVALID_JSON");

        assertThat(status.row(id)).isEqualTo(rowBefore);
        assertThat(status.events(id)).isEmpty();

        // Task 291: true, false, null and absent are accepted (here nobody is in the pipeline, so they change
        // nothing), and so are the scalar coercions Jackson applies to a boolean ("true", 1). A confirmation sent
        // with a change that needs none is ignored.
        List<Object> accepted = Arrays.asList(true, false, null, "true", 1);
        for (Object confirm : accepted) {
            UUID filled = requisition(it, RequisitionStatus.APPROVED);
            Map<String, Object> body = new HashMap<>(Map.of("status", "FILLED", "reason", "Đã tuyển đủ",
                    "expectedRevision", 0));
            body.put("confirmActiveCandidates", confirm);
            assertThat(status.ok(status.change(filled, body, hrToken)).path("status").asText())
                    .as(String.valueOf(confirm)).isEqualTo("FILLED");
        }
        assertThat(status.ok(status.change(id, "PAUSED", "Chờ ngân sách", 0, true, hrToken)).path("status").asText())
                .isEqualTo("PAUSED");
    }

    // Task 291: without a candidate module the backend knows of nobody in any requisition's pipeline. The answer is
    // the honest "no data" ({count: 0, stages: []}), in every status, and never anything invented.
    @ParameterizedTest
    @EnumSource(RequisitionStatus.class)
    void theActivePipelineIsEmptyWithoutACandidateModule(RequisitionStatus requisitionStatus) throws Exception {
        UUID id = requisition(it, requisitionStatus);

        for (String token : List.of(hrToken, adminToken)) {
            JsonNode pipeline = status.ok(status.activePipeline(id, token));
            assertThat(fieldNames(pipeline)).containsExactly("count", "stages");
            assertThat(pipeline.path("count").isInt()).isTrue();
            assertThat(pipeline.path("count").asInt()).isZero();
            assertThat(pipeline.path("stages").isArray()).isTrue();
            assertThat(pipeline.path("stages").size()).isZero();
        }
    }

    // Task 291: the pipeline is shown in HR's close dialog, so only those who may close read it, with the read checks
    // of a change: read access at all (403 before 404), then the department read scope. Being the head of the
    // department or its assigned recruiter is not enough.
    @Test
    void theActivePipelineIsReadOnlyByThoseWhoClose() throws Exception {
        UUID id = requisition(it, RequisitionStatus.APPROVED);
        api.expect(assign(id, "/assign", Map.of("recruiterId", recruiterId)), 200);
        // The assigned recruiter and the head read the requisition itself (tasks 245, 285), but not its pipeline.
        for (String token : List.of(recruiterToken, itHeadToken)) {
            api.expect(api.request("GET", RequisitionStatusTestSupport.REQUISITIONS + id, null, token), 200);
        }

        postings.error(status.activePipeline(id, null), 401, "UNAUTHORIZED");
        for (String token : List.of(itHeadToken, recruiterToken, approverToken, interviewerToken, noRoleToken)) {
            postings.forbidden(status.activePipeline(id, token));
        }
        // The service checks REQUISITIONS_WRITE_ALL itself, so a matcher that let a reader through would still refuse.
        for (String token : List.of(itHeadToken, recruiterToken, approverToken)) {
            Jwt jwt = jwtDecoder.decode(token);
            assertThatThrownBy(() -> statusService.activePipeline(jwt, id)).isInstanceOf(AccessDeniedException.class);
        }
        JsonNode badId = postings.error(api.request("GET", RequisitionStatusTestSupport.REQUISITIONS
                + "not-a-uuid/active-pipeline", null, hrToken), 400, "VALIDATION_ERROR");
        assertThat(badId.path("message").asText()).isEqualTo("Tham số đường dẫn hoặc bộ lọc không hợp lệ.");
        JsonNode missing = postings.error(status.activePipeline(UUID.randomUUID(), hrToken), 404,
                "REQUISITION_NOT_FOUND");
        assertThat(missing.path("message").asText()).isEqualTo("Không tìm thấy yêu cầu tuyển dụng.");

        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' "
                + "AND permission_code = 'REQUISITIONS_READ_ALL'");
        try {
            for (UUID requisition : List.of(id, UUID.randomUUID())) {
                postings.forbidden(status.activePipeline(requisition, hrToken));
            }
            // With READ_SCOPED the unknown requisition is 404, but IT is managed by its head, not by HR: 403 until HR
            // manages it.
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                    + "VALUES ('HR_MANAGER', 'REQUISITIONS_READ_SCOPED')");
            postings.error(status.activePipeline(UUID.randomUUID(), hrToken), 404, "REQUISITION_NOT_FOUND");
            postings.forbidden(status.activePipeline(id, hrToken));
            // On the team, HR reads the requisition itself (task 285) but still not its pipeline.
            postings.assign(id, hrId, RequisitionRecruiterRole.SUPPORTING, adminId, START);
            api.expect(api.request("GET", RequisitionStatusTestSupport.REQUISITIONS + id, null, hrToken), 200);
            postings.forbidden(status.activePipeline(id, hrToken));
            jdbc.update("UPDATE departments SET manager_user_id = ? WHERE id = ?", hrId, it);
            assertThat(status.ok(status.activePipeline(id, hrToken)).path("count").asInt()).isZero();
        } finally {
            jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' "
                    + "AND permission_code = 'REQUISITIONS_READ_SCOPED'");
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                    + "VALUES ('HR_MANAGER', 'REQUISITIONS_READ_ALL') ON CONFLICT DO NOTHING");
        }
    }

    // Story S3-07 and Q18.3: only the HR manager (and ADMIN, who holds the same REQUISITIONS_WRITE_ALL) changes a
    // status. Neither the head of the department, nor its recruiter, nor the approver who approved it may.
    @Test
    void onlyHrManagerAndAdminChangeStatus() throws Exception {
        UUID id = api.draft(position, ops, 22_000_000L, itHeadToken);
        api.expect(api.request("POST", RequisitionStatusTestSupport.REQUISITIONS + id + "/submit", null, itHeadToken),
                200);
        api.expect(api.decide(id, "APPROVE", null, 1, approverToken), 200);
        postings.assign(id, recruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
        Map<String, Object> rowBefore = status.row(id);
        List<Map<String, Object>> eventsBefore = status.events(id);

        for (String token : List.of(itHeadToken, recruiterToken, approverToken, interviewerToken, noRoleToken)) {
            postings.forbidden(status.change(id, "PAUSED", "Tạm dừng thay", 2, token));
            postings.forbidden(status.change(id, "CANCELLED", "Hủy thay", 2, token));
        }
        postings.error(status.change(id, "PAUSED", "Tạm dừng thay", 2, null), 401, "UNAUTHORIZED");
        assertThat(status.row(id)).isEqualTo(rowBefore);
        assertThat(status.events(id)).isEqualTo(eventsBefore);

        assertThat(status.ok(status.change(id, "PAUSED", "Chờ ngân sách", 2, hrToken)).path("status").asText())
                .isEqualTo("PAUSED");
        assertThat(status.ok(status.change(id, "APPROVED", null, 3, adminToken)).path("status").asText())
                .isEqualTo("APPROVED");
        assertThat(status.events(id)).extracting(event -> event.get("actor_id")).containsExactly(itHeadId, approverId,
                hrId, adminId);
    }

    // Like assignment and copy: without any REQUISITIONS read permission the caller learns nothing, so a known and an
    // unknown requisition both answer 403, never 404. A SCOPED reader must manage the requisition's department.
    @Test
    void writeWithoutReadIs403BeforeTheLookup() throws Exception {
        UUID id = requisition(it, RequisitionStatus.APPROVED);
        Map<String, Object> rowBefore = status.row(id);
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' "
                + "AND permission_code = 'REQUISITIONS_READ_ALL'");
        try {
            for (UUID requisition : List.of(id, UUID.randomUUID())) {
                postings.forbidden(status.change(requisition, "PAUSED", "Chờ ngân sách", 0, hrToken));
            }
            assertThat(status.row(id)).isEqualTo(rowBefore);
            assertThat(status.events(id)).isEmpty();

            // With READ_SCOPED the unknown requisition is 404, but IT is managed by its head, not by HR: 403 until HR
            // manages it.
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                    + "VALUES ('HR_MANAGER', 'REQUISITIONS_READ_SCOPED')");
            postings.error(status.change(UUID.randomUUID(), "PAUSED", "Chờ ngân sách", 0, hrToken), 404,
                    "REQUISITION_NOT_FOUND");
            postings.forbidden(status.change(id, "PAUSED", "Chờ ngân sách", 0, hrToken));
            assertThat(status.row(id)).isEqualTo(rowBefore);
            assertThat(status.events(id)).isEmpty();

            jdbc.update("UPDATE departments SET manager_user_id = ? WHERE id = ?", hrId, it);
            assertThat(status.ok(status.change(id, "PAUSED", "Chờ ngân sách", 0, hrToken)).path("status").asText())
                    .isEqualTo("PAUSED");
            assertThat(status.events(id)).hasSize(1);
        } finally {
            jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' "
                    + "AND permission_code = 'REQUISITIONS_READ_SCOPED'");
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                    + "VALUES ('HR_MANAGER', 'REQUISITIONS_READ_ALL') ON CONFLICT DO NOTHING");
        }
        // With the permission back, the unknown requisition is 404.
        JsonNode missing = postings.error(status.change(UUID.randomUUID(), "PAUSED", "Chờ ngân sách", 0, hrToken), 404,
                "REQUISITION_NOT_FOUND");
        assertThat(missing.path("message").asText()).isEqualTo("Không tìm thấy yêu cầu tuyển dụng.");
    }

    @Test
    void staleRevisionsAreRefusedAfterTheStatus() throws Exception {
        UUID id = status.approved(position, it, itHeadToken, hrToken);
        Map<String, Object> rowBefore = status.row(id);
        for (int stale : List.of(1, 3)) {
            JsonNode error = postings.error(status.change(id, "PAUSED", "Chờ ngân sách", stale, hrToken), 409,
                    "REQUISITION_STATE_CHANGED");
            assertThat(error.path("message").asText()).isEqualTo(STATE_CHANGED);
        }
        assertThat(status.row(id)).isEqualTo(rowBefore);
        assertThat(status.events(id)).hasSize(2);

        status.ok(status.change(id, "PAUSED", "Chờ ngân sách", 2, hrToken));
        // A second click on the same outdated page: the status answers before the revision.
        for (int revision : List.of(2, 3)) {
            postings.error(status.change(id, "PAUSED", "Chờ ngân sách", revision, hrToken), 409,
                    "REQUISITION_STATUS_CHANGE_NOT_ALLOWED");
        }
        assertThat(status.events(id)).hasSize(3);
        assertThat(status.row(id).get("status")).isEqualTo("PAUSED");
    }

    @Test
    void aChangeIsRecordedWithWhoWhenBeforeAfterAndWhy() throws Exception {
        UUID id = status.approved(position, it, adminToken, hrToken);
        String updatedAt = status.ok(api.request("GET", RequisitionStatusTestSupport.REQUISITIONS + id, null, hrToken))
                .path("updatedAt").asText();

        clock.set(START.plusSeconds(60).plusNanos(987_654_321));
        JsonNode paused = status.ok(status.change(id, "PAUSED", "Chờ ngân sách\nquý 4", 2, hrToken));
        clock.set(START.plusSeconds(120).plusNanos(987_654_321));
        JsonNode resumed = status.ok(status.change(id, "APPROVED", null, 3, hrToken));
        clock.set(START.plusSeconds(180).plusNanos(987_654_321));
        JsonNode filled = status.ok(status.change(id, "FILLED", "Đã tuyển đủ 2 người qua đợt tháng 10.", 4, hrToken));

        assertThat(List.of(paused, resumed, filled)).extracting(view -> view.path("status").asText())
                .containsExactly("PAUSED", "APPROVED", "FILLED");
        assertThat(List.of(paused, resumed, filled)).extracting(view -> view.path("updatedAt").asText())
                .containsOnly(updatedAt);
        assertThat(fieldNames(filled)).hasSize(15);
        JsonNode progress = status.progress(id, hrToken);
        assertThat(progress.path("status").asText()).isEqualTo("FILLED");
        assertThat(progress.path("revision").asInt()).isEqualTo(5);
        JsonNode history = progress.path("history");
        assertThat(history.size()).isEqualTo(5);
        assertEntry(history.path(0), 5, "FILLED", "APPROVED", "FILLED", "2026-10-11T03:03:00.987654Z",
                "Đã tuyển đủ 2 người qua đợt tháng 10.");
        assertEntry(history.path(1), 4, "RESUMED", "PAUSED", "APPROVED", "2026-10-11T03:02:00.987654Z", null);
        assertEntry(history.path(2), 3, "PAUSED", "APPROVED", "PAUSED", "2026-10-11T03:01:00.987654Z",
                "Chờ ngân sách\nquý 4");
        // The approval itself is unchanged: ADMIN created and sent it, HR approved it.
        assertThat(history.path(3).path("action").asText()).isEqualTo("APPROVED");
        assertThat(history.path(4).path("action").asText()).isEqualTo("SUBMITTED");
        assertThat(history.path(4).path("actorId").asText()).isEqualTo(adminId.toString());
        assertThat(progress.path("levels").path(0).path("status").asText()).isEqualTo("APPROVED");
        assertThat(progress.path("waitingOn").isNull()).isTrue();
    }

    // Story S3-07 and §7.3 of the design: what the status groups do once a status changes. No code of task 290 does
    // any of this; the groups of task 293 do.
    @Test
    void effectsOnTheRestOfTheSystem() throws Exception {
        UUID department = api.department("HC", itHeadId, null, START);
        UUID paused = requisition(department, RequisitionStatus.APPROVED);
        UUID filled = requisition(department, RequisitionStatus.APPROVED);
        UUID cancelled = requisition(department, RequisitionStatus.APPROVED);
        UUID stillApproved = requisition(department, RequisitionStatus.APPROVED);
        for (UUID id : List.of(filled, cancelled)) {
            postings.assign(id, recruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
            postings.assign(id, backupRecruiterId, RequisitionRecruiterRole.SUPPORTING, hrId, START);
        }
        assertThat(headcountUsed(department)).isEqualTo(8);

        // Pausing and filling keep their places in the plan; cancelling gives them back.
        status.ok(status.change(paused, "PAUSED", "Chờ ngân sách", 0, hrToken));
        status.ok(status.change(filled, "FILLED", "Đã tuyển đủ", 0, hrToken));
        assertThat(headcountUsed(department)).isEqualTo(8);
        status.ok(status.change(cancelled, "CANCELLED", "Không còn nhu cầu", 0, hrToken));
        assertThat(headcountUsed(department)).isEqualTo(6);

        // A paused requisition may still hand over its recruiters; a filled or cancelled one is finished.
        api.expect(assign(paused, "/assign", Map.of("recruiterId", recruiterId)), 200);
        for (UUID id : List.of(filled, cancelled)) {
            postings.error(assign(id, "/assign", Map.of("recruiterId", backupRecruiterId)), 409,
                    "REQUISITION_NOT_ASSIGNABLE");
            postings.error(assign(id, "/unassign", Map.of("recruiterId", backupRecruiterId)), 409,
                    "REQUISITION_NOT_ASSIGNABLE");
        }

        // None of the three is edited or sent for approval again.
        for (UUID id : List.of(paused, filled, cancelled)) {
            postings.error(api.request("PUT", RequisitionStatusTestSupport.REQUISITIONS + id,
                    json.writeValueAsString(content(department)), hrToken), 409, "REQUISITION_NOT_DRAFT");
            postings.error(api.request("POST", RequisitionStatusTestSupport.REQUISITIONS + id + "/submit", null,
                    hrToken), 409, "REQUISITION_NOT_SUBMITTABLE");
        }

        // Past its needed-by date only the requisition still being staffed is overdue.
        jdbc.update("UPDATE recruitment_requisitions SET needed_by = ? WHERE department_id = ?",
                LocalDate.of(2026, 10, 1), department);
        JsonNode list = status.ok(api.request("GET", "/api/v1/requisitions?departmentId=" + department + "&size=100",
                null, hrToken));
        Map<String, Boolean> overdue = new HashMap<>();
        list.path("items").forEach(item -> overdue.put(item.path("id").asText(), item.path("overdue").asBoolean()));
        assertThat(overdue).containsExactlyInAnyOrderEntriesOf(Map.of(paused.toString(), false, filled.toString(),
                false, cancelled.toString(), false, stillApproved.toString(), true));

        // To recruit again, a filled or cancelled requisition is copied into a fresh draft.
        for (UUID id : List.of(filled, cancelled)) {
            JsonNode copy = api.expect(api.request("POST", RequisitionStatusTestSupport.REQUISITIONS + id + "/copy",
                    "{}", hrToken), 201);
            assertThat(copy.path("status").asText()).isEqualTo("DRAFT");
        }

        cancelWhilePending();
        pausedPostingsAreFrozenAndHidden();
    }

    // HR cancels a requisition the approver of OPS still has to decide: it leaves the approver's inbox, and the
    // approver's late decision on the old page is told the requisition no longer waits (409, not 403).
    private void cancelWhilePending() throws Exception {
        UUID id = api.draft(position, ops, 22_000_000L, itHeadToken);
        api.expect(api.request("POST", RequisitionStatusTestSupport.REQUISITIONS + id + "/submit", null, itHeadToken),
                200);
        String inbox = "/api/v1/requisitions/approval-inbox";
        assertThat(api.expect(api.request("GET", inbox, null, approverToken), 200).path("totalElements").asLong())
                .isOne();

        status.ok(status.change(id, "CANCELLED", "Trùng yêu cầu khác", 1, hrToken));

        assertThat(api.expect(api.request("GET", inbox, null, approverToken), 200).path("totalElements").asLong())
                .isZero();
        api.expect(api.request("GET", RequisitionStatusTestSupport.REQUISITIONS + id, null, approverToken), 200);
        for (String token : List.of(approverToken, hrToken)) {
            postings.error(api.decide(id, "APPROVE", null, 2, token), 409, "REQUISITION_NOT_PENDING_APPROVAL");
        }
        assertThat(status.events(id)).hasSize(2);
        assertThat(status.row(id).get("status")).isEqualTo("CANCELLED");
    }

    // The recruiter writes and sends the postings, HR reviews and publishes them (HR is never an author here). While
    // the requisition is paused, every posting write is refused, every action flag is off and the published posting
    // leaves the portal; resuming brings it back.
    private void pausedPostingsAreFrozenAndHidden() throws Exception {
        UUID draft = postingFor(requisition(it, RequisitionStatus.APPROVED));
        UUID pending = postingFor(requisition(it, RequisitionStatus.APPROVED));
        UUID approved = postingFor(requisition(it, RequisitionStatus.APPROVED));
        UUID published = postingFor(requisition(it, RequisitionStatus.APPROVED));
        for (UUID posting : List.of(pending, approved, published)) {
            api.expect(postings.submit(posting, 1, null, recruiterToken), 200);
        }
        for (UUID posting : List.of(approved, published)) {
            api.expect(postings.decide(posting, "APPROVE", null, 2, hrToken), 200);
        }
        api.expect(postings.publish(published, 3, hrToken), 200);
        api.expect(postings.publicDetail(published), 200);
        assertThat(api.expect(postings.get(pending, hrToken), 200).path("actions").path("canApprove").asBoolean())
                .isTrue();
        for (UUID posting : List.of(draft, pending, approved, published)) {
            status.ok(status.change(requisitionOf(posting), "PAUSED", "Chờ ngân sách", 0, hrToken));
        }
        List<Map<String, Object>> rowsBefore = jdbc.queryForList("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1");

        JsonNode draftView = api.expect(postings.get(draft, recruiterToken), 200);
        List<HttpResponse<String>> blocked = List.of(
                postings.update(draft, postings.putBody(draftView), recruiterToken),
                postings.submit(draft, 1, null, recruiterToken),
                postings.decide(pending, "APPROVE", null, 2, hrToken),
                postings.decide(pending, "REQUEST_REVISION", "Sửa lại", 2, hrToken),
                postings.decide(approved, "REQUEST_REVISION", "Sửa lại", 3, hrToken),
                postings.publish(approved, 3, hrToken));
        for (var response : blocked) {
            assertThat(postings.error(response, 409, "JOB_POSTING_REQUISITION_NOT_APPROVED").path("message").asText())
                    .isEqualTo(NOT_APPROVED_REQUISITION);
        }
        for (UUID posting : List.of(draft, pending, approved, published)) {
            JsonNode actions = api.expect(postings.get(posting, hrToken), 200).path("actions");
            for (String flag : List.of("canEdit", "canSubmit", "canApprove", "canRequestRevision", "canPublish")) {
                assertThat(actions.path(flag).asBoolean()).as(flag).isFalse();
            }
        }
        postings.error(postings.publicDetail(published), 404, "JOB_POSTING_NOT_FOUND");
        assertThat(api.expect(postings.publicList(0, 20), 200).path("totalElements").asLong()).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1")).isEqualTo(eventsBefore);

        // The posting kept its status while hidden; its deadline (30 Nov) has not passed, so it is public again.
        status.ok(status.change(requisitionOf(published), "APPROVED", null, 1, hrToken));
        assertThat(api.expect(postings.publicDetail(published), 200).path("id").asText())
                .isEqualTo(published.toString());
    }

    private void assertEntry(JsonNode entry, int revision, String action, String before, String after,
                             String occurredAt, String comment) {
        assertThat(entry.path("revision").asInt()).isEqualTo(revision);
        assertThat(entry.path("round").isNull()).isTrue();
        assertThat(entry.path("level").isNull()).isTrue();
        assertThat(entry.path("action").asText()).isEqualTo(action);
        assertThat(entry.path("statusBefore").asText()).isEqualTo(before);
        assertThat(entry.path("statusAfter").asText()).isEqualTo(after);
        assertThat(entry.path("actorId").asText()).isEqualTo(hrId.toString());
        assertThat(entry.path("actorName").asText()).isEqualTo("Nguyễn Thị Hoa");
        assertThat(entry.path("occurredAt").asText()).isEqualTo(occurredAt);
        if (comment == null) {
            assertThat(entry.path("comment").isNull()).isTrue();
        } else {
            assertThat(entry.path("comment").asText()).isEqualTo(comment);
        }
        for (String empty : List.of("flowVersion", "levelCount", "salaryBasis")) {
            assertThat(entry.path(empty).isNull()).as(empty).isTrue();
        }
    }

    // A complete requisition of the department in this status, written with SQL by ADMIN, with no history.
    private UUID requisition(UUID department, RequisitionStatus requisitionStatus) {
        return postings.requisition(position, department, adminId, requisitionStatus, START);
    }

    // A complete posting of an approved requisition, written by its primary recruiter.
    private UUID postingFor(UUID requisition) throws Exception {
        postings.assign(requisition, recruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
        return UUID.fromString(postings.created(postings.body(requisition, workLocation, employmentType),
                recruiterToken).path("id").asText());
    }

    private UUID requisitionOf(UUID posting) {
        return (UUID) postings.row(posting).get("requisition_id");
    }

    private long headcountUsed(UUID department) throws Exception {
        return api.expect(api.request("GET", "/api/v1/headcount-plans/remaining?departmentId=" + department
                + "&year=2026", null, hrToken), 200).path("headcountUsed").asLong();
    }

    private HttpResponse<String> assign(UUID requisition, String action, Map<String, Object> body) throws Exception {
        return api.request("POST", RequisitionStatusTestSupport.REQUISITIONS + requisition + action,
                json.writeValueAsString(body), hrToken);
    }

    // The content every SQL requisition has, as a PUT body.
    private Map<String, Object> content(UUID department) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("positionId", position);
        body.put("departmentId", department);
        body.put("headcount", 2);
        body.put("reason", "NEW_HEADCOUNT");
        body.put("proposedSalaryMin", 20_000_000L);
        body.put("proposedSalaryMax", 22_000_000L);
        body.put("neededBy", "2026-12-01");
        body.put("jobDescription", JobPostingTestSupport.JOB_DESCRIPTION);
        body.put("candidateRequirements", JobPostingTestSupport.REQUIREMENTS);
        return body;
    }

    private UUID account(String email, String fullName, Role... roles) {
        return accounts.saveAndFlush(new Account(email, fullName, passwordHash, Set.of(roles), START)).getId();
    }

    private static List<String> fieldNames(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }

    private static String codePoints(int... characters) {
        return new String(characters, 0, characters.length);
    }
}
