package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.jobposting.JobPostingApplicationGate;
import vn.ttcs.recruitment.jobposting.JobPostingStatus;
import vn.ttcs.recruitment.requisition.RequisitionRecruiterRole;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.EMPLOYMENT_TYPE;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.WORK_LOCATION;

// Task 355 (story S4-08): "Xây dựng API gỡ tin tuyển dụng ngay lập tức". The recruiter of the requisition or HR takes a
// published posting down at any time, with a reason: it moves to UNPUBLISHED, the step is added to its history, and
// the public list, the detail, the filter values and the application gate (task 321) stop showing it at once. A
// posting taken down is final: it is never published again, and only the closure of its requisition (task 292) still
// moves it, to CLOSED.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobPostingUnpublishIntegrationTest {
    // 10:00 on 11 Oct 2026 in Vietnam. Every posting made here may be applied to until 30 Nov 2026.
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");
    private static final String REASON = "Đã nhận đủ hồ sơ, gỡ tin.";
    private static final String REASON_REQUIRED = "Vui lòng nhập lý do gỡ tin tuyển dụng.";
    private static final String CLOSED_WITH_REQUISITION = "Tin tuyển dụng đã đóng cùng yêu cầu tuyển dụng nên không "
            + "thể sửa, gửi duyệt, duyệt, xuất bản hay gỡ nữa.";
    private static final String ALREADY_UNPUBLISHED = "Tin tuyển dụng đã được gỡ khỏi trang tuyển dụng.";
    private static final String NOT_PUBLISHED = "Chỉ gỡ được tin tuyển dụng đã xuất bản.";
    private static final List<String> FLAGS = List.of("canEdit", "canSubmit", "canApprove", "canRequestRevision",
            "canPublish", "canUnpublish");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JobPostingApplicationGate gate;

    private RequisitionStatusTestSupport status;
    private JobPostingTestSupport postings;
    private ApprovalTestSupport api;
    private String passwordHash;
    private UUID adminId;
    private String adminToken;
    private UUID hrId;
    private String hrToken;
    private UUID recruiterId;
    private String recruiterToken;
    private UUID headId;
    private UUID department;
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
        headId = account("head@example.test", "Trưởng phòng IT", Role.HIRING_MANAGER);
        recruiterId = account("recruiter@example.test", "Trần Văn Tuyển", Role.RECRUITER);
        recruiterToken = api.token("recruiter@example.test");
        department = api.department("IT", headId, null, START);
        position = api.position(START);
        workLocation = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, START);
        employmentType = postings.catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", true, START);
    }

    // S4-08 AC1: "gỡ tin bất cứ lúc nào, tin biến mất khỏi trang tuyển dụng công khai ngay". HR published both
    // postings; the recruiter of the requisition, HR or ADMIN takes one down. Nothing runs later: the very next public
    // request no longer finds it.
    @ParameterizedTest
    @ValueSource(strings = {"recruiter", "hr", "admin"})
    void aPublishedPostingIsTakenDownAndLeavesThePortalAtOnce(String who) throws Exception {
        UUID solo = api.department("SOLO", headId, null, START);
        UUID requisition = requisition(solo);
        UUID posting = publishedPosting(requisition);
        UUID other = publishedPosting(requisition(department));
        assertThat(publicIds(ok(postings.publicList(0, 20)))).containsExactlyInAnyOrder(posting, other);
        assertThat(filterDepartments()).containsExactlyInAnyOrder(solo, department);
        assertThat(apply(posting)).isNull();
        Map<String, Object> rowBefore = postings.row(posting);
        JsonNode before = ok(postings.get(posting, hrToken));
        assertThat(before.path("actions").path("canUnpublish").asBoolean()).isTrue();
        String token = switch (who) {
            case "recruiter" -> recruiterToken;
            case "hr" -> hrToken;
            default -> adminToken;
        };
        UUID actor = switch (who) {
            case "recruiter" -> recruiterId;
            case "hr" -> hrId;
            default -> adminId;
        };
        clock.set(START.plusSeconds(90).plusNanos(123_456_789));
        // A reason with a visible character is kept exactly as sent, the invisible characters around it included.
        String reason = codePoints(0x00A0) + "Đã nhận đủ hồ sơ.\nGỡ tin sớm." + codePoints(0x200B);

        var response = postings.unpublish(posting, 4, reason, token);

        postings.noStore(response);
        JsonNode view = ok(response);
        assertThat(view.path("status").asText()).isEqualTo("UNPUBLISHED");
        assertThat(view.path("revision").asInt()).isEqualTo(5);
        for (String kept : List.of("updatedAt", "publishedById", "publishedByName", "publishedAt", "title",
                "applicationDeadline", "lastReview")) {
            assertThat(view.path(kept)).as(kept).isEqualTo(before.path(kept));
        }
        assertThat(view.path("publishedById").asText()).isEqualTo(hrId.toString());
        assertNoAction(view);
        assertNoAction(ok(postings.get(posting, hrToken)));
        // The row: one more revision; the content, updated_at and the publication trail are kept.
        Map<String, Object> row = postings.row(posting);
        assertThat(row.get("status")).isEqualTo("UNPUBLISHED");
        assertThat(row.get("revision")).isEqualTo(5);
        assertThat(withoutStatus(row)).isEqualTo(withoutStatus(rowBefore));
        // One UNPUBLISHED step with the caller, the server time and the reason.
        Map<String, Object> step = postings.events(posting).getLast();
        assertThat(postings.events(posting)).hasSize(4);
        assertThat(step).containsEntry("revision", 5).containsEntry("event_type", "UNPUBLISHED")
                .containsEntry("status_before", "PUBLISHED").containsEntry("status_after", "UNPUBLISHED")
                .containsEntry("actor_id", actor).containsEntry("comment", reason);
        assertThat(((Timestamp) step.get("occurred_at")).toInstant())
                .isEqualTo(Instant.parse("2026-10-11T03:01:30.123456Z"));
        JsonNode history = ok(postings.history(posting, hrToken));
        assertThat(history.size()).isEqualTo(4);
        JsonNode top = history.path(0);
        assertThat(top.path("revision").asInt()).isEqualTo(5);
        assertThat(top.path("action").asText()).isEqualTo("UNPUBLISHED");
        assertThat(top.path("statusBefore").asText()).isEqualTo("PUBLISHED");
        assertThat(top.path("statusAfter").asText()).isEqualTo("UNPUBLISHED");
        assertThat(top.path("actorId").asText()).isEqualTo(actor.toString());
        assertThat(top.path("occurredAt").asText()).isEqualTo("2026-10-11T03:01:30.123456Z");
        assertThat(top.path("comment").asText()).isEqualTo(reason);
        assertThat(history.path(1).path("action").asText()).isEqualTo("PUBLISHED");
        // Taking down makes nobody an author.
        assertThat(postings.authorsOf(posting)).containsExactly(hrId);

        // The portal: the list and its count drop it, its detail answers exactly like an id that does not exist, the
        // filter values drop the department only it used, and an application is refused the same way.
        var list = postings.publicList(0, 20);
        postings.noStore(list);
        JsonNode page = ok(list);
        assertThat(publicIds(page)).containsExactly(other);
        assertThat(page.path("totalElements").asLong()).isOne();
        var unknown = postings.publicDetail(UUID.randomUUID());
        var detail = postings.publicDetail(posting);
        postings.error(detail, 404, "JOB_POSTING_NOT_FOUND");
        assertThat(detail.body()).isEqualTo(unknown.body());
        assertThat(filterDepartments()).containsExactly(department);
        assertThat(apply(posting)).isInstanceOfSatisfying(ApiException.class, refusal -> {
            assertThat(refusal.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(refusal.getCode()).isEqualTo("JOB_POSTING_NOT_FOUND");
        });
        assertThat(apply(other)).isNull();
        // Its authors and HR still preview it and find it by its status.
        assertThat(ok(postings.preview(posting, recruiterToken)).path("status").asText()).isEqualTo("UNPUBLISHED");
        assertThat(publicIds(ok(postings.list("?status=UNPUBLISHED", hrToken)))).containsExactly(posting);
        // The requisition is never written.
        assertThat(status.events(requisition)).isEmpty();
        assertThat(status.row(requisition).get("status")).isEqualTo("APPROVED");
    }

    // "Bất cứ lúc nào": the requisition's status and the deadline are not checked. A paused requisition's posting is
    // taken down and stays down once the requisition is resumed; a posting whose deadline has passed (its detail shows
    // the closed notice, task 320) is taken down too, and its detail then answers 404.
    @Test
    void aPostingIsTakenDownWhileItsRequisitionIsPausedOrAfterItsDeadline() throws Exception {
        UUID paused = requisition(department);
        UUID hidden = publishedPosting(paused);
        status.ok(status.change(paused, "PAUSED", "Chờ ngân sách quý 4", 0, hrToken));
        JsonNode frozen = ok(postings.get(hidden, recruiterToken));
        assertThat(frozen.path("actions").path("canUnpublish").asBoolean()).isTrue();
        assertThat(frozen.path("actions").path("canEdit").asBoolean()).isFalse();

        assertThat(ok(postings.unpublish(hidden, 4, REASON, recruiterToken)).path("status").asText())
                .isEqualTo("UNPUBLISHED");
        status.ok(status.change(paused, "APPROVED", null, 1, hrToken));
        postings.error(postings.publicDetail(hidden), 404, "JOB_POSTING_NOT_FOUND");
        assertThat(ok(postings.publicList(0, 20)).path("totalElements").asLong()).isZero();
        assertThat(postings.row(hidden).get("status")).isEqualTo("UNPUBLISHED");

        UUID expired = publishedPosting(requisition(department));
        // 10:00 on 1 Dec 2026 in Vietnam: the deadline (30 Nov) has passed; the tokens of October have expired.
        clock.set(Instant.parse("2026-12-01T03:00:00Z"));
        String lateRecruiter = api.token("recruiter@example.test");
        JobPostingTestSupport.assertApplicationState(ok(postings.publicDetail(expired)), false);
        assertThat(ok(postings.get(expired, lateRecruiter)).path("actions").path("canUnpublish").asBoolean())
                .isTrue();

        assertThat(ok(postings.unpublish(expired, 4, REASON, lateRecruiter)).path("status").asText())
                .isEqualTo("UNPUBLISHED");
        postings.error(postings.publicDetail(expired), 404, "JOB_POSTING_NOT_FOUND");
    }

    // The body: expectedRevision is required, the reason is required and limited like a requisition's reason, and
    // nothing else is accepted. A missing reason is answered before any lock or lookup, so an unknown posting gets the
    // same 400.
    @Test
    void invalidBodiesAreRefusedAndChangeNothing() throws Exception {
        UUID posting = publishedPosting(requisition(department));
        List<Map<String, Object>> rowsBefore = jdbc.queryForList("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1");

        for (UUID id : List.of(posting, UUID.randomUUID())) {
            for (String reason : Arrays.asList(null, "", "  \n\t ", codePoints(0x00A0), codePoints(0x0085),
                    codePoints(0x200B, 0xFEFF), codePoints(0x2007, 0x202F, '\n', 0x180E, 0x2060, '\t'))) {
                postings.businessError(postings.unpublish(id, 4, reason, hrToken),
                        "JOB_POSTING_UNPUBLISH_REASON_REQUIRED", "reason", REASON_REQUIRED);
            }
        }
        JsonNode tooLong = postings.fieldErrors(postings.unpublish(posting, 4, "a".repeat(2_001), hrToken), "reason");
        assertThat(tooLong.path("reason").asText()).isEqualTo("Lý do tối đa 2.000 ký tự.");
        JsonNode nul = postings.fieldErrors(postings.unpublish(posting, 4, "Gỡ tin" + codePoints(0), hrToken),
                "reason");
        assertThat(nul.path("reason").asText()).isEqualTo("Lý do chứa ký tự không hợp lệ.");
        JsonNode noRevision = postings.fieldErrors(postings.unpublish(posting, null, REASON, hrToken),
                "expectedRevision");
        assertThat(noRevision.path("expectedRevision").asText())
                .isEqualTo("Thiếu số phiên bản tin tuyển dụng đang xem.");
        JsonNode zero = postings.fieldErrors(postings.unpublish(posting, 0, REASON, hrToken), "expectedRevision");
        assertThat(zero.path("expectedRevision").asText()).isEqualTo("Số phiên bản tin tuyển dụng không hợp lệ.");
        // Who takes the posting down and when, and its status, are the server's.
        Map<String, Object> clientOwned = new LinkedHashMap<>();
        clientOwned.put("status", "UNPUBLISHED");
        clientOwned.put("comment", "Gỡ tin");
        clientOwned.put("unpublishedAt", "2026-10-11T03:00:00Z");
        clientOwned.put("actorId", hrId.toString());
        for (var field : clientOwned.entrySet()) {
            Map<String, Object> body = new LinkedHashMap<>(Map.of("expectedRevision", 4, "reason", REASON));
            body.put(field.getKey(), field.getValue());
            postings.error(api.request("POST", JobPostingTestSupport.POSTINGS + "/" + posting + "/unpublish",
                    json.writeValueAsString(body), hrToken), 400, "INVALID_JSON");
        }
        postings.error(api.request("POST", JobPostingTestSupport.POSTINGS + "/" + posting + "/unpublish", null,
                hrToken), 400, "INVALID_JSON");

        assertThat(jdbc.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1")).isEqualTo(eventsBefore);
        // 2,000 characters are allowed.
        String longest = "a".repeat(1_999) + "!";
        assertThat(ok(postings.unpublish(posting, 4, longest, hrToken)).path("status").asText())
                .isEqualTo("UNPUBLISHED");
        assertThat(postings.events(posting).getLast().get("comment")).isEqualTo(longest);
    }

    // Who may: JOB_POSTINGS_WRITE_ALL (HR, ADMIN) for every posting, JOB_POSTINGS_WRITE_SCOPED (RECRUITER) only for the
    // postings of the requisitions assigned to it; the other roles are refused by the matcher. An unknown posting is
    // 404 before the scope, for every composer.
    @Test
    void onlyComposersInScopeTakeAPostingDown() throws Exception {
        UUID posting = publishedPosting(requisition(department));
        account("outsider@example.test", "Người ngoài", Role.RECRUITER);
        account("approver@example.test", "Người duyệt", Role.APPROVER);
        account("interviewer@example.test", "Người phỏng vấn", Role.INTERVIEWER);
        accounts.saveAndFlush(new Account("norole@example.test", "Không vai trò", passwordHash, Set.of(), START));
        List<Map<String, Object>> rowsBefore = jdbc.queryForList("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1");

        postings.error(postings.unpublish(posting, 4, REASON, null), 401, "UNAUTHORIZED");
        String outsider = api.token("outsider@example.test");
        postings.forbidden(postings.unpublish(posting, 4, REASON, outsider));
        for (String email : List.of("head@example.test", "approver@example.test", "interviewer@example.test",
                "norole@example.test")) {
            postings.forbidden(postings.unpublish(posting, 4, REASON, api.token(email)));
        }
        for (String token : List.of(outsider, recruiterToken, hrToken)) {
            postings.error(postings.unpublish(UUID.randomUUID(), 4, REASON, token), 404, "JOB_POSTING_NOT_FOUND");
        }

        assertThat(jdbc.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1")).isEqualTo(eventsBefore);
        assertThat(ok(postings.publicDetail(posting)).path("id").asText()).isEqualTo(posting.toString());
    }

    // Only a published posting is taken down. A closed one is told so first (the message names taking down), one
    // taken down already is told so, and one never published is told it is not published.
    @ParameterizedTest
    @EnumSource(JobPostingStatus.class)
    void onlyAPublishedPostingIsTakenDown(JobPostingStatus postingStatus) throws Exception {
        UUID posting = postingIn(postingStatus);
        Map<String, Object> rowBefore = postings.row(posting);
        List<Map<String, Object>> eventsBefore = postings.events(posting);

        var response = postings.unpublish(posting, postings.revision(posting), REASON, hrToken);

        String refusal = switch (postingStatus) {
            case PUBLISHED -> null;
            case UNPUBLISHED -> "JOB_POSTING_ALREADY_UNPUBLISHED";
            case CLOSED -> "JOB_POSTING_CLOSED";
            case DRAFT, PENDING_APPROVAL, REVISION_REQUESTED, APPROVED -> "JOB_POSTING_NOT_PUBLISHED";
        };
        if (refusal == null) {
            assertThat(ok(response).path("status").asText()).isEqualTo("UNPUBLISHED");
            assertThat(postings.events(posting)).hasSize(eventsBefore.size() + 1);
            return;
        }
        JsonNode error = postings.error(response, 409, refusal);
        assertThat(error.path("message").asText()).isEqualTo(switch (postingStatus) {
            case UNPUBLISHED -> ALREADY_UNPUBLISHED;
            case CLOSED -> CLOSED_WITH_REQUISITION;
            default -> NOT_PUBLISHED;
        });
        assertThat(postings.row(posting)).isEqualTo(rowBefore);
        assertThat(postings.events(posting)).isEqualTo(eventsBefore);
        // The flag agrees with the answer.
        assertThat(ok(postings.get(posting, hrToken)).path("actions").path("canUnpublish").asBoolean()).isFalse();
    }

    // A take-down on an outdated view (a second tab) is refused like every posting step.
    @Test
    void aStaleRevisionIsRefused() throws Exception {
        UUID posting = publishedPosting(requisition(department));
        Map<String, Object> rowBefore = postings.row(posting);

        for (int stale : List.of(3, 5)) {
            postings.error(postings.unpublish(posting, stale, REASON, hrToken), 409, "JOB_POSTING_CHANGED");
        }

        assertThat(postings.row(posting)).isEqualTo(rowBefore);
        assertThat(postings.events(posting)).hasSize(3);
    }

    // Taken down is final: it is never published again (a posting is published once), never edited, sent or reviewed,
    // and never taken down twice. Closing its requisition (task 292) still closes it, from UNPUBLISHED.
    @Test
    void aPostingTakenDownStaysDownUntilItsRequisitionClosesIt() throws Exception {
        UUID requisition = requisition(department);
        UUID posting = publishedPosting(requisition);
        JsonNode down = ok(postings.unpublish(posting, 4, REASON, recruiterToken));
        int revision = down.path("revision").asInt();
        List<Map<String, Object>> rowsBefore = jdbc.queryForList("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1");

        JsonNode again = postings.error(postings.publish(posting, revision, hrToken), 409,
                "JOB_POSTING_ALREADY_PUBLISHED");
        assertThat(again.path("message").asText()).isEqualTo("Tin tuyển dụng đã được xuất bản.");
        Map<String, Object> change = postings.putBody(down);
        change.put("benefits", "Bảo hiểm đầy đủ.");
        postings.error(postings.update(posting, change, hrToken), 409, "JOB_POSTING_NOT_EDITABLE");
        postings.error(postings.submit(posting, revision, null, hrToken), 409, "JOB_POSTING_NOT_SUBMITTABLE");
        postings.error(postings.decide(posting, "APPROVE", null, revision, adminToken), 409,
                "JOB_POSTING_NOT_UNDER_REVIEW");
        postings.error(postings.decide(posting, "REQUEST_REVISION", "Sửa lại", revision, adminToken), 409,
                "JOB_POSTING_NOT_UNDER_REVIEW");
        JsonNode twice = postings.error(postings.unpublish(posting, revision, REASON, hrToken), 409,
                "JOB_POSTING_ALREADY_UNPUBLISHED");
        assertThat(twice.path("message").asText()).isEqualTo(ALREADY_UNPUBLISHED);
        assertThat(jdbc.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1")).isEqualTo(eventsBefore);

        clock.set(START.plusSeconds(300));
        status.ok(status.change(requisition, "FILLED", "Đã tuyển đủ", 0, hrToken));

        Map<String, Object> closed = postings.row(posting);
        assertThat(closed.get("status")).isEqualTo("CLOSED");
        assertThat(closed.get("revision")).isEqualTo(revision + 1);
        assertThat(closed.get("published_by")).isEqualTo(hrId);
        assertThat(postings.events(posting).getLast()).containsEntry("event_type", "CLOSED")
                .containsEntry("status_before", "UNPUBLISHED").containsEntry("status_after", "CLOSED")
                .containsEntry("comment", "Đã tuyển đủ");
        assertThat(postings.events(posting)).extracting(event -> event.get("event_type"))
                .containsExactly("SUBMITTED", "APPROVED", "PUBLISHED", "UNPUBLISHED", "CLOSED");
        JsonNode closedAgain = postings.error(postings.unpublish(posting, revision + 1, REASON, hrToken), 409,
                "JOB_POSTING_CLOSED");
        assertThat(closedAgain.path("message").asText()).isEqualTo(CLOSED_WITH_REQUISITION);
    }

    // A posting of its own requisition brought to the status through the API: HR writes and sends it (1, 2), ADMIN asks
    // for changes or approves it (3), HR publishes it (4); a closed posting is published, then HR fills its requisition
    // (task 290); a posting taken down is published, then the recruiter takes it down.
    private UUID postingIn(JobPostingStatus postingStatus) throws Exception {
        UUID requisition = requisition(department);
        UUID id = UUID.fromString(postings.created(postings.body(requisition, workLocation, employmentType), hrToken)
                .path("id").asText());
        if (postingStatus != JobPostingStatus.DRAFT) {
            ok(postings.submit(id, 1, null, hrToken));
        }
        if (postingStatus == JobPostingStatus.REVISION_REQUESTED) {
            ok(postings.decide(id, "REQUEST_REVISION", "Bổ sung quyền lợi.", 2, adminToken));
        }
        if (postingStatus == JobPostingStatus.APPROVED || postingStatus == JobPostingStatus.PUBLISHED
                || postingStatus == JobPostingStatus.CLOSED || postingStatus == JobPostingStatus.UNPUBLISHED) {
            ok(postings.decide(id, "APPROVE", null, 2, adminToken));
        }
        if (postingStatus == JobPostingStatus.PUBLISHED || postingStatus == JobPostingStatus.CLOSED
                || postingStatus == JobPostingStatus.UNPUBLISHED) {
            ok(postings.publish(id, 3, hrToken));
        }
        if (postingStatus == JobPostingStatus.CLOSED) {
            status.ok(status.change(requisition, "FILLED", "Đã tuyển đủ", 0, hrToken));
        }
        if (postingStatus == JobPostingStatus.UNPUBLISHED) {
            ok(postings.unpublish(id, 4, REASON, recruiterToken));
        }
        assertThat(postings.row(id).get("status")).isEqualTo(postingStatus.name());
        return id;
    }

    // HR writes, sends and publishes it; ADMIN approves it (revision 4).
    private UUID publishedPosting(UUID requisition) throws Exception {
        return status.publishedPosting(requisition, workLocation, employmentType, hrToken, adminToken);
    }

    // An approved requisition of the department written with SQL, without history (revision 0), whose primary
    // recruiter is the recruiter.
    private UUID requisition(UUID department) {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        postings.assign(requisition, recruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
        return requisition;
    }

    // The gate the apply API (task 326) calls, in its own transaction: null when it passes, else its refusal.
    private ApiException apply(UUID posting) {
        try {
            new TransactionTemplate(transactions).execute(transaction -> gate.requireOpen(posting));
            return null;
        } catch (ApiException refusal) {
            return refusal;
        }
    }

    private List<UUID> filterDepartments() throws Exception {
        var response = postings.publicFilters();
        postings.noStore(response);
        List<UUID> ids = new ArrayList<>();
        ok(response).path("departments").forEach(option -> ids.add(id(option)));
        return ids;
    }

    private static List<UUID> publicIds(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(id(item)));
        return ids;
    }

    private void assertNoAction(JsonNode view) {
        assertThat(new ArrayList<>(view.path("actions").propertyNames())).containsExactlyElementsOf(FLAGS);
        for (String flag : FLAGS) {
            assertThat(view.path("actions").path(flag).asBoolean()).as(flag).isFalse();
        }
    }

    // The row without what taking down changes.
    private static Map<String, Object> withoutStatus(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.remove("status");
        copy.remove("revision");
        return copy;
    }

    private JsonNode ok(HttpResponse<String> response) {
        return api.expect(response, 200);
    }

    private static UUID id(JsonNode node) {
        return UUID.fromString(node.path("id").asText());
    }

    // Special characters are built from their code points, so the source shows which ones they are.
    private static String codePoints(int... characters) {
        return new String(characters, 0, characters.length);
    }

    private UUID account(String email, String fullName, Role role) {
        return accounts.saveAndFlush(new Account(email, fullName, passwordHash, Set.of(role), START)).getId();
    }
}
