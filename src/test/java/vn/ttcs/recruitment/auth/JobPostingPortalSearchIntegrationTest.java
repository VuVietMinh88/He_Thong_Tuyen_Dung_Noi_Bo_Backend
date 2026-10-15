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
import vn.ttcs.recruitment.catalog.RecruitmentCatalogType;
import vn.ttcs.recruitment.requisition.RequisitionRecruiterRole;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.auth.JobPostingTestSupport.PUBLIC_POSTINGS;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.EMPLOYMENT_TYPE;
import static vn.ttcs.recruitment.catalog.RecruitmentCatalogType.WORK_LOCATION;

// Task 315 (story S4-01): "Xử lý bộ lọc và tìm kiếm nội dung việc làm". The public job list is filtered by department,
// work location and employment type, and searched by a keyword in the posting's title and job description; every
// filter given narrows the open postings (AND), in the page and in its count. The values of the filter lists come
// from GET /public/job-postings/filters, only from the postings listed today. Everything is read without an account.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobPostingPortalSearchIntegrationTest {
    // 10:00 on 11 Oct 2026 in Vietnam. Postings may be applied to until 30 Nov 2026 unless a test says otherwise.
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");
    private static final String KEYWORD_MESSAGE = "Từ khóa tìm kiếm tin tuyển dụng không hợp lệ.";
    private static final String PAGE_MESSAGE = "Trang hoặc số lượng tin tuyển dụng không hợp lệ.";
    private static final String PARAMETER_MESSAGE = "Tham số đường dẫn hoặc bộ lọc không hợp lệ.";
    private static final String TOO_LONG = "Từ khóa tìm kiếm tối đa 255 ký tự.";
    private static final String CONTROL = "Từ khóa tìm kiếm không được chứa ký tự điều khiển.";

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
    private UUID hrId;
    private String hrToken;
    private UUID headId;
    private UUID recruiterId;
    private String recruiterToken;
    private UUID position;
    private Instant now;

    // One posting of a test with the values it was published with, newest publication last.
    private record Posting(UUID id, UUID department, UUID workLocation, UUID employmentType) { }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void resetFixture() throws Exception {
        now = START;
        clock.set(START);
        postings = new JobPostingTestSupport(jdbc, json, transactions,
                Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        api = postings.api;
        postings.reset();
        bootstrap.run(new DefaultApplicationArguments());
        adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = account("hr@example.test", "Nguyễn Thị Hoa", Role.HR_MANAGER);
        hrToken = api.token("hr@example.test");
        headId = account("head@example.test", "Trưởng phòng IT", Role.HIRING_MANAGER);
        recruiterId = account("recruiter@example.test", "Trần Văn Tuyển", Role.RECRUITER);
        recruiterToken = api.token("recruiter@example.test");
        position = api.position(START);
    }

    // S4-01 AC2: "Lọc theo phòng ban, địa điểm, hình thức làm việc". Each filter alone and together narrows the page,
    // totalElements and totalPages; the order stays newest publication first. A department is exactly that
    // department: the posting of a sub-department is not found with its parent's id.
    @Test
    void eachFilterNarrowsTheListAndTheCount() throws Exception {
        UUID it = api.department("IT", headId, null, START);
        UUID kt = api.department("KT", headId, null, START);
        UUID itApp = api.department("IT_APP", headId, it, START);
        UUID hn = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, START);
        UUID hcm = postings.catalogValue(WORK_LOCATION, "HCM", "TP. Hồ Chí Minh", true, START);
        UUID full = postings.catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", true, START);
        UUID part = postings.catalogValue(EMPLOYMENT_TYPE, "PART_TIME", "Bán thời gian", true, START);
        List<Posting> all = new ArrayList<>();
        for (UUID department : List.of(it, kt)) {
            for (UUID location : List.of(hn, hcm)) {
                for (UUID type : List.of(full, part)) {
                    all.add(new Posting(published(department, location, type, "Kỹ sư", "Mô tả công việc."),
                            department, location, type));
                }
            }
        }
        all.add(new Posting(published(itApp, hn, full, "Kỹ sư ứng dụng", "Mô tả công việc."), itApp, hn, full));

        assertPage("", all, posting -> true);
        assertPage("?departmentId=" + it, all, posting -> posting.department().equals(it));
        assertPage("?departmentId=" + kt, all, posting -> posting.department().equals(kt));
        assertPage("?departmentId=" + itApp, all, posting -> posting.department().equals(itApp));
        assertPage("?workLocationId=" + hn, all, posting -> posting.workLocation().equals(hn));
        assertPage("?workLocationId=" + hcm, all, posting -> posting.workLocation().equals(hcm));
        assertPage("?employmentTypeId=" + full, all, posting -> posting.employmentType().equals(full));
        assertPage("?employmentTypeId=" + part, all, posting -> posting.employmentType().equals(part));
        assertPage("?departmentId=" + it + "&workLocationId=" + hn, all,
                posting -> posting.department().equals(it) && posting.workLocation().equals(hn));
        assertPage("?workLocationId=" + hcm + "&employmentTypeId=" + part, all,
                posting -> posting.workLocation().equals(hcm) && posting.employmentType().equals(part));
        // Every filter at once matches one posting of the grid.
        JsonNode one = assertPage("?departmentId=" + kt + "&workLocationId=" + hcm + "&employmentTypeId=" + full, all,
                posting -> posting.department().equals(kt) && posting.workLocation().equals(hcm)
                        && posting.employmentType().equals(full));
        assertThat(one.path("totalElements").asLong()).isEqualTo(1);
        assertThat(ok(postings.publicList("?departmentId=" + itApp + "&workLocationId=" + hcm))
                .path("totalElements").asLong()).isZero();

        // Pages under a filter: five postings in Hà Nội, two per page.
        List<UUID> inHanoi = expected(all, posting -> posting.workLocation().equals(hn));
        assertThat(inHanoi).hasSize(5);
        for (int page = 0; page < 4; page++) {
            JsonNode body = ok(postings.publicList("?workLocationId=" + hn + "&page=" + page + "&size=2"));
            assertThat(ids(body)).as("page " + page)
                    .containsExactlyElementsOf(inHanoi.subList(Math.min(page * 2, 5), Math.min(page * 2 + 2, 5)));
            assertThat(body.path("page").asInt()).isEqualTo(page);
            assertThat(body.path("size").asInt()).isEqualTo(2);
            assertThat(body.path("totalElements").asLong()).isEqualTo(5);
            assertThat(body.path("totalPages").asLong()).isEqualTo(3);
        }
    }

    // An id that matches nothing is not an error: an unknown id, the id of a value of the other catalog and a department
    // whose only posting is a draft all give the same empty page, so the portal tells nothing about them.
    @Test
    void unknownIdsGiveAnEmptyPageNotAnError() throws Exception {
        UUID it = api.department("IT", headId, null, START);
        UUID ns = api.department("NS", headId, null, START);
        UUID hn = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, START);
        UUID full = postings.catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", true, START);
        UUID open = published(it, hn, full, "Kỹ sư", "Mô tả công việc.");
        draft(ns, hn, full, "Kỹ sư nhân sự", "Mô tả công việc.", JobPostingTestSupport.DEADLINE);
        assertThat(ids(ok(postings.publicList("")))).containsExactly(open);

        List<String> bodies = new ArrayList<>();
        for (String query : List.of("?departmentId=" + UUID.randomUUID(), "?workLocationId=" + UUID.randomUUID(),
                "?employmentTypeId=" + UUID.randomUUID(), "?employmentTypeId=" + hn, "?workLocationId=" + full,
                "?departmentId=" + ns, "?departmentId=" + it + "&employmentTypeId=" + hn)) {
            var response = postings.publicList(query);
            JsonNode page = ok(response);
            postings.noStore(response);
            assertThat(fieldNames(page)).as(query)
                    .containsExactly("items", "page", "size", "totalElements", "totalPages");
            assertThat(page.path("items").size()).as(query).isZero();
            assertThat(page.path("totalElements").asLong()).as(query).isZero();
            assertThat(page.path("totalPages").asLong()).as(query).isZero();
            bodies.add(response.body());
        }
        assertThat(bodies).allSatisfy(body -> assertThat(body).isEqualTo(bodies.get(0)));
    }

    // A value that is not a UUID is refused before the service runs; an empty value is no filter, and parameters the
    // portal does not know (keyword instead of q, utm_* of a shared link) are ignored.
    @Test
    void malformedIdsAreRefusedAndEmptyMeansNone() throws Exception {
        UUID it = api.department("IT", headId, null, START);
        UUID hn = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, START);
        UUID full = postings.catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", true, START);
        UUID open = published(it, hn, full, "Kỹ sư", "Mô tả công việc.");

        for (String parameter : List.of("departmentId", "workLocationId", "employmentTypeId")) {
            for (String value : List.of("abc", "123", it + "x")) {
                var refused = postings.publicList("?" + parameter + "=" + value);
                JsonNode body = postings.error(refused, 400, "VALIDATION_ERROR");
                assertThat(fieldNames(body)).as(parameter).containsExactly("code", "message", "fieldErrors");
                assertThat(body.path("message").asText()).as(parameter).isEqualTo(PARAMETER_MESSAGE);
                assertThat(body.path("fieldErrors")).as(parameter).isEqualTo(json.createObjectNode());
            }
        }

        String unfiltered = ok(postings.publicList("")).toString();
        assertThat(ids(json.readTree(unfiltered))).containsExactly(open);
        for (String query : List.of("?departmentId=", "?departmentId=&workLocationId=&employmentTypeId=&q=",
                "?keyword=xyz&utm_source=facebook&utm_campaign=s4", "?q=&page=&size=")) {
            assertThat(ok(postings.publicList(query)).toString()).as(query).isEqualTo(unfiltered);
        }
    }

    // S4-01 AC3: "Tìm từ khóa trong tiêu đề và mô tả". The keyword is one phrase found in the title or the job
    // description, ignoring upper/lower case, the spaces and line breaks between the words (in the title as in the
    // description) and the way a Vietnamese letter is encoded (NFC or NFD, on either side), but not the accents
    // (question 21(4)); %, _ and ! are searched as themselves and a quote cannot change the SQL. It works together with
    // every filter.
    @Test
    void theKeywordSearchesTitleAndDescription() throws Exception {
        UUID it = api.department("IT", headId, null, START);
        UUID kt = api.department("KT", headId, null, START);
        UUID hn = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, START);
        UUID hcm = postings.catalogValue(WORK_LOCATION, "HCM", "TP. Hồ Chí Minh", true, START);
        UUID full = postings.catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", true, START);
        UUID part = postings.catalogValue(EMPLOYMENT_TYPE, "PART_TIME", "Bán thời gian", true, START);
        // A title may keep runs of spaces between its words: the API only strips its two ends.
        UUID accountant = published(kt, hn, full, "Kế  toán   tổng hợp", "Quản lý 1000 hóa đơn mỗi tháng.");
        assertThat(postings.row(accountant).get("title")).isEqualTo("Kế  toán   tổng hợp");
        UUID developer = published(it, hcm, full, "Lập trình viên Java",
                "Phối hợp với phòng kế toán. Làm việc 100% từ xa.");
        UUID tester = published(it, hn, part, "Kiểm thử", "Viết kịch bản.");
        // Texts saved by another client as base letters plus combining marks (NFD), with a line break and a run of
        // spaces between words.
        String storedTitle = Normalizer.normalize("Kiểm thử phần mềm", Normalizer.Form.NFD);
        String storedDescription = Normalizer.normalize(
                "Viết kịch bản\n\nkiểm   thử theo quy ước snake_case. Chú ý!", Normalizer.Form.NFD);
        assertThat(storedTitle).isNotEqualTo("Kiểm thử phần mềm");
        jdbc.update("UPDATE job_postings SET title = ?, job_description = ? WHERE id = ?", storedTitle,
                storedDescription, tester);

        // A hit in the title, a hit in the description, and both; upper case Vietnamese letters.
        assertSearch("java", developer);
        assertSearch("KẾ TOÁN", accountant, developer);
        assertSearch("  kế \t toán  ", accountant, developer);
        // An NFD keyword finds NFC text, and an NFC keyword finds NFD text.
        assertSearch(Normalizer.normalize("kế toán", Normalizer.Form.NFD), accountant, developer);
        assertSearch("kiểm thử phần mềm", tester);
        // The line break and the run of spaces in the stored text count as one space.
        assertSearch("kịch bản kiểm thử theo", tester);
        // The runs of spaces in the title count as one space too.
        assertSearch("kế toán tổng hợp", accountant);
        // Accents are compared (question 21(4)): "ke toan" does not find "kế toán".
        assertSearch("ke toan");
        // %, _ and ! are themselves, not LIKE wildcards or the escape character.
        assertSearch("100%", developer);
        assertSearch("_", tester);
        assertSearch("!", tester);
        assertSearch("1000", accountant);
        // A quote cannot change the SQL; text found nowhere gives an empty page.
        assertSearch("' OR 1=1 --");
        assertSearch("kế toán trưởng");
        // A blank keyword is no search.
        assertSearch(" \t\n ", accountant, developer, tester);
        // The keyword works together with each filter.
        assertSearchWith("kế toán", "&departmentId=" + kt, accountant);
        assertSearchWith("kế toán", "&departmentId=" + it, developer);
        assertSearchWith("kế toán", "&workLocationId=" + hcm, developer);
        assertSearchWith("kế toán", "&employmentTypeId=" + part);
        assertSearchWith("kiểm thử", "&employmentTypeId=" + part + "&workLocationId=" + hn + "&departmentId=" + it,
                tester);
        // A keyword of exactly 255 characters is accepted.
        assertSearch("x".repeat(255));
    }

    // A keyword over 255 characters or with a control character is refused with fieldErrors.q, after page and size;
    // the keyword is never repeated in the answer.
    @Test
    void invalidKeywordsAre400() throws Exception {
        UUID it = api.department("IT", headId, null, START);
        UUID hn = postings.catalogValue(WORK_LOCATION, "HN", "Hà Nội", true, START);
        UUID full = postings.catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", true, START);
        UUID open = published(it, hn, full, "Kỹ sư", "Mô tả công việc.");

        keywordError("?q=" + "x".repeat(256), TOO_LONG);
        keywordError("?q=" + encode("kế toán " + "y".repeat(248)), TOO_LONG);
        // U+0000 (PostgreSQL cannot take it), U+0001, U+007F and U+009F.
        for (String control : List.of("%00", "a%01b", "%7F", "%C2%9F")) {
            keywordError("?q=" + control, CONTROL);
        }
        // Page and size are checked first, with their own message.
        for (String query : List.of("?page=-1&q=%00", "?size=101&q=" + "x".repeat(256))) {
            JsonNode body = postings.error(postings.publicList(query), 400, "VALIDATION_ERROR");
            assertThat(fieldNames(body)).containsExactly("code", "message", "fieldErrors");
            assertThat(body.path("message").asText()).as(query).isEqualTo(PAGE_MESSAGE);
            assertThat(body.path("fieldErrors")).as(query).isEqualTo(json.createObjectNode());
        }

        // The limit counts letters as people see them, after the spaces between words are reduced to one; tabs and
        // line breaks are spaces, not control characters.
        assertThat(ok(postings.publicList("?q=" + encode(Normalizer.normalize("ệ".repeat(255), Normalizer.Form.NFD))))
                .path("totalElements").asLong()).isZero();
        assertThat(ok(postings.publicList("?q=" + encode("x" + " ".repeat(300) + "y"))).path("totalElements").asLong())
                .isZero();
        assertThat(ids(ok(postings.publicList("?q=" + encode("kỹ\tsư"))))).containsExactly(open);
        assertThat(ids(ok(postings.publicList("?q=" + encode("kỹ\r\nsư"))))).containsExactly(open);
    }

    // The values of the filter lists come only from the postings listed today: not from drafts, approved postings
    // not published yet, postings closed with their requisition, postings of a paused requisition or past their
    // deadline. Each value once; a value HR made inactive stays while a listed posting uses it. Departments by name;
    // catalog values by sort order, then name. Read like the list: without a token, the same JSON with one, 401 with
    // a forged one, never cached. Every value fed back as a filter finds postings.
    @Test
    void filterOptionsListOnlyOpenPostings() throws Exception {
        // Created in another order than the expected one, so the order cannot come from the insert order.
        UUID kt = api.department("KT", headId, null, START);
        UUID it = api.department("IT", headId, null, START);
        UUID vt = api.department("VT", headId, null, START);
        UUID hidden = api.department("AN", headId, null, START);
        UUID hn = catalogValue(WORK_LOCATION, "HN", "Hà Nội", 1);
        UUID hcm = catalogValue(WORK_LOCATION, "HCM", "TP. Hồ Chí Minh", 0);
        UUID vungTau = catalogValue(WORK_LOCATION, "VT", "Vũng Tàu", 2);
        UUID hiddenLocation = catalogValue(WORK_LOCATION, "DN", "Đà Nẵng", 0);
        UUID full = catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", 0);
        UUID part = catalogValue(EMPLOYMENT_TYPE, "PART_TIME", "Bán thời gian", 0);
        UUID seasonal = catalogValue(EMPLOYMENT_TYPE, "SEASONAL", "Thời vụ", 0);
        UUID hiddenType = catalogValue(EMPLOYMENT_TYPE, "INTERN", "Thực tập", 0);

        // Open: two postings of IT (the department is listed once) and one of KT.
        published(it, hn, full, "Kỹ sư", "Mô tả công việc.");
        published(it, hcm, part, "Kỹ sư bán thời gian", "Mô tả công việc.");
        published(kt, hn, full, "Kế toán", "Mô tả công việc.");
        // Open until 20 Oct 2026 only.
        draftWith(vt, vungTau, seasonal, "2026-10-20", true);
        // Never open: a draft, an approved posting, a posting closed with its requisition, a posting of a paused
        // requisition.
        draftWith(hidden, hiddenLocation, hiddenType, JobPostingTestSupport.DEADLINE, false);
        approved(hidden, hiddenLocation, hiddenType, "Chưa xuất bản", "Mô tả công việc.");
        UUID closed = published(hidden, hiddenLocation, hiddenType, "Đã đóng", "Mô tả công việc.");
        ok(api.request("POST", "/api/v1/requisitions/" + postings.row(closed).get("requisition_id")
                + "/status-changes", json.writeValueAsString(Map.of("status", "FILLED", "reason", "Đã tuyển đủ",
                "expectedRevision", 0)), hrToken));
        assertThat(postings.row(closed).get("status")).isEqualTo("CLOSED");
        UUID paused = published(hidden, hiddenLocation, hiddenType, "Tạm dừng", "Mô tả công việc.");
        jdbc.update("UPDATE recruitment_requisitions SET status = ? WHERE id = ?", RequisitionStatus.PAUSED.name(),
                postings.row(paused).get("requisition_id"));
        // HR stops using Hà Nội after the postings were published.
        jdbc.update("UPDATE recruitment_catalog_items SET active = FALSE WHERE id = ?", hn);

        var anonymous = postings.publicFilters();
        postings.noStore(anonymous);
        assertThat(anonymous.headers().allValues("Set-Cookie")).isEmpty();
        JsonNode filters = ok(anonymous);
        assertThat(fieldNames(filters)).containsExactly("departments", "workLocations", "employmentTypes");
        assertThat(options(filters.path("departments"))).containsExactly(
                Map.entry(it, "Phòng IT"), Map.entry(kt, "Phòng KT"), Map.entry(vt, "Phòng VT"));
        assertThat(options(filters.path("workLocations"))).containsExactly(
                Map.entry(hcm, "TP. Hồ Chí Minh"), Map.entry(hn, "Hà Nội"), Map.entry(vungTau, "Vũng Tàu"));
        assertThat(options(filters.path("employmentTypes"))).containsExactly(
                Map.entry(part, "Bán thời gian"), Map.entry(seasonal, "Thời vụ"), Map.entry(full, "Toàn thời gian"));
        for (String list : List.of("departments", "workLocations", "employmentTypes")) {
            filters.path(list).forEach(option -> assertThat(fieldNames(option)).containsExactly("id", "name"));
        }

        // Every value finds postings when it is sent back as its filter.
        Map<String, String> parameters = Map.of("departments", "departmentId", "workLocations", "workLocationId",
                "employmentTypes", "employmentTypeId");
        parameters.forEach((list, parameter) -> filters.path(list).forEach(option -> {
            try {
                assertThat(ok(postings.publicList("?" + parameter + "=" + option.path("id").asText()))
                        .path("totalElements").asLong()).as(parameter + " " + option).isPositive();
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }));

        // Anyone signed in reads the same JSON; a forged bearer is refused like on every public API; only GET is open.
        for (String token : List.of(hrToken, recruiterToken)) {
            var signed = api.request("GET", PUBLIC_POSTINGS + "/filters", null, token);
            assertThat(ok(signed)).isEqualTo(filters);
            postings.noStore(signed);
        }
        var forged = api.request("GET", PUBLIC_POSTINGS + "/filters", null, "not-a-jwt");
        assertThat(api.code(forged, 401)).isEqualTo("UNAUTHORIZED");
        postings.noStore(forged);
        assertThat(api.code(api.request("POST", PUBLIC_POSTINGS + "/filters", null, null), 401))
                .isEqualTo("UNAUTHORIZED");
        assertThat(api.code(api.request("POST", PUBLIC_POSTINGS + "/filters", null, hrToken), 403))
                .isEqualTo("FORBIDDEN");

        // 23:59:59 on 20 Oct 2026 in Vietnam: Vũng Tàu is still open; from 00:00 on 21 Oct its values leave.
        clock.set(Instant.parse("2026-10-20T16:59:59Z"));
        assertThat(ok(postings.publicFilters())).isEqualTo(filters);
        clock.set(Instant.parse("2026-10-20T17:00:00Z"));
        JsonNode nextDay = ok(postings.publicFilters());
        assertThat(options(nextDay.path("departments"))).containsExactly(
                Map.entry(it, "Phòng IT"), Map.entry(kt, "Phòng KT"));
        assertThat(options(nextDay.path("workLocations"))).containsExactly(
                Map.entry(hcm, "TP. Hồ Chí Minh"), Map.entry(hn, "Hà Nội"));
        assertThat(options(nextDay.path("employmentTypes"))).containsExactly(
                Map.entry(part, "Bán thời gian"), Map.entry(full, "Toàn thời gian"));

        // Nothing open, nothing to filter by.
        jdbc.update("UPDATE recruitment_requisitions SET status = ?", RequisitionStatus.PAUSED.name());
        assertThat(ok(postings.publicFilters()).toString())
                .isEqualTo("{\"departments\":[],\"workLocations\":[],\"employmentTypes\":[]}");
    }

    // Names are not unique: V5 makes only the department code unique and V10 only the code inside one catalog type, so
    // the development departments of two divisions may both be "Phòng Phát triển", and two values of one catalog may
    // share a name. Each is its own option with its own id, which filters only its own postings; equal names are
    // ordered by id. The options carry nothing else that tells them apart (question 21(5) asks BA/PO about a label).
    @Test
    void sameNamedValuesAreSeparateOptions() throws Exception {
        UUID technology = api.department("CN", headId, null, START);
        UUID product = api.department("SP", headId, null, START);
        UUID technologyDevelopment = api.department("CN_DEV", headId, technology, START);
        UUID productDevelopment = api.department("SP_DEV", headId, product, START);
        String development = "Phòng Phát triển";
        jdbc.update("UPDATE departments SET name = ? WHERE id IN (?, ?)", development, technologyDevelopment,
                productDevelopment);
        UUID hanoi = catalogValue(WORK_LOCATION, "HN", "Hà Nội", 0);
        UUID hanoiAgain = catalogValue(WORK_LOCATION, "HN_2", "Hà Nội", 0);
        UUID full = catalogValue(EMPLOYMENT_TYPE, "FULL_TIME", "Toàn thời gian", 0);
        UUID fullAgain = catalogValue(EMPLOYMENT_TYPE, "FULL_TIME_2", "Toàn thời gian", 0);
        UUID first = published(technologyDevelopment, hanoi, full, "Kỹ sư", "Mô tả công việc.");
        UUID second = published(productDevelopment, hanoiAgain, fullAgain, "Kỹ sư", "Mô tả công việc.");

        JsonNode filters = ok(postings.publicFilters());
        assertThat(options(filters.path("departments")))
                .containsExactlyElementsOf(sameName(development, technologyDevelopment, productDevelopment));
        assertThat(options(filters.path("workLocations")))
                .containsExactlyElementsOf(sameName("Hà Nội", hanoi, hanoiAgain));
        assertThat(options(filters.path("employmentTypes")))
                .containsExactlyElementsOf(sameName("Toàn thời gian", full, fullAgain));

        Map<String, UUID> firstOnly = Map.of("departmentId", technologyDevelopment, "workLocationId", hanoi,
                "employmentTypeId", full);
        Map<String, UUID> secondOnly = Map.of("departmentId", productDevelopment, "workLocationId", hanoiAgain,
                "employmentTypeId", fullAgain);
        for (String parameter : firstOnly.keySet()) {
            assertThat(ids(ok(postings.publicList("?" + parameter + "=" + firstOnly.get(parameter)))))
                    .as(parameter).containsExactly(first);
            assertThat(ids(ok(postings.publicList("?" + parameter + "=" + secondOnly.get(parameter)))))
                    .as(parameter).containsExactly(second);
        }
    }

    // normalize(text, NFC) in the keyword search only works in a UTF8 database.
    @Test
    void theDatabaseIsUtf8() {
        assertThat(jdbc.queryForObject("SHOW server_encoding", String.class)).isEqualTo("UTF8");
    }

    // The page of the query holds exactly the postings the predicate keeps, newest publication first, and its count.
    private JsonNode assertPage(String query, List<Posting> all, Predicate<Posting> kept) throws Exception {
        var response = postings.publicList(query);
        postings.noStore(response);
        JsonNode page = ok(response);
        List<UUID> expected = expected(all, kept);
        assertThat(ids(page)).as(query).containsExactlyElementsOf(expected);
        assertThat(page.path("totalElements").asLong()).as(query).isEqualTo(expected.size());
        assertThat(page.path("totalPages").asLong()).as(query).isEqualTo(expected.isEmpty() ? 0 : 1);
        return page;
    }

    private static List<UUID> expected(List<Posting> all, Predicate<Posting> kept) {
        List<UUID> expected = new ArrayList<>();
        for (int index = all.size() - 1; index >= 0; index--) {
            if (kept.test(all.get(index))) {
                expected.add(all.get(index).id());
            }
        }
        return expected;
    }

    private void assertSearch(String keyword, UUID... found) throws Exception {
        assertSearchWith(keyword, "", found);
    }

    private void assertSearchWith(String keyword, String filters, UUID... found) throws Exception {
        var response = postings.publicList("?q=" + encode(keyword) + filters);
        postings.noStore(response);
        JsonNode page = ok(response);
        assertThat(ids(page)).as(keyword + filters).containsExactlyInAnyOrder(found);
        assertThat(page.path("totalElements").asLong()).as(keyword + filters).isEqualTo(found.length);
    }

    private void keywordError(String query, String fieldError) throws Exception {
        var response = postings.publicList(query);
        JsonNode body = postings.error(response, 400, "VALIDATION_ERROR");
        assertThat(fieldNames(body)).as(query).containsExactly("code", "message", "fieldErrors");
        assertThat(body.path("message").asText()).as(query).isEqualTo(KEYWORD_MESSAGE);
        assertThat(fieldNames(body.path("fieldErrors"))).as(query).containsExactly("q");
        assertThat(body.path("fieldErrors").path("q").asText()).as(query).isEqualTo(fieldError);
        assertThat(response.body()).as(query).doesNotContain("xxxxxxxx", "yyyyyyyy");
    }

    // A posting the recruiter writes for a new approved requisition of the department, whose primary recruiter they
    // are: the title and the job description given, the other texts inherited. Revision 1.
    private UUID draft(UUID department, UUID workLocation, UUID employmentType, String title, String jobDescription,
                       String deadline) throws Exception {
        UUID requisition = postings.requisition(position, department, adminId, RequisitionStatus.APPROVED, START);
        postings.assign(requisition, recruiterId, RequisitionRecruiterRole.PRIMARY, hrId, START);
        Map<String, Object> body = postings.body(requisition, workLocation, employmentType);
        body.put("title", title);
        body.put("jobDescription", jobDescription);
        body.put("applicationDeadline", deadline);
        return id(postings.created(body, recruiterToken));
    }

    // The recruiter sends the draft for review (2) and HR approves it (3).
    private UUID approved(UUID department, UUID workLocation, UUID employmentType, String title,
                          String jobDescription) throws Exception {
        return approve(draft(department, workLocation, employmentType, title, jobDescription,
                JobPostingTestSupport.DEADLINE));
    }

    // Then HR publishes it (4), one second after the previous publication of the test.
    private UUID published(UUID department, UUID workLocation, UUID employmentType, String title,
                           String jobDescription) throws Exception {
        return publish(approved(department, workLocation, employmentType, title, jobDescription));
    }

    // A posting with another deadline, published or left a draft.
    private void draftWith(UUID department, UUID workLocation, UUID employmentType, String deadline, boolean publish)
            throws Exception {
        UUID id = draft(department, workLocation, employmentType, "Nhân viên", "Mô tả công việc.", deadline);
        if (publish) {
            publish(approve(id));
        }
    }

    private UUID approve(UUID id) throws Exception {
        ok(postings.submit(id, 1, null, recruiterToken));
        ok(postings.decide(id, "APPROVE", null, 2, hrToken));
        return id;
    }

    private UUID publish(UUID id) throws Exception {
        now = now.plusSeconds(1);
        clock.set(now);
        ok(postings.publish(id, 3, hrToken));
        return id;
    }

    private UUID catalogValue(RecruitmentCatalogType type, String code, String name, int sortOrder) {
        UUID id = postings.catalogValue(type, code, name, true, START);
        jdbc.update("UPDATE recruitment_catalog_items SET sort_order = ? WHERE id = ?", sortOrder, id);
        return id;
    }

    private JsonNode ok(HttpResponse<String> response) {
        return api.expect(response, 200);
    }

    private static List<Map.Entry<UUID, String>> options(JsonNode list) {
        List<Map.Entry<UUID, String>> options = new ArrayList<>();
        list.forEach(option -> options.add(Map.entry(id(option), option.path("name").asText())));
        return options;
    }

    // The options of values sharing one name, ordered by id the way PostgreSQL orders UUIDs: byte by byte, which is
    // the order of their lowercase text (UUID.compareTo compares signed longs and may differ).
    private static List<Map.Entry<UUID, String>> sameName(String name, UUID... ids) {
        return Arrays.stream(ids).sorted(Comparator.comparing(UUID::toString)).map(id -> Map.entry(id, name)).toList();
    }

    private static UUID id(JsonNode node) {
        return UUID.fromString(node.path("id").asText());
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(id(item)));
        return ids;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private UUID account(String email, String fullName, Role role) {
        return accounts.saveAndFlush(new Account(email, fullName, passwordHash, Set.of(role), START)).getId();
    }

    private static List<String> fieldNames(JsonNode node) {
        return new ArrayList<>(node.propertyNames());
    }
}
