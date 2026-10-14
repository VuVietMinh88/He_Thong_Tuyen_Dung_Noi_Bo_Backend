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
import org.springframework.jdbc.core.JdbcTemplate;
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
import vn.ttcs.recruitment.jobposting.JobPostingExpiry;
import vn.ttcs.recruitment.jobposting.JobPostingStatus;
import vn.ttcs.recruitment.requisition.RequisitionRecruiterRole;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.EMPLOYMENT_TYPE;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.WORK_LOCATION;

// Task 292 (story S3-07): "Đóng yêu cầu phải đồng bộ trạng thái gỡ tin và không còn công khai tin liên quan". When HR
// closes a requisition as filled or cancels it (task 290), every posting of it that is not closed yet becomes CLOSED in
// the same transaction, leaves the portal for good and records the closure in its history. Pausing only hides it.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobPostingClosureIntegrationTest {
    // 10:00 on 11 Oct 2026 in Vietnam. Every posting made here may be applied to until 30 Nov 2026.
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");
    // Task 355: the text names taking down too.
    private static final String CLOSED_WITH_REQUISITION = "Tin tuyển dụng đã đóng cùng yêu cầu tuyển dụng nên không "
            + "thể sửa, gửi duyệt, duyệt, xuất bản hay gỡ nữa.";
    private static final String NOT_APPROVED_REQUISITION =
            "Chỉ tạo và xử lý được tin tuyển dụng của yêu cầu tuyển dụng đã duyệt.";
    private static final String REASON_REQUIRED =
            "Vui lòng nhập lý do khi tạm dừng, đóng vì đã tuyển đủ hoặc hủy yêu cầu tuyển dụng.";
    private static final List<String> FLAGS = List.of("canEdit", "canSubmit", "canApprove", "canRequestRevision",
            "canPublish", "canUnpublish");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JobPostingExpiry expiry;

    private RequisitionStatusTestSupport status;
    private JobPostingTestSupport postings;
    private ApprovalTestSupport api;
    private String passwordHash;
    private UUID adminId;
    private String adminToken;
    private UUID hrId;
    private String hrToken;
    private UUID closerId;
    private String closerToken;
    private String headToken;
    private UUID recruiterId;
    private String recruiterToken;
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
        // A second HR manager closes the requisitions: neither author, approver nor publisher of their postings.
        closerId = account("closer@example.test", "Phạm Thu Trang", Role.HR_MANAGER);
        closerToken = api.token("closer@example.test");
        UUID headId = account("head@example.test", "Trưởng phòng IT", Role.HIRING_MANAGER);
        headToken = api.token("head@example.test");
        recruiterId = account("recruiter@example.test", "Trần Văn Tuyển", Role.RECRUITER);
        recruiterToken = api.token("recruiter@example.test");
        department = api.department("IT", headId, null, START);
        position = api.position(START);
        workLocation = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, START);
        employmentType = postings.catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", true, START);
    }

    // S3-07 AC2: "Đóng yêu cầu tự động gỡ tin tuyển dụng đang đăng", for a filled and for a cancelled requisition.
    @ParameterizedTest
    @ValueSource(strings = {"FILLED", "CANCELLED"})
    void fillingARequisitionTakesItsPublishedPostingOffThePortal(String end) throws Exception {
        UUID requisition = requisition();
        UUID posting = publishedPosting(requisition);
        JsonNode before = ok(postings.get(posting, hrToken));
        assertThat(publicIds()).containsExactly(posting);
        clock.set(START.plusSeconds(90).plusNanos(123_456_789));
        // A reason with a visible character is kept exactly as sent, the invisible characters around it included.
        String reason = codePoints(0x00A0) + "Lý do " + end + "\nghi rõ cho người xem." + codePoints(0x200B);

        status.ok(status.change(requisition, end, reason, 0, closerToken));

        // The portal answers exactly as for an id that does not exist, and the list no longer counts it.
        JsonNode unknown = postings.error(postings.publicDetail(UUID.randomUUID()), 404, "JOB_POSTING_NOT_FOUND");
        assertThat(postings.error(postings.publicDetail(posting), 404, "JOB_POSTING_NOT_FOUND")).isEqualTo(unknown);
        JsonNode list = ok(postings.publicList(0, 20));
        assertThat(list.path("items").size()).isZero();
        assertThat(list.path("totalElements").asLong()).isZero();
        // Internally: closed with one more revision; the content time and the publication trail are kept, and no
        // button is offered to anyone.
        JsonNode closed = ok(postings.get(posting, hrToken));
        assertThat(closed.path("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.path("revision").asInt()).isEqualTo(before.path("revision").asInt() + 1);
        for (String kept : List.of("updatedAt", "publishedById", "publishedByName", "publishedAt", "title",
                "applicationDeadline", "lastReview")) {
            assertThat(closed.path(kept)).as(kept).isEqualTo(before.path(kept));
        }
        assertThat(closed.path("publishedById").asText()).isEqualTo(hrId.toString());
        for (String token : List.of(hrToken, adminToken, closerToken)) {
            assertNoAction(ok(postings.get(posting, token)));
        }
        // The history names the closer, the time of the requisition's own history row and its reason.
        JsonNode requisitionEvent = status.history(requisition, hrToken).path(0);
        assertThat(requisitionEvent.path("action").asText()).isEqualTo(end);
        JsonNode history = ok(postings.history(posting, hrToken));
        assertThat(history.size()).isEqualTo(4);
        JsonNode top = history.path(0);
        assertThat(top.path("revision").asInt()).isEqualTo(closed.path("revision").asInt());
        assertThat(top.path("action").asText()).isEqualTo("CLOSED");
        assertThat(top.path("statusBefore").asText()).isEqualTo("PUBLISHED");
        assertThat(top.path("statusAfter").asText()).isEqualTo("CLOSED");
        assertThat(top.path("actorId").asText()).isEqualTo(closerId.toString())
                .isNotIn(hrId.toString(), adminId.toString());
        assertThat(top.path("actorName").asText()).isEqualTo("Phạm Thu Trang");
        assertThat(top.path("occurredAt").asText()).isEqualTo("2026-10-11T03:01:30.123456Z")
                .isEqualTo(requisitionEvent.path("occurredAt").asText());
        assertThat(top.path("comment").asText()).isEqualTo(reason);
        assertThat(history.path(1).path("action").asText()).isEqualTo("PUBLISHED");
    }

    // Every posting that is not closed yet is closed, whatever its step; a closed one stays as it is, because its
    // requisition can no longer change. Task 355: a posting taken down is closed too. Task 356: an expired one too.
    @ParameterizedTest
    @EnumSource(JobPostingStatus.class)
    void closingClosesAPostingInEveryOpenStatus(JobPostingStatus postingStatus) throws Exception {
        UUID requisition = requisition();
        UUID posting = postingIn(requisition, postingStatus);
        Map<String, Object> rowBefore = postings.row(posting);
        List<Map<String, Object>> eventsBefore = postings.events(posting);
        List<Map<String, Object>> requisitionEventsBefore = status.events(requisition);
        clock.set(START.plusSeconds(300).plusNanos(654_321_987));

        var response = status.change(requisition, "CANCELLED", "Không còn nhu cầu", requisitionEventsBefore.size(),
                closerToken);

        boolean open = switch (postingStatus) {
            case DRAFT, PENDING_APPROVAL, REVISION_REQUESTED, APPROVED, PUBLISHED, UNPUBLISHED, EXPIRED -> true;
            case CLOSED -> false;
        };
        if (open) {
            status.ok(response);
            Map<String, Object> row = postings.row(posting);
            assertThat(row.get("status")).isEqualTo("CLOSED");
            assertThat(row.get("revision")).isEqualTo((Integer) rowBefore.get("revision") + 1);
            assertThat(withoutStatus(row)).isEqualTo(withoutStatus(rowBefore));
            List<Map<String, Object>> events = postings.events(posting);
            assertThat(events.subList(0, eventsBefore.size())).isEqualTo(eventsBefore);
            assertThat(events).hasSize(eventsBefore.size() + 1);
            Map<String, Object> closure = events.getLast();
            assertThat(closure.get("revision")).isEqualTo(row.get("revision"));
            assertThat(closure.get("event_type")).isEqualTo("CLOSED");
            assertThat(closure.get("status_before")).isEqualTo(postingStatus.name());
            assertThat(closure.get("status_after")).isEqualTo("CLOSED");
            assertThat(closure.get("actor_id")).isEqualTo(closerId);
            assertThat(closure.get("occurred_at")).isEqualTo(status.events(requisition).getLast().get("occurred_at"));
            assertThat(((Timestamp) closure.get("occurred_at")).toInstant())
                    .isEqualTo(Instant.parse("2026-10-11T03:05:00.654321Z"));
            assertThat(closure.get("comment")).isEqualTo("Không còn nhu cầu");
            assertThat(status.row(requisition).get("status")).isEqualTo("CANCELLED");
        } else {
            postings.error(response, 409, "REQUISITION_STATUS_CHANGE_NOT_ALLOWED");
            assertThat(postings.row(posting)).isEqualTo(rowBefore);
            assertThat(postings.events(posting)).isEqualTo(eventsBefore);
            assertThat(status.events(requisition)).isEqualTo(requisitionEventsBefore);
        }
    }

    // Pausing only hides the posting (task 309's filter): it stays PUBLISHED with its revision and history, and comes
    // back when HR resumes. Cancelling the paused requisition then closes it from PUBLISHED.
    @Test
    void pausingHidesWithoutClosing() throws Exception {
        UUID requisition = requisition();
        UUID posting = publishedPosting(requisition);
        Map<String, Object> rowBefore = postings.row(posting);
        List<Map<String, Object>> eventsBefore = postings.events(posting);

        status.ok(status.change(requisition, "PAUSED", "Chờ ngân sách quý 4", 0, closerToken));
        postings.error(postings.publicDetail(posting), 404, "JOB_POSTING_NOT_FOUND");
        assertThat(postings.row(posting)).isEqualTo(rowBefore);
        assertThat(postings.events(posting)).isEqualTo(eventsBefore);

        status.ok(status.change(requisition, "APPROVED", null, 1, closerToken));
        assertThat(ok(postings.publicDetail(posting)).path("id").asText()).isEqualTo(posting.toString());
        assertThat(postings.row(posting)).isEqualTo(rowBefore);
        assertThat(postings.events(posting)).isEqualTo(eventsBefore);

        status.ok(status.change(requisition, "PAUSED", "Chờ lần nữa", 2, closerToken));
        status.ok(status.change(requisition, "CANCELLED", "Không còn nhu cầu", 3, closerToken));
        assertThat(postings.row(posting).get("status")).isEqualTo("CLOSED");
        assertThat(postings.events(posting).getLast()).containsEntry("event_type", "CLOSED")
                .containsEntry("status_before", "PUBLISHED").containsEntry("comment", "Không còn nhu cầu");
        postings.error(postings.publicDetail(posting), 404, "JOB_POSTING_NOT_FOUND");
    }

    // A requisition without a posting closes as before, and the postings of other requisitions are left alone.
    @Test
    void aRequisitionWithoutPostingClosesNormally() throws Exception {
        UUID lonely = requisition();
        UUID posting = publishedPosting(requisition());
        List<Map<String, Object>> rowsBefore = jdbc.queryForList("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1");

        assertThat(status.ok(status.change(lonely, "FILLED", "Đã tuyển đủ", 0, closerToken)).path("status").asText())
                .isEqualTo("FILLED");

        assertThat(status.events(lonely)).extracting(event -> event.get("event_type")).containsExactly("FILLED");
        assertThat(jdbc.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1")).isEqualTo(eventsBefore);
        assertThat(ok(postings.publicDetail(posting)).path("id").asText()).isEqualTo(posting.toString());
    }

    // A refused change closes nothing: the posting stays published and public.
    @Test
    void aRefusedChangeLeavesThePostingLive() throws Exception {
        UUID requisition = requisition();
        UUID posting = publishedPosting(requisition);
        Map<String, Object> requisitionBefore = status.row(requisition);
        List<Map<String, Object>> rowsBefore = jdbc.queryForList("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1");

        postings.error(status.change(requisition, "FILLED", "Đã tuyển đủ", 0, null), 401, "UNAUTHORIZED");
        for (String token : List.of(headToken, recruiterToken)) {
            postings.forbidden(status.change(requisition, "FILLED", "Đã tuyển đủ", 0, token));
        }
        postings.businessError(status.change(requisition, "CANCELLED", null, 0, closerToken),
                "REQUISITION_STATUS_REASON_REQUIRED", "reason", REASON_REQUIRED);
        // A reason without any visible character is no reason either, so no posting is closed with it.
        for (String invisible : List.of(codePoints(0x00A0), codePoints(0x0085), codePoints(0x200B, 0xFEFF))) {
            postings.businessError(status.change(requisition, "FILLED", invisible, 0, closerToken),
                    "REQUISITION_STATUS_REASON_REQUIRED", "reason", REASON_REQUIRED);
        }
        postings.error(status.change(requisition, "APPROVED", null, 0, closerToken), 409,
                "REQUISITION_STATUS_CHANGE_NOT_ALLOWED");
        postings.error(status.change(requisition, "CANCELLED", "Không còn nhu cầu", 1, closerToken), 409,
                "REQUISITION_STATE_CHANGED");

        assertThat(status.row(requisition)).isEqualTo(requisitionBefore);
        assertThat(status.events(requisition)).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1")).isEqualTo(eventsBefore);
        assertThat(postings.row(posting).get("status")).isEqualTo("PUBLISHED");
        assertThat(ok(postings.publicDetail(posting)).path("id").asText()).isEqualTo(posting.toString());
    }

    // JOB_POSTING_CLOSED is the first status check of every posting write, so even a reviewer who did not write the
    // posting is told it is closed (409), not that it may not approve it or that it is not under review. It comes
    // after the scope, so a recruiter who is not on the requisition's team gets 403 and does not learn the status.
    @Test
    void everyPostingWriteRefusesAClosedPosting() throws Exception {
        UUID requisition = requisition();
        UUID posting = publishedPosting(requisition);
        status.ok(status.change(requisition, "CANCELLED", "Không còn nhu cầu", 0, closerToken));
        account("outsider@example.test", "Người ngoài", Role.RECRUITER);
        String outsiderToken = api.token("outsider@example.test");
        JsonNode view = ok(postings.get(posting, hrToken));
        int revision = view.path("revision").asInt();
        Map<String, Object> change = postings.putBody(view);
        change.put("benefits", "Bảo hiểm đầy đủ.");
        List<Map<String, Object>> rowsBefore = jdbc.queryForList("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1");
        List<Map<String, Object>> authorsBefore = jdbc.queryForList("SELECT * FROM job_posting_authors ORDER BY 1, 2");

        // HR wrote, sent and published it; ADMIN approved it and is not an author. Task 355: taking it down is
        // refused the same way, for HR and for the recruiter of the requisition.
        List<HttpResponse<String>> refused = List.of(
                postings.update(posting, change, hrToken),
                postings.submit(posting, revision, null, hrToken),
                postings.decide(posting, "APPROVE", null, revision, adminToken),
                postings.decide(posting, "APPROVE", null, revision, hrToken),
                postings.decide(posting, "REQUEST_REVISION", "Sửa lại", revision, adminToken),
                postings.publish(posting, revision, hrToken),
                postings.unpublish(posting, revision, "Gỡ tin", hrToken),
                postings.unpublish(posting, revision, "Gỡ tin", recruiterToken));
        for (var response : refused) {
            assertThat(postings.error(response, 409, "JOB_POSTING_CLOSED").path("message").asText())
                    .isEqualTo(CLOSED_WITH_REQUISITION);
        }
        // The outsider's PUT and submit: 403 as for an open posting. /approvals and /publish need
        // JOB_POSTINGS_WRITE_ALL, which no recruiter holds.
        postings.forbidden(postings.update(posting, change, outsiderToken));
        postings.forbidden(postings.submit(posting, revision, null, outsiderToken));
        postings.forbidden(postings.unpublish(posting, revision, "Gỡ tin", outsiderToken));
        // A new posting of the cancelled requisition: the requisition is checked before the existing posting.
        JsonNode create = postings.error(postings.create(postings.body(requisition, workLocation, employmentType),
                hrToken), 409, "JOB_POSTING_REQUISITION_NOT_APPROVED");
        assertThat(create.path("message").asText()).isEqualTo(NOT_APPROVED_REQUISITION);

        assertThat(jdbc.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1")).isEqualTo(eventsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_authors ORDER BY 1, 2")).isEqualTo(authorsBefore);
        // The authors and reviewers still read it, its preview and its history.
        assertThat(ok(postings.preview(posting, hrToken)).path("status").asText()).isEqualTo("CLOSED");
        assertThat(ok(postings.list("?status=CLOSED", hrToken)).path("totalElements").asLong()).isOne();
    }

    // Q18.11: closing is part of HR's decision about the requisition, so the closer needs no JOB_POSTINGS permission.
    @Test
    void theCloserNeedsNoJobPostingPermission() throws Exception {
        UUID requisition = requisition();
        UUID posting = publishedPosting(requisition);
        List<String> grants = jdbc.queryForList("SELECT permission_code FROM role_permissions "
                + "WHERE role_code = 'HR_MANAGER' AND permission_code LIKE 'JOB_POSTINGS%'", String.class);
        assertThat(grants).isNotEmpty();
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' "
                + "AND permission_code LIKE 'JOB_POSTINGS%'");
        try {
            String closer = api.token("closer@example.test");
            postings.forbidden(postings.get(posting, closer));

            status.ok(status.change(requisition, "FILLED", "Đã tuyển đủ", 0, closer));

            assertThat(postings.row(posting).get("status")).isEqualTo("CLOSED");
            assertThat(postings.events(posting).getLast()).containsEntry("event_type", "CLOSED")
                    .containsEntry("actor_id", closerId);
        } finally {
            for (String grant : grants) {
                jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', ?) "
                        + "ON CONFLICT DO NOTHING", grant);
            }
        }
    }

    // One transaction: when the posting cannot be closed, the requisition is not closed either, and the posting
    // stays on the portal.
    @Test
    void aFailureWhileClosingThePostingKeepsTheRequisitionOpen() throws Exception {
        UUID requisition = requisition();
        UUID posting = publishedPosting(requisition);
        Map<String, Object> requisitionBefore = status.row(requisition);
        List<Map<String, Object>> rowsBefore = jdbc.queryForList("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1");
        jdbc.execute("""
                CREATE FUNCTION test_refuse_job_posting_closure() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'test: closing a job posting is refused';
                END $$""");
        jdbc.execute("""
                CREATE TRIGGER test_refuse_job_posting_closure BEFORE INSERT ON job_posting_events
                    FOR EACH ROW WHEN (NEW.event_type = 'CLOSED')
                    EXECUTE FUNCTION test_refuse_job_posting_closure()""");
        try {
            var response = status.change(requisition, "FILLED", "Đã tuyển đủ", 0, closerToken);
            assertThat(response.statusCode() / 100).as(response.body()).isNotEqualTo(2);
        } finally {
            jdbc.execute("DROP TRIGGER test_refuse_job_posting_closure ON job_posting_events");
            jdbc.execute("DROP FUNCTION test_refuse_job_posting_closure()");
        }

        assertUnchanged(requisition, posting, requisitionBefore, rowsBefore, eventsBefore);
        // Nothing is left locked or half written: the same change succeeds once nothing refuses it.
        status.ok(status.change(requisition, "FILLED", "Đã tuyển đủ", 0, closerToken));
        assertThat(postings.row(posting).get("status")).isEqualTo("CLOSED");
    }

    // The closure is not committed on its own either: when the change fails as it commits, after the posting was
    // closed, the posting is still published.
    @Test
    void aFailureWhenTheChangeCommitsKeepsThePostingPublished() throws Exception {
        UUID requisition = requisition();
        UUID posting = publishedPosting(requisition);
        Map<String, Object> requisitionBefore = status.row(requisition);
        List<Map<String, Object>> rowsBefore = jdbc.queryForList("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1");
        jdbc.execute("""
                CREATE FUNCTION test_refuse_requisition_end() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'test: closing a requisition is refused at commit';
                END $$""");
        // Deferred: it fires when the change commits, after the listener closed the posting.
        jdbc.execute("""
                CREATE CONSTRAINT TRIGGER test_refuse_requisition_end AFTER INSERT ON requisition_workflow_events
                    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.event_type IN ('FILLED', 'CANCELLED'))
                    EXECUTE FUNCTION test_refuse_requisition_end()""");
        try {
            var response = status.change(requisition, "CANCELLED", "Không còn nhu cầu", 0, closerToken);
            assertThat(response.statusCode() / 100).as(response.body()).isNotEqualTo(2);
        } finally {
            jdbc.execute("DROP TRIGGER test_refuse_requisition_end ON requisition_workflow_events");
            jdbc.execute("DROP FUNCTION test_refuse_requisition_end()");
        }

        assertUnchanged(requisition, posting, requisitionBefore, rowsBefore, eventsBefore);
    }

    private void assertUnchanged(UUID requisition, UUID posting, Map<String, Object> requisitionBefore,
                                 List<Map<String, Object>> rowsBefore, List<Map<String, Object>> eventsBefore)
            throws Exception {
        assertThat(status.row(requisition)).isEqualTo(requisitionBefore);
        assertThat(status.events(requisition)).isEmpty();
        assertThat(status.history(requisition, hrToken).size()).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1")).isEqualTo(eventsBefore);
        assertThat(ok(postings.publicDetail(posting)).path("id").asText()).isEqualTo(posting.toString());
    }

    // A posting of the requisition brought to the status through the API: the recruiter writes and sends it (1, 2), HR
    // asks for changes or approves it (3) and publishes it (4). The draft never got its portal details, so closing it
    // relies on V25's exemption. A closed posting is published, then HR fills its requisition (task 290). Task 355: a
    // posting taken down is published, then the recruiter takes it down (5). Task 356: an expired posting is
    // published, then the daily task expires it (5) at 00:00 after its deadline; the clock then goes back.
    private UUID postingIn(UUID requisition, JobPostingStatus postingStatus) throws Exception {
        if (postingStatus == JobPostingStatus.DRAFT) {
            return id(postings.created(Map.of("requisitionId", requisition), recruiterToken));
        }
        UUID id = id(postings.created(postings.body(requisition, workLocation, employmentType), recruiterToken));
        ok(postings.submit(id, 1, null, recruiterToken));
        if (postingStatus == JobPostingStatus.REVISION_REQUESTED) {
            ok(postings.decide(id, "REQUEST_REVISION", "Bổ sung quyền lợi.", 2, hrToken));
        }
        if (postingStatus == JobPostingStatus.APPROVED || postingStatus == JobPostingStatus.PUBLISHED
                || postingStatus == JobPostingStatus.CLOSED || postingStatus == JobPostingStatus.UNPUBLISHED
                || postingStatus == JobPostingStatus.EXPIRED) {
            ok(postings.decide(id, "APPROVE", null, 2, hrToken));
        }
        if (postingStatus == JobPostingStatus.PUBLISHED || postingStatus == JobPostingStatus.CLOSED
                || postingStatus == JobPostingStatus.UNPUBLISHED || postingStatus == JobPostingStatus.EXPIRED) {
            ok(postings.publish(id, 3, hrToken));
        }
        if (postingStatus == JobPostingStatus.CLOSED) {
            status.ok(status.change(requisition, "FILLED", "Đã tuyển đủ", 0, hrToken));
        }
        if (postingStatus == JobPostingStatus.UNPUBLISHED) {
            ok(postings.unpublish(id, 4, "Đã nhận đủ hồ sơ.", recruiterToken));
        }
        if (postingStatus == JobPostingStatus.EXPIRED) {
            Instant now = clock.instant();
            clock.set(Instant.parse(JobPostingTestSupport.CLOSES_AT));
            assertThat(expiry.expire(id)).isTrue();
            clock.set(now);
        }
        assertThat(postings.row(id).get("status")).isEqualTo(postingStatus.name());
        return id;
    }

    // HR writes, sends and publishes it; ADMIN approves it.
    private UUID publishedPosting(UUID requisition) throws Exception {
        return status.publishedPosting(requisition, workLocation, employmentType, hrToken, adminToken);
    }

    // An approved requisition written with SQL, without history (revision 0), whose primary recruiter is the recruiter.
    private UUID requisition() {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        postings.assign(requisition, recruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
        return requisition;
    }

    private List<UUID> publicIds() throws Exception {
        List<UUID> ids = new ArrayList<>();
        ok(postings.publicList(0, 20)).path("items").forEach(item -> ids.add(id(item)));
        return ids;
    }

    private void assertNoAction(JsonNode view) {
        for (String flag : FLAGS) {
            assertThat(view.path("actions").path(flag).asBoolean()).as(flag).isFalse();
        }
    }

    // The row without what a closure changes.
    private static Map<String, Object> withoutStatus(Map<String, Object> row) {
        Map<String, Object> copy = new LinkedHashMap<>(row);
        copy.remove("status");
        copy.remove("revision");
        return copy;
    }

    private JsonNode ok(HttpResponse<String> response) {
        return api.expect(response, 200);
    }

    private static UUID id(JsonNode view) {
        return UUID.fromString(view.path("id").asText());
    }

    private static String codePoints(int... characters) {
        return new String(characters, 0, characters.length);
    }

    private UUID account(String email, String fullName, Role role) {
        return accounts.saveAndFlush(new Account(email, fullName, passwordHash, Set.of(role), START)).getId();
    }
}
