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
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.auth.JobPostingTestSupport.PUBLIC_POSTINGS;
import static vn.ttcs.recruitment.auth.JobPostingTestSupport.withoutApplicationState;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.EMPLOYMENT_TYPE;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.WORK_LOCATION;

// Task 309 (story S3-10): "Dùng chung nội dung cho xem trước và tin công khai". The preview of a posting and the public
// posting are built by one builder from one row query, so HR approves and publishes exactly what candidates then see.
// Candidates read only published postings whose deadline has not passed and whose requisition is still approved,
// without an account; every other posting answers 404 like an id that does not exist.
// Task 320 (story S4-02): "Xây dựng API chi tiết tin tuyển dụng công khai". The public detail is the preview's content
// plus whether candidates may still apply (applicationOpen) and when applications close (applicationClosesAt). A
// published posting whose deadline has passed leaves the list but its detail still opens, with applicationOpen false.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobPostingPublicViewIntegrationTest {
    // 10:00 on 11 Oct 2026 in Vietnam. Every posting made here may be applied to until 30 Nov 2026.
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");
    private static final String TITLE = "Lập trình viên Java (Hà Nội)";
    private static final String BENEFITS = "Bảo hiểm đầy đủ.\nThưởng tháng 13.";
    // The eleven keys of a public posting whose salary is hidden, in this order; a shown salary adds two.
    private static final List<String> PUBLIC_KEYS = List.of("id", "title", "positionName", "departmentName",
            "headcount", "workLocationName", "employmentTypeName", "applicationDeadline", "jobDescription",
            "candidateRequirements", "benefits");
    private static final List<String> PUBLIC_KEYS_WITH_SALARY = List.of("id", "title", "positionName",
            "departmentName", "headcount", "workLocationName", "employmentTypeName", "applicationDeadline",
            "salaryMin", "salaryMax", "jobDescription", "candidateRequirements", "benefits");
    private static final List<String> LIST_ITEM_KEYS = List.of("id", "title", "positionName", "departmentName",
            "workLocationName", "employmentTypeName", "applicationDeadline");
    // Task 316: a list item of a posting that shows its salary, in this order.
    private static final List<String> LIST_ITEM_KEYS_WITH_SALARY = List.of("id", "title", "positionName",
            "departmentName", "workLocationName", "employmentTypeName", "applicationDeadline", "salaryMin",
            "salaryMax");
    // Task 320: the public detail is the public posting followed by the application state.
    private static final List<String> DETAIL_KEYS = withApplicationState(PUBLIC_KEYS);
    private static final List<String> DETAIL_KEYS_WITH_SALARY = withApplicationState(PUBLIC_KEYS_WITH_SALARY);

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JobPostingExpiry expiry;
    @Autowired private List<ScheduledTaskHolder> schedules;

    private JobPostingTestSupport postings;
    private ApprovalTestSupport api;
    private String passwordHash;
    private UUID adminId;
    private String adminToken;
    private UUID hrId;
    private String hrToken;
    private UUID headId;
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
        headId = account("head@example.test", "Trưởng phòng IT", Role.HIRING_MANAGER);
        recruiterId = account("recruiter@example.test", "Trần Văn Tuyển", Role.RECRUITER);
        recruiterToken = api.token("recruiter@example.test");
        department = api.department("IT", headId, null, START);
        position = api.position(START);
        workLocation = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, START);
        employmentType = postings.catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", true, START);
    }

    // S3-10 AC2: "Xem trước đúng như giao diện công khai trước khi xuất bản".
    @Test
    void theApprovedPreviewIsExactlyWhatCandidatesSeeOncePublished() throws Exception {
        UUID id = draft(false);
        ok(postings.submit(id, 1, null, recruiterToken));

        // HR previews the posting it is asked to approve.
        var previewing = postings.preview(id, hrToken);
        JsonNode underReview = ok(previewing);
        postings.noStore(previewing);
        assertThat(fieldNames(underReview)).containsExactly("id", "status", "revision", "content");
        assertThat(underReview.path("id").asText()).isEqualTo(id.toString());
        assertThat(underReview.path("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(underReview.path("revision").asInt()).isEqualTo(2);
        JsonNode content = underReview.path("content");
        assertThat(fieldNames(content)).containsExactlyElementsOf(PUBLIC_KEYS);
        assertThat(content.path("id").asText()).isEqualTo(id.toString());
        assertThat(content.path("title").asText()).isEqualTo(TITLE);
        assertThat(content.path("positionName").asText()).isEqualTo("Lập trình viên");
        assertThat(content.path("departmentName").asText()).isEqualTo("Phòng IT");
        assertThat(content.path("headcount").asInt()).isEqualTo(2);
        assertThat(content.path("workLocationName").asText()).isEqualTo("Hà Nội");
        assertThat(content.path("employmentTypeName").asText()).isEqualTo("Toàn thời gian");
        assertThat(content.path("applicationDeadline").asText()).isEqualTo(JobPostingTestSupport.DEADLINE);
        assertThat(content.path("jobDescription").asText()).isEqualTo(JobPostingTestSupport.JOB_DESCRIPTION);
        assertThat(content.path("candidateRequirements").asText()).isEqualTo(JobPostingTestSupport.REQUIREMENTS);
        assertThat(content.path("benefits").asText()).isEqualTo(BENEFITS);
        postings.error(postings.publicDetail(id), 404, "JOB_POSTING_NOT_FOUND");

        // HR approves the revision it previewed; approving changes the status, never the content.
        ok(postings.decide(id, "APPROVE", null, underReview.path("revision").asInt(), hrToken));
        JsonNode approved = ok(postings.preview(id, hrToken));
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("revision").asInt()).isEqualTo(3);
        assertThat(approved.path("content")).isEqualTo(content);
        // The assigned recruiter previews exactly the same thing.
        assertThat(ok(postings.preview(id, recruiterToken))).isEqualTo(approved);
        // Approved is not public yet.
        postings.error(postings.publicDetail(id), 404, "JOB_POSTING_NOT_FOUND");
        assertThat(ok(postings.publicList(0, 20)).path("items").isEmpty()).isTrue();

        ok(postings.publish(id, approved.path("revision").asInt(), hrToken));

        // No organization value is renamed in between (see aRenamedOrganizationValueChangesPreviewAndPublicTogether),
        // so the public posting is byte for byte the preview HR approved. Task 320: the detail adds that candidates
        // may still apply, until 00:00 on 1 Dec 2026 in Vietnam.
        var reading = postings.publicDetail(id);
        JsonNode published = ok(reading);
        postings.noStore(reading);
        assertThat(fieldNames(published)).containsExactlyElementsOf(DETAIL_KEYS);
        assertThat(withoutApplicationState(published)).isEqualTo(approved.path("content"));
        JobPostingTestSupport.assertApplicationState(published, true);
        assertThat(ok(postings.preview(id, hrToken)).path("content")).isEqualTo(withoutApplicationState(published));
        // The list item is the same posting without its texts.
        JsonNode item = onlyItem(ok(postings.publicList(0, 20)));
        assertThat(fieldNames(item)).containsExactlyElementsOf(LIST_ITEM_KEYS);
        for (String key : LIST_ITEM_KEYS) {
            assertThat(item.path(key)).as(key).isEqualTo(published.path(key));
        }
    }

    // HR approves and publishes with the revision of the preview it looked at, so a change made after the preview is
    // refused instead of being approved or published unseen.
    @Test
    void approvingWithThePreviewRevisionBindsTheContent() throws Exception {
        UUID id = draft(false);
        ok(postings.submit(id, 1, null, recruiterToken));
        JsonNode seen = ok(postings.preview(id, hrToken));
        // While HR looks at it, ADMIN sends it back, and the recruiter rewrites it and sends it again.
        ok(postings.decide(id, "REQUEST_REVISION", "Bổ sung quyền lợi.", 2, adminToken));
        Map<String, Object> change = postings.putBody(ok(postings.get(id, recruiterToken)));
        change.put("benefits", "Bảo hiểm đầy đủ, 13 tháng lương.");
        ok(postings.update(id, change, recruiterToken));
        ok(postings.submit(id, 4, null, recruiterToken));
        Map<String, Object> rowBefore = postings.row(id);
        List<Map<String, Object>> eventsBefore = postings.events(id);

        postings.error(postings.decide(id, "APPROVE", null, seen.path("revision").asInt(), hrToken), 409,
                "JOB_POSTING_CHANGED");

        assertThat(postings.row(id)).isEqualTo(rowBefore);
        assertThat(postings.events(id)).isEqualTo(eventsBefore);
        JsonNode current = ok(postings.preview(id, hrToken));
        assertThat(current.path("revision").asInt()).isEqualTo(5);
        assertThat(current.path("content").path("benefits").asText()).isEqualTo("Bảo hiểm đầy đủ, 13 tháng lương.");
        assertThat(current.path("content")).isNotEqualTo(seen.path("content"));
        ok(postings.decide(id, "APPROVE", null, current.path("revision").asInt(), hrToken));

        // The same for the publication: HR previews the approved posting, then it is sent back, changed and approved
        // again before HR publishes.
        JsonNode approved = ok(postings.preview(id, hrToken));
        assertThat(approved.path("revision").asInt()).isEqualTo(6);
        ok(postings.decide(id, "REQUEST_REVISION", "Đổi hạn nộp.", 6, adminToken));
        change = postings.putBody(ok(postings.get(id, recruiterToken)));
        change.put("applicationDeadline", "2026-12-15");
        ok(postings.update(id, change, recruiterToken));
        ok(postings.submit(id, 8, null, recruiterToken));
        ok(postings.decide(id, "APPROVE", null, 9, adminToken));
        rowBefore = postings.row(id);
        eventsBefore = postings.events(id);

        postings.error(postings.publish(id, approved.path("revision").asInt(), hrToken), 409, "JOB_POSTING_CHANGED");

        assertThat(postings.row(id)).isEqualTo(rowBefore);
        assertThat(postings.events(id)).isEqualTo(eventsBefore);
        postings.error(postings.publicDetail(id), 404, "JOB_POSTING_NOT_FOUND");
        JsonNode latest = ok(postings.preview(id, hrToken));
        assertThat(latest.path("revision").asInt()).isEqualTo(10);
        ok(postings.publish(id, latest.path("revision").asInt(), hrToken));
        JsonNode published = ok(postings.publicDetail(id));
        assertThat(withoutApplicationState(published)).isEqualTo(latest.path("content"));
        assertThat(published.path("applicationDeadline").asText()).isEqualTo("2026-12-15");
        assertThat(published.path("applicationOpen").asBoolean()).isTrue();
        assertThat(published.path("applicationClosesAt").asText()).isEqualTo("2026-12-15T17:00:00Z");
    }

    @Test
    void theSalaryIsPublicOnlyWhenChosen() throws Exception {
        UUID hidden = published(false);

        var hiddenPreview = postings.preview(hidden, hrToken);
        var hiddenDetail = postings.publicDetail(hidden);
        var hiddenList = postings.publicList(0, 20);

        // The keys are left out, not sent as null, and the amounts are not anywhere in the answers.
        for (var response : List.of(hiddenPreview, hiddenDetail, hiddenList)) {
            assertThat(response.body()).doesNotContain("salary", "20000000", "22000000");
        }
        assertThat(fieldNames(ok(hiddenPreview).path("content"))).containsExactlyElementsOf(PUBLIC_KEYS);
        assertThat(fieldNames(ok(hiddenDetail))).containsExactlyElementsOf(DETAIL_KEYS);
        assertThat(fieldNames(onlyItem(ok(hiddenList)))).containsExactlyElementsOf(LIST_ITEM_KEYS);
        // The authors still see the approved proposal in their own view, to know what showSalary would publish.
        JsonNode internal = ok(postings.get(hidden, recruiterToken));
        assertThat(internal.path("showSalary").asBoolean()).isFalse();
        assertThat(internal.path("salaryMin").asLong()).isEqualTo(20_000_000L);
        assertThat(internal.path("salaryMax").asLong()).isEqualTo(22_000_000L);

        UUID shown = published(true);
        Map<String, Object> requisition = postings.requisitionRow((UUID) postings.row(shown).get("requisition_id"));

        JsonNode shownPreview = ok(postings.preview(shown, hrToken)).path("content");
        JsonNode shownDetail = ok(postings.publicDetail(shown));
        assertThat(withoutApplicationState(shownDetail)).isEqualTo(shownPreview);
        JobPostingTestSupport.assertApplicationState(shownDetail, true);
        assertThat(fieldNames(shownDetail)).containsExactlyElementsOf(DETAIL_KEYS_WITH_SALARY);
        // The requisition's approved proposal, whole VND per person and month.
        assertThat(shownDetail.path("salaryMin").asLong())
                .isEqualTo(((Number) requisition.get("proposed_salary_min")).longValue()).isEqualTo(20_000_000L);
        assertThat(shownDetail.path("salaryMax").asLong())
                .isEqualTo(((Number) requisition.get("proposed_salary_max")).longValue()).isEqualTo(22_000_000L);
        JsonNode list = ok(postings.publicList(0, 20));
        JsonNode shownItem = item(list, shown);
        assertThat(fieldNames(shownItem)).containsExactlyElementsOf(LIST_ITEM_KEYS_WITH_SALARY);
        assertThat(shownItem.path("salaryMin").asLong()).isEqualTo(20_000_000L);
        assertThat(shownItem.path("salaryMax").asLong()).isEqualTo(22_000_000L);
        assertThat(fieldNames(item(list, hidden))).containsExactlyElementsOf(LIST_ITEM_KEYS);
    }

    // Task 356: the daily job posting expiry never starts by itself in a test, which moves the clock as it needs: the
    // test settings (src/test/resources/application-default.properties) turn its cron off, so no task is scheduled
    // although scheduling is on. Tests run the expiry themselves (JobPostingExpiryIntegrationTest).
    @Test
    void theDailyExpiryIsNeverScheduledInTests() {
        assertThat(environment.getProperty("app.job-posting-expiry.cron")).isEqualTo("-");
        assertThat(schedules).isNotEmpty();
        for (ScheduledTaskHolder holder : schedules) {
            assertThat(holder.getScheduledTasks()).isEmpty();
        }
    }

    // S3-10 AC1: only a published posting reaches candidates; a draft, a posting under review or an approved one that
    // is not published yet answers exactly like an id that does not exist. Task 292: so does a closed one.
    @ParameterizedTest
    @EnumSource(JobPostingStatus.class)
    void onlyLivePublishedPostingsArePublic(JobPostingStatus status) throws Exception {
        UUID id = postingIn(status);
        JsonNode unknown = postings.error(postings.publicDetail(UUID.randomUUID()), 404, "JOB_POSTING_NOT_FOUND");
        assertThat(unknown.path("message").asText()).isEqualTo("Không tìm thấy tin tuyển dụng.");

        var detail = postings.publicDetail(id);
        JsonNode list = ok(postings.publicList(0, 20));

        // Whether the detail opens with applications open (listed too) or with the closed notice; null: 404.
        Boolean applicationOpen = switch (status) {
            case PUBLISHED -> true;
            // Task 356: a posting the daily task expired keeps its detail with the closed notice, outside the list.
            case EXPIRED -> false;
            // Task 355: a posting taken down is never public again.
            case DRAFT, PENDING_APPROVAL, REVISION_REQUESTED, APPROVED, CLOSED, UNPUBLISHED -> null;
        };
        if (applicationOpen != null) {
            assertThat(ok(detail).path("id").asText()).isEqualTo(id.toString());
            // Task 320: before its deadline a published posting takes applications.
            JobPostingTestSupport.assertApplicationState(ok(detail), applicationOpen);
            assertThat(ids(list)).isEqualTo(applicationOpen ? List.of(id) : List.of());
            assertThat(list.path("totalElements").asLong()).isEqualTo(applicationOpen ? 1 : 0);
        } else {
            assertThat(postings.error(detail, 404, "JOB_POSTING_NOT_FOUND")).isEqualTo(unknown);
            assertThat(ids(list)).isEmpty();
            assertThat(list.path("totalElements").asLong()).isZero();
        }
        // The preview shows every status to the authors and reviewers.
        assertThat(ok(postings.preview(id, hrToken)).path("status").asText()).isEqualTo(status.name());
    }

    // A published posting leaves the list once its last day to apply has passed in Vietnam, and leaves the portal
    // once its requisition is no longer approved. It stays PUBLISHED, and its authors still preview it.
    // Task 320 (story S4-02): past its deadline the detail still opens with the same content and applicationOpen
    // false, so a candidate who follows a link sees a notice instead of the apply form. A posting whose requisition is
    // no longer approved still answers 404 like an id that does not exist.
    @Test
    void aPublishedPostingLeavesTheListAfterItsDeadlineAndItsDetailSaysItExpired() throws Exception {
        UUID expiring = published(false);
        UUID withdrawn = published(false);
        assertThat(ids(ok(postings.publicList(0, 20)))).containsExactlyInAnyOrder(expiring, withdrawn);
        JsonNode unknown = postings.error(postings.publicDetail(UUID.randomUUID()), 404, "JOB_POSTING_NOT_FOUND");

        jdbc.update("UPDATE recruitment_requisitions SET status = ? WHERE id = ?",
                RequisitionStatus.PENDING_APPROVAL.name(), postings.row(withdrawn).get("requisition_id"));

        assertThat(postings.error(postings.publicDetail(withdrawn), 404, "JOB_POSTING_NOT_FOUND")).isEqualTo(unknown);
        JsonNode remaining = ok(postings.publicList(0, 20));
        assertThat(ids(remaining)).containsExactly(expiring);
        assertThat(remaining.path("totalElements").asLong()).isEqualTo(1);
        assertThat(postings.row(withdrawn).get("status")).isEqualTo("PUBLISHED");
        assertThat(ok(postings.preview(withdrawn, hrToken)).path("status").asText()).isEqualTo("PUBLISHED");

        // 23:59:59 on 30 Nov 2026 in Vietnam (still 30 Nov in UTC too): the deadline day itself is open.
        clock.set(Instant.parse("2026-11-30T16:59:59Z"));
        var lastDay = postings.publicDetail(expiring);
        postings.noStore(lastDay);
        JsonNode open = ok(lastDay);
        assertThat(fieldNames(open)).containsExactlyElementsOf(DETAIL_KEYS);
        JobPostingTestSupport.assertApplicationState(open, true);
        assertThat(ids(ok(postings.publicList(0, 20)))).containsExactly(expiring);

        // 00:00 on 1 Dec 2026 in Vietnam, while it is still 30 Nov in UTC: the deadline has passed. The posting has
        // left the list and its count, but its detail still opens: the same content, no longer taking applications,
        // and still no status name.
        clock.set(Instant.parse("2026-11-30T17:00:00Z"));
        var nextDay = postings.publicDetail(expiring);
        postings.noStore(nextDay);
        JsonNode expired = ok(nextDay);
        assertThat(fieldNames(expired)).containsExactlyElementsOf(DETAIL_KEYS);
        JobPostingTestSupport.assertApplicationState(expired, false);
        assertThat(withoutApplicationState(expired)).isEqualTo(withoutApplicationState(open));
        assertThat(nextDay.body()).doesNotContain("\"status\"", "PUBLISHED", "EXPIRED");
        JsonNode none = ok(postings.publicList(0, 20));
        assertThat(ids(none)).isEmpty();
        assertThat(none.path("totalElements").asLong()).isZero();
        // The notice follows the date: the posting is still PUBLISHED, and its authors still preview the same content.
        assertThat(postings.row(expiring).get("status")).isEqualTo("PUBLISHED");
        String laterToken = api.token("hr@example.test");
        JsonNode preview = ok(postings.preview(expiring, laterToken));
        assertThat(preview.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(preview.path("content")).isEqualTo(withoutApplicationState(expired));
        assertThat(preview.path("content").path("applicationDeadline").asText())
                .isEqualTo(JobPostingTestSupport.DEADLINE);
        // Past its deadline too, the posting of a requisition that is no longer approved shows nothing.
        assertThat(postings.error(postings.publicDetail(withdrawn), 404, "JOB_POSTING_NOT_FOUND")).isEqualTo(unknown);
    }

    // Task 314 (story S4-01): "Là Ứng viên, tôi muốn xem danh sách việc làm đang tuyển trên trang công khai". Task 309
    // already lists only open postings without an account (AC1, AC4); this pins it on one mixed set. Open means
    // published, the application deadline is today or later in Vietnam (the deadline day included) and the
    // requisition is still approved. The page, its count and its number of pages leave out everything else: drafts,
    // postings under review, sent back or approved but not published, closed with their requisition (task 292), of a
    // paused requisition (task 290) and past their deadline. Only the list is asserted here.
    @Test
    void theAnonymousListShowsOnlyOpenPostingsAmongEveryOtherKind() throws Exception {
        UUID open = published(false);
        clock.set(START.plusSeconds(10));
        Map<String, Object> body = postings.body(requisition(), workLocation, employmentType);
        body.put("title", TITLE);
        body.put("applicationDeadline", "2026-10-20");
        UUID early = id(postings.created(body, recruiterToken));
        ok(postings.submit(early, 1, null, recruiterToken));
        ok(postings.decide(early, "APPROVE", null, 2, hrToken));
        ok(postings.publish(early, 3, hrToken));
        // Published after both, so each would come first if it were listed.
        clock.set(START.plusSeconds(20));
        UUID paused = published(false);
        jdbc.update("UPDATE recruitment_requisitions SET status = ? WHERE id = ?", RequisitionStatus.PAUSED.name(),
                postings.row(paused).get("requisition_id"));
        clock.set(START.plusSeconds(30));
        List<UUID> hidden = new ArrayList<>(List.of(paused));
        for (JobPostingStatus status : List.of(JobPostingStatus.DRAFT, JobPostingStatus.PENDING_APPROVAL,
                JobPostingStatus.REVISION_REQUESTED, JobPostingStatus.APPROVED, JobPostingStatus.CLOSED)) {
            hidden.add(postingIn(status));
        }
        assertThat(postings.count()).isEqualTo(8);

        // 23:59:59 on 20 Oct 2026 in Vietnam: the early posting's last day is still open.
        clock.set(Instant.parse("2026-10-20T16:59:59Z"));
        var lastDay = postings.publicList(0, 20);
        postings.noStore(lastDay);
        JsonNode both = ok(lastDay);
        assertThat(ids(both)).containsExactly(early, open).doesNotContainAnyElementsOf(hidden);
        assertThat(both.path("totalElements").asLong()).isEqualTo(2);
        assertThat(both.path("totalPages").asLong()).isEqualTo(1);

        // 00:00 on 21 Oct 2026 in Vietnam: the early posting has left the list, its count and its pages.
        clock.set(Instant.parse("2026-10-20T17:00:00Z"));
        var nextDay = postings.publicList(0, 20);
        postings.noStore(nextDay);
        JsonNode one = ok(nextDay);
        assertThat(ids(one)).containsExactly(open);
        assertThat(one.path("totalElements").asLong()).isEqualTo(1);
        assertThat(one.path("totalPages").asLong()).isEqualTo(1);
        JsonNode beyond = ok(postings.publicList(1, 1));
        assertThat(ids(beyond)).isEmpty();
        assertThat(beyond.path("page").asInt()).isEqualTo(1);
        assertThat(beyond.path("totalElements").asLong()).isEqualTo(1);
        assertThat(beyond.path("totalPages").asLong()).isEqualTo(1);
    }

    @Test
    void thePublicViewCarriesNoInternalData() throws Exception {
        UUID id = draft(true);
        ok(postings.submit(id, 1, "Nhờ chị duyệt giúp.", recruiterToken));
        ok(postings.decide(id, "REQUEST_REVISION", "Bổ sung quyền lợi riêng cho vị trí.", 2, adminToken));
        ok(postings.submit(id, 3, null, recruiterToken));
        ok(postings.decide(id, "APPROVE", "Đồng ý đăng tin.", 4, hrToken));
        ok(postings.publish(id, 5, hrToken));
        UUID requisition = (UUID) postings.row(id).get("requisition_id");

        List<String> bodies = List.of(postings.publicDetail(id).body(), postings.publicList(0, 20).body(),
                ok(postings.preview(id, hrToken)).path("content").toString());

        for (String body : bodies) {
            assertThat(body).contains(id.toString()).doesNotContain(requisition.toString(), recruiterId.toString(),
                    hrId.toString(), adminId.toString(), "Trần Văn Tuyển", "Nguyễn Thị Hoa", "recruiter@example.test",
                    "hr@example.test", "Nhờ chị duyệt giúp.", "Bổ sung quyền lợi riêng", "Đồng ý đăng tin.",
                    "PUBLISHED", "APPROVED", "\"status\"", "\"revision\"", "requisitionId", "createdBy",
                    "lastReview", "actions", "publishedAt", "updatedAt", "2026-10-11");
        }
    }

    @Test
    void thePortalNeedsNoTokenAndIsNotCached() throws Exception {
        UUID id = published(true);

        var anonymousDetail = postings.publicDetail(id);
        var anonymousList = api.request("GET", PUBLIC_POSTINGS, null, null);
        JsonNode detail = ok(anonymousDetail);
        JsonNode list = ok(anonymousList);
        postings.noStore(anonymousDetail);
        postings.noStore(anonymousList);
        // Task 314: no account also means no server session (STATELESS): nothing sets a cookie.
        noCookie(anonymousDetail);
        noCookie(anonymousList);
        // Without parameters the list is the first page of 20.
        assertThat(list.path("page").asInt()).isZero();
        assertThat(list.path("size").asInt()).isEqualTo(20);
        assertThat(ids(list)).containsExactly(id);

        // Logged-in callers of every role, and without a role, read exactly the same JSON: nothing depends on who
        // asks, and nobody needs a JOB_POSTINGS permission.
        List<String> tokens = new ArrayList<>(List.of(adminToken, hrToken, recruiterToken));
        for (Role role : List.of(Role.HIRING_MANAGER, Role.APPROVER, Role.INTERVIEWER)) {
            String email = role.name().toLowerCase() + "@example.test";
            account(email, "Vai trò " + role, role);
            tokens.add(api.token(email));
        }
        accounts.saveAndFlush(new Account("norole@example.test", "Không vai trò", passwordHash, Set.of(), START));
        tokens.add(api.token("norole@example.test"));
        for (String token : tokens) {
            var signedDetail = api.request("GET", PUBLIC_POSTINGS + "/" + id, null, token);
            var signedList = api.request("GET", PUBLIC_POSTINGS, null, token);
            assertThat(ok(signedDetail)).isEqualTo(detail);
            assertThat(ok(signedList)).isEqualTo(list);
            postings.noStore(signedDetail);
            postings.noStore(signedList);
            noCookie(signedDetail);
            noCookie(signedList);
        }

        // Only GET is open. Another method on the portal paths sends a caller without a token to the login entry
        // point (401, like any other denied route) and refuses a valid token (403).
        for (String method : List.of("POST", "PUT", "DELETE")) {
            for (String path : List.of(PUBLIC_POSTINGS, PUBLIC_POSTINGS + "/" + id)) {
                var anonymous = api.request(method, path, null, null);
                assertThat(api.code(anonymous, 401)).as(method + " " + path).isEqualTo("UNAUTHORIZED");
                assertThat(anonymous.headers().firstValue("WWW-Authenticate")).contains("Bearer");
                postings.noStore(anonymous);
                assertThat(api.code(api.request(method, path, null, hrToken), 403)).as(method + " " + path)
                        .isEqualTo("FORBIDDEN");
            }
        }

        // A forged or expired bearer is refused like on every public API, so the portal calls without one.
        clock.set(START.plus(Duration.ofHours(1)));
        for (String token : List.of("not-a-jwt", hrToken)) {
            for (String path : List.of(PUBLIC_POSTINGS + "/" + id, PUBLIC_POSTINGS)) {
                var refused = api.request("GET", path, null, token);
                assertThat(api.code(refused, 401)).isEqualTo("UNAUTHORIZED");
                postings.noStore(refused);
            }
        }
        assertThat(ok(postings.publicDetail(id))).isEqualTo(detail);
    }

    @Test
    void thePreviewFollowsTheComposerScopeAndWritesNothing() throws Exception {
        UUID draft = draft(false);
        UUID pending = draft(false);
        ok(postings.submit(pending, 1, null, recruiterToken));
        account("outsider@example.test", "Người ngoài", Role.RECRUITER);
        String outsiderToken = api.token("outsider@example.test");
        String headToken = api.token("head@example.test");
        account("approver@example.test", "Người duyệt", Role.APPROVER);
        String approverToken = api.token("approver@example.test");
        List<Map<String, Object>> rowsBefore = jdbc.queryForList("SELECT * FROM job_postings ORDER BY id");
        List<Map<String, Object>> eventsBefore = jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1");
        List<Map<String, Object>> authorsBefore = jdbc.queryForList("SELECT * FROM job_posting_authors ORDER BY 1, 2");

        // Any status, a draft included, for the assigned recruiter and for every JOB_POSTINGS_WRITE_ALL holder.
        JsonNode preview = ok(postings.preview(draft, recruiterToken));
        assertThat(preview.path("status").asText()).isEqualTo("DRAFT");
        assertThat(preview.path("revision").asInt()).isEqualTo(1);
        assertThat(ok(postings.preview(draft, adminToken))).isEqualTo(preview);
        assertThat(ok(postings.preview(draft, hrToken))).isEqualTo(preview);
        // A recruiter not on the requisition's team, and JOB_POSTINGS_READ_ALL alone (HIRING_MANAGER, APPROVER).
        for (UUID posting : List.of(draft, pending)) {
            postings.forbidden(postings.preview(posting, outsiderToken));
            postings.forbidden(postings.preview(posting, headToken));
            postings.forbidden(postings.preview(posting, approverToken));
        }
        // An unknown id is 404 before the scope for every composer; the matcher refuses the others first.
        for (String token : List.of(hrToken, recruiterToken, outsiderToken)) {
            postings.error(postings.preview(UUID.randomUUID(), token), 404, "JOB_POSTING_NOT_FOUND");
        }
        postings.forbidden(postings.preview(UUID.randomUUID(), headToken));
        assertThat(api.code(postings.preview(draft, null), 401)).isEqualTo("UNAUTHORIZED");
        // Without the read permission a composer is refused before the lookup, like a read of the posting.
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'RECRUITER' "
                + "AND permission_code = 'JOB_POSTINGS_READ_SCOPED'");
        try {
            postings.forbidden(postings.preview(draft, recruiterToken));
            postings.forbidden(postings.preview(UUID.randomUUID(), recruiterToken));
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                    + "VALUES ('RECRUITER', 'JOB_POSTINGS_READ_SCOPED') ON CONFLICT DO NOTHING");
        }

        assertThat(jdbc.queryForList("SELECT * FROM job_postings ORDER BY id")).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_events ORDER BY 1")).isEqualTo(eventsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM job_posting_authors ORDER BY 1, 2")).isEqualTo(authorsBefore);
    }

    @Test
    void thePublicListPagesNewestPublicationFirst() throws Exception {
        // Created a, b, c in this order, and published in another one: b, then a, then c.
        UUID a = approved();
        clock.set(START.plusSeconds(10));
        UUID b = approved();
        clock.set(START.plusSeconds(20));
        UUID c = approved();
        draft(false);
        clock.set(START.plusSeconds(60));
        ok(postings.publish(b, 3, hrToken));
        clock.set(START.plusSeconds(120));
        ok(postings.publish(a, 3, hrToken));
        clock.set(START.plusSeconds(180));
        ok(postings.publish(c, 3, hrToken));

        JsonNode first = ok(postings.publicList(0, 2));
        assertThat(ids(first)).containsExactly(c, a);
        assertThat(first.path("page").asInt()).isZero();
        assertThat(first.path("size").asInt()).isEqualTo(2);
        assertThat(first.path("totalElements").asLong()).isEqualTo(3);
        assertThat(first.path("totalPages").asLong()).isEqualTo(2);
        JsonNode second = ok(postings.publicList(1, 2));
        assertThat(ids(second)).containsExactly(b);
        assertThat(second.path("totalElements").asLong()).isEqualTo(3);
        JsonNode beyond = ok(postings.publicList(2, 2));
        assertThat(ids(beyond)).isEmpty();
        assertThat(beyond.path("totalElements").asLong()).isEqualTo(3);
        assertThat(ids(ok(postings.publicList(0, 20)))).containsExactly(c, a, b);

        for (String query : List.of("?page=-1", "?size=0", "?size=101", "?page=21474837&size=100")) {
            JsonNode error = postings.error(api.request("GET", PUBLIC_POSTINGS + query, null, null), 400,
                    "VALIDATION_ERROR");
            assertThat(error.path("message").asText()).isEqualTo("Trang hoặc số lượng tin tuyển dụng không hợp lệ.");
        }
    }

    // D11, BA/PO question 16: the names of the position, the department, the work location and the employment type
    // are read live in both views. A rename after the publication shows at once in the preview and on the portal,
    // without a new review: the two stay equal, but no longer equal the preview HR approved.
    @Test
    void aRenamedOrganizationValueChangesPreviewAndPublicTogether() throws Exception {
        UUID id = published(false);
        JsonNode before = ok(postings.preview(id, hrToken));
        assertThat(withoutApplicationState(ok(postings.publicDetail(id)))).isEqualTo(before.path("content"));
        assertThat(onlyItem(ok(postings.publicList(0, 20))).path("workLocationName").asText()).isEqualTo("Hà Nội");
        Map<String, Object> rowBefore = postings.row(id);
        List<Map<String, Object>> eventsBefore = postings.events(id);

        api.expect(api.request("PUT", "/api/v1/recruitment-catalogs/WORK_LOCATION/items/" + workLocation,
                json.writeValueAsString(Map.of("code", "HN", "name", "Hà Nội (Cầu Giấy)", "active", true)), hrToken),
                200);
        api.expect(api.request("PUT", "/api/v1/departments/" + department, json.writeValueAsString(Map.of(
                "code", "IT", "name", "Phòng Công nghệ", "managerUserId", headId, "active", true)), hrToken), 200);

        JsonNode after = ok(postings.preview(id, hrToken));
        JsonNode detail = ok(postings.publicDetail(id));
        JsonNode item = onlyItem(ok(postings.publicList(0, 20)));
        assertThat(after.path("content")).isEqualTo(withoutApplicationState(detail));
        JobPostingTestSupport.assertApplicationState(detail, true);
        ObjectNode renamed = (ObjectNode) before.path("content").deepCopy();
        renamed.put("departmentName", "Phòng Công nghệ");
        renamed.put("workLocationName", "Hà Nội (Cầu Giấy)");
        assertThat(withoutApplicationState(detail)).isEqualTo(renamed);
        assertThat(item.path("departmentName").asText()).isEqualTo("Phòng Công nghệ");
        assertThat(item.path("workLocationName").asText()).isEqualTo("Hà Nội (Cầu Giấy)");
        // Nothing about the posting changed: same status and revision, no new review step.
        assertThat(after.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(after.path("revision")).isEqualTo(before.path("revision"));
        assertThat(postings.row(id)).isEqualTo(rowBefore);
        assertThat(postings.events(id)).isEqualTo(eventsBefore);
    }

    // A draft the assigned recruiter wrote: the title and benefits from the body, the texts inherited, the public
    // details filled in (deadline 30 Nov 2026). Revision 1.
    private UUID draft(boolean showSalary) throws Exception {
        Map<String, Object> body = postings.body(requisition(), workLocation, employmentType);
        body.put("title", TITLE);
        body.put("benefits", BENEFITS);
        body.put("showSalary", showSalary);
        return id(postings.created(body, recruiterToken));
    }

    // The recruiter sends the draft for review (2) and HR approves it (3).
    private UUID approved() throws Exception {
        UUID id = draft(false);
        ok(postings.submit(id, 1, null, recruiterToken));
        ok(postings.decide(id, "APPROVE", null, 2, hrToken));
        return id;
    }

    // Then HR publishes it (4).
    private UUID published(boolean showSalary) throws Exception {
        UUID id = draft(showSalary);
        ok(postings.submit(id, 1, null, recruiterToken));
        ok(postings.decide(id, "APPROVE", null, 2, hrToken));
        ok(postings.publish(id, 3, hrToken));
        return id;
    }

    // A posting brought to the status through the API: the recruiter writes and sends it (1, 2), HR approves it or
    // asks for changes (3) and publishes it (4). Task 292: a closed posting is published, then HR fills its
    // requisition (task 290), which closes it (5). Task 355: a posting taken down is published, then the recruiter
    // takes it down (5). Task 356: an expired posting is published, then the daily task expires it (5) at 00:00 after
    // its deadline; the clock then goes back, and the posting stays EXPIRED.
    private UUID postingIn(JobPostingStatus status) throws Exception {
        UUID id = draft(false);
        if (status != JobPostingStatus.DRAFT) {
            ok(postings.submit(id, 1, null, recruiterToken));
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
        if (status == JobPostingStatus.UNPUBLISHED) {
            ok(postings.unpublish(id, 4, "Đã nhận đủ hồ sơ.", recruiterToken));
        }
        if (status == JobPostingStatus.EXPIRED) {
            Instant now = clock.instant();
            clock.set(Instant.parse(JobPostingTestSupport.CLOSES_AT));
            assertThat(expiry.expire(id)).isTrue();
            clock.set(now);
        }
        if (status == JobPostingStatus.CLOSED) {
            ok(api.request("POST", "/api/v1/requisitions/" + postings.row(id).get("requisition_id")
                    + "/status-changes", json.writeValueAsString(Map.of("status", "FILLED", "reason", "Đã tuyển đủ",
                    "expectedRevision", 0)), hrToken));
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

    private JsonNode ok(HttpResponse<String> response) {
        return api.expect(response, 200);
    }

    private static void noCookie(HttpResponse<String> response) {
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
    }

    private static UUID id(JsonNode view) {
        return UUID.fromString(view.path("id").asText());
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(id(item)));
        return ids;
    }

    private static JsonNode onlyItem(JsonNode page) {
        assertThat(page.path("items").size()).as(page.toString()).isEqualTo(1);
        return page.path("items").get(0);
    }

    private static JsonNode item(JsonNode page, UUID posting) {
        for (JsonNode item : page.path("items")) {
            if (id(item).equals(posting)) {
                return item;
            }
        }
        throw new AssertionError("Posting " + posting + " is not in " + page);
    }

    private UUID account(String email, String fullName, Role role) {
        return accounts.saveAndFlush(new Account(email, fullName, passwordHash, Set.of(role), START)).getId();
    }

    private static List<String> fieldNames(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }

    private static List<String> withApplicationState(List<String> keys) {
        List<String> detail = new ArrayList<>(keys);
        detail.addAll(List.of("applicationOpen", "applicationClosesAt"));
        return List.copyOf(detail);
    }
}
