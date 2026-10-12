package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static vn.ttcs.recruitment.auth.ApprovalTestSupport.step;

// Task 297 (story S3-08): GET /api/v1/requisitions filtered by status, department, primary recruiter and needed-by
// dates. Every filter only narrows what the caller may already see (the department scope of task 245 and, task 285,
// the requisitions the caller is assigned to); a filter outside that scope is an empty page, never a 403. Department
// tree of the fixture: IT (head) > IT_DEV (devlead) > IT_QA (lead); SALES is separate. Every requisition is created by
// HR, so the result cannot come from "who created it".
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionListFilterIntegrationTest {
    private static final String BASE = "/api/v1/requisitions";
    // 10:00 in Vietnam on 12 October 2026. Access tokens last 15 minutes, so the fixture stays within seconds of it.
    private static final Instant START = Instant.parse("2026-10-12T03:00:00Z");
    private static final String PARAMETER_ERROR = "Tham số đường dẫn hoặc bộ lọc không hợp lệ.";
    private static final String RANGE_ERROR = "Khoảng ngày cần người không hợp lệ.";
    private static final String PAGE_ERROR = "Trang hoặc số lượng yêu cầu tuyển dụng không hợp lệ.";
    private static final List<String> DETAIL_FIELDS = List.of("id", "positionId", "departmentId", "headcount", "reason",
            "proposedSalaryMin", "proposedSalaryMax", "salaryJustification", "neededBy", "jobDescription",
            "candidateRequirements", "status", "createdBy", "createdAt", "updatedAt");
    // The fixture, newest first (the order of the list).
    private static final List<String> EVERY_REQUISITION = List.of("sales", "qaRejected", "qa", "dev", "itApproved",
            "it");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;

    private ApprovalTestSupport api;
    private String passwordHash;
    private UUID positionId;
    // Access tokens of the callers by name (admin, hr, head, lead, nobody, approver, r1, r2), logged in when first
    // used.
    private final Map<String, String> tokens = new HashMap<>();
    // Departments, recruiters and requisitions by the name a query or an expectation uses ({IT}, {r1}, "qa"...).
    private final Map<String, UUID> ids = new LinkedHashMap<>();
    private final Map<UUID, String> requisitionNames = new HashMap<>();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    // Requisitions, oldest first, a second apart:
    // - it: IT, DRAFT, needed by 1 Nov, primary r1
    // - itApproved: IT, APPROVED, 30 Nov, primary r2, r1 supporting
    // - dev: IT_DEV, RETURNED, no needed-by date, no recruiter
    // - qa: IT_QA, PENDING_APPROVAL (really submitted; the approval waits for the named APPROVER), 1 Dec, primary r1
    // - qaRejected: IT_QA, REJECTED, 20 Oct
    // - sales: SALES, APPROVED, 1 Nov, primary r1
    @BeforeEach
    void resetFixture() throws Exception {
        clock.set(START);
        api = new ApprovalTestSupport(jdbc, json, transactions,
                Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        api.reset();
        tokens.clear();
        ids.clear();
        requisitionNames.clear();
        bootstrap.run(new DefaultApplicationArguments());
        UUID adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        UUID hrId = account("hr", Role.HR_MANAGER);
        UUID headId = account("head", Role.HIRING_MANAGER);
        UUID devLeadId = account("devlead", Role.HIRING_MANAGER);
        UUID leadId = account("lead", Role.HIRING_MANAGER);
        UUID salesHeadId = account("sales", Role.HIRING_MANAGER);
        account("nobody", Role.HIRING_MANAGER);
        UUID approverId = account("approver", Role.APPROVER);
        ids.put("r1", account("r1", Role.RECRUITER));
        ids.put("r2", account("r2", Role.RECRUITER));
        UUID it = api.department("IT", headId, null, START);
        UUID itDev = api.department("IT_DEV", devLeadId, it, START);
        UUID itQa = api.department("IT_QA", leadId, itDev, START);
        UUID sales = api.department("SALES", salesHeadId, null, START);
        ids.put("IT", it);
        ids.put("IT_DEV", itDev);
        ids.put("IT_QA", itQa);
        ids.put("SALES", sales);
        ids.put("UNKNOWN", UUID.randomUUID());
        positionId = api.position(START);
        api.flow(itQa, hrId, START, step(1, null, approverId, null));

        UUID itDraft = requisition("it", it, "2026-11-01", 1);
        UUID itApproved = requisition("itApproved", it, "2026-11-30", 2);
        UUID dev = requisition("dev", itDev, null, 3);
        UUID qa = requisition("qa", itQa, "2026-12-01", 4);
        UUID qaRejected = requisition("qaRejected", itQa, "2026-10-20", 5);
        UUID salesApproved = requisition("sales", sales, "2026-11-01", 6);
        // Recruiters are assigned while every requisition is still assignable (a draft is).
        assign(itDraft, "r1", null);
        assign(itApproved, "r2", null);
        assign(itApproved, "r1", "SUPPORTING");
        assign(qa, "r1", null);
        assign(salesApproved, "r1", null);
        api.expect(api.request("POST", BASE + "/" + qa + "/submit", null, token("hr")), 200);
        status(itApproved, "APPROVED");
        status(dev, "RETURNED");
        status(qaRejected, "REJECTED");
        status(salesApproved, "APPROVED");
        assertThat(api.status(qa)).isEqualTo("PENDING_APPROVAL");
    }

    @ParameterizedTest(name = "{0} ?{1}")
    @MethodSource("narrowingCases")
    void filtersNarrowTheScopeAndNeverWidenIt(String caller, String query, List<String> expected) throws Exception {
        var response = list(caller, query);
        JsonNode page = api.expect(response, 200);
        noStore(response);
        assertThat(names(page)).containsExactlyElementsOf(expected);
        assertThat(page.path("totalElements").asLong()).isEqualTo(expected.size());
    }

    static Stream<Arguments> narrowingCases() {
        return Stream.of(
                // ALL callers: each filter alone, then all of them together.
                arguments("hr", "", EVERY_REQUISITION),
                arguments("hr", "status=APPROVED", List.of("sales", "itApproved")),
                // That department only, without IT_DEV and IT_QA below it.
                arguments("hr", "departmentId={IT}", List.of("itApproved", "it")),
                arguments("hr", "recruiterId={r1}", List.of("sales", "qa", "it")),
                arguments("hr", "startDate=2026-11-01", List.of("sales", "qa", "itApproved", "it")),
                arguments("hr", "endDate=2026-11-01", List.of("sales", "qaRejected", "it")),
                arguments("hr", "status=DRAFT&status=APPROVED&departmentId={IT}&recruiterId={r1}"
                        + "&startDate=2026-11-01&endDate=2026-11-30", List.of("it")),
                arguments("admin", "departmentId={IT_QA}&status=PENDING_APPROVAL,REJECTED",
                        List.of("qaRejected", "qa")),
                arguments("hr", "departmentId={UNKNOWN}", List.of()),
                // SCOPED callers: a filter inside the scope narrows it, one outside it gives an empty page.
                arguments("head", "", List.of("qaRejected", "qa", "dev", "itApproved", "it")),
                arguments("head", "departmentId={IT_QA}", List.of("qaRejected", "qa")),
                arguments("head", "departmentId={SALES}", List.of()),
                arguments("head", "status=APPROVED", List.of("itApproved")),
                arguments("head", "recruiterId={r1}", List.of("qa", "it")),
                arguments("head", "departmentId={UNKNOWN}", List.of()),
                arguments("lead", "", List.of("qaRejected", "qa")),
                arguments("lead", "departmentId={IT}", List.of()),
                arguments("lead", "departmentId={IT_QA}&status=REJECTED", List.of("qaRejected")),
                // Task 285: a recruiter lists the requisitions assigned to them, primary or supporting; filters narrow
                // that too.
                arguments("r1", "", List.of("sales", "qa", "itApproved", "it")),
                arguments("r1", "recruiterId={r1}", List.of("sales", "qa", "it")),
                arguments("r1", "status=APPROVED", List.of("sales", "itApproved")),
                arguments("r1", "status=APPROVED&recruiterId={r1}", List.of("sales")),
                arguments("r1", "departmentId={IT}", List.of("itApproved", "it")),
                arguments("r1", "departmentId={IT_DEV}", List.of()),
                arguments("r1", "recruiterId={r2}", List.of("itApproved")),
                arguments("r2", "", List.of("itApproved")),
                // A SCOPED caller who manages no department and is assigned to nothing (nobody) sees nothing, whatever
                // the filters.
                arguments("nobody", "departmentId={IT}", List.of()),
                arguments("nobody", "status=DRAFT&recruiterId={r1}&startDate=2026-11-01", List.of()));
    }

    @Test
    void recruiterFilterMatchesTheCurrentPrimaryOnly() throws Exception {
        // r1 is the primary of it, qa and sales, and only supports itApproved, whose primary is r2.
        assertThat(names(page("hr", "recruiterId={r1}"))).containsExactly("sales", "qa", "it");
        assertThat(names(page("hr", "recruiterId={r2}"))).containsExactly("itApproved");

        // After a handover only the new primary matches.
        assign(ids.get("it"), "r2", "PRIMARY");
        assertThat(names(page("hr", "recruiterId={r1}"))).containsExactly("sales", "qa");
        assertThat(names(page("hr", "recruiterId={r2}"))).containsExactly("itApproved", "it");
        assertThat(names(page("hr", "recruiterId={UNKNOWN}"))).isEmpty();

        // Task 285: the list follows the current team: the old primary loses it with the handover. The filter on the
        // primary recruiter only narrows what the caller is assigned to.
        assertPage("r1", "", List.of("sales", "qa", "itApproved"));
        assertPage("r1", "recruiterId={r1}", List.of("sales", "qa"));
        assertPage("r2", "", List.of("itApproved", "it"));

        // An approval participant reads the requisition they decide (task 262), but the list still follows the
        // departments they manage.
        assertThat(api.expect(api.request("GET", BASE + "/" + ids.get("qa"), null, token("approver")), 200)
                .path("status").asText()).isEqualTo("PENDING_APPROVAL");
        for (String query : List.of("", "departmentId={IT_QA}&status=PENDING_APPROVAL", "recruiterId={r1}")) {
            assertThat(names(page("approver", query))).as(query).isEmpty();
        }
    }

    // Task 285: a caller who manages a department and is assigned elsewhere sees both, and every filter narrows both
    // (the scope is one parenthesised condition). r2 manages SALES (sales, APPROVED) and is the primary of itApproved
    // (IT, APPROVED). Without the parentheses, status=DRAFT would list sales and departmentId={IT} would list both.
    @Test
    void aCallerWhoManagesADepartmentAndIsAssignedElsewhereSeesBoth() throws Exception {
        assertThat(jdbc.update("UPDATE departments SET manager_user_id = ? WHERE id = ?", ids.get("r2"),
                ids.get("SALES"))).isOne();
        assertPage("r2", "", List.of("sales", "itApproved"));
        assertPage("r2", "status=DRAFT", List.of());
        assertPage("r2", "departmentId={IT}", List.of("itApproved"));
        assertPage("r2", "departmentId={SALES}&recruiterId={r1}", List.of("sales"));
        List<String> walked = new ArrayList<>();
        for (int number = 0; number < 2; number++) {
            JsonNode one = page("r2", "size=1&page=" + number);
            assertThat(one.path("totalElements").asLong()).isEqualTo(2);
            assertThat(one.path("totalPages").asLong()).isEqualTo(2);
            walked.addAll(names(one));
        }
        assertThat(walked).containsExactly("sales", "itApproved");
    }

    @Test
    void statusFilterTakesSeveralValues() throws Exception {
        List<String> draftOrApproved = List.of("sales", "itApproved", "it");
        assertThat(names(page("hr", "status=DRAFT&status=APPROVED"))).containsExactlyElementsOf(draftOrApproved);
        assertThat(names(page("hr", "status=DRAFT,APPROVED"))).containsExactlyElementsOf(draftOrApproved);
        assertThat(names(page("hr", "status=APPROVED,DRAFT,APPROVED"))).containsExactlyElementsOf(draftOrApproved);
        assertThat(names(page("hr", "status=RETURNED&status=PENDING_APPROVAL&status=REJECTED")))
                .containsExactly("qaRejected", "qa", "dev");

        // An empty element is dropped, and an empty value is the same as no status filter.
        assertThat(names(page("hr", "status=DRAFT,"))).containsExactly("it");
        assertThat(names(page("hr", "status=DRAFT&status="))).containsExactly("it");
        assertThat(names(page("hr", "status="))).containsExactlyElementsOf(EVERY_REQUISITION);

        // Codes are case-sensitive and only statuses that exist are accepted, so the frontend's PENDING, OPEN and
        // CLOSED are refused too; one bad value refuses the whole request. The two forms cannot be mixed: a repeated
        // value is not split at commas.
        for (String status : List.of("draft", "SUBMITTED", "PENDING", "OPEN", "CLOSED", "DRAFT,OPEN",
                "RETURNED,PENDING_APPROVAL&status=REJECTED")) {
            badRequest(list("hr", "status=" + status), PARAMETER_ERROR);
        }
    }

    @Test
    void neededByRangeIsInclusiveAndSkipsMissingDates() throws Exception {
        // Both bounds are inclusive; either one may be sent alone.
        assertThat(names(page("hr", "startDate=2026-11-01&endDate=2026-11-30")))
                .containsExactly("sales", "itApproved", "it");
        assertThat(names(page("hr", "startDate=2026-11-02&endDate=2026-11-29"))).isEmpty();
        assertThat(names(page("hr", "startDate=2026-11-30"))).containsExactly("qa", "itApproved");
        assertThat(names(page("hr", "endDate=2026-10-20"))).containsExactly("qaRejected");
        assertThat(names(page("hr", "startDate=2026-11-01&endDate=2026-11-01"))).containsExactly("sales", "it");

        // dev has no needed-by date: left out as soon as one bound is sent, listed when none is.
        List<String> withNeededBy = List.of("sales", "qaRejected", "qa", "itApproved", "it");
        assertThat(names(page("hr", "startDate=0001-01-01"))).containsExactlyElementsOf(withNeededBy);
        assertThat(names(page("hr", "endDate=9999-12-31"))).containsExactlyElementsOf(withNeededBy);
        assertThat(names(page("hr", "startDate="))).containsExactlyElementsOf(EVERY_REQUISITION);
        assertThat(names(page("hr", "startDate=&endDate="))).containsExactlyElementsOf(EVERY_REQUISITION);

        // A start after the end, and a year outside 1..9999 (it parses, but could not come from the date input).
        badRequest(list("hr", "startDate=2026-12-01&endDate=2026-11-01"), RANGE_ERROR);
        badRequest(list("hr", "startDate=0000-01-01"), RANGE_ERROR);
        badRequest(list("hr", "endDate=0000-12-31"), RANGE_ERROR);
        // The strict yyyy-MM-dd format reads a signed five-digit year as year 10000.
        badRequest(list("hr", "startDate=%2B10000-01-01"), RANGE_ERROR);

        // Strict yyyy-MM-dd: no impossible day, no other format, no time, no offset (ISO_DATE would accept one).
        for (String date : List.of("2026-02-30", "11/10/2026", "2026-10-11T00:00", "2026-10-11%2B07:00")) {
            badRequest(list("hr", "startDate=" + date), PARAMETER_ERROR);
            badRequest(list("hr", "endDate=" + date), PARAMETER_ERROR);
        }
    }

    @Test
    void filterValuesAreBoundNeverConcatenated() throws Exception {
        int before = count();
        Map<String, String> attempts = new LinkedHashMap<>();
        attempts.put("departmentId", "' OR '1'='1");
        attempts.put("recruiterId", "1;DROP TABLE recruitment_requisitions");
        attempts.put("status", "DRAFT') OR ('1'='1");
        attempts.put("startDate", "2026-01-01' OR '1'='1");
        attempts.put("endDate", "2026-12-31'; DELETE FROM recruitment_requisitions; --");
        for (var attempt : attempts.entrySet()) {
            badRequest(list("hr", attempt.getKey() + "=" + URLEncoder.encode(attempt.getValue(), StandardCharsets.UTF_8)),
                    PARAMETER_ERROR);
        }
        assertThat(count()).isEqualTo(before).isEqualTo(EVERY_REQUISITION.size());
        assertThat(names(page("hr", ""))).containsExactlyElementsOf(EVERY_REQUISITION);
    }

    @Test
    void pagingWalksTheFilteredSet() throws Exception {
        String filter = "status=DRAFT,APPROVED,PENDING_APPROVAL";
        List<String> walked = new ArrayList<>();
        for (int number = 0; number < 4; number++) {
            JsonNode one = page("hr", filter + "&size=1&page=" + number);
            assertThat(one.path("items").size()).isEqualTo(1);
            assertThat(one.path("page").asInt()).isEqualTo(number);
            assertThat(one.path("size").asInt()).isEqualTo(1);
            assertThat(one.path("totalElements").asLong()).isEqualTo(4);
            assertThat(one.path("totalPages").asLong()).isEqualTo(4);
            walked.addAll(names(one));
        }
        assertThat(walked).containsExactly("sales", "qa", "itApproved", "it");
        JsonNode end = page("hr", filter + "&size=1&page=4");
        assertThat(end.path("items").size()).isZero();
        assertThat(end.path("totalElements").asLong()).isEqualTo(4);
        JsonNode second = page("hr", filter + "&size=3&page=1");
        assertThat(names(second)).containsExactly("it");
        assertThat(second.path("totalPages").asLong()).isEqualTo(2);

        // The offset edges of task 245 answer the same with a filter present, for ALL (HR) and SCOPED (head).
        for (String caller : List.of("hr", "head")) {
            long total = caller.equals("hr") ? 4 : 3;
            for (String query : List.of("page=21474836&size=100", "page=2147483647&size=1")) {
                JsonNode beyond = page(caller, filter + "&" + query);
                assertThat(beyond.path("items").size()).as(query).isZero();
                assertThat(beyond.path("totalElements").asLong()).as(query).isEqualTo(total);
            }
            for (String query : List.of("page=21474837&size=100", "page=2147483647&size=2", "page=2147483647&size=100")) {
                badRequest(list(caller, filter + "&" + query), PAGE_ERROR);
            }
        }

        // Binding comes first, then the page, then the date range.
        badRequest(list("hr", "page=-1&status=draft"), PARAMETER_ERROR);
        badRequest(list("hr", "page=-1&startDate=2026-12-01&endDate=2026-11-01"), PAGE_ERROR);
    }

    @Test
    void itemsKeepTheDeadlineMetrics() throws Exception {
        clock.set(Instant.parse("2026-12-15T03:00:00Z")); // 10:00 in Vietnam on 15 December, after every needed-by date.
        String hrToken = api.token("hr@example.test");
        JsonNode page = api.expect(api.request("GET", BASE, null, hrToken), 200);
        assertThat(names(page)).containsExactlyElementsOf(EVERY_REQUISITION);
        for (JsonNode item : page.path("items")) {
            List<String> fields = new ArrayList<>(item.propertyNames());
            assertThat(fields).hasSize(18).containsAll(DETAIL_FIELDS).contains("daysOpen", "daysUntilNeededBy", "overdue");
            JsonNode detail = api.expect(api.request("GET", BASE + "/" + item.path("id").asText(), null, hrToken), 200);
            DETAIL_FIELDS.forEach(field -> assertThat(item.path(field)).as(field).isEqualTo(detail.path(field)));
            // 12 October to 15 December.
            assertThat(item.path("daysOpen").asLong()).isEqualTo(64);
        }

        JsonNode approved = item(page, "itApproved");
        assertThat(approved.path("daysUntilNeededBy").asLong()).isEqualTo(-15);
        assertThat(approved.path("overdue").asBoolean()).isTrue();
        // A rejected requisition is finished: its needed-by date has passed, but it is not overdue (task 297).
        JsonNode rejected = item(page, "qaRejected");
        assertThat(rejected.path("daysUntilNeededBy").asLong()).isEqualTo(-56);
        assertThat(rejected.path("overdue").asBoolean()).isFalse();
        JsonNode undated = item(page, "dev");
        assertThat(undated.path("daysUntilNeededBy").isNull()).isTrue();
        assertThat(undated.path("overdue").asBoolean()).isFalse();
        for (String stillStaffed : List.of("it", "qa", "sales")) {
            assertThat(item(page, stillStaffed).path("overdue").asBoolean()).as(stillStaffed).isTrue();
        }

        // A filtered page carries the same metrics.
        JsonNode filtered = api.expect(api.request("GET", BASE + "?status=REJECTED,APPROVED&startDate=2026-11-30", null,
                hrToken), 200);
        assertThat(names(filtered)).containsExactly("itApproved");
        assertThat(filtered.path("items").path(0)).isEqualTo(approved);
    }

    // A complete draft (it can be submitted) of the department, created by HR "second" seconds after START.
    private UUID requisition(String name, UUID department, String neededBy, int second) throws Exception {
        clock.set(START.plusSeconds(second));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("positionId", positionId);
        body.put("departmentId", department);
        body.put("headcount", 1);
        body.put("reason", "NEW_HEADCOUNT");
        body.put("proposedSalaryMin", 20_000_000L);
        body.put("proposedSalaryMax", 22_000_000L);
        body.put("neededBy", neededBy);
        body.put("jobDescription", "Phát triển dịch vụ tuyển dụng.");
        body.put("candidateRequirements", "Ba năm kinh nghiệm Java.");
        UUID id = UUID.fromString(api.expect(api.request("POST", BASE, json.writeValueAsString(body), token("hr")),
                201).path("id").asText());
        ids.put(name, id);
        requisitionNames.put(id, name);
        return id;
    }

    private void assign(UUID requisition, String recruiter, String role) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recruiterId", ids.get(recruiter));
        if (role != null) {
            body.put("role", role);
        }
        api.expect(api.request("POST", BASE + "/" + requisition + "/assign", json.writeValueAsString(body),
                token("hr")), 200);
    }

    // V19 allows every status and the table has no trigger, so a status other than the submitted one is set with SQL.
    private void status(UUID requisition, String status) {
        assertThat(jdbc.update("UPDATE recruitment_requisitions SET status = ? WHERE id = ?", status, requisition))
                .isEqualTo(1);
    }

    private UUID account(String name, Role role) {
        return accounts.saveAndFlush(new Account(name + "@example.test", "List filter " + name, passwordHash,
                Set.of(role), START)).getId();
    }

    private String token(String caller) throws Exception {
        String token = tokens.get(caller);
        if (token == null) {
            token = api.token(caller + "@example.test");
            tokens.put(caller, token);
        }
        return token;
    }

    // GET /requisitions?query as the named caller; {NAME} in the query is replaced by that id.
    private HttpResponse<String> list(String caller, String query) throws Exception {
        String resolved = query;
        for (var entry : ids.entrySet()) {
            resolved = resolved.replace("{" + entry.getKey() + "}", entry.getValue().toString());
        }
        return api.request("GET", resolved.isEmpty() ? BASE : BASE + "?" + resolved, null, token(caller));
    }

    private JsonNode page(String caller, String query) throws Exception {
        var response = list(caller, query);
        JsonNode page = api.expect(response, 200);
        noStore(response);
        return page;
    }

    // The page of the named caller lists exactly these requisitions, and its total says so.
    private void assertPage(String caller, String query, List<String> expected) throws Exception {
        JsonNode page = page(caller, query);
        assertThat(names(page)).as(caller + " ?" + query).containsExactlyElementsOf(expected);
        assertThat(page.path("totalElements").asLong()).as(caller + " ?" + query).isEqualTo(expected.size());
    }

    private List<String> names(JsonNode page) {
        List<String> result = new ArrayList<>();
        page.path("items").forEach(item -> result.add(requisitionNames.get(UUID.fromString(item.path("id").asText()))));
        return result;
    }

    private JsonNode item(JsonNode page, String name) {
        for (JsonNode item : page.path("items")) {
            if (item.path("id").asText().equals(ids.get(name).toString())) {
                return item;
            }
        }
        throw new AssertionError("No item " + name);
    }

    private void badRequest(HttpResponse<String> response, String message) {
        JsonNode error = api.expect(response, 400);
        assertThat(error.path("code").asText()).as(response.uri().toString()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.path("message").asText()).as(response.uri().toString()).isEqualTo(message);
        noStore(response);
    }

    private void noStore(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM recruitment_requisitions", Integer.class);
    }
}
