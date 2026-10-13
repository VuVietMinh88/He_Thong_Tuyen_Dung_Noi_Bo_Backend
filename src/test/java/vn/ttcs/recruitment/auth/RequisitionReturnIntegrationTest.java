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

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.auth.ApprovalTestSupport.step;

// Task 264: an approver asks for more information; the requester edits the requisition and submits it again, which
// starts a new round; the earlier decisions stay in the history (story S3-02).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionReturnIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");
    private static final String BASE = "/api/v1/requisitions/";

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private PlatformTransactionManager transactions;

    private ApprovalTestSupport api;
    private String passwordHash;
    private UUID hrId;
    private String hrToken;
    private String hr2Token;
    private UUID itHeadId;
    private String itHeadToken;
    private UUID approverA;
    private String approverAToken;
    private String approverBToken;
    private UUID it;
    private UUID itQa;
    private UUID position;

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
        api = new ApprovalTestSupport(jdbc, json, transactions,
                Integer.parseInt(environment.getRequiredProperty("local.server.port")));
        api.reset();
        bootstrap.run(new DefaultApplicationArguments());
        UUID adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = account("hr@example.test", "Nguyễn Thị Hoa", Role.HR_MANAGER);
        account("hr2@example.test", "Trần Văn Nam", Role.HR_MANAGER);
        itHeadId = account("it@example.test", "Trưởng IT", Role.HIRING_MANAGER);
        approverA = account("a@example.test", "Giám đốc A", Role.APPROVER);
        account("b@example.test", "Giám đốc B", Role.APPROVER);
        hrToken = api.token("hr@example.test");
        hr2Token = api.token("hr2@example.test");
        itHeadToken = api.token("it@example.test");
        approverAToken = api.token("a@example.test");
        approverBToken = api.token("b@example.test");
        it = api.department("IT", itHeadId, null, START);
        itQa = api.department("IT_QA", itHeadId, it, START);
        position = api.position(START);
    }

    @Test
    void askingForMoreInformationNeedsACommentAndReturnsTheRequisitionWithItsHistoryKept() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"), step(2, 20_000_000L, approverA, null));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);
        api.expect(api.decide(id, "APPROVE", "Đồng ý", 1, hrToken), 200);

        // A comment without any visible character, such as only a non-breaking or a zero-width space, is no comment.
        for (String comment : new String[]{null, "  ", String.valueOf((char) 0x00A0),
                new String(Character.toChars(0x200B))}) {
            assertThat(api.code(api.decide(id, "REQUEST_INFO", comment, 2, approverAToken), 400))
                    .isEqualTo("APPROVAL_COMMENT_REQUIRED");
        }
        JsonNode returned = api.expect(api.decide(id, "REQUEST_INFO", "Bổ sung mô tả công việc", 2, approverAToken), 200);
        assertThat(returned.path("status").asText()).isEqualTo("RETURNED");
        assertThat(jdbc.queryForList("""
                SELECT event_type || ':' || COALESCE(level_number::text, '-') || ':' || status_after
                FROM requisition_workflow_events WHERE requisition_id = ? ORDER BY revision""", String.class, id))
                .containsExactly("SUBMITTED:-:PENDING_APPROVAL", "APPROVED:1:PENDING_APPROVAL", "INFO_REQUESTED:2:RETURNED");
        // Waiting for the requester: no more decisions; a copy is a draft without history.
        assertThat(api.code(api.decide(id, "APPROVE", null, 3, approverAToken), 409))
                .isEqualTo("REQUISITION_NOT_PENDING_APPROVAL");
        JsonNode copy = api.expect(api.request("POST", BASE + id + "/copy", null, itHeadToken), 201);
        assertThat(api.events(UUID.fromString(copy.path("id").asText()))).isZero();
    }

    @Test
    void theRequesterEditsAReturnedRequisitionUnderTheHeadcountPlanAndSubmitsItAsANewRound() throws Exception {
        UUID flow = api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"), step(2, 20_000_000L, approverA, null),
                step(3, 50_000_000L, null, "APPROVER"));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);
        api.expect(api.decide(id, "APPROVE", null, 1, hrToken), 200);
        api.expect(api.decide(id, "REQUEST_INFO", "Nêu rõ lý do lương", 2, approverAToken), 200);
        var roundOne = jdbc.queryForList("SELECT * FROM requisition_workflow_events WHERE requisition_id = ? ORDER BY revision", id);

        // The plan of IT allows one person: asking for two is refused, unless HR confirms going over it.
        api.expect(api.request("POST", "/api/v1/headcount-plans", json.writeValueAsString(Map.of("departmentId", it,
                "year", 2026, "headcountLimit", 1)), hrToken), 201);
        assertThat(api.code(api.request("PUT", BASE + id, json.writeValueAsString(content(it, 2, 60_000_000L, null)),
                itHeadToken), 409)).isEqualTo("HEADCOUNT_LIMIT_EXCEEDED");
        api.expect(api.request("PUT", BASE + id, json.writeValueAsString(content(it, 2, 60_000_000L,
                "Dự án mới cần thêm người")), hrToken), 200);
        // HR publishes a new version meanwhile: the new round uses it, the old round keeps its own.
        api.publish(flow, 2, hrId, START, step(1, null, null, "HR_MANAGER"), step(2, 20_000_000L, approverA, null),
                step(3, 50_000_000L, null, "APPROVER"));

        clock.set(START.plusSeconds(60));
        JsonNode resubmitted = api.expect(api.request("POST", BASE + id + "/submit",
                json.writeValueAsString(Map.of("comment", "Đã nêu lý do")), itHeadToken), 200);
        assertThat(resubmitted.path("status").asText()).isEqualTo("PENDING_APPROVAL");
        Map<String, Object> submission = jdbc.queryForMap(
                "SELECT * FROM requisition_workflow_events WHERE requisition_id = ? AND revision = 4", id);
        assertThat(submission).containsEntry("event_type", "SUBMITTED").containsEntry("round_number", 2)
                .containsEntry("status_before", "RETURNED").containsEntry("flow_version", 2)
                .containsEntry("level_count", 3).containsEntry("salary_basis", 60_000_000L)
                .containsEntry("comment", "Đã nêu lý do");
        assertThat(jdbc.queryForList("SELECT * FROM requisition_workflow_events WHERE requisition_id = ? AND revision < 4 "
                + "ORDER BY revision", id)).isEqualTo(roundOne);

        // A second submit is refused; a click from round 1 is outdated; HR decides level 1 of the new round again.
        assertThat(api.code(api.request("POST", BASE + id + "/submit", null, itHeadToken), 409))
                .isEqualTo("REQUISITION_NOT_SUBMITTABLE");
        assertThat(api.code(api.decide(id, "APPROVE", null, 2, approverAToken), 409)).isEqualTo("APPROVAL_STATE_CHANGED");
        api.expect(api.decide(id, "APPROVE", null, 4, hrToken), 200);
        api.expect(api.decide(id, "APPROVE", null, 5, approverAToken), 200);
        assertThat(api.expect(api.decide(id, "APPROVE", null, 6, approverBToken), 200).path("status").asText())
                .isEqualTo("APPROVED");
    }

    // Director A was one of the approvers of level 2 in round 1 but did not decide; round 2 has not reached level 2
    // yet. A still reads the requisition, and a click from round 1 is outdated rather than forbidden.
    @Test
    void theApproversOfALevelReachedInAnEarlierRoundKeepReadingTheRequisition() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"), step(2, null, null, "APPROVER"));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);
        api.expect(api.decide(id, "APPROVE", null, 1, hrToken), 200);
        api.expect(api.decide(id, "REQUEST_INFO", "Bổ sung mô tả công việc", 2, approverBToken), 200);
        api.expect(api.request("POST", BASE + id + "/submit", null, itHeadToken), 200);

        api.expect(api.request("GET", BASE + id, null, approverAToken), 200);
        assertThat(api.code(api.decide(id, "APPROVE", null, 3, approverAToken), 409)).isEqualTo("APPROVAL_STATE_CHANGED");
    }

    @Test
    void aReturnedRequisitionMovedToAnotherDepartmentUsesThatDepartmentsFlow() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"));
        UUID qaFlow = api.flow(itQa, hrId, START, step(1, null, null, "APPROVER"));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);
        api.expect(api.decide(id, "REQUEST_INFO", "Chuyển sang IT_QA", 1, hrToken), 200);

        api.expect(api.request("PUT", BASE + id, json.writeValueAsString(content(itQa, 1, 22_000_000L, null)),
                itHeadToken), 200);
        api.expect(api.request("POST", BASE + id + "/submit", null, itHeadToken), 200);

        assertThat(jdbc.queryForObject("SELECT flow_id FROM requisition_workflow_events WHERE requisition_id = ? AND revision = 3",
                UUID.class, id)).isEqualTo(qaFlow);
        api.expect(api.decide(id, "APPROVE", null, 3, approverBToken), 200);
    }

    @Test
    void theSubmitterOfTheNewRoundDoesNotDecideItAndOnlyReturnedOrDraftRequisitionsAreEdited() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);
        assertThat(api.code(api.request("PUT", BASE + id, json.writeValueAsString(content(it, 1, 22_000_000L, null)),
                itHeadToken), 409)).isEqualTo("REQUISITION_NOT_DRAFT");
        api.expect(api.decide(id, "REQUEST_INFO", "Thiếu thông tin", 1, hrToken), 200);

        // HR (who may write every requisition) submits it again this time, so the other HR manager decides.
        api.expect(api.request("POST", BASE + id + "/submit", null, hrToken), 200);
        api.forbidden(api.decide(id, "APPROVE", null, 3, hrToken));
        assertThat(api.expect(api.decide(id, "APPROVE", null, 3, hr2Token), 200).path("status").asText())
                .isEqualTo("APPROVED");
        assertThat(api.code(api.request("PUT", BASE + id, json.writeValueAsString(content(it, 1, 22_000_000L, null)),
                itHeadToken), 409)).isEqualTo("REQUISITION_NOT_DRAFT");
    }

    private Map<String, Object> content(UUID department, int headcount, long maxSalary, String overrideReason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("positionId", position);
        body.put("departmentId", department);
        body.put("headcount", headcount);
        body.put("reason", "NEW_HEADCOUNT");
        body.put("proposedSalaryMin", 20_000_000L);
        body.put("proposedSalaryMax", maxSalary);
        body.put("neededBy", "2026-12-01");
        body.put("jobDescription", "Phát triển dịch vụ tuyển dụng, bổ sung.");
        body.put("candidateRequirements", "Ba năm kinh nghiệm Java.");
        if (overrideReason != null) {
            body.put("headcountOverrideReason", overrideReason);
        }
        return body;
    }

    private UUID account(String email, String name, Role... roles) {
        return accounts.saveAndFlush(new Account(email, name, passwordHash, Set.of(roles), START)).getId();
    }
}
