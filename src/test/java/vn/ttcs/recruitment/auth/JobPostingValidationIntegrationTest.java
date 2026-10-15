package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.EMPLOYMENT_TYPE;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.WORK_LOCATION;

// Task 304 (story S3-09): "Kiểm tra dữ liệu tin tuyển dụng trước khi lưu". Every save of a posting (POST and PUT)
// checks its texts (required, length, plain text), its application deadline (today or later in Vietnam) and its
// catalog values (of their own catalog and still in use) before anything is written, and editing the posting's job
// description never changes the requisition it was copied from.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobPostingValidationIntegrationTest {
    // 10:00 on 11 Oct 2026 in Vietnam.
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");
    private static final List<String> TEXTS = List.of("title", "jobDescription", "candidateRequirements", "benefits");
    private static final Map<String, String> PLAIN_TEXT_MESSAGES = Map.of(
            "title", "Tiêu đề tin phải là văn bản thuần trên một dòng, có ký tự nhìn thấy được, không chứa thẻ HTML.",
            "jobDescription", "Mô tả công việc phải là văn bản thuần có ký tự nhìn thấy được, không chứa thẻ HTML.",
            "candidateRequirements",
            "Yêu cầu ứng viên phải là văn bản thuần có ký tự nhìn thấy được, không chứa thẻ HTML.",
            "benefits", "Quyền lợi phải là văn bản thuần có ký tự nhìn thấy được, không chứa thẻ HTML.");
    private static final String DEADLINE_IN_PAST = "Hạn nộp hồ sơ không được trước ngày hôm nay.";
    private static final String LOCATION_INACTIVE = "Địa điểm làm việc đã ngừng sử dụng, vui lòng chọn giá trị khác.";
    private static final String TYPE_INACTIVE = "Hình thức làm việc đã ngừng sử dụng, vui lòng chọn giá trị khác.";

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
        account("recruiter2@example.test", "Người ngoài", Role.RECRUITER);
        otherRecruiterToken = api.token("recruiter2@example.test");
        department = api.department("IT", headId, null, START);
        position = api.position(START);
        // HR_MANAGER takes the only level, so requisitions created and submitted by ADMIN are approved by HR.
        api.flow(department, hrId, START, step(1, null, null, "HR_MANAGER"));
        workLocation = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, START);
        employmentType = postings.catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", true, START);
    }

    @Test
    void requiredFieldsOfASaveHaveTheirOwnMessage() throws Exception {
        JsonNode draft = draft();
        UUID id = id(draft);
        Map<String, Object> rowBefore = postings.row(id);

        JsonNode missing = postings.fieldErrors(postings.update(id, Map.of("expectedRevision", 1), hrToken),
                "title", "jobDescription", "candidateRequirements", "showSalary");

        assertThat(missing.path("title").asText()).isEqualTo("Tiêu đề tin không được để trống.");
        assertThat(missing.path("jobDescription").asText()).isEqualTo("Mô tả công việc không được để trống.");
        assertThat(missing.path("candidateRequirements").asText()).isEqualTo("Yêu cầu ứng viên không được để trống.");
        assertThat(missing.path("showSalary").asText()).isEqualTo("Vui lòng chọn có hiển thị mức lương hay không.");

        // Texts made only of spaces and line breaks count as missing: only the "không được để trống" message, never
        // the plain text message as well.
        Map<String, Object> blank = postings.putBody(draft);
        blank.put("title", " \u00a0\t");
        blank.put("jobDescription", " \r\n ");
        blank.put("candidateRequirements", "");
        JsonNode blanks = postings.fieldErrors(postings.update(id, blank, hrToken),
                "title", "jobDescription", "candidateRequirements");
        assertThat(blanks.path("title").asText()).isEqualTo("Tiêu đề tin không được để trống.");
        assertThat(blanks.path("jobDescription").asText()).isEqualTo("Mô tả công việc không được để trống.");

        Map<String, Object> noRevision = postings.putBody(draft);
        noRevision.remove("expectedRevision");
        assertThat(postings.fieldErrors(postings.update(id, noRevision, hrToken), "expectedRevision")
                .path("expectedRevision").asText()).isEqualTo("Thiếu số phiên bản tin tuyển dụng đang xem.");
        Map<String, Object> zeroRevision = postings.putBody(draft);
        zeroRevision.put("expectedRevision", 0);
        assertThat(postings.fieldErrors(postings.update(id, zeroRevision, hrToken), "expectedRevision")
                .path("expectedRevision").asText()).isEqualTo("Số phiên bản tin tuyển dụng không hợp lệ.");
        assertThat(postings.row(id)).isEqualTo(rowBefore);
    }

    @Test
    void textLimitsAtTheEdge() throws Exception {
        JsonNode view = draft();
        UUID id = id(view);
        // "Đ" is one character but two bytes in UTF-8: the limits count characters, as VARCHAR(255) does.
        Map<String, Integer> limits = Map.of("title", 255, "jobDescription", 10_000, "candidateRequirements", 10_000,
                "benefits", 10_000);
        Map<String, String> messages = Map.of("title", "Tiêu đề tin tối đa 255 ký tự.",
                "jobDescription", "Mô tả công việc tối đa 10.000 ký tự.",
                "candidateRequirements", "Yêu cầu ứng viên tối đa 10.000 ký tự.",
                "benefits", "Quyền lợi tối đa 10.000 ký tự.");

        for (String field : TEXTS) {
            int limit = limits.get(field);
            Map<String, Object> atLimit = postings.putBody(view);
            atLimit.put(field, "Đ".repeat(limit));
            view = api.expect(postings.update(id, atLimit, hrToken), 200);
            assertThat(view.path(field).asText()).as(field).isEqualTo("Đ".repeat(limit));

            Map<String, Object> over = postings.putBody(view);
            over.put(field, "Đ".repeat(limit + 1));
            assertThat(postings.fieldErrors(postings.update(id, over, hrToken), field).path(field).asText())
                    .as(field).isEqualTo(messages.get(field));
        }
        assertThat(postings.row(id).get("revision")).isEqualTo(5);

        // The same limits when the posting is created.
        UUID vacant = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        for (String field : TEXTS) {
            Map<String, Object> body = new LinkedHashMap<>(Map.of("requisitionId", vacant));
            body.put(field, "Đ".repeat(limits.get(field) + 1));
            assertThat(postings.fieldErrors(postings.create(body, hrToken), field).path(field).asText())
                    .as(field).isEqualTo(messages.get(field));
        }
        assertThat(postings.count()).isOne();
    }

    @Test
    void textsArePlainText() throws Exception {
        JsonNode view = draft();
        UUID id = id(view);
        Map<String, Object> rowBefore = postings.row(id);
        // Tags, closing tags, comments and declarations; NUL; the Unicode line separator; a bidi override; a text
        // made only of an invisible character.
        List<String> refused = List.of("Lập trình viên <b>Java</b>", "Hết đoạn</p>", "Ẩn <!-- ghi chú -->",
                "<?xml version=\"1.0\"?>", "Có ký tự NUL\u0000ở giữa", "Dòng một\u2028Dòng hai",
                "Java \u202egnirpS", "\u200b");

        for (String field : TEXTS) {
            for (String text : refused) {
                Map<String, Object> body = postings.putBody(view);
                body.put(field, text);
                assertThat(postings.fieldErrors(postings.update(id, body, hrToken), field).path(field).asText())
                        .as(field + ": " + text).isEqualTo(PLAIN_TEXT_MESSAGES.get(field));
            }
        }
        // The title stays on one line, without tabs; the other texts may use both.
        for (String title : List.of("Lập trình viên\nJava", "Lập trình viên\tJava")) {
            Map<String, Object> body = postings.putBody(view);
            body.put("title", title);
            postings.fieldErrors(postings.update(id, body, hrToken), "title");
        }
        UUID vacant = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        for (String field : TEXTS) {
            postings.fieldErrors(postings.create(Map.of("requisitionId", vacant, field, "<b>Java</b>"), hrToken),
                    field);
        }
        assertThat(postings.row(id)).isEqualTo(rowBefore);
        assertThat(postings.count()).isOne();

        // A '<' that no browser reads as markup is plain text and is kept exactly.
        Map<String, Object> lookAlike = postings.putBody(view);
        lookAlike.put("title", "Lập trình viên C++ <= 3 năm kinh nghiệm");
        lookAlike.put("jobDescription", "Mức lương < 20 triệu.\n\tLàm việc từ xa <3 ngày mỗi tuần.");
        lookAlike.put("candidateRequirements", "C++ <= 5 năm.\n<3 dự án lớn.");
        lookAlike.put("benefits", "Thưởng khi 5 < 10.");
        JsonNode saved = api.expect(postings.update(id, lookAlike, hrToken), 200);
        for (String field : TEXTS) {
            assertThat(saved.path(field).asText()).as(field).isEqualTo(lookAlike.get(field));
        }
    }

    @Test
    void lineBreaksAreStoredAsLineFeedsAndIndentationIsKept() throws Exception {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        Map<String, Object> body = postings.body(requisition, workLocation, employmentType);
        body.put("title", "\u00a0 Lập trình viên  Java\t ");
        body.put("jobDescription", "  Phát triển API.\r\n\r\n    - Spring Boot\r- PostgreSQL\r\n");
        body.put("candidateRequirements", "Hai năm Java.\rĐọc tiếng Anh.");
        body.put("benefits", "\tBảo hiểm.\r\n\tThưởng tháng 13.");

        JsonNode created = postings.created(body, hrToken);

        // Spaces at both ends of the title go (the space inside stays); the texts keep every space, tab and empty
        // line, and only their line breaks change.
        assertThat(created.path("title").asText()).isEqualTo("Lập trình viên  Java");
        assertThat(created.path("jobDescription").asText())
                .isEqualTo("  Phát triển API.\n\n    - Spring Boot\n- PostgreSQL\n");
        assertThat(created.path("candidateRequirements").asText()).isEqualTo("Hai năm Java.\nĐọc tiếng Anh.");
        assertThat(created.path("benefits").asText()).isEqualTo("\tBảo hiểm.\n\tThưởng tháng 13.");
        UUID id = id(created);
        Map<String, Object> row = postings.row(id);
        assertThat(row.get("title")).isEqualTo("Lập trình viên  Java");
        assertThat(row.get("job_description")).isEqualTo("  Phát triển API.\n\n    - Spring Boot\n- PostgreSQL\n");
        assertThat(row.get("benefits")).isEqualTo("\tBảo hiểm.\n\tThưởng tháng 13.");

        Map<String, Object> change = postings.putBody(created);
        change.put("title", "  Kỹ sư phần mềm\r\n");
        change.put("jobDescription", "Dòng một\r\n  Dòng hai thụt lề\r\n\r\n");
        JsonNode saved = api.expect(postings.update(id, change, hrToken), 200);

        assertThat(saved.path("title").asText()).isEqualTo("Kỹ sư phần mềm");
        assertThat(saved.path("jobDescription").asText()).isEqualTo("Dòng một\n  Dòng hai thụt lề\n\n");
        assertThat(postings.row(id).get("job_description")).isEqualTo("Dòng một\n  Dòng hai thụt lề\n\n");
    }

    @Test
    void inheritedTextIsNormalizedAndKeptUntilTheAuthorRewritesIt() throws Exception {
        // The requisition API accepts HTML and keeps Windows and old Mac line breaks; the posting copies both texts
        // with \n.
        UUID requisition = postings.approvedRequisition(position, department, adminToken, hrToken,
                "<b>Java</b>\r\nSpring", "Hai năm Java.\r\nĐọc tiếng Anh.\rLàm việc nhóm.");
        assertThat(postings.requisitionRow(requisition).get("job_description")).isEqualTo("<b>Java</b>\r\nSpring");
        assertThat(postings.requisitionRow(requisition).get("candidate_requirements"))
                .isEqualTo("Hai năm Java.\r\nĐọc tiếng Anh.\rLàm việc nhóm.");

        JsonNode created = postings.created(Map.of("requisitionId", requisition), hrToken);

        assertThat(created.path("jobDescription").asText()).isEqualTo("<b>Java</b>\nSpring");
        assertThat(created.path("candidateRequirements").asText())
                .isEqualTo("Hai năm Java.\nĐọc tiếng Anh.\nLàm việc nhóm.");
        UUID id = id(created);
        Map<String, Object> rowBefore = postings.row(id);
        assertThat(rowBefore.get("job_description")).isEqualTo("<b>Java</b>\nSpring");
        assertThat(rowBefore.get("candidate_requirements")).isEqualTo("Hai năm Java.\nĐọc tiếng Anh.\nLàm việc nhóm.");

        // Saving the inherited text unchanged is refused: the author rewrites it first.
        assertThat(postings.fieldErrors(postings.update(id, postings.putBody(created), hrToken), "jobDescription")
                .path("jobDescription").asText()).isEqualTo(PLAIN_TEXT_MESSAGES.get("jobDescription"));
        assertThat(postings.row(id)).isEqualTo(rowBefore);

        Map<String, Object> rewritten = postings.putBody(created);
        rewritten.put("jobDescription", "Java\nSpring");
        JsonNode saved = api.expect(postings.update(id, rewritten, hrToken), 200);

        assertThat(saved.path("jobDescription").asText()).isEqualTo("Java\nSpring");
        assertThat(saved.path("revision").asInt()).isEqualTo(2);
        // The requisition keeps its own texts.
        assertThat(postings.requisitionRow(requisition).get("job_description")).isEqualTo("<b>Java</b>\r\nSpring");
        assertThat(postings.requisitionRow(requisition).get("candidate_requirements"))
                .isEqualTo("Hai năm Java.\r\nĐọc tiếng Anh.\rLàm việc nhóm.");
    }

    @Test
    void theApplicationDeadlineIsTodayOrLaterInVietnam() throws Exception {
        // 17:30 UTC on 11 Oct is 00:30 on 12 Oct in Vietnam: 11 Oct is already yesterday although UTC is still on it.
        clock.set(Instant.parse("2026-10-11T17:30:00Z"));
        String token = api.token("hr@example.test");
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        UUID vacant = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        Map<String, Object> body = postings.body(requisition, workLocation, employmentType);
        body.put("applicationDeadline", "2026-10-11");

        postings.businessError(postings.create(body, token), "JOB_POSTING_DEADLINE_IN_PAST", "applicationDeadline",
                DEADLINE_IN_PAST);
        assertThat(postings.count()).isZero();

        // Today is allowed.
        body.put("applicationDeadline", "2026-10-12");
        JsonNode created = postings.created(body, token);
        assertThat(created.path("applicationDeadline").asText()).isEqualTo("2026-10-12");
        UUID id = id(created);
        Map<String, Object> rowBefore = postings.row(id);
        Map<String, Object> past = postings.putBody(created);
        past.put("applicationDeadline", "2026-10-11");
        postings.businessError(postings.update(id, past, token), "JOB_POSTING_DEADLINE_IN_PAST",
                "applicationDeadline", DEADLINE_IN_PAST);
        assertThat(postings.row(id)).isEqualTo(rowBefore);

        // No latest date, and a draft may leave the deadline empty.
        JsonNode view = created;
        for (String deadline : new String[] {"9999-12-31", null, "2026-10-12"}) {
            Map<String, Object> change = postings.putBody(view);
            change.put("applicationDeadline", deadline);
            view = api.expect(postings.update(id, change, token), 200);
        }
        assertThat(view.path("revision").asInt()).isEqualTo(4);

        // An impossible date is not a date at all.
        Map<String, Object> impossible = postings.putBody(view);
        impossible.put("applicationDeadline", "2026-02-30");
        postings.error(postings.update(id, impossible, token), 400, "INVALID_JSON");
        Map<String, Object> impossibleCreate = postings.body(vacant, workLocation, employmentType);
        impossibleCreate.put("applicationDeadline", "2026-02-30");
        postings.error(postings.create(impossibleCreate, token), 400, "INVALID_JSON");

        // The next day starts at 17:00 UTC: the saved deadline is now in the past. The draft is still read as it is,
        // but saved again only with a new date (or none).
        clock.set(Instant.parse("2026-10-12T17:00:00Z"));
        token = api.token("hr@example.test");
        JsonNode read = api.expect(postings.get(id, token), 200);
        assertThat(read.path("applicationDeadline").asText()).isEqualTo("2026-10-12");
        postings.businessError(postings.update(id, postings.putBody(read), token), "JOB_POSTING_DEADLINE_IN_PAST",
                "applicationDeadline", DEADLINE_IN_PAST);
        Map<String, Object> moved = postings.putBody(read);
        moved.put("applicationDeadline", "2026-10-13");
        assertThat(api.expect(postings.update(id, moved, token), 200).path("applicationDeadline").asText())
                .isEqualTo("2026-10-13");
    }

    @Test
    void deactivatedOrForeignCatalogValuesAreRefused() throws Exception {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        UUID closedLocation = postings.catalogValue(WORK_LOCATION, "DN", "Đà Nẵng", false, START);
        UUID closedType = postings.catalogValue(EMPLOYMENT_TYPE, "PART_TIME", "Bán thời gian", false, START);

        postings.businessError(postings.create(postings.body(requisition, closedLocation, employmentType), hrToken),
                "JOB_POSTING_WORK_LOCATION_INACTIVE", "workLocationId", LOCATION_INACTIVE);
        postings.businessError(postings.create(postings.body(requisition, workLocation, closedType), hrToken),
                "JOB_POSTING_EMPLOYMENT_TYPE_INACTIVE", "employmentTypeId", TYPE_INACTIVE);
        // A value of another catalog does not exist for this field, in use or not.
        postings.businessError(postings.create(postings.body(requisition, closedType, employmentType), hrToken),
                "INVALID_JOB_POSTING_WORK_LOCATION", "workLocationId", "Địa điểm làm việc không tồn tại.");
        postings.businessError(postings.create(postings.body(requisition, workLocation, closedLocation), hrToken),
                "INVALID_JOB_POSTING_EMPLOYMENT_TYPE", "employmentTypeId", "Hình thức làm việc không tồn tại.");
        // The work location comes before the employment type, and the deadline before both.
        postings.businessError(postings.create(postings.body(requisition, closedLocation, closedType), hrToken),
                "JOB_POSTING_WORK_LOCATION_INACTIVE", "workLocationId", LOCATION_INACTIVE);
        Map<String, Object> pastAndClosed = postings.body(requisition, closedLocation, closedType);
        pastAndClosed.put("applicationDeadline", "2026-10-10");
        postings.businessError(postings.create(pastAndClosed, hrToken), "JOB_POSTING_DEADLINE_IN_PAST",
                "applicationDeadline", DEADLINE_IN_PAST);
        assertThat(postings.count()).isZero();

        // HR stops using a value a draft holds: the draft stays readable as it is, and is saved again only with
        // another value.
        JsonNode created = postings.created(postings.body(requisition, workLocation, employmentType), hrToken);
        UUID id = id(created);
        api.expect(api.request("PUT", "/api/v1/recruitment-catalogs/WORK_LOCATION/items/" + workLocation,
                json.writeValueAsString(Map.of("code", "HN", "name", "Hà Nội", "active", false)), hrToken), 200);
        JsonNode read = api.expect(postings.get(id, hrToken), 200);
        assertThat(read.path("workLocationId").asText()).isEqualTo(workLocation.toString());
        assertThat(read.path("workLocationName").asText()).isEqualTo("Hà Nội");
        assertThat(read.path("actions").path("canEdit").asBoolean()).isTrue();
        Map<String, Object> rowBefore = postings.row(id);

        postings.businessError(postings.update(id, postings.putBody(read), hrToken),
                "JOB_POSTING_WORK_LOCATION_INACTIVE", "workLocationId", LOCATION_INACTIVE);
        assertThat(postings.row(id)).isEqualTo(rowBefore);

        UUID open = postings.catalogValue(WORK_LOCATION, "HCM", "TP. Hồ Chí Minh", true, START);
        Map<String, Object> another = postings.putBody(read);
        another.put("workLocationId", open);
        assertThat(api.expect(postings.update(id, another, hrToken), 200).path("workLocationName").asText())
                .isEqualTo("TP. Hồ Chí Minh");
    }

    @Test
    void editingTheJobDescriptionOfThePostingNeverChangesTheRequisition() throws Exception {
        UUID requisition = postings.approvedRequisition(position, department, adminToken, hrToken);
        postings.assign(requisition, recruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
        List<Object> before = requisitionState(requisition);
        Instant now = START.plusSeconds(60);
        clock.set(now);

        JsonNode view = postings.created(Map.of("requisitionId", requisition), recruiterToken);

        assertThat(requisitionState(requisition)).isEqualTo(before);
        UUID id = id(view);
        for (String text : List.of("Viết lại cho ứng viên.", "Lần sửa thứ hai.\n- Có gạch đầu dòng.",
                "Lần sửa thứ ba.")) {
            now = now.plusSeconds(60);
            clock.set(now);
            Map<String, Object> change = postings.putBody(view);
            change.put("jobDescription", text);
            change.put("candidateRequirements", "Yêu cầu: " + text);
            view = api.expect(postings.update(id, change, recruiterToken), 200);
            assertThat(view.path("jobDescription").asText()).isEqualTo(text);
            assertThat(requisitionState(requisition)).isEqualTo(before);
        }
        assertThat(view.path("revision").asInt()).isEqualTo(4);

        // The requisition API still shows the original texts, and a copy of the requisition copies them.
        JsonNode original = api.expect(api.request("GET", "/api/v1/requisitions/" + requisition, null, hrToken), 200);
        assertThat(original.path("jobDescription").asText()).isEqualTo(JOB_DESCRIPTION);
        assertThat(original.path("candidateRequirements").asText()).isEqualTo(REQUIREMENTS);
        JsonNode copy = api.expect(api.request("POST", "/api/v1/requisitions/" + requisition + "/copy", null,
                hrToken), 201);
        assertThat(copy.path("jobDescription").asText()).isEqualTo(JOB_DESCRIPTION);
        assertThat(copy.path("candidateRequirements").asText()).isEqualTo(REQUIREMENTS);
    }

    @Test
    void invalidSavesWriteNothing() throws Exception {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        JsonNode view = postings.created(postings.body(requisition, workLocation, employmentType), hrToken);
        UUID id = id(view);
        UUID closedLocation = postings.catalogValue(WORK_LOCATION, "DN", "Đà Nẵng", false, START);
        Map<String, Object> rowBefore = postings.row(id);
        List<Map<String, Object>> invalid = new ArrayList<>();
        for (Object[] change : new Object[][] {{"title", "<b>Java</b>"}, {"jobDescription", "x".repeat(10_001)},
                {"benefits", "\u200b"}, {"showSalary", null}, {"applicationDeadline", "2026-10-10"},
                {"workLocationId", closedLocation}, {"employmentTypeId", UUID.randomUUID()}}) {
            Map<String, Object> body = postings.putBody(view);
            body.put((String) change[0], change[1]);
            invalid.add(body);
        }

        // ADMIN is not an author yet, so a save that slipped through would also add an author.
        for (Map<String, Object> body : invalid) {
            var response = postings.update(id, body, adminToken);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
            postings.noStore(response);
        }

        assertThat(postings.row(id)).isEqualTo(rowBefore);
        assertThat(postings.row(id).get("revision")).isEqualTo(1);
        assertThat(postings.authorsOf(id)).containsExactly(hrId);
    }

    @Test
    void forbiddenCallersGet403BeforeFieldChecks() throws Exception {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        JsonNode view = postings.created(postings.body(requisition, workLocation, employmentType), hrToken);
        UUID id = id(view);
        Map<String, Object> rowBefore = postings.row(id);
        String headToken = api.token("head@example.test");
        Map<String, Object> invalidCreate = postings.body(UUID.randomUUID(), UUID.randomUUID(), employmentType);
        invalidCreate.put("title", "<b>Java</b>");
        invalidCreate.put("applicationDeadline", "2026-10-10");
        Map<String, Object> invalidSave = postings.putBody(view);
        invalidSave.put("title", "<b>Java</b>");
        invalidSave.put("applicationDeadline", "2026-10-10");

        // HIRING_MANAGER (JOB_POSTINGS_READ_ALL only) is stopped by the route rule before the body is read.
        postings.forbidden(postings.create(invalidCreate, headToken));
        postings.forbidden(postings.update(id, invalidSave, headToken));

        // A recruiter passes the route rule (JOB_POSTINGS_WRITE_SCOPED), and @Valid checks the body before the
        // service sees the scope (like requisitions, docs/api/requisitions.md): a recruiter outside the requisition's
        // team gets the 400 of the body. With a valid body it gets the 403 of the scope, before the deadline.
        postings.fieldErrors(postings.update(id, invalidSave, otherRecruiterToken), "title");
        Map<String, Object> pastOnly = postings.putBody(view);
        pastOnly.put("applicationDeadline", "2026-10-10");
        postings.forbidden(postings.update(id, pastOnly, otherRecruiterToken));
        assertThat(postings.row(id)).isEqualTo(rowBefore);
        assertThat(postings.count()).isOne();
    }

    // A draft made by HR from an approved requisition, with the inherited texts and no public details yet.
    private JsonNode draft() throws Exception {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        return postings.created(Map.of("requisitionId", requisition), hrToken);
    }

    // Everything the posting code must never write: the requisition row (updated_at included), its approval history
    // and its recruiter team.
    private List<Object> requisitionState(UUID requisition) {
        return List.of(postings.requisitionRow(requisition),
                jdbc.queryForList("SELECT * FROM requisition_workflow_events WHERE requisition_id = ? "
                        + "ORDER BY revision", requisition),
                jdbc.queryForList("SELECT * FROM requisition_recruiters WHERE requisition_id = ? "
                        + "ORDER BY recruiter_id", requisition));
    }

    private UUID account(String email, String fullName, Role role) {
        return accounts.saveAndFlush(new Account(email, fullName, passwordHash, Set.of(role), START)).getId();
    }

    private static UUID id(JsonNode view) {
        return UUID.fromString(view.path("id").asText());
    }
}
