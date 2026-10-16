package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.time.Duration;
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

// Task 308 (story S3-10): "Xây dựng API duyệt và xuất bản tin tuyển dụng". The authors send a saved posting for review,
// HR approves it or asks for changes, and only an approved posting is published. Nobody approves a posting they
// wrote, edited or last sent; every step is added to job_posting_events.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobPostingReviewIntegrationTest {
    // 10:00 on 11 Oct 2026 in Vietnam. Every posting made here may be applied to until 30 Nov 2026.
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");
    private static final String NOT_UNDER_REVIEW =
            "Tin tuyển dụng không còn ở bước duyệt này. Vui lòng tải lại để xem trạng thái mới nhất.";
    private static final String NOT_APPROVED = "Tin tuyển dụng chưa được duyệt nên chưa thể xuất bản.";
    private static final String NOT_APPROVED_REQUISITION =
            "Chỉ tạo và xử lý được tin tuyển dụng của yêu cầu tuyển dụng đã duyệt.";
    private static final String DEADLINE_PASSED = "Hạn nộp hồ sơ của tin đã qua. Vui lòng yêu cầu chỉnh sửa để đổi "
            + "hạn nộp trước khi duyệt hoặc xuất bản.";
    // Task 355: the text names taking down too.
    private static final String CLOSED_WITH_REQUISITION = "Tin tuyển dụng đã đóng cùng yêu cầu tuyển dụng nên không "
            + "thể sửa, gửi duyệt, duyệt, xuất bản hay gỡ nữa.";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JobPostingExpiry expiry;

    private JobPostingTestSupport postings;
    private ApprovalTestSupport api;
    private String passwordHash;
    private UUID adminId;
    private String adminToken;
    private UUID hrId;
    private String hrToken;
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
        postings = new JobPostingTestSupport(jdbc, json, transactions,
                Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        api = postings.api;
        postings.reset();
        bootstrap.run(new DefaultApplicationArguments());
        adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        adminToken = api.token("admin@example.test");
        hrId = account("hr@example.test", "Nguyễn Thị Hoa", Role.HR_MANAGER);
        hrToken = api.token("hr@example.test");
        UUID headId = account("head@example.test", "Trưởng phòng IT", Role.HIRING_MANAGER);
        recruiterId = account("recruiter@example.test", "Trần Văn Tuyển", Role.RECRUITER);
        recruiterToken = api.token("recruiter@example.test");
        department = api.department("IT", headId, null, START);
        position = api.position(START);
        workLocation = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, START);
        employmentType = postings.catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", true, START);
    }

    @Test
    void aRecruiterSubmitsHrApprovesAndPublishes() throws Exception {
        UUID requisition = requisition();
        Map<String, Object> requisitionBefore = postings.requisitionRow(requisition);
        JsonNode created = postings.created(postings.body(requisition, workLocation, employmentType), recruiterToken);
        UUID id = id(created);
        assertActions(created, true, true, false, false, false);
        // HR may compose the draft too, but there is nothing to review yet.
        assertActions(ok(postings.get(id, hrToken)), true, true, false, false, false);
        clock.set(START.plusSeconds(60).plusNanos(111_111_111));
        Map<String, Object> change = postings.putBody(created);
        change.put("benefits", "Bảo hiểm đầy đủ.");
        JsonNode saved = ok(postings.update(id, change, recruiterToken));
        assertThat(saved.path("revision").asInt()).isEqualTo(2);
        String updatedAt = saved.path("updatedAt").asText();

        clock.set(START.plusSeconds(120).plusNanos(222_222_222));
        var submitting = postings.submit(id, 2, "Nhờ chị duyệt giúp.", recruiterToken);
        JsonNode submitted = ok(submitting);
        postings.noStore(submitting);
        assertThat(submitted.path("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(submitted.path("revision").asInt()).isEqualTo(3);
        assertThat(submitted.path("lastReview").isNull()).isTrue();
        // The content is frozen and the recruiter does not review.
        assertActions(submitted, false, false, false, false, false);
        assertActions(ok(postings.get(id, hrToken)), false, false, true, true, false);
        assertActions(ok(postings.get(id, adminToken)), false, false, true, true, false);

        clock.set(START.plusSeconds(180).plusNanos(333_333_333));
        var approving = postings.decide(id, "APPROVE", null, 3, hrToken);
        JsonNode approved = ok(approving);
        postings.noStore(approving);
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("revision").asInt()).isEqualTo(4);
        JsonNode review = approved.path("lastReview");
        assertThat(fieldNames(review)).containsExactly("action", "comment", "actorId", "actorName", "occurredAt");
        assertThat(review.path("action").asText()).isEqualTo("APPROVED");
        assertThat(review.path("comment").isNull()).isTrue();
        assertThat(review.path("actorId").asText()).isEqualTo(hrId.toString());
        assertThat(review.path("actorName").asText()).isEqualTo("Nguyễn Thị Hoa");
        assertThat(review.path("occurredAt").asText()).isEqualTo("2026-10-11T03:03:00.333333Z");
        // HR may still ask for changes, or publish (the approver may publish its own approval).
        assertActions(approved, false, false, false, true, true);
        assertActions(ok(postings.get(id, recruiterToken)), false, false, false, false, false);

        clock.set(START.plusSeconds(240).plusNanos(444_444_444));
        var publishing = postings.publish(id, 4, hrToken);
        JsonNode published = ok(publishing);
        postings.noStore(publishing);
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published.path("revision").asInt()).isEqualTo(5);
        assertThat(published.path("lastReview")).isEqualTo(review);
        // Task 355: a published posting can only be taken down, by HR or by the recruiter.
        assertActions(published, false, false, false, false, false, true);
        assertActions(ok(postings.get(id, recruiterToken)), false, false, false, false, false, true);
        assertThat(ok(postings.get(id, hrToken))).isEqualTo(published);

        // Workflow steps never change the content's updatedAt; the text published is the text saved.
        assertThat(published.path("updatedAt").asText()).isEqualTo(updatedAt);
        assertThat(published.path("benefits").asText()).isEqualTo("Bảo hiểm đầy đủ.");
        assertThat(steps(id)).containsExactly(
                new Step(3, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", recruiterId, "Nhờ chị duyệt giúp."),
                new Step(4, "APPROVED", "PENDING_APPROVAL", "APPROVED", hrId, null),
                new Step(5, "PUBLISHED", "APPROVED", "PUBLISHED", hrId, null));
        assertThat(postings.events(id)).extracting(row -> ((Timestamp) row.get("occurred_at")).toInstant())
                .containsExactly(Instant.parse("2026-10-11T03:02:00.222222Z"),
                        Instant.parse("2026-10-11T03:03:00.333333Z"), Instant.parse("2026-10-11T03:04:00.444444Z"));
        assertThat(postings.row(id).get("status")).isEqualTo("PUBLISHED");
        // Sending and deciding make nobody an author; the requisition is never written.
        assertThat(postings.authorsOf(id)).containsExactly(recruiterId);
        assertThat(postings.requisitionRow(requisition)).isEqualTo(requisitionBefore);
    }

    // S3-10 AC1: a posting reaches candidates only after HR approved it.
    @ParameterizedTest
    @EnumSource(JobPostingStatus.class)
    void aPostingThatWasNotApprovedCannotBePublished(JobPostingStatus status) throws Exception {
        UUID id = postingIn(status);
        Map<String, Object> rowBefore = postings.row(id);
        List<Step> stepsBefore = steps(id);

        var response = postings.publish(id, postings.revision(id), hrToken);

        String refusal = switch (status) {
            case APPROVED -> null;
            // Task 355: a posting is published once, so one taken down is told it was published. Task 356: so is an
            // expired one.
            case PUBLISHED, UNPUBLISHED, EXPIRED -> "JOB_POSTING_ALREADY_PUBLISHED";
            case DRAFT, PENDING_APPROVAL, REVISION_REQUESTED -> "JOB_POSTING_NOT_APPROVED";
            // Task 292: a posting closed with its requisition is told so before anything else.
            case CLOSED -> "JOB_POSTING_CLOSED";
        };
        if (refusal == null) {
            assertThat(ok(response).path("status").asText()).isEqualTo("PUBLISHED");
            assertThat(steps(id)).hasSize(stepsBefore.size() + 1).last()
                    .isEqualTo(new Step(postings.revision(id), "PUBLISHED", "APPROVED", "PUBLISHED", hrId, null));
        } else {
            JsonNode error = postings.error(response, 409, refusal);
            assertThat(error.path("message").asText()).isEqualTo(switch (status) {
                case PUBLISHED, UNPUBLISHED, EXPIRED -> "Tin tuyển dụng đã được xuất bản.";
                case CLOSED -> CLOSED_WITH_REQUISITION;
                default -> NOT_APPROVED;
            });
            assertThat(postings.row(id)).isEqualTo(rowBefore);
            assertThat(steps(id)).isEqualTo(stepsBefore);
        }
    }

    @Test
    void submittingNeedsACompleteValidPosting() throws Exception {
        // A draft that never got its portal details: every missing one is reported at once.
        UUID bare = id(postings.created(Map.of("requisitionId", requisition()), recruiterToken));
        JsonNode incomplete = postings.error(postings.submit(bare, 1, null, recruiterToken), 400,
                "JOB_POSTING_INCOMPLETE");
        assertThat(incomplete.path("message").asText())
                .isEqualTo("Tin tuyển dụng chưa đủ thông tin hợp lệ để gửi duyệt.");
        assertThat(fieldNames(incomplete.path("fieldErrors")))
                .containsExactlyInAnyOrder("workLocationId", "employmentTypeId", "applicationDeadline");
        assertThat(incomplete.path("fieldErrors").path("workLocationId").asText())
                .isEqualTo("Vui lòng chọn địa điểm làm việc trước khi gửi duyệt.");
        assertThat(incomplete.path("fieldErrors").path("employmentTypeId").asText())
                .isEqualTo("Vui lòng chọn hình thức làm việc trước khi gửi duyệt.");
        assertThat(incomplete.path("fieldErrors").path("applicationDeadline").asText())
                .isEqualTo("Vui lòng chọn hạn nộp hồ sơ trước khi gửi duyệt.");

        // A job description copied with HTML from the requisition and never rewritten: the saved text is checked
        // with the rules of a save, so HR never reviews it.
        UUID htmlRequisition = requisition();
        jdbc.update("UPDATE recruitment_requisitions SET job_description = ? WHERE id = ?", "<b>Java</b>\nSpring",
                htmlRequisition);
        UUID copied = id(postings.created(Map.of("requisitionId", htmlRequisition), recruiterToken));
        JsonNode html = postings.error(postings.submit(copied, 1, null, recruiterToken), 400,
                "JOB_POSTING_INCOMPLETE");
        assertThat(fieldNames(html.path("fieldErrors"))).containsExactlyInAnyOrder("jobDescription",
                "workLocationId", "employmentTypeId", "applicationDeadline");
        assertThat(html.path("fieldErrors").path("jobDescription").asText())
                .isEqualTo("Mô tả công việc phải là văn bản thuần có ký tự nhìn thấy được, không chứa thẻ HTML.");

        // A catalog value HR stopped using after the draft was saved.
        UUID complete = id(postings.created(postings.body(requisition(), workLocation, employmentType),
                recruiterToken));
        jdbc.update("UPDATE recruitment_catalog_items SET active = FALSE WHERE id = ?", employmentType);
        postings.businessError(postings.submit(complete, 1, null, recruiterToken),
                "JOB_POSTING_EMPLOYMENT_TYPE_INACTIVE", "employmentTypeId",
                "Hình thức làm việc đã ngừng sử dụng, vui lòng chọn giá trị khác.");
        jdbc.update("UPDATE recruitment_catalog_items SET active = TRUE WHERE id = ?", employmentType);

        // A deadline that passed after the draft was saved (60 days later, after 30 Nov 2026).
        clock.set(START.plus(Duration.ofDays(60)));
        String later = api.token("recruiter@example.test");
        postings.businessError(postings.submit(complete, 1, null, later), "JOB_POSTING_DEADLINE_IN_PAST",
                "applicationDeadline", "Hạn nộp hồ sơ không được trước ngày hôm nay.");

        for (UUID refused : List.of(bare, copied, complete)) {
            assertThat(postings.row(refused).get("status")).isEqualTo("DRAFT");
            assertThat(postings.row(refused).get("revision")).isEqualTo(1);
            assertThat(postings.events(refused)).isEmpty();
        }
        // A new deadline makes the same draft sendable.
        Map<String, Object> change = postings.putBody(ok(postings.get(complete, later)));
        change.put("applicationDeadline", "2027-01-31");
        ok(postings.update(complete, change, later));
        assertThat(ok(postings.submit(complete, 2, null, later)).path("status").asText())
                .isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void authorsAndTheLatestSubmitterNeverApprove() throws Exception {
        // (a) HR wrote and sent the posting: ADMIN approves it, HR does not.
        UUID byHr = id(postings.created(postings.body(requisition(), workLocation, employmentType), hrToken));
        ok(postings.submit(byHr, 1, null, hrToken));
        // Only APPROVE has the rule: HR may still ask for changes, and publish what someone else approved.
        assertActions(ok(postings.get(byHr, hrToken)), false, false, false, true, false);
        assertThat(ok(postings.get(byHr, adminToken)).path("actions").path("canApprove").asBoolean()).isTrue();
        refusedApproval(byHr, 2);
        assertThat(ok(postings.decide(byHr, "APPROVE", null, 2, adminToken)).path("status").asText())
                .isEqualTo("APPROVED");
        assertActions(ok(postings.get(byHr, hrToken)), false, false, false, true, true);
        assertThat(ok(postings.publish(byHr, 3, hrToken)).path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(steps(byHr)).last().isEqualTo(new Step(4, "PUBLISHED", "APPROVED", "PUBLISHED", hrId, null));

        // (b) The recruiter wrote it, HR sent it: HR is the latest submitter.
        UUID sentByHr = id(postings.created(postings.body(requisition(), workLocation, employmentType),
                recruiterToken));
        ok(postings.submit(sentByHr, 1, null, hrToken));
        refusedApproval(sentByHr, 2);
        // The latest submitter may still send it back to its authors.
        JsonNode sentBack = ok(postings.decide(sentByHr, "REQUEST_REVISION", "Bổ sung quyền lợi.", 2, hrToken));
        assertThat(sentBack.path("status").asText()).isEqualTo("REVISION_REQUESTED");
        assertThat(sentBack.path("lastReview").path("actorId").asText()).isEqualTo(hrId.toString());

        // (c) ADMIN asked for changes, HR fixed the text, the recruiter sent it again: HR is an author now, even
        // though HR neither created nor last sent it.
        UUID editedByHr = id(postings.created(postings.body(requisition(), workLocation, employmentType),
                recruiterToken));
        ok(postings.submit(editedByHr, 1, null, recruiterToken));
        JsonNode returned = ok(postings.decide(editedByHr, "REQUEST_REVISION", "Bổ sung quyền lợi.", 2, adminToken));
        Map<String, Object> fix = postings.putBody(returned);
        fix.put("benefits", "Bảo hiểm đầy đủ.");
        ok(postings.update(editedByHr, fix, hrToken));
        ok(postings.submit(editedByHr, 4, null, recruiterToken));
        assertThat(postings.authorsOf(editedByHr)).containsExactlyInAnyOrder(recruiterId, hrId);
        assertThat(ok(postings.get(editedByHr, hrToken)).path("actions").path("canApprove").asBoolean()).isFalse();
        refusedApproval(editedByHr, 5);
        assertThat(ok(postings.decide(editedByHr, "APPROVE", null, 5, adminToken)).path("status").asText())
                .isEqualTo("APPROVED");
        // The editor publishes it.
        assertThat(ok(postings.publish(editedByHr, 6, hrToken)).path("status").asText()).isEqualTo("PUBLISHED");

        // (d) HR sent it once, ADMIN asked for changes, the recruiter sent it again without editing: HR was an
        // earlier submitter, not the latest one, and never wrote the content, so HR approves.
        UUID earlierByHr = id(postings.created(postings.body(requisition(), workLocation, employmentType),
                recruiterToken));
        ok(postings.submit(earlierByHr, 1, null, hrToken));
        ok(postings.decide(earlierByHr, "REQUEST_REVISION", "Kiểm tra lại hạn nộp.", 2, adminToken));
        ok(postings.submit(earlierByHr, 3, null, recruiterToken));
        assertThat(ok(postings.get(earlierByHr, hrToken)).path("actions").path("canApprove").asBoolean()).isTrue();
        JsonNode approved = ok(postings.decide(earlierByHr, "APPROVE", null, 4, hrToken));
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("lastReview").path("actorId").asText()).isEqualTo(hrId.toString());
    }

    @Test
    void revisionRequestsNeedACommentAndReopenThePosting() throws Exception {
        UUID id = id(postings.created(postings.body(requisition(), workLocation, employmentType), recruiterToken));
        ok(postings.submit(id, 1, null, recruiterToken));
        Map<String, Object> rowBefore = postings.row(id);
        // Checked before any lock or lookup: an unknown posting gets the same answer. A comment without any visible
        // character counts as none: String.isBlank() keeps U+00A0 and U+0085, which V22's CHECK counts as spaces (the
        // insert would answer 500), and zero-width characters would be stored as a comment that shows nothing.
        for (UUID posting : List.of(id, UUID.randomUUID())) {
            for (String comment : Arrays.asList(null, "  \n\t ", codePoints(0x00A0), codePoints(0x0085),
                    codePoints(0x200B, 0xFEFF), codePoints(0x2007, 0x202F, '\n', 0x180E, 0x2060, '\t'))) {
                postings.businessError(postings.decide(posting, "REQUEST_REVISION", comment, 2, hrToken),
                        "JOB_POSTING_COMMENT_REQUIRED", "comment",
                        "Vui lòng nhập nhận xét khi yêu cầu chỉnh sửa tin tuyển dụng.");
            }
        }
        assertThat(postings.row(id)).isEqualTo(rowBefore);
        assertThat(postings.events(id)).hasSize(1);

        clock.set(START.plusSeconds(60).plusNanos(555_555_555));
        JsonNode returned = ok(postings.decide(id, "REQUEST_REVISION", "Bổ sung quyền lợi.\nGhi rõ bảo hiểm.", 2,
                hrToken));
        assertThat(returned.path("status").asText()).isEqualTo("REVISION_REQUESTED");
        assertThat(returned.path("revision").asInt()).isEqualTo(3);
        JsonNode review = returned.path("lastReview");
        assertThat(review.path("action").asText()).isEqualTo("REVISION_REQUESTED");
        assertThat(review.path("comment").asText()).isEqualTo("Bổ sung quyền lợi.\nGhi rõ bảo hiểm.");
        assertThat(review.path("actorName").asText()).isEqualTo("Nguyễn Thị Hoa");
        assertThat(review.path("occurredAt").asText()).isEqualTo("2026-10-11T03:01:00.555555Z");
        assertActions(returned, true, true, false, false, false);
        assertActions(ok(postings.get(id, recruiterToken)), true, true, false, false, false);

        // The recruiter fixes the text, so HR stays eligible to approve.
        Map<String, Object> fix = postings.putBody(returned);
        fix.put("benefits", "Bảo hiểm sức khỏe và 13 tháng lương.");
        assertThat(ok(postings.update(id, fix, recruiterToken)).path("revision").asInt()).isEqualTo(4);
        ok(postings.submit(id, 4, "Đã bổ sung.", recruiterToken));
        assertThat(ok(postings.decide(id, "APPROVE", "Đạt.", 5, hrToken)).path("status").asText())
                .isEqualTo("APPROVED");
        // An approved posting is sent back too, as long as it is not published.
        JsonNode reopened = ok(postings.decide(id, "REQUEST_REVISION", "Đổi hạn nộp.", 6, adminToken));
        assertThat(reopened.path("status").asText()).isEqualTo("REVISION_REQUESTED");
        assertThat(reopened.path("lastReview").path("actorId").asText()).isEqualTo(adminId.toString());
        ok(postings.submit(id, 7, null, recruiterToken));
        JsonNode again = ok(postings.decide(id, "APPROVE", null, 8, hrToken));
        assertThat(again.path("status").asText()).isEqualTo("APPROVED");
        assertThat(again.path("revision").asInt()).isEqualTo(9);

        assertThat(steps(id)).containsExactly(
                new Step(2, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", recruiterId, null),
                new Step(3, "REVISION_REQUESTED", "PENDING_APPROVAL", "REVISION_REQUESTED", hrId,
                        "Bổ sung quyền lợi.\nGhi rõ bảo hiểm."),
                new Step(5, "SUBMITTED", "REVISION_REQUESTED", "PENDING_APPROVAL", recruiterId, "Đã bổ sung."),
                new Step(6, "APPROVED", "PENDING_APPROVAL", "APPROVED", hrId, "Đạt."),
                new Step(7, "REVISION_REQUESTED", "APPROVED", "REVISION_REQUESTED", adminId, "Đổi hạn nộp."),
                new Step(8, "SUBMITTED", "REVISION_REQUESTED", "PENDING_APPROVAL", recruiterId, null),
                new Step(9, "APPROVED", "PENDING_APPROVAL", "APPROVED", hrId, null));
        assertThat(postings.row(id).get("benefits")).isEqualTo("Bảo hiểm sức khỏe và 13 tháng lương.");
    }

    @Test
    void postingsUnderReviewOrPublishedAreFrozen() throws Exception {
        for (JobPostingStatus status : List.of(JobPostingStatus.PENDING_APPROVAL, JobPostingStatus.APPROVED,
                JobPostingStatus.PUBLISHED, JobPostingStatus.UNPUBLISHED, JobPostingStatus.EXPIRED)) {
            UUID id = postingIn(status);
            JsonNode view = ok(postings.get(id, adminToken));
            assertThat(view.path("actions").path("canEdit").asBoolean()).as(status.name()).isFalse();
            assertThat(view.path("actions").path("canSubmit").asBoolean()).as(status.name()).isFalse();
            Map<String, Object> rowBefore = postings.row(id);
            Map<String, Object> change = postings.putBody(view);
            change.put("title", "Bị sửa sau khi gửi duyệt");

            JsonNode edit = postings.error(postings.update(id, change, adminToken), 409, "JOB_POSTING_NOT_EDITABLE");
            JsonNode send = postings.error(postings.submit(id, view.path("revision").asInt(), null, adminToken), 409,
                    "JOB_POSTING_NOT_SUBMITTABLE");

            assertThat(edit.path("message").asText())
                    .isEqualTo("Chỉ sửa được tin tuyển dụng đang ở trạng thái nháp hoặc cần chỉnh sửa.");
            assertThat(send.path("message").asText())
                    .isEqualTo("Chỉ gửi duyệt được tin tuyển dụng đang ở trạng thái nháp hoặc cần chỉnh sửa.");
            assertThat(postings.row(id)).as(status.name()).isEqualTo(rowBefore);
            assertThat(postings.authorsOf(id)).containsExactly(adminId);
        }
    }

    @Test
    void decisionsOutsideTheirStatusesAreRefused() throws Exception {
        Map<JobPostingStatus, UUID> byStatus = new LinkedHashMap<>();
        for (JobPostingStatus status : JobPostingStatus.values()) {
            byStatus.put(status, postingIn(status));
        }
        List<Map<String, Object>> rowsBefore = jdbc.queryForList("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1");

        record Refused(JobPostingStatus status, String action) { }
        for (Refused refused : List.of(new Refused(JobPostingStatus.DRAFT, "APPROVE"),
                new Refused(JobPostingStatus.REVISION_REQUESTED, "APPROVE"),
                new Refused(JobPostingStatus.APPROVED, "APPROVE"), new Refused(JobPostingStatus.PUBLISHED, "APPROVE"),
                new Refused(JobPostingStatus.DRAFT, "REQUEST_REVISION"),
                new Refused(JobPostingStatus.REVISION_REQUESTED, "REQUEST_REVISION"),
                new Refused(JobPostingStatus.PUBLISHED, "REQUEST_REVISION"),
                // Task 355: a posting taken down is reviewed no more. Task 356: nor an expired one.
                new Refused(JobPostingStatus.UNPUBLISHED, "APPROVE"),
                new Refused(JobPostingStatus.UNPUBLISHED, "REQUEST_REVISION"),
                new Refused(JobPostingStatus.EXPIRED, "APPROVE"),
                new Refused(JobPostingStatus.EXPIRED, "REQUEST_REVISION"))) {
            UUID id = byStatus.get(refused.status());
            JsonNode error = postings.error(postings.decide(id, refused.action(), "Nhận xét", postings.revision(id),
                    hrToken), 409, "JOB_POSTING_NOT_UNDER_REVIEW");
            assertThat(error.path("message").asText()).as(refused.toString()).isEqualTo(NOT_UNDER_REVIEW);
        }
        // ADMIN created and sent the approved posting: the status answers before the rule that authors never
        // approve, so it is told to reload, not that it may not approve.
        UUID approved = byStatus.get(JobPostingStatus.APPROVED);
        postings.error(postings.decide(approved, "APPROVE", null, postings.revision(approved), adminToken), 409,
                "JOB_POSTING_NOT_UNDER_REVIEW");

        assertThat(jdbc.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1")).isEqualTo(eventsBefore);
    }

    @Test
    void staleRevisionsAreRefused() throws Exception {
        UUID draft = postingIn(JobPostingStatus.DRAFT);
        UUID pending = postingIn(JobPostingStatus.PENDING_APPROVAL);
        UUID approved = postingIn(JobPostingStatus.APPROVED);
        List<Map<String, Object>> rowsBefore = jdbc.queryForList("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1");

        List<HttpResponse<String>> stale = List.of(
                postings.submit(draft, 2, null, adminToken),
                postings.decide(pending, "APPROVE", null, 1, hrToken),
                // ADMIN wrote and sent "pending": a stale revision is told to reload before the rule that authors
                // never approve answers.
                postings.decide(pending, "APPROVE", null, 1, adminToken),
                postings.decide(pending, "REQUEST_REVISION", "Sửa lại", 3, hrToken),
                postings.publish(approved, 2, hrToken));

        for (var response : stale) {
            JsonNode error = postings.error(response, 409, "JOB_POSTING_CHANGED");
            assertThat(error.path("message").asText()).isEqualTo("Tin tuyển dụng đã thay đổi (có thể người khác vừa "
                    + "sửa hoặc xử lý). Vui lòng tải lại trước khi thao tác.");
        }
        assertThat(jdbc.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1")).isEqualTo(eventsBefore);
    }

    @Test
    void approveAndPublishRefuseAPassedDeadline() throws Exception {
        UUID onTheDay = postingIn(JobPostingStatus.PENDING_APPROVAL);
        UUID pending = postingIn(JobPostingStatus.PENDING_APPROVAL);
        UUID approved = postingIn(JobPostingStatus.APPROVED);
        // 23:59 on 30 Nov 2026 in Vietnam: the last day to apply is still today.
        clock.set(Instant.parse("2026-11-30T16:59:00Z"));
        String hr = api.token("hr@example.test");
        assertThat(ok(postings.decide(onTheDay, "APPROVE", null, 2, hr)).path("status").asText())
                .isEqualTo("APPROVED");

        // 00:00 on 1 Dec 2026 in Vietnam, still 30 Nov in UTC: candidates can no longer apply.
        clock.set(Instant.parse("2026-11-30T17:00:00Z"));
        Map<String, Object> pendingBefore = postings.row(pending);
        Map<String, Object> approvedBefore = postings.row(approved);
        JsonNode approval = postings.error(postings.decide(pending, "APPROVE", null, 2, hr), 409,
                "JOB_POSTING_DEADLINE_PASSED");
        JsonNode publication = postings.error(postings.publish(approved, 3, hr), 409, "JOB_POSTING_DEADLINE_PASSED");
        assertThat(approval.path("message").asText()).isEqualTo(DEADLINE_PASSED);
        assertThat(publication.path("message").asText()).isEqualTo(DEADLINE_PASSED);
        assertThat(postings.row(pending)).isEqualTo(pendingBefore);
        assertThat(postings.row(approved)).isEqualTo(approvedBefore);
        assertThat(postings.events(pending)).hasSize(1);
        assertThat(postings.events(approved)).hasSize(2);

        // HR cannot change the deadline of a frozen posting, so it sends both back to their authors.
        assertThat(ok(postings.decide(pending, "REQUEST_REVISION", "Đổi hạn nộp.", 2, hr)).path("status").asText())
                .isEqualTo("REVISION_REQUESTED");
        assertThat(ok(postings.decide(approved, "REQUEST_REVISION", "Đổi hạn nộp.", 3, hr)).path("status").asText())
                .isEqualTo("REVISION_REQUESTED");
    }

    // Task 290 pauses, fills and cancels requisitions; SQL moves it back here (closing postings is task 292).
    @Test
    void aRequisitionNoLongerApprovedBlocksEveryStep() throws Exception {
        UUID draft = postingIn(JobPostingStatus.DRAFT);
        UUID pending = postingIn(JobPostingStatus.PENDING_APPROVAL);
        UUID approved = postingIn(JobPostingStatus.APPROVED);
        JsonNode draftView = ok(postings.get(draft, adminToken));
        jdbc.update("UPDATE recruitment_requisitions SET status = ?", RequisitionStatus.PENDING_APPROVAL.name());
        List<Map<String, Object>> rowsBefore = jdbc.queryForList("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1");

        List<HttpResponse<String>> blocked = List.of(
                postings.update(draft, postings.putBody(draftView), adminToken),
                postings.submit(draft, 1, null, adminToken),
                postings.decide(pending, "APPROVE", null, 2, hrToken),
                postings.decide(pending, "REQUEST_REVISION", "Sửa lại", 2, hrToken),
                postings.publish(approved, 3, hrToken));

        for (var response : blocked) {
            assertThat(postings.error(response, 409, "JOB_POSTING_REQUISITION_NOT_APPROVED").path("message").asText())
                    .isEqualTo(NOT_APPROVED_REQUISITION);
        }
        // ADMIN wrote and sent "pending": the rule that authors never approve answers before the requisition.
        postings.forbidden(postings.decide(pending, "APPROVE", null, 2, adminToken));
        assertActions(ok(postings.get(draft, adminToken)), false, false, false, false, false);
        assertThat(jdbc.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1")).isEqualTo(eventsBefore);
    }

    // Task 290: the review buttons follow the requisition like the steps do, so a paused, filled or cancelled
    // requisition shows none. HR reads both postings: ADMIN wrote and sent them, so ADMIN may never approve them.
    @ParameterizedTest
    @EnumSource(RequisitionStatus.class)
    void reviewButtonsFollowTheRequisitionStatus(RequisitionStatus status) throws Exception {
        UUID pending = postingIn(JobPostingStatus.PENDING_APPROVAL);
        UUID approved = postingIn(JobPostingStatus.APPROVED);
        jdbc.update("UPDATE recruitment_requisitions SET status = ? WHERE id IN (?, ?)", status.name(),
                postings.row(pending).get("requisition_id"), postings.row(approved).get("requisition_id"));

        boolean source = switch (status) {
            case APPROVED -> true;
            case DRAFT, PENDING_APPROVAL, RETURNED, REJECTED, PAUSED, FILLED, CANCELLED -> false;
        };
        // The posting's own status still decides which step it is at; edit and submit stay off for both.
        assertActions(ok(postings.get(pending, hrToken)), false, false, source, source, false);
        assertActions(ok(postings.get(approved, hrToken)), false, false, false, source, source);
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void onlyJobPostingsWriteAllReviewsAndPublishes(Role role) throws Exception {
        UUID toApprove = postingIn(JobPostingStatus.PENDING_APPROVAL);
        UUID toReturn = postingIn(JobPostingStatus.PENDING_APPROVAL);
        UUID toPublish = postingIn(JobPostingStatus.APPROVED);
        UUID caller = account("role@example.test", "Vai trò " + role, role);
        if (role == Role.RECRUITER) {
            // Assigned to every requisition, so only the missing JOB_POSTINGS_WRITE_ALL refuses it.
            for (UUID posting : List.of(toApprove, toReturn, toPublish)) {
                postings.assign((UUID) postings.row(posting).get("requisition_id"), caller,
                        RequisitionRecruiterRole.SUPPORTING, hrId, START);
            }
        }
        String token = api.token("role@example.test");
        List<Map<String, Object>> rowsBefore = jdbc.queryForList("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1");

        var approval = postings.decide(toApprove, "APPROVE", null, 2, token);
        var revision = postings.decide(toReturn, "REQUEST_REVISION", "Sửa lại", 2, token);
        var publication = postings.publish(toPublish, 3, token);

        if (role == Role.ADMIN || role == Role.HR_MANAGER) {
            // A second ADMIN or HR manager, neither author nor submitter of these postings.
            assertThat(ok(approval).path("status").asText()).isEqualTo("APPROVED");
            assertThat(ok(revision).path("status").asText()).isEqualTo("REVISION_REQUESTED");
            assertThat(ok(publication).path("status").asText()).isEqualTo("PUBLISHED");
        } else {
            for (var response : List.of(approval, revision, publication)) {
                postings.forbidden(response);
            }
            assertThat(jdbc.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(rowsBefore);
            assertThat(jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1")).isEqualTo(eventsBefore);
        }
    }

    // Like a save (task 303): without the JOB_POSTINGS read permission the caller is refused before the posting is
    // looked up, so an unknown id is 403 like a known one, never 404, and tells it nothing about which ids exist.
    @Test
    void stepsWithoutTheReadPermissionAreRefusedBeforeTheLookup() throws Exception {
        UUID draft = id(postings.created(postings.body(requisition(), workLocation, employmentType), recruiterToken));
        UUID pending = postingIn(JobPostingStatus.PENDING_APPROVAL);
        UUID approved = postingIn(JobPostingStatus.APPROVED);
        List<Map<String, Object>> rowsBefore = jdbc.queryForList("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1");
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'RECRUITER' "
                + "AND permission_code = 'JOB_POSTINGS_READ_SCOPED'");
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' "
                + "AND permission_code = 'JOB_POSTINGS_READ_ALL'");
        try {
            for (UUID posting : List.of(draft, UUID.randomUUID())) {
                postings.forbidden(postings.submit(posting, 1, null, recruiterToken));
            }
            for (UUID posting : List.of(pending, UUID.randomUUID())) {
                postings.forbidden(postings.decide(posting, "APPROVE", null, 2, hrToken));
                postings.forbidden(postings.decide(posting, "REQUEST_REVISION", "Sửa lại", 2, hrToken));
            }
            for (UUID posting : List.of(approved, UUID.randomUUID())) {
                postings.forbidden(postings.publish(posting, 3, hrToken));
            }
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                    + "VALUES ('RECRUITER', 'JOB_POSTINGS_READ_SCOPED') ON CONFLICT DO NOTHING");
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                    + "VALUES ('HR_MANAGER', 'JOB_POSTINGS_READ_ALL') ON CONFLICT DO NOTHING");
        }
        assertThat(jdbc.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1")).isEqualTo(eventsBefore);
    }

    // A comment without any visible character counts as none and is stored as NULL, on a submission and on an
    // approval; before this rule a comment of only U+00A0 answered 500 (V22's CHECK) and one of only zero-width
    // characters was stored. A comment with a visible character is stored exactly as sent.
    @Test
    void aCommentWithoutAnyVisibleCharacterIsNoComment() throws Exception {
        String nonBreaking = codePoints(0x00A0);
        String zeroWidth = codePoints(0x200B, 0xFEFF);
        UUID id = id(postings.created(postings.body(requisition(), workLocation, employmentType), recruiterToken));

        assertThat(ok(postings.submit(id, 1, nonBreaking, recruiterToken)).path("status").asText())
                .isEqualTo("PENDING_APPROVAL");
        JsonNode approved = ok(postings.decide(id, "APPROVE", zeroWidth, 2, hrToken));
        assertThat(approved.path("lastReview").path("comment").isNull()).isTrue();
        String visible = nonBreaking + "Bổ sung quyền lợi.\nGhi rõ bảo hiểm." + zeroWidth;
        JsonNode returned = ok(postings.decide(id, "REQUEST_REVISION", visible, 3, hrToken));
        assertThat(returned.path("lastReview").path("comment").asText()).isEqualTo(visible);
        ok(postings.submit(id, 4, codePoints(0x2007, 0x202F, '\n', 0x0085, 0x180E, 0x2060, '\t'), recruiterToken));

        assertThat(steps(id)).containsExactly(
                new Step(2, "SUBMITTED", "DRAFT", "PENDING_APPROVAL", recruiterId, null),
                new Step(3, "APPROVED", "PENDING_APPROVAL", "APPROVED", hrId, null),
                new Step(4, "REVISION_REQUESTED", "APPROVED", "REVISION_REQUESTED", hrId, visible),
                new Step(5, "SUBMITTED", "REVISION_REQUESTED", "PENDING_APPROVAL", recruiterId, null));
    }

    // HR's review queue is GET /job-postings?status=PENDING_APPROVAL (job-posting-publication.md): the list keeps only
    // the status asked for, shows the newest created posting first whatever was saved later, and pages that order.
    @Test
    void theListFiltersByStatusAndShowsTheNewestCreatedFirst() throws Exception {
        UUID draft = postingIn(JobPostingStatus.DRAFT);
        clock.set(START.plusSeconds(60));
        UUID pending = postingIn(JobPostingStatus.PENDING_APPROVAL);
        clock.set(START.plusSeconds(120));
        UUID published = postingIn(JobPostingStatus.PUBLISHED);
        // A save of the oldest posting does not move it up.
        clock.set(START.plusSeconds(180));
        Map<String, Object> change = postings.putBody(ok(postings.get(draft, adminToken)));
        change.put("title", "Lập trình viên Java");
        ok(postings.update(draft, change, adminToken));

        // HR, and the recruiter assigned to every requisition here.
        for (String token : List.of(hrToken, recruiterToken)) {
            JsonNode all = ok(postings.list("", token));
            assertThat(listedIds(all)).containsExactly(published, pending, draft);
            assertThat(all.path("totalElements").asLong()).isEqualTo(3);
            JsonNode queue = ok(postings.list("?status=PENDING_APPROVAL", token));
            assertThat(listedIds(queue)).containsExactly(pending);
            assertThat(queue.path("totalElements").asLong()).isOne();
            assertThat(listedIds(ok(postings.list("?status=DRAFT", token)))).containsExactly(draft);
            assertThat(listedIds(ok(postings.list("?status=PUBLISHED", token)))).containsExactly(published);
            JsonNode none = ok(postings.list("?status=APPROVED", token));
            assertThat(none.path("items").isEmpty()).isTrue();
            assertThat(none.path("totalElements").asLong()).isZero();
            JsonNode second = ok(postings.list("?page=1&size=1", token));
            assertThat(listedIds(second)).containsExactly(pending);
            assertThat(second.path("totalElements").asLong()).isEqualTo(3);
            assertThat(second.path("totalPages").asLong()).isEqualTo(3);
        }
        JsonNode item = ok(postings.list("?status=DRAFT", hrToken)).path("items").path(0);
        // Task 310 added publishedAt: null until the posting is published.
        assertThat(fieldNames(item)).containsExactly("id", "requisitionId", "title", "positionName", "departmentName",
                "status", "revision", "applicationDeadline", "createdByName", "createdAt", "updatedAt", "publishedAt");
        assertThat(item.path("requisitionId").asText()).isEqualTo(postings.row(draft).get("requisition_id").toString());
        assertThat(item.path("title").asText()).isEqualTo("Lập trình viên Java");
        assertThat(item.path("positionName").asText()).isEqualTo("Lập trình viên");
        assertThat(item.path("departmentName").asText()).isEqualTo("Phòng IT");
        assertThat(item.path("status").asText()).isEqualTo("DRAFT");
        assertThat(item.path("revision").asInt()).isEqualTo(2);
        assertThat(item.path("applicationDeadline").asText()).isEqualTo(JobPostingTestSupport.DEADLINE);
        assertThat(item.path("createdByName").asText()).isEqualTo(jdbc.queryForObject(
                "SELECT full_name FROM user_accounts WHERE id = ?", String.class, adminId));
        assertThat(Instant.parse(item.path("createdAt").asText())).isEqualTo(START);
        assertThat(Instant.parse(item.path("updatedAt").asText())).isEqualTo(START.plusSeconds(180));
        assertThat(item.path("publishedAt").isNull()).isTrue();
        JsonNode publishedItem = ok(postings.list("?status=PUBLISHED", hrToken)).path("items").path(0);
        assertThat(Instant.parse(publishedItem.path("publishedAt").asText())).isEqualTo(START.plusSeconds(120));
    }

    // The review actions need JOB_POSTINGS_WRITE_ALL, like the steps (onlyJobPostingsWriteAllReviewsAndPublishes): the
    // recruiter on the team never wrote, edited or sent these postings (ADMIN did), so only the missing permission
    // keeps canApprove, canRequestRevision and canPublish false.
    @Test
    void aRecruiterOnTheTeamIsNeverOfferedTheReviewSteps() throws Exception {
        UUID pending = postingIn(JobPostingStatus.PENDING_APPROVAL);
        UUID approved = postingIn(JobPostingStatus.APPROVED);
        for (UUID id : List.of(pending, approved)) {
            assertThat(postings.authorsOf(id)).containsExactly(adminId);
            assertThat(postings.events(id).getFirst().get("actor_id")).isEqualTo(adminId);
            assertActions(ok(postings.get(id, recruiterToken)), false, false, false, false, false);
        }
        // HR, neither an author nor the submitter, is offered them.
        assertActions(ok(postings.get(pending, hrToken)), false, false, true, true, false);
        assertActions(ok(postings.get(approved, hrToken)), false, false, false, true, true);
    }

    @Test
    void clientOwnedWorkflowFieldsAreInvalidJson() throws Exception {
        UUID draft = postingIn(JobPostingStatus.DRAFT);
        UUID pending = postingIn(JobPostingStatus.PENDING_APPROVAL);
        UUID approved = postingIn(JobPostingStatus.APPROVED);
        List<Map<String, Object>> rowsBefore = jdbc.queryForList("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1");
        Map<String, Object> clientOwned = new LinkedHashMap<>();
        clientOwned.put("status", "PUBLISHED");
        clientOwned.put("note", "Ghi chú");
        clientOwned.put("reason", "Không phù hợp");
        clientOwned.put("feedback", "Sửa lại");
        clientOwned.put("channels", List.of("CAREER_PAGE"));
        clientOwned.put("publishedAt", "2026-10-11T03:00:00Z");
        clientOwned.put("approvedAt", "2026-10-11T03:00:00Z");

        for (var field : clientOwned.entrySet()) {
            Map<String, Object> submit = new LinkedHashMap<>(Map.of("expectedRevision", 1));
            Map<String, Object> decide = new LinkedHashMap<>(Map.of("action", "APPROVE", "expectedRevision", 2));
            Map<String, Object> publish = new LinkedHashMap<>(Map.of("expectedRevision", 3));
            for (var body : List.of(submit, decide, publish)) {
                body.put(field.getKey(), field.getValue());
            }
            postings.error(api.request("POST", JobPostingTestSupport.POSTINGS + "/" + draft + "/submit",
                    json.writeValueAsString(submit), adminToken), 400, "INVALID_JSON");
            postings.error(api.request("POST", JobPostingTestSupport.POSTINGS + "/" + pending + "/approvals",
                    json.writeValueAsString(decide), hrToken), 400, "INVALID_JSON");
            postings.error(api.request("POST", JobPostingTestSupport.POSTINGS + "/" + approved + "/publish",
                    json.writeValueAsString(publish), hrToken), 400, "INVALID_JSON");
        }
        assertThat(jdbc.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1")).isEqualTo(eventsBefore);
    }

    // One step of the history, without its id and time.
    private record Step(int revision, String type, String before, String after, UUID actor, String comment) { }

    private List<Step> steps(UUID posting) {
        List<Step> steps = new ArrayList<>();
        for (var row : postings.events(posting)) {
            steps.add(new Step((Integer) row.get("revision"), (String) row.get("event_type"),
                    (String) row.get("status_before"), (String) row.get("status_after"), (UUID) row.get("actor_id"),
                    (String) row.get("comment")));
        }
        return steps;
    }

    // HR may not approve: 403, and nothing changes in the postings, their history or their authors.
    private void refusedApproval(UUID posting, int revision) throws Exception {
        List<Map<String, Object>> rowsBefore = jdbc.queryForList("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1");
        List<Map<String, Object>> authorsBefore = jdbc.queryForList("SELECT * FROM job_posting_authors ORDER BY 1, 2");

        postings.forbidden(postings.decide(posting, "APPROVE", null, revision, hrToken));

        assertThat(jdbc.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1")).isEqualTo(eventsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_authors ORDER BY 1, 2")).isEqualTo(authorsBefore);
    }

    // A complete posting brought to the status through the API: ADMIN writes and sends it (revisions 1, 2), HR
    // approves it or asks for changes (3) and publishes it (4). Task 292: a closed posting is published, then HR fills
    // its requisition (task 290), which closes it (5). Task 355: a posting taken down is published, then HR takes it
    // down (5). Task 356: an expired posting is published, then the daily task expires it (5) at 00:00 after its
    // deadline; the clock then goes back, and the posting stays EXPIRED.
    private UUID postingIn(JobPostingStatus status) throws Exception {
        UUID requisition = requisition();
        UUID id = id(postings.created(postings.body(requisition, workLocation, employmentType), adminToken));
        if (status != JobPostingStatus.DRAFT) {
            ok(postings.submit(id, 1, null, adminToken));
        }
        if (status == JobPostingStatus.REVISION_REQUESTED) {
            ok(postings.decide(id, "REQUEST_REVISION", "Bổ sung quyền lợi.", 2, hrToken));
        }
        if (status == JobPostingStatus.APPROVED || status == JobPostingStatus.PUBLISHED
                || status == JobPostingStatus.CLOSED || status == JobPostingStatus.UNPUBLISHED
                || status == JobPostingStatus.EXPIRED) {
            ok(postings.decide(id, "APPROVE", null, 2, hrToken));
        }
        if (status == JobPostingStatus.PUBLISHED || status == JobPostingStatus.CLOSED
                || status == JobPostingStatus.UNPUBLISHED || status == JobPostingStatus.EXPIRED) {
            ok(postings.publish(id, 3, hrToken));
        }
        if (status == JobPostingStatus.CLOSED) {
            ok(api.request("POST", "/api/v1/requisitions/" + requisition + "/status-changes", json.writeValueAsString(
                    Map.of("status", "FILLED", "reason", "Đã tuyển đủ", "expectedRevision", 0)), hrToken));
        }
        if (status == JobPostingStatus.UNPUBLISHED) {
            ok(postings.unpublish(id, 4, "Đã nhận đủ hồ sơ.", hrToken));
        }
        if (status == JobPostingStatus.EXPIRED) {
            Instant now = clock.instant();
            clock.set(Instant.parse(JobPostingTestSupport.CLOSES_AT));
            assertThat(expiry.expire(id)).isTrue();
            clock.set(now);
        }
        assertThat(postings.row(id).get("status")).isEqualTo(status.name());
        return id;
    }

    // An approved requisition the recruiter is the primary recruiter of.
    private UUID requisition() {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        postings.assign(requisition, recruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
        return requisition;
    }

    // Every flag of a posting that cannot be taken down (task 355: canUnpublish false).
    private void assertActions(JsonNode view, boolean canEdit, boolean canSubmit, boolean canApprove,
                               boolean canRequestRevision, boolean canPublish) {
        assertActions(view, canEdit, canSubmit, canApprove, canRequestRevision, canPublish, false);
    }

    private void assertActions(JsonNode view, boolean canEdit, boolean canSubmit, boolean canApprove,
                               boolean canRequestRevision, boolean canPublish, boolean canUnpublish) {
        JsonNode actions = view.path("actions");
        assertThat(fieldNames(actions)).containsExactly("canEdit", "canSubmit", "canApprove", "canRequestRevision",
                "canPublish", "canUnpublish");
        assertThat(List.of(actions.path("canEdit").asBoolean(), actions.path("canSubmit").asBoolean(),
                actions.path("canApprove").asBoolean(), actions.path("canRequestRevision").asBoolean(),
                actions.path("canPublish").asBoolean(), actions.path("canUnpublish").asBoolean()))
                .as(actions.toString())
                .containsExactly(canEdit, canSubmit, canApprove, canRequestRevision, canPublish, canUnpublish);
    }

    private JsonNode ok(HttpResponse<String> response) {
        return api.expect(response, 200);
    }

    private static UUID id(JsonNode view) {
        return UUID.fromString(view.path("id").asText());
    }

    private UUID account(String email, String fullName, Role role) {
        return accounts.saveAndFlush(new Account(email, fullName, passwordHash, Set.of(role), START)).getId();
    }

    private static List<String> fieldNames(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }

    private static List<UUID> listedIds(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(UUID.fromString(item.path("id").asText())));
        return ids;
    }

    // Special characters are built from their code points, so the source shows which ones they are.
    private static String codePoints(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }
}
