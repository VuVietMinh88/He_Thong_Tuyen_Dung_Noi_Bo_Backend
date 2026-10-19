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
import vn.ttcs.recruitment.requisition.RequisitionRecruiterRole;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.auth.ApprovalTestSupport.step;
import static vn.ttcs.recruitment.auth.JobPostingTestSupport.JOB_DESCRIPTION;
import static vn.ttcs.recruitment.auth.JobPostingTestSupport.REQUIREMENTS;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.CANDIDATE_SOURCE;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.EMPLOYMENT_TYPE;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.WORK_LOCATION;

// Task 303 (story S3-09): "Xây dựng API tạo, sửa bản nháp tin tuyển dụng". A posting is made from an approved
// requisition, copies its texts once and is saved again as a whole; recruiters compose only the postings of the
// requisitions assigned to them.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobPostingDraftIntegrationTest {
    // 10:00 on 11 Oct 2026 in Vietnam.
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");
    private static final List<String> VIEW_FIELDS = List.of("id", "requisitionId", "status", "revision", "title",
            "positionId", "positionName", "departmentId", "departmentName", "headcount", "jobDescription",
            "candidateRequirements", "benefits", "workLocationId", "workLocationName", "employmentTypeId",
            "employmentTypeName", "applicationDeadline", "showSalary", "salaryMin", "salaryMax", "createdById",
            "createdByName", "createdAt", "updatedAt", "actions", "lastReview", "publishedById", "publishedByName",
            "publishedAt");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;

    private JobPostingTestSupport postings;
    private ApprovalTestSupport api;
    private String passwordHash;
    private UUID adminId;
    private String adminToken;
    private UUID hrId;
    private String hrToken;
    private UUID recruiterId;
    private String recruiterToken;
    private UUID otherRecruiterId;
    private String otherRecruiterToken;
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
        otherRecruiterId = account("recruiter2@example.test", "Lê Thị Hỗ Trợ", Role.RECRUITER);
        otherRecruiterToken = api.token("recruiter2@example.test");
        department = api.department("IT", headId, null, START);
        position = api.position(START);
        // HR_MANAGER takes the only level, so requisitions created and submitted by ADMIN are approved by HR.
        api.flow(department, hrId, START, step(1, null, null, "HR_MANAGER"));
        workLocation = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, START);
        employmentType = postings.catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", true, START);
    }

    @Test
    void hrCreatesADraftThatCopiesTheApprovedRequisition() throws Exception {
        UUID requisition = postings.approvedRequisition(position, department, adminToken, hrToken);
        Map<String, Object> requisitionBefore = postings.requisitionRow(requisition);
        clock.set(START.plusNanos(987_654_321));

        var response = postings.create(Map.of("requisitionId", requisition), hrToken);

        JsonNode view = api.expect(response, 201);
        postings.noStore(response);
        assertThat(fieldNames(view)).containsExactlyElementsOf(VIEW_FIELDS);
        UUID id = UUID.fromString(view.path("id").asText());
        assertThat(view.path("requisitionId").asText()).isEqualTo(requisition.toString());
        assertThat(view.path("status").asText()).isEqualTo("DRAFT");
        assertThat(view.path("revision").asInt()).isEqualTo(1);
        // The title is the position name; both texts are byte-for-byte copies of the requisition's.
        assertThat(view.path("title").asText()).isEqualTo("Lập trình viên");
        assertThat(view.path("jobDescription").asText()).isEqualTo(JOB_DESCRIPTION);
        assertThat(view.path("candidateRequirements").asText()).isEqualTo(REQUIREMENTS);
        assertThat(view.path("positionId").asText()).isEqualTo(position.toString());
        assertThat(view.path("positionName").asText()).isEqualTo("Lập trình viên");
        assertThat(view.path("departmentId").asText()).isEqualTo(department.toString());
        assertThat(view.path("departmentName").asText()).isEqualTo("Phòng IT");
        assertThat(view.path("headcount").asInt()).isEqualTo(2);
        // The approved proposal, read-only; hidden from candidates until the author chooses to show it.
        assertThat(view.path("salaryMin").asLong()).isEqualTo(20_000_000L);
        assertThat(view.path("salaryMax").asLong()).isEqualTo(22_000_000L);
        assertThat(view.path("showSalary").asBoolean()).isFalse();
        for (String empty : List.of("benefits", "workLocationId", "workLocationName", "employmentTypeId",
                "employmentTypeName", "applicationDeadline")) {
            assertThat(view.path(empty).isNull()).as(empty).isTrue();
        }
        assertThat(view.path("createdById").asText()).isEqualTo(hrId.toString());
        assertThat(view.path("createdByName").asText()).isEqualTo("Nguyễn Thị Hoa");
        // PostgreSQL keeps microseconds, so the answer shows the time a later read returns.
        assertThat(view.path("createdAt").asText()).isEqualTo("2026-10-11T03:00:00.987654Z");
        assertThat(view.path("updatedAt").asText()).isEqualTo("2026-10-11T03:00:00.987654Z");
        // Task 308 added the review actions; HR may send the new draft for review, nothing else yet. Task 355 added
        // taking a published posting down.
        assertThat(fieldNames(view.path("actions"))).containsExactly("canEdit", "canSubmit", "canApprove",
                "canRequestRevision", "canPublish", "canUnpublish");
        assertThat(view.path("actions").path("canEdit").asBoolean()).isTrue();
        assertThat(view.path("actions").path("canSubmit").asBoolean()).isTrue();
        for (String review : List.of("canApprove", "canRequestRevision", "canPublish", "canUnpublish")) {
            assertThat(view.path("actions").path(review).asBoolean()).as(review).isFalse();
        }
        assertThat(view.path("lastReview").isNull()).isTrue();
        // Task 310: a draft has no publication trail.
        for (String trail : List.of("publishedById", "publishedByName", "publishedAt")) {
            assertThat(view.path(trail).isNull()).as(trail).isTrue();
        }

        var read = postings.get(id, hrToken);
        assertThat(api.expect(read, 200)).isEqualTo(view);
        postings.noStore(read);
        Map<String, Object> row = postings.row(id);
        assertThat(row.get("job_description")).isEqualTo(JOB_DESCRIPTION);
        assertThat(row.get("candidate_requirements")).isEqualTo(REQUIREMENTS);
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("revision")).isEqualTo(1);
        assertThat(row.get("created_by")).isEqualTo(hrId);
        // Copying never writes the requisition.
        assertThat(postings.requisitionRow(requisition)).isEqualTo(requisitionBefore);
        assertThat(postings.authorsOf(id)).containsExactly(hrId);
    }

    @Test
    void bodyContentReplacesTheInheritedContent() throws Exception {
        UUID requisition = postings.approvedRequisition(position, department, adminToken, hrToken);
        Map<String, Object> requisitionBefore = postings.requisitionRow(requisition);
        Map<String, Object> body = postings.body(requisition, workLocation, employmentType);
        body.put("title", "Lập trình viên Java (Hà Nội)");
        body.put("jobDescription", "Viết API tuyển dụng.\n\tDùng Spring Boot.");
        body.put("candidateRequirements", "Hai năm kinh nghiệm Java.");
        body.put("benefits", "Bảo hiểm đầy đủ, 13 tháng lương.");
        body.put("showSalary", true);

        JsonNode view = postings.created(body, hrToken);

        assertThat(view.path("title").asText()).isEqualTo("Lập trình viên Java (Hà Nội)");
        assertThat(view.path("jobDescription").asText()).isEqualTo("Viết API tuyển dụng.\n\tDùng Spring Boot.");
        assertThat(view.path("candidateRequirements").asText()).isEqualTo("Hai năm kinh nghiệm Java.");
        assertThat(view.path("benefits").asText()).isEqualTo("Bảo hiểm đầy đủ, 13 tháng lương.");
        assertThat(view.path("workLocationId").asText()).isEqualTo(workLocation.toString());
        assertThat(view.path("workLocationName").asText()).isEqualTo("Hà Nội");
        assertThat(view.path("employmentTypeId").asText()).isEqualTo(employmentType.toString());
        assertThat(view.path("employmentTypeName").asText()).isEqualTo("Toàn thời gian");
        assertThat(view.path("applicationDeadline").asText()).isEqualTo("2026-11-30");
        assertThat(view.path("showSalary").asBoolean()).isTrue();
        assertThat(postings.requisitionRow(requisition)).isEqualTo(requisitionBefore);

        // Blank texts count as left out: they are inherited too.
        UUID other = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        JsonNode inherited = postings.created(Map.of("requisitionId", other, "title", "   ",
                "jobDescription", " \n\t", "candidateRequirements", "", "benefits", "  "), hrToken);
        assertThat(inherited.path("title").asText()).isEqualTo("Lập trình viên");
        assertThat(inherited.path("jobDescription").asText()).isEqualTo(JOB_DESCRIPTION);
        assertThat(inherited.path("candidateRequirements").asText()).isEqualTo(REQUIREMENTS);
        assertThat(inherited.path("benefits").isNull()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(RequisitionStatus.class)
    void onlyAnApprovedRequisitionMakesAPosting(RequisitionStatus status) throws Exception {
        UUID requisition = postings.requisition(position, department, adminId, status, START);

        var response = postings.create(Map.of("requisitionId", requisition), hrToken);

        boolean source = switch (status) {
            case APPROVED -> true;
            case DRAFT, PENDING_APPROVAL, RETURNED, REJECTED, PAUSED, FILLED, CANCELLED -> false;
        };
        if (source) {
            api.expect(response, 201);
            assertThat(postings.count()).isOne();
        } else {
            JsonNode error = postings.error(response, 409, "JOB_POSTING_REQUISITION_NOT_APPROVED");
            assertThat(error.path("message").asText())
                    .isEqualTo("Chỉ tạo và xử lý được tin tuyển dụng của yêu cầu tuyển dụng đã duyệt.");
            assertThat(postings.count()).isZero();
        }
    }

    @Test
    void anUnknownRequisitionIsAFormErrorOfRequisitionId() throws Exception {
        postings.businessError(postings.create(Map.of("requisitionId", UUID.randomUUID()), hrToken),
                "INVALID_JOB_POSTING_REQUISITION", "requisitionId", "Yêu cầu tuyển dụng không tồn tại.");
        JsonNode missing = postings.fieldErrors(postings.create(Map.of("showSalary", true), hrToken),
                "requisitionId");
        assertThat(missing.path("requisitionId").asText()).isEqualTo("Vui lòng chọn yêu cầu tuyển dụng đã duyệt.");
        assertThat(postings.count()).isZero();
    }

    @Test
    void aRequisitionHasAtMostOnePosting() throws Exception {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        UUID first = UUID.fromString(postings.created(Map.of("requisitionId", requisition), hrToken)
                .path("id").asText());

        JsonNode error = postings.error(postings.create(Map.of("requisitionId", requisition, "title", "Tin thứ hai"),
                adminToken), 409, "JOB_POSTING_ALREADY_EXISTS");

        assertThat(error.path("message").asText()).isEqualTo("Yêu cầu tuyển dụng này đã có tin tuyển dụng.");
        assertThat(postings.count()).isOne();
        assertThat(postings.authorsOf(first)).containsExactly(hrId);
    }

    @Test
    void savingReplacesTheContentAndBumpsTheRevision() throws Exception {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        Map<String, Object> body = postings.body(requisition, workLocation, employmentType);
        body.put("benefits", "Bảo hiểm đầy đủ.");
        body.put("showSalary", true);
        JsonNode created = postings.created(body, hrToken);
        UUID id = UUID.fromString(created.path("id").asText());
        clock.set(START.plusSeconds(60).plusNanos(123_456_789));
        // Everything optional is left out, so it is cleared: a draft stays saveable while it is being written.
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("expectedRevision", 1);
        change.put("title", "Kỹ sư phần mềm");
        change.put("jobDescription", "Mô tả mới.\n  - Thụt lề được giữ nguyên.");
        change.put("candidateRequirements", "Yêu cầu mới.");
        change.put("showSalary", false);

        var response = postings.update(id, change, hrToken);

        JsonNode saved = api.expect(response, 200);
        postings.noStore(response);
        assertThat(saved.path("revision").asInt()).isEqualTo(2);
        assertThat(saved.path("status").asText()).isEqualTo("DRAFT");
        assertThat(saved.path("title").asText()).isEqualTo("Kỹ sư phần mềm");
        assertThat(saved.path("jobDescription").asText()).isEqualTo("Mô tả mới.\n  - Thụt lề được giữ nguyên.");
        assertThat(saved.path("candidateRequirements").asText()).isEqualTo("Yêu cầu mới.");
        for (String cleared : List.of("benefits", "workLocationId", "workLocationName", "employmentTypeId",
                "employmentTypeName", "applicationDeadline")) {
            assertThat(saved.path(cleared).isNull()).as(cleared).isTrue();
        }
        assertThat(saved.path("showSalary").asBoolean()).isFalse();
        assertThat(saved.path("createdAt").asText()).isEqualTo(created.path("createdAt").asText());
        assertThat(saved.path("updatedAt").asText()).isEqualTo("2026-10-11T03:01:00.123456Z");
        assertThat(saved.path("actions").path("canEdit").asBoolean()).isTrue();
        assertThat(api.expect(postings.get(id, hrToken), 200)).isEqualTo(saved);
        assertThat(postings.row(id).get("revision")).isEqualTo(2);
    }

    @Test
    void aStaleRevisionChangesNothing() throws Exception {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        JsonNode created = postings.created(postings.body(requisition, workLocation, employmentType), hrToken);
        UUID id = UUID.fromString(created.path("id").asText());
        api.expect(postings.update(id, postings.putBody(created), hrToken), 200);
        Map<String, Object> rowBefore = postings.row(id);

        for (int stale : List.of(1, 3)) {
            Map<String, Object> change = postings.putBody(created);
            change.put("expectedRevision", stale);
            change.put("title", "Bị ghi đè");
            JsonNode error = postings.error(postings.update(id, change, adminToken), 409, "JOB_POSTING_CHANGED");
            assertThat(error.path("message").asText()).isEqualTo("Tin tuyển dụng đã thay đổi (có thể người khác vừa "
                    + "sửa hoặc xử lý). Vui lòng tải lại trước khi thao tác.");
        }

        assertThat(postings.row(id)).isEqualTo(rowBefore);
        assertThat(postings.authorsOf(id)).containsExactly(hrId);
    }

    // Task 290 pauses, fills and cancels requisitions; SQL reaches every status here (closing postings is task 292).
    @ParameterizedTest
    @EnumSource(RequisitionStatus.class)
    void aPostingIsSavedOnlyWhileItsRequisitionIsApproved(RequisitionStatus status) throws Exception {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        JsonNode created = postings.created(postings.body(requisition, workLocation, employmentType), hrToken);
        UUID id = UUID.fromString(created.path("id").asText());
        jdbc.update("UPDATE recruitment_requisitions SET status = ? WHERE id = ?", status.name(), requisition);
        Map<String, Object> rowBefore = postings.row(id);

        // A recruiter outside the requisition is refused before the status check, so it learns nothing about it.
        postings.forbidden(postings.update(id, postings.putBody(created), otherRecruiterToken));
        var read = postings.get(id, hrToken);
        var saved = postings.update(id, postings.putBody(created), hrToken);

        boolean source = switch (status) {
            case APPROVED -> true;
            case DRAFT, PENDING_APPROVAL, RETURNED, REJECTED, PAUSED, FILLED, CANCELLED -> false;
        };
        assertThat(api.expect(read, 200).path("actions").path("canEdit").asBoolean()).isEqualTo(source);
        if (source) {
            assertThat(api.expect(saved, 200).path("revision").asInt()).isEqualTo(2);
        } else {
            JsonNode error = postings.error(saved, 409, "JOB_POSTING_REQUISITION_NOT_APPROVED");
            assertThat(error.path("message").asText())
                    .isEqualTo("Chỉ tạo và xử lý được tin tuyển dụng của yêu cầu tuyển dụng đã duyệt.");
            assertThat(postings.row(id)).isEqualTo(rowBefore);
            assertThat(postings.authorsOf(id)).containsExactly(hrId);
        }
    }

    @Test
    void everyoneWhoSavesTheContentBecomesAnAuthor() throws Exception {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        postings.assign(requisition, recruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
        JsonNode created = postings.created(Map.of("requisitionId", requisition), recruiterToken);
        UUID id = UUID.fromString(created.path("id").asText());
        assertThat(postings.authorsOf(id)).containsExactly(recruiterId);

        JsonNode byAdmin = api.expect(postings.update(id, postings.putBody(created), adminToken), 200);
        assertThat(postings.authorsOf(id)).containsExactlyInAnyOrder(recruiterId, adminId);
        JsonNode again = api.expect(postings.update(id, postings.putBody(byAdmin), recruiterToken), 200);
        assertThat(postings.authorsOf(id)).containsExactlyInAnyOrder(recruiterId, adminId);

        // Refused saves add nobody: an outdated revision, a recruiter outside the requisition, an invalid body.
        postings.error(postings.update(id, postings.putBody(byAdmin), hrToken), 409, "JOB_POSTING_CHANGED");
        postings.forbidden(postings.update(id, postings.putBody(again), otherRecruiterToken));
        Map<String, Object> invalid = postings.putBody(again);
        invalid.put("title", " ");
        postings.fieldErrors(postings.update(id, invalid, hrToken), "title");
        assertThat(postings.authorsOf(id)).containsExactlyInAnyOrder(recruiterId, adminId);
        assertThat(postings.row(id).get("revision")).isEqualTo(3);
    }

    @Test
    void theSourceListShowsApprovedRequisitionsWithoutAPostingInTheAuthorsScope() throws Exception {
        UUID oldest = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED,
                START.minusSeconds(300));
        UUID middle = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED,
                START.minusSeconds(200));
        UUID newest = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED,
                START.minusSeconds(100));
        UUID draft = postings.requisition(position, department, adminId, RequisitionStatus.DRAFT, START);
        UUID rejected = postings.requisition(position, department, adminId, RequisitionStatus.REJECTED, START);
        postings.assign(oldest, recruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
        postings.assign(middle, otherRecruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
        postings.assign(middle, recruiterId, RequisitionRecruiterRole.SUPPORTING, hrId, START);
        postings.assign(draft, recruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
        postings.assign(rejected, recruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
        account("recruiter3@example.test", "Người ngoài", Role.RECRUITER);
        String outsiderToken = api.token("recruiter3@example.test");

        JsonNode all = api.expect(postings.sources("", hrToken), 200);
        assertThat(ids(all, "requisitionId")).containsExactly(newest, middle, oldest);
        assertThat(all.path("totalElements").asLong()).isEqualTo(3);
        JsonNode item = all.path("items").path(0);
        assertThat(fieldNames(item)).containsExactly("requisitionId", "positionId", "positionName", "departmentId",
                "departmentName", "headcount", "neededBy");
        assertThat(item.path("positionId").asText()).isEqualTo(position.toString());
        assertThat(item.path("positionName").asText()).isEqualTo("Lập trình viên");
        assertThat(item.path("departmentId").asText()).isEqualTo(department.toString());
        assertThat(item.path("departmentName").asText()).isEqualTo("Phòng IT");
        assertThat(item.path("headcount").asInt()).isEqualTo(2);
        assertThat(item.path("neededBy").asText()).isEqualTo("2026-12-01");
        // A primary or a supporting recruiter sees the requisition; drafts and rejected ones are never sources.
        assertThat(ids(api.expect(postings.sources("", recruiterToken), 200), "requisitionId"))
                .containsExactly(middle, oldest);

        postings.created(Map.of("requisitionId", oldest), recruiterToken);

        assertThat(ids(api.expect(postings.sources("", hrToken), 200), "requisitionId"))
                .containsExactly(newest, middle);
        assertThat(ids(api.expect(postings.sources("", recruiterToken), 200), "requisitionId"))
                .containsExactly(middle);
        JsonNode second = api.expect(postings.sources("?page=1&size=1", hrToken), 200);
        assertThat(ids(second, "requisitionId")).containsExactly(middle);
        assertThat(second.path("totalElements").asLong()).isEqualTo(2);
        assertThat(second.path("totalPages").asLong()).isEqualTo(2);
        JsonNode none = api.expect(postings.sources("", outsiderToken), 200);
        assertThat(none.path("items").isEmpty()).isTrue();
        assertThat(none.path("totalElements").asLong()).isZero();
        assertThat(none.path("totalPages").asLong()).isZero();
    }

    @ParameterizedTest
    @EnumSource(RequisitionRecruiterRole.class)
    void assignedRecruitersComposeTheirPostings(RequisitionRecruiterRole role) throws Exception {
        UUID requisition = postings.approvedRequisition(position, department, adminToken, hrToken);
        // The team is built through the API, the way HR builds it.
        if (role == RequisitionRecruiterRole.PRIMARY) {
            assign(requisition, Map.of("recruiterId", recruiterId));
        } else {
            assign(requisition, Map.of("recruiterId", otherRecruiterId));
            assign(requisition, Map.of("recruiterId", recruiterId, "role", "SUPPORTING"));
        }
        UUID outsider = account("recruiter3@example.test", "Người ngoài", Role.RECRUITER);
        String outsiderToken = api.token("recruiter3@example.test");

        JsonNode created = postings.created(Map.of("requisitionId", requisition), recruiterToken);
        UUID id = UUID.fromString(created.path("id").asText());
        api.expect(postings.get(id, recruiterToken), 200);
        JsonNode saved = api.expect(postings.update(id, postings.putBody(created), recruiterToken), 200);
        assertThat(ids(api.expect(postings.list("?status=DRAFT", recruiterToken), 200), "id")).containsExactly(id);

        // A recruiter who is not on the team: 403 for this posting, 404 first for an unknown one.
        Map<String, Object> rowBefore = postings.row(id);
        postings.forbidden(postings.create(Map.of("requisitionId", requisition), outsiderToken));
        postings.forbidden(postings.get(id, outsiderToken));
        postings.forbidden(postings.update(id, postings.putBody(saved), outsiderToken));
        // The scope answers before the revision and the catalog values, so the outsider learns neither the posting's
        // revision (the created view is one save behind) nor which catalog values exist.
        postings.forbidden(postings.update(id, postings.putBody(created), outsiderToken));
        Map<String, Object> unknownLocation = postings.putBody(saved);
        unknownLocation.put("workLocationId", UUID.randomUUID());
        postings.forbidden(postings.update(id, unknownLocation, outsiderToken));
        postings.error(postings.get(UUID.randomUUID(), outsiderToken), 404, "JOB_POSTING_NOT_FOUND");
        postings.error(postings.update(UUID.randomUUID(), postings.putBody(saved), outsiderToken), 404,
                "JOB_POSTING_NOT_FOUND");
        assertThat(api.expect(postings.list("", outsiderToken), 200).path("totalElements").asLong()).isZero();
        assertThat(postings.authorsOf(id)).doesNotContain(outsider);
        assertThat(postings.row(id)).isEqualTo(rowBefore);

        // The recruiter leaves the team the only way the API allows: a primary is handed over, a supporting
        // recruiter is removed.
        if (role == RequisitionRecruiterRole.PRIMARY) {
            assign(requisition, Map.of("recruiterId", otherRecruiterId, "role", "PRIMARY"));
        } else {
            api.expect(api.request("POST", "/api/v1/requisitions/" + requisition + "/unassign",
                    json.writeValueAsString(Map.of("recruiterId", recruiterId)), hrToken), 200);
        }

        postings.forbidden(postings.get(id, recruiterToken));
        postings.forbidden(postings.update(id, postings.putBody(saved), recruiterToken));
        assertThat(api.expect(postings.list("", recruiterToken), 200).path("totalElements").asLong()).isZero();
        // The new primary recruiter takes the draft over.
        api.expect(postings.update(id, postings.putBody(saved), otherRecruiterToken), 200);
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, mode = EnumSource.Mode.EXCLUDE, names = "RECRUITER")
    void onlyPostingWritersComposeAndRead(Role role) throws Exception {
        // Recruiters (JOB_POSTINGS_WRITE_SCOPED) are covered by assignedRecruitersComposeTheirPostings.
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        UUID vacant = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        JsonNode posting = postings.created(Map.of("requisitionId", requisition), hrToken);
        UUID id = UUID.fromString(posting.path("id").asText());
        Map<String, Object> rowBefore = postings.row(id);
        account("role@example.test", "Vai trò " + role, role);
        String token = api.token("role@example.test");

        var created = postings.create(Map.of("requisitionId", vacant), token);
        var saved = postings.update(id, postings.putBody(posting), token);
        var list = postings.list("", token);
        var read = postings.get(id, token);
        var sources = postings.sources("", token);

        if (role == Role.ADMIN || role == Role.HR_MANAGER) {
            api.expect(created, 201);
            assertThat(api.expect(saved, 200).path("revision").asInt()).isEqualTo(2);
            assertThat(api.expect(list, 200).path("totalElements").asLong()).isEqualTo(2);
            api.expect(read, 200);
            api.expect(sources, 200);
        } else {
            // JOB_POSTINGS_READ_ALL alone (HIRING_MANAGER, APPROVER) only reads the public portal.
            for (var response : List.of(created, saved, list, read, sources)) {
                postings.forbidden(response);
            }
            assertThat(postings.row(id)).isEqualTo(rowBefore);
            assertThat(postings.count()).isOne();
        }
    }

    @Test
    void writeWithoutReadIsRefusedBeforeTheLookup() throws Exception {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        postings.assign(requisition, recruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
        JsonNode posting = postings.created(Map.of("requisitionId", requisition), recruiterToken);
        UUID id = UUID.fromString(posting.path("id").asText());
        Map<String, Object> rowBefore = postings.row(id);
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'RECRUITER' "
                + "AND permission_code = 'JOB_POSTINGS_READ_SCOPED'");
        try {
            // Unknown ids would be 400 and 404: the missing read permission answers first, so a caller who may only
            // write learns nothing about which ids exist.
            postings.forbidden(postings.create(Map.of("requisitionId", UUID.randomUUID()), recruiterToken));
            postings.forbidden(postings.update(UUID.randomUUID(), postings.putBody(posting), recruiterToken));
            postings.forbidden(postings.get(UUID.randomUUID(), recruiterToken));
            postings.forbidden(postings.get(id, recruiterToken));
            postings.forbidden(postings.update(id, postings.putBody(posting), recruiterToken));
            postings.forbidden(postings.list("", recruiterToken));
            postings.forbidden(postings.sources("", recruiterToken));
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) "
                    + "VALUES ('RECRUITER', 'JOB_POSTINGS_READ_SCOPED') ON CONFLICT DO NOTHING");
        }
        assertThat(postings.row(id)).isEqualTo(rowBefore);
        assertThat(postings.count()).isOne();
    }

    // The grants of several roles add up (role-permission-matrix.md, mục 7.3): a recruiter who is also a hiring
    // manager or an approver holds JOB_POSTINGS_WRITE_SCOPED and JOB_POSTINGS_READ_ALL. ALL needs both the write and
    // the read scope to be ALL, so such an account still composes and reads only the postings of the requisitions
    // assigned to it.
    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"HIRING_MANAGER", "APPROVER"})
    void aRecruiterWhoAlsoReadsAllPostingsStillComposesOnlyAssignedOnes(Role second) throws Exception {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        UUID vacant = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        JsonNode posting = postings.created(Map.of("requisitionId", requisition), hrToken);
        UUID id = UUID.fromString(posting.path("id").asText());
        Map<String, Object> rowBefore = postings.row(id);
        UUID both = accounts.saveAndFlush(new Account("both@example.test", "Hai vai trò", passwordHash,
                Set.of(Role.RECRUITER, second), START)).getId();
        String token = api.token("both@example.test");

        postings.forbidden(postings.get(id, token));
        // Task 309: the preview is an internal read too, and so is the history of task 310.
        postings.forbidden(postings.preview(id, token));
        postings.forbidden(postings.history(id, token));
        postings.forbidden(postings.update(id, postings.putBody(posting), token));
        postings.forbidden(postings.create(Map.of("requisitionId", vacant), token));
        assertThat(api.expect(postings.list("", token), 200).path("totalElements").asLong()).isZero();
        assertThat(api.expect(postings.sources("", token), 200).path("totalElements").asLong()).isZero();
        assertThat(postings.row(id)).isEqualTo(rowBefore);
        assertThat(postings.count()).isOne();
        assertThat(postings.authorsOf(id)).doesNotContain(both);

        // Once on the team of both requisitions, the account composes like any assigned recruiter.
        postings.assign(requisition, both, RequisitionRecruiterRole.PRIMARY, hrId, START);
        postings.assign(vacant, both, RequisitionRecruiterRole.PRIMARY, hrId, START);
        api.expect(postings.get(id, token), 200);
        api.expect(postings.preview(id, token), 200);
        assertThat(api.expect(postings.history(id, token), 200).isArray()).isTrue();
        assertThat(api.expect(postings.update(id, postings.putBody(posting), token), 200).path("revision").asInt())
                .isEqualTo(2);
        assertThat(ids(api.expect(postings.list("", token), 200), "id")).containsExactly(id);
        assertThat(ids(api.expect(postings.sources("", token), 200), "requisitionId")).containsExactly(vacant);
        postings.created(Map.of("requisitionId", vacant), token);
        assertThat(postings.count()).isEqualTo(2);
    }

    @Test
    void serverOwnedAndUnsupportedFieldsAreInvalidJson() throws Exception {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        UUID vacant = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        JsonNode posting = postings.created(Map.of("requisitionId", requisition), hrToken);
        UUID id = UUID.fromString(posting.path("id").asText());
        Map<String, Object> rowBefore = postings.row(id);
        Map<String, Object> unsupported = new LinkedHashMap<>();
        unsupported.put("status", "PUBLISHED");
        unsupported.put("id", UUID.randomUUID());
        unsupported.put("revision", 5);
        unsupported.put("createdBy", adminId);
        unsupported.put("publishedAt", "2026-10-11T03:00:00Z");
        unsupported.put("salaryMin", 1);
        unsupported.put("level", "Senior");
        unsupported.put("skills", List.of("Java"));

        for (var field : unsupported.entrySet()) {
            Map<String, Object> create = new LinkedHashMap<>(Map.of("requisitionId", vacant));
            create.put(field.getKey(), field.getValue());
            postings.error(postings.create(create, hrToken), 400, "INVALID_JSON");
            Map<String, Object> change = postings.putBody(posting);
            change.put(field.getKey(), field.getValue());
            postings.error(postings.update(id, change, hrToken), 400, "INVALID_JSON");
        }
        // The requisition of a posting never changes.
        Map<String, Object> moved = postings.putBody(posting);
        moved.put("requisitionId", vacant);
        postings.error(postings.update(id, moved, hrToken), 400, "INVALID_JSON");

        assertThat(postings.count()).isOne();
        assertThat(postings.row(id)).isEqualTo(rowBefore);
    }

    @Test
    void catalogValuesMustExistInTheirOwnCatalog() throws Exception {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        UUID source = postings.catalogValue(CANDIDATE_SOURCE, "WEB", "Trang tuyển dụng", true, START);
        for (UUID wrong : List.of(employmentType, source, UUID.randomUUID())) {
            postings.businessError(postings.create(postings.body(requisition, wrong, employmentType), hrToken),
                    "INVALID_JOB_POSTING_WORK_LOCATION", "workLocationId", "Địa điểm làm việc không tồn tại.");
        }
        for (UUID wrong : List.of(workLocation, source, UUID.randomUUID())) {
            postings.businessError(postings.create(postings.body(requisition, workLocation, wrong), hrToken),
                    "INVALID_JOB_POSTING_EMPLOYMENT_TYPE", "employmentTypeId", "Hình thức làm việc không tồn tại.");
        }
        assertThat(postings.count()).isZero();

        JsonNode posting = postings.created(postings.body(requisition, workLocation, employmentType), hrToken);
        UUID id = UUID.fromString(posting.path("id").asText());
        Map<String, Object> rowBefore = postings.row(id);
        Map<String, Object> wrongLocation = postings.putBody(posting);
        wrongLocation.put("workLocationId", employmentType);
        postings.businessError(postings.update(id, wrongLocation, hrToken), "INVALID_JOB_POSTING_WORK_LOCATION",
                "workLocationId", "Địa điểm làm việc không tồn tại.");
        Map<String, Object> wrongType = postings.putBody(posting);
        wrongType.put("employmentTypeId", UUID.randomUUID());
        postings.businessError(postings.update(id, wrongType, hrToken), "INVALID_JOB_POSTING_EMPLOYMENT_TYPE",
                "employmentTypeId", "Hình thức làm việc không tồn tại.");
        assertThat(postings.row(id)).isEqualTo(rowBefore);
    }

    @Test
    void aCatalogValueUsedByAPostingCannotBeDeleted() throws Exception {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        postings.created(postings.body(requisition, workLocation, employmentType), hrToken);
        UUID unused = postings.catalogValue(WORK_LOCATION, "HCM", "TP. Hồ Chí Minh", true, START);

        for (var item : List.of("WORK_LOCATION/items/" + workLocation, "EMPLOYMENT_TYPE/items/" + employmentType)) {
            postings.error(api.request("DELETE", "/api/v1/recruitment-catalogs/" + item, null, hrToken), 409,
                    "RECRUITMENT_CATALOG_ITEM_IN_USE");
        }
        assertThat(api.request("DELETE", "/api/v1/recruitment-catalogs/WORK_LOCATION/items/" + unused, null,
                hrToken).statusCode()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recruitment_catalog_items", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void pageAndSizeAreChecked() throws Exception {
        for (String query : List.of("?page=-1", "?size=0", "?size=101", "?page=2147483647&size=100")) {
            assertThat(postings.error(postings.list(query, hrToken), 400, "VALIDATION_ERROR").path("message")
                    .asText()).as(query).isEqualTo("Trang hoặc số lượng tin tuyển dụng không hợp lệ.");
            assertThat(postings.error(postings.sources(query, hrToken), 400, "VALIDATION_ERROR").path("message")
                    .asText()).as(query).isEqualTo("Trang hoặc số lượng yêu cầu tuyển dụng không hợp lệ.");
        }
        postings.error(postings.list("?status=draft", hrToken), 400, "VALIDATION_ERROR");
        api.expect(postings.list("?status=DRAFT&page=0&size=100", hrToken), 200);
        api.expect(postings.sources("?page=0&size=100", hrToken), 200);
    }

    private void assign(UUID requisition, Map<String, Object> body) throws Exception {
        api.expect(api.request("POST", "/api/v1/requisitions/" + requisition + "/assign",
                json.writeValueAsString(body), hrToken), 200);
    }

    private UUID account(String email, String fullName, Role role) {
        return accounts.saveAndFlush(new Account(email, fullName, passwordHash, Set.of(role), START)).getId();
    }

    private static List<String> fieldNames(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }

    private static List<UUID> ids(JsonNode page, String field) {
        List<UUID> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(UUID.fromString(item.path(field).asText())));
        return ids;
    }
}
