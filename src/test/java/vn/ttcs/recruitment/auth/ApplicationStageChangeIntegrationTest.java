package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.CvValidator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 380 (story S5-03, "Kiểm tra trạng thái hiện tại, giai đoạn đích và quyền theo hồ sơ ứng tuyển"):
 * POST /api/v1/applications/{id}/stage-changes over real HTTP and PostgreSQL.
 * <p>
 * Fixture: two postings of two requisitions in two departments. The Java requisition has a PRIMARY (primary) and a
 * SUPPORTING (supporting) recruiter and its department is managed by itHead; the accounting requisition has no team.
 * Lan applied to Java, Minh to accounting. Tokens come from real session rows, so the seeded permissions decide.
 * Task 381 adds the rejection reasons (an active and an inactive REJECTION_REASON value, and a CANDIDATE_SOURCE value
 * that is no rejection reason) and the HR exception.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000", "app.application-notice.poll-delay-ms=3600000",
        "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationStageChangeIntegrationTest {
    private static final Instant NOW = ApplicationTestSupport.NOW;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private Environment environment;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationStorageService storage;
    @Autowired private CvValidator validator;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private TokenService tokens;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private ApplicationTestSupport applications;
    private int port;
    private UUID admin, hr, primary, supporting, otherRecruiter, itHead, interviewer, approver, noRole;
    private UUID javaPosting, javaRequisition, accountingRequisition;
    private UUID activeReason, inactiveReason, sourceValue;
    private ApplicationTestSupport.Stored lan, minh;

    @BeforeEach
    void fixture() {
        clock.set(NOW);
        port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();

        javaPosting = applications.publishedPosting("Lập trình viên Java");
        UUID accountingPosting = applications.publishedPosting("Kế toán tổng hợp");
        javaRequisition = applications.requisitionOf(javaPosting);
        accountingRequisition = applications.requisitionOf(accountingPosting);

        admin = applications.account("Quản trị viên", "admin@example.test", Role.ADMIN);
        hr = applications.account("Nguyễn Thị Hoa", "hr@example.test", Role.HR_MANAGER);
        primary = applications.account("Trần Văn Bình", "primary@example.test", Role.RECRUITER);
        supporting = applications.account("Lê Thị Cúc", "supporting@example.test", Role.RECRUITER);
        otherRecruiter = applications.account("Đỗ Văn Dũng", "other-recruiter@example.test", Role.RECRUITER);
        itHead = applications.account("Phạm Văn Cường", "it-head@example.test", Role.HIRING_MANAGER);
        interviewer = applications.account("Người phỏng vấn", "interviewer@example.test", Role.INTERVIEWER);
        approver = applications.account("Người duyệt", "approver@example.test", Role.APPROVER);
        noRole = applications.account("Không vai trò", "no-role@example.test");

        jdbc.update("UPDATE departments SET manager_user_id = ? WHERE id = ?", itHead, departmentOf(javaRequisition));
        applications.assign(javaRequisition, primary, "PRIMARY", hr);
        applications.assign(javaRequisition, supporting, "SUPPORTING", hr);

        lan = applications.stored(javaPosting, "lan@example.test");
        minh = applications.stored(accountingPosting, "minh@example.test");
        // Task 381: rejection reasons as HR declares them (V10 seeds none), and a value of another catalog.
        activeReason = catalogValue("REJECTION_REASON", "Chưa phù hợp kỹ năng", true);
        inactiveReason = catalogValue("REJECTION_REASON", "Lý do cũ", false);
        sourceValue = catalogValue("CANDIDATE_SOURCE", "LinkedIn", true);
    }

    // The forward path, one stage at a time; each answer is the history row it wrote, at the clock time of the change.
    @Test
    void thePrimaryRecruiterMovesAnApplicationForwardOneStageAtATime() throws Exception {
        String token = token(primary);
        List<ApplicationStage> path = List.of(ApplicationStage.NEW, ApplicationStage.SCREENING,
                ApplicationStage.INTERVIEW, ApplicationStage.OFFER, ApplicationStage.HIRED);
        for (int step = 1; step < path.size(); step++) {
            Instant at = NOW.plusSeconds(step * 60L).plusNanos(123_456_789);
            clock.set(at);
            ApplicationStage before = path.get(step - 1), after = path.get(step);
            JsonNode body = ok(change(lan.applicationId(), after.name(), before.name(), token));
            assertThat(body.path("applicationId").asString()).isEqualTo(lan.applicationId().toString());
            assertThat(body.path("previousStage").asString()).isEqualTo(before.name());
            assertThat(body.path("previousStageName").asString()).isEqualTo(before.displayName());
            assertThat(body.path("stage").asString()).isEqualTo(after.name());
            assertThat(body.path("stageName").asString()).isEqualTo(after.displayName());
            assertThat(Instant.parse(body.path("changedAt").asString())).isEqualTo(at.truncatedTo(
                    java.time.temporal.ChronoUnit.MICROS));
            assertThat(body.path("changedById").asString()).isEqualTo(primary.toString());
            assertThat(body.path("changedByName").asString()).isEqualTo("Trần Văn Bình");
            assertThat(stageOf(lan.applicationId())).isEqualTo(after.name());
        }
        var events = events(lan.applicationId());
        assertThat(events).hasSize(4);
        for (int step = 1; step < path.size(); step++) {
            var event = events.get(step - 1);
            assertThat(event.get("stage_before")).isEqualTo(path.get(step - 1).name());
            assertThat(event.get("stage_after")).isEqualTo(path.get(step).name());
            assertThat(event.get("actor_type")).isEqualTo("STAFF");
            assertThat(event.get("actor_id")).isEqualTo(primary);
            assertThat(event.get("reason")).isNull();
            assertThat(((Timestamp) event.get("occurred_at")).toInstant())
                    .isEqualTo(NOW.plusSeconds(step * 60L).plusNanos(123_456_000));
        }
    }

    // A SUPPORTING recruiter counts like the PRIMARY one; CANDIDATES_WRITE_ALL moves an application of any
    // requisition, with or without a team.
    @Test
    void theSupportingRecruiterAndTheAllScopeWritersMayMoveToo() throws Exception {
        ok(change(lan.applicationId(), "SCREENING", "NEW", token(supporting)));
        ok(change(minh.applicationId(), "SCREENING", "NEW", token(hr)));
        ok(change(minh.applicationId(), "INTERVIEW", "SCREENING", token(admin)));
        assertThat(stageOf(lan.applicationId())).isEqualTo("SCREENING");
        assertThat(stageOf(minh.applicationId())).isEqualTo("INTERVIEW");
        assertThat(events(minh.applicationId())).extracting(event -> event.get("actor_id")).containsExactly(hr, admin);
    }

    // A recruiter outside the requisition's team is refused, after the 404 check: the record exists.
    @Test
    void aRecruiterOutsideTheTeamIsRefusedAndNothingIsWritten() throws Exception {
        forbidden(change(lan.applicationId(), "SCREENING", "NEW", token(otherRecruiter)));
        forbidden(change(minh.applicationId(), "SCREENING", "NEW", token(primary)));
        assertUnchanged();
    }

    // Read-only roles never pass the matcher, whatever the id: the hiring manager of the department reads Lan's profile
    // but does not move her; nobody looks anything up.
    @Test
    void callersWithoutACandidatesWritePermissionAreRefusedForEveryId() throws Exception {
        for (UUID caller : List.of(itHead, interviewer, approver, noRole)) {
            String token = token(caller);
            forbidden(change(lan.applicationId(), "SCREENING", "NEW", token));
            forbidden(change(UUID.randomUUID(), "SCREENING", "NEW", token));
        }
        assertUnchanged();
    }

    @Test
    void anAnonymousCallerGets401() throws Exception {
        var response = change(lan.applicationId(), "SCREENING", "NEW", null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("UNAUTHORIZED");
        assertUnchanged();
    }

    // 404 comes before the scope, so every writer gets the same answer for an id that does not exist.
    @Test
    void anUnknownApplicationIs404ForEveryWriter() throws Exception {
        for (UUID caller : List.of(hr, primary, otherRecruiter)) {
            var response = change(UUID.randomUUID(), "SCREENING", "NEW", token(caller));
            assertThat(response.statusCode()).as(response.body()).isEqualTo(404);
            assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("APPLICATION_NOT_FOUND");
            assertNoStore(response);
        }
    }

    // The page showed NEW, but the application is already in SCREENING: refused as stale, whatever the target.
    @Test
    void aChangeMadeOnAnOutdatedViewIsRefusedWithoutWriting() throws Exception {
        String token = token(primary);
        ok(change(lan.applicationId(), "SCREENING", "NEW", token));
        for (String target : List.of("SCREENING", "INTERVIEW")) {
            var response = change(lan.applicationId(), target, "NEW", token);
            conflict(response, "APPLICATION_STAGE_CHANGED");
        }
        assertThat(stageOf(lan.applicationId())).isEqualTo("SCREENING");
        assertThat(events(lan.applicationId())).hasSize(1);
    }

    // Skipping a stage, going back, staying, withdrawing for the candidate, or leaving a final stage (rejecting it too,
    // with a valid reason): 409, nothing written. ApplicationStageTransitionsTest holds the whole matrix.
    @Test
    void movesOutsideTheForwardPathAreRefused() throws Exception {
        String token = token(hr);
        for (String target : List.of("INTERVIEW", "OFFER", "HIRED", "NEW", "WITHDRAWN")) {
            conflict(change(lan.applicationId(), target, "NEW", token), "APPLICATION_STAGE_TRANSITION_NOT_ALLOWED");
        }
        // Even a confirming HR_MANAGER skips only the interview, never the screening too.
        conflict(post(lan.applicationId(), body("OFFER", "NEW", null, true), token),
                "APPLICATION_STAGE_TRANSITION_NOT_ALLOWED");
        for (ApplicationStage end : List.of(ApplicationStage.HIRED, ApplicationStage.REJECTED,
                ApplicationStage.WITHDRAWN)) {
            applications.stage(minh.applicationId(), end);
            for (ApplicationStage target : ApplicationStage.values()) {
                var response = target == ApplicationStage.REJECTED
                        ? post(minh.applicationId(), body("REJECTED", end.name(), activeReason, null), token)
                        : change(minh.applicationId(), target.name(), end.name(), token);
                conflict(response, "APPLICATION_STAGE_TRANSITION_NOT_ALLOWED");
            }
        }
        applications.stage(minh.applicationId(), ApplicationStage.INTERVIEW);
        conflict(change(minh.applicationId(), "SCREENING", "INTERVIEW", token),
                "APPLICATION_STAGE_TRANSITION_NOT_ALLOWED");
        assertThat(stageOf(lan.applicationId())).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events", Integer.class)).isZero();
    }

    // A write never implies a read (V3): a recruiter whose role lost CANDIDATES_READ_SCOPED is refused, although it is
    // still assigned and still holds the write code. The next request sees the grant again.
    @Test
    void aWriterWithoutACandidatesReadPermissionIsRefused() throws Exception {
        String token = token(primary);
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'RECRUITER' AND permission_code = 'CANDIDATES_READ_SCOPED'");
        try {
            forbidden(change(lan.applicationId(), "SCREENING", "NEW", token));
            assertUnchanged();
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('RECRUITER', 'CANDIDATES_READ_SCOPED')");
        }
        ok(change(lan.applicationId(), "SCREENING", "NEW", token));
    }

    // After a handover only the new team writes: the assignment history never counts.
    @Test
    void aRecruiterTakenOffTheTeamIsRefusedAtTheNextRequest() throws Exception {
        String token = token(supporting);
        ok(change(lan.applicationId(), "SCREENING", "NEW", token));
        jdbc.update("DELETE FROM requisition_recruiters WHERE requisition_id = ? AND recruiter_id = ?", javaRequisition,
                supporting);
        forbidden(change(lan.applicationId(), "INTERVIEW", "SCREENING", token));
        assertThat(stageOf(lan.applicationId())).isEqualTo("SCREENING");
    }

    // The body is checked before any lookup: a missing or unknown field, a stage name that does not exist (the
    // frontend's old INTERVIEWING) or in lower case is 400, for an existing and an unknown id alike.
    @Test
    void anInvalidBodyIs400BeforeAnyLookup() throws Exception {
        String token = token(primary);
        List<String> bodies = List.of("{}", "{\"targetStage\":\"SCREENING\"}", "{\"expectedCurrentStage\":\"NEW\"}",
                "{\"targetStage\":\"INTERVIEWING\",\"expectedCurrentStage\":\"NEW\"}",
                "{\"targetStage\":\"screening\",\"expectedCurrentStage\":\"NEW\"}",
                "{\"targetStage\":\"SCREENING\",\"expectedCurrentStage\":\"NEW\",\"actorId\":\"" + hr + "\"}",
                "not json");
        for (UUID id : List.of(lan.applicationId(), UUID.randomUUID())) {
            for (String body : bodies) {
                var response = post(id, body, token);
                assertThat(response.statusCode()).as(body + ": " + response.body()).isEqualTo(400);
                assertThat(json.readTree(response.body()).path("code").asString())
                        .isIn("VALIDATION_ERROR", "INVALID_JSON");
            }
        }
        var response = post(lan.applicationId(), "{}", token);
        assertThat(json.readTree(response.body()).path("fieldErrors").path("targetStage").asString())
                .isEqualTo("Vui lòng chọn giai đoạn mới.");
        assertThat(json.readTree(response.body()).path("fieldErrors").path("expectedCurrentStage").asString())
                .isEqualTo("Thiếu giai đoạn hiện tại của hồ sơ đang xem.");
        assertUnchanged();
    }

    // The requisition's status plays no part: candidates left in the pipeline of a filled requisition move on.
    @Test
    void candidatesOfAFilledRequisitionStillMoveOn() throws Exception {
        jdbc.update("UPDATE recruitment_requisitions SET status = 'FILLED' WHERE id = ?", javaRequisition);
        ok(change(lan.applicationId(), "SCREENING", "NEW", token(primary)));
        assertThat(stageOf(lan.applicationId())).isEqualTo("SCREENING");
    }

    // Task 381: a rejection from every active stage, by the assigned recruiter, stores the reason's id and its name as
    // it was; renaming the value later does not change the history, and the value cannot be deleted while a rejection
    // names it (RESTRICT, so HR gets the catalog's 409).
    @Test
    void aRejectionFromEveryActiveStageStoresItsReason() throws Exception {
        String token = token(primary);
        for (ApplicationStage from : List.of(ApplicationStage.NEW, ApplicationStage.SCREENING,
                ApplicationStage.INTERVIEW, ApplicationStage.OFFER)) {
            var stored = applications.stored(javaPosting, from.name().toLowerCase(java.util.Locale.ROOT) + "@example.test");
            applications.stage(stored.applicationId(), from);
            JsonNode body = ok(post(stored.applicationId(), body("REJECTED", from.name(), activeReason, null), token));
            assertThat(body.path("stage").asString()).isEqualTo("REJECTED");
            assertThat(body.path("stageName").asString()).isEqualTo("Loại");
            assertThat(body.path("rejectionReasonId").asString()).isEqualTo(activeReason.toString());
            assertThat(body.path("rejectionReasonName").asString()).isEqualTo("Chưa phù hợp kỹ năng");
            assertThat(body.path("interviewSkipped").asBoolean()).isFalse();
            var event = events(stored.applicationId()).getFirst();
            assertThat(event.get("stage_before")).isEqualTo(from.name());
            assertThat(event.get("rejection_reason_id")).isEqualTo(activeReason);
            assertThat(event.get("rejection_reason_name")).isEqualTo("Chưa phù hợp kỹ năng");
            assertThat(event.get("interview_skipped")).isEqualTo(false);
        }
        jdbc.update("UPDATE recruitment_catalog_items SET name = 'Tên mới' WHERE id = ?", activeReason);
        assertThat(jdbc.queryForList("SELECT DISTINCT rejection_reason_name FROM application_stage_events "
                + "WHERE rejection_reason_id = ?", String.class, activeReason)).containsExactly("Chưa phù hợp kỹ năng");
        var deletion = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/recruitment-catalogs/REJECTION_REASON/items/" + activeReason))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token(hr)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(deletion.statusCode()).as(deletion.body()).isEqualTo(409);
        assertThat(json.readTree(deletion.body()).path("code").asString()).isEqualTo("RECRUITMENT_CATALOG_ITEM_IN_USE");
        // Any other change names no reason.
        ok(change(lan.applicationId(), "SCREENING", "NEW", token));
        assertThat(events(lan.applicationId()).getFirst().get("rejection_reason_id")).isNull();
    }

    // Task 381: the reason is required, belongs to rejections only, must exist in the REJECTION_REASON catalog (a value
    // of another catalog does not count) and must still be offered. Each refusal names the field and writes nothing.
    @Test
    void aRejectionNeedsAnActiveValueOfTheRejectionReasonCatalog() throws Exception {
        String token = token(primary);
        rejected(post(lan.applicationId(), body("REJECTED", "NEW", null, null), token), "REJECTION_REASON_REQUIRED",
                "Vui lòng chọn lý do loại.");
        rejected(post(lan.applicationId(), body("SCREENING", "NEW", activeReason, null), token),
                "REJECTION_REASON_NOT_ALLOWED", "Chỉ chọn lý do loại khi chuyển hồ sơ sang Loại.");
        rejected(post(lan.applicationId(), body("REJECTED", "NEW", UUID.randomUUID(), null), token),
                "INVALID_REJECTION_REASON", "Lý do loại không tồn tại.");
        rejected(post(lan.applicationId(), body("REJECTED", "NEW", sourceValue, null), token),
                "INVALID_REJECTION_REASON", "Lý do loại không tồn tại.");
        rejected(post(lan.applicationId(), body("REJECTED", "NEW", inactiveReason, null), token),
                "REJECTION_REASON_INACTIVE", "Lý do loại đã ngừng sử dụng, vui lòng chọn lý do khác.");
        var malformed = post(lan.applicationId(),
                "{\"targetStage\":\"REJECTED\",\"expectedCurrentStage\":\"NEW\",\"rejectionReasonId\":\"abc\"}", token);
        assertThat(malformed.statusCode()).as(malformed.body()).isEqualTo(400);
        assertThat(json.readTree(malformed.body()).path("code").asString()).isEqualTo("INVALID_JSON");
        // The scope still comes first for a caller outside it, whatever the reason.
        forbidden(post(lan.applicationId(), body("REJECTED", "NEW", inactiveReason, null), token(otherRecruiter)));
        assertUnchanged();
    }

    // Task 381, the story's exception: SCREENING straight to OFFER only for an HR_MANAGER (the role, not ADMIN or a
    // write permission) who confirms with the JSON value true. The row says so; no other move does.
    @Test
    void onlyAConfirmingHrManagerSkipsTheInterview() throws Exception {
        applications.stage(lan.applicationId(), ApplicationStage.SCREENING);
        conflict(post(lan.applicationId(), body("OFFER", "SCREENING", null, true), token(primary)),
                "APPLICATION_INTERVIEW_REQUIRED");
        conflict(post(lan.applicationId(), body("OFFER", "SCREENING", null, true), token(admin)),
                "APPLICATION_INTERVIEW_REQUIRED");
        String hrToken = token(hr);
        conflict(post(lan.applicationId(), body("OFFER", "SCREENING", null, null), hrToken),
                "APPLICATION_INTERVIEW_SKIP_UNCONFIRMED");
        conflict(post(lan.applicationId(), body("OFFER", "SCREENING", null, false), hrToken),
                "APPLICATION_INTERVIEW_SKIP_UNCONFIRMED");
        for (String confirm : List.of("\"true\"", "1")) {
            var response = post(lan.applicationId(), "{\"targetStage\":\"OFFER\",\"expectedCurrentStage\":\"SCREENING\","
                    + "\"confirmSkipInterview\":" + confirm + "}", hrToken);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
            assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("INVALID_JSON");
        }
        assertThat(events(lan.applicationId())).isEmpty();

        JsonNode skipped = ok(post(lan.applicationId(), body("OFFER", "SCREENING", null, true), hrToken));
        assertThat(skipped.path("stage").asString()).isEqualTo("OFFER");
        assertThat(skipped.path("interviewSkipped").asBoolean()).isTrue();
        assertThat(skipped.has("rejectionReasonId")).isFalse();
        var event = events(lan.applicationId()).getFirst();
        assertThat(event.get("stage_before")).isEqualTo("SCREENING");
        assertThat(event.get("actor_id")).isEqualTo(hr);
        assertThat(event.get("interview_skipped")).isEqualTo(true);

        // The confirmation is ignored when nothing is skipped: INTERVIEW → OFFER by the recruiter is no exception.
        applications.stage(minh.applicationId(), ApplicationStage.INTERVIEW);
        JsonNode normal = ok(post(minh.applicationId(), body("OFFER", "INTERVIEW", null, true), hrToken));
        assertThat(normal.path("interviewSkipped").asBoolean()).isFalse();
    }

    // Task 381: the HR exception needs the role and the normal record access. An HR_MANAGER whose role was narrowed to
    // the SCOPED grants, and who is on no team, is refused before the exception is even considered.
    @Test
    void theHrExceptionStillNeedsAccessToTheApplication() throws Exception {
        applications.stage(lan.applicationId(), ApplicationStage.SCREENING);
        String hrToken = token(hr);
        jdbc.update("UPDATE role_permissions SET permission_code = 'CANDIDATES_WRITE_SCOPED' "
                + "WHERE role_code = 'HR_MANAGER' AND permission_code = 'CANDIDATES_WRITE_ALL'");
        try {
            forbidden(post(lan.applicationId(), body("OFFER", "SCREENING", null, true), hrToken));
        } finally {
            jdbc.update("UPDATE role_permissions SET permission_code = 'CANDIDATES_WRITE_ALL' "
                    + "WHERE role_code = 'HR_MANAGER' AND permission_code = 'CANDIDATES_WRITE_SCOPED'");
        }
        assertThat(stageOf(lan.applicationId())).isEqualTo("SCREENING");
        assertThat(events(lan.applicationId())).isEmpty();
    }

    private HttpResponse<String> change(UUID application, String target, String expected, String token)
            throws Exception {
        return post(application, body(target, expected, null, null), token);
    }

    // The body with the given fields; a null reason or confirmation is left out.
    private String body(String target, String expected, UUID reason, Boolean confirmSkipInterview) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetStage", target);
        body.put("expectedCurrentStage", expected);
        if (reason != null) body.put("rejectionReasonId", reason);
        if (confirmSkipInterview != null) body.put("confirmSkipInterview", confirmSkipInterview);
        return json.writeValueAsString(body);
    }

    private void rejected(HttpResponse<String> response, String code, String message) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        JsonNode body = json.readTree(response.body());
        assertThat(body.path("code").asString()).isEqualTo(code);
        assertThat(body.path("fieldErrors").path("rejectionReasonId").asString()).isEqualTo(message);
        assertNoStore(response);
    }

    private UUID catalogValue(String type, String name, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO recruitment_catalog_items(id,catalog_type,code,name,sort_order,active,created_at,"
                + "updated_at) VALUES (?,?,?,?,0,?,?,?)", id, type, id.toString(), name, active, Timestamp.from(NOW),
                Timestamp.from(NOW));
        return id;
    }

    private HttpResponse<String> post(UUID application, String body, String token) throws Exception {
        return applications.post("/api/v1/applications/" + application + "/stage-changes", body, "application/json",
                token == null ? Map.of() : Map.of("Authorization", "Bearer " + token));
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertNoStore(response);
        return json.readTree(response.body());
    }

    private void forbidden(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo("FORBIDDEN");
        assertNoStore(response);
    }

    private void conflict(HttpResponse<String> response, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat(json.readTree(response.body()).path("code").asString()).isEqualTo(code);
        assertNoStore(response);
    }

    private static void assertNoStore(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    // Neither application moved and no history row exists.
    private void assertUnchanged() {
        assertThat(stageOf(lan.applicationId())).isEqualTo("NEW");
        assertThat(stageOf(minh.applicationId())).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events", Integer.class)).isZero();
    }

    private String stageOf(UUID application) {
        return jdbc.queryForObject("SELECT stage FROM job_applications WHERE id = ?", String.class, application);
    }

    private List<Map<String, Object>> events(UUID application) {
        return jdbc.queryForList("""
                SELECT stage_before, stage_after, actor_type, actor_id, reason, occurred_at, rejection_reason_id,
                       rejection_reason_name, interview_skipped
                FROM application_stage_events WHERE application_id = ? ORDER BY occurred_at
                """, application);
    }

    private UUID departmentOf(UUID requisition) {
        return jdbc.queryForObject("SELECT department_id FROM recruitment_requisitions WHERE id = ?", UUID.class,
                requisition);
    }

    private String token(UUID account) {
        return applications.token(tokens, account);
    }
}
