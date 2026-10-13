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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.auth.ApprovalTestSupport.step;

// Task 262: the current approval level decides; only the people ApproverRule admits may, and they may read what they
// decide (story S3-02).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionApprovalDecisionIntegrationTest {
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
    private String adminToken;
    private UUID itHeadId;
    private String itHeadToken;
    private String parentHeadToken;
    private String siblingHeadToken;
    private UUID approverA;
    private String approverAToken;
    private String approverBToken;
    private UUID it;
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
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'REQUISITIONS_READ_ALL') ON CONFLICT DO NOTHING");
        api.reset();
        bootstrap.run(new DefaultApplicationArguments());
        UUID adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = account("hr@example.test", "Nguyễn Thị Hoa", Role.HR_MANAGER);
        account("hr2@example.test", "Trần Văn Nam", Role.HR_MANAGER);
        UUID parentHead = account("parent@example.test", "Trưởng khối", Role.HIRING_MANAGER);
        itHeadId = account("it@example.test", "Trưởng IT", Role.HIRING_MANAGER);
        UUID siblingHead = account("sibling@example.test", "Trưởng Kinh doanh", Role.HIRING_MANAGER);
        approverA = account("a@example.test", "Giám đốc A", Role.APPROVER);
        account("b@example.test", "Giám đốc B", Role.APPROVER);
        hrToken = api.token("hr@example.test");
        hr2Token = api.token("hr2@example.test");
        adminToken = api.token("admin@example.test");
        itHeadToken = api.token("it@example.test");
        parentHeadToken = api.token("parent@example.test");
        siblingHeadToken = api.token("sibling@example.test");
        approverAToken = api.token("a@example.test");
        approverBToken = api.token("b@example.test");
        UUID parent = api.department("PARENT", parentHead, null, START);
        it = api.department("IT", itHeadId, parent, START);
        api.department("SALES", siblingHead, parent, START);
        position = api.position(START);
    }

    @Test
    void anHrManagerRejectsTheFirstLevelWithAComment() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);
        String updatedAt = jdbc.queryForObject("SELECT updated_at::text FROM recruitment_requisitions WHERE id = ?",
                String.class, id);
        clock.set(START.plusSeconds(30));

        var response = api.decide(id, "REJECT", "Mức lương vượt kế hoạch\nquý IV", 1, hrToken);
        JsonNode body = api.expect(response, 200);
        assertThat(body.path("status").asText()).isEqualTo("REJECTED");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(jdbc.queryForObject("SELECT updated_at::text FROM recruitment_requisitions WHERE id = ?", String.class,
                id)).isEqualTo(updatedAt);
        Map<String, Object> decision = jdbc.queryForMap(
                "SELECT * FROM requisition_workflow_events WHERE requisition_id = ? AND revision = 2", id);
        assertThat(decision).containsEntry("event_type", "REJECTED").containsEntry("level_number", 1)
                .containsEntry("round_number", 1).containsEntry("status_before", "PENDING_APPROVAL")
                .containsEntry("status_after", "REJECTED").containsEntry("actor_id", hrId)
                .containsEntry("comment", "Mức lương vượt kế hoạch\nquý IV");
        assertThat(((Timestamp) decision.get("occurred_at")).toInstant()).isEqualTo(START.plusSeconds(30));

        // A rejected requisition is finished: no recruiters, no more decisions; a copy starts over without history.
        UUID recruiter = account("r@example.test", "Recruiter", Role.RECRUITER);
        assertThat(api.code(api.request("POST", BASE + id + "/assign", json.writeValueAsString(Map.of("recruiterId",
                recruiter)), hrToken), 409)).isEqualTo("REQUISITION_NOT_ASSIGNABLE");
        assertThat(api.code(api.decide(id, "REJECT", "Lần hai", 2, hrToken), 409))
                .isEqualTo("REQUISITION_NOT_PENDING_APPROVAL");
        JsonNode copy = api.expect(api.request("POST", BASE + id + "/copy", null, itHeadToken), 201);
        assertThat(copy.path("status").asText()).isEqualTo("DRAFT");
        assertThat(api.events(UUID.fromString(copy.path("id").asText()))).isZero();
    }

    @Test
    void aNamedApproverWhoManagesNoDepartmentReadsAndDecidesTheirLevel() throws Exception {
        api.flow(it, hrId, START, step(1, null, approverA, null));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);

        assertThat(api.expect(api.request("GET", BASE + id, null, approverAToken), 200).path("status").asText())
                .isEqualTo("PENDING_APPROVAL");
        api.expect(api.request("GET", BASE + id + "/assignment", null, approverAToken), 200);
        api.forbidden(api.request("GET", BASE + id, null, approverBToken));
        api.expect(api.decide(id, "REJECT", "Không phù hợp", 1, approverAToken), 200);

        // A past decider keeps reading it; the list and the plan exceptions stay limited to managed departments.
        api.expect(api.request("GET", BASE + id, null, approverAToken), 200);
        api.expect(api.request("GET", BASE + id + "/assignment-history", null, approverAToken), 200);
        assertThat(api.expect(api.request("GET", "/api/v1/requisitions", null, approverAToken), 200)
                .path("totalElements").asLong()).isZero();
        api.forbidden(api.request("GET", BASE + id + "/headcount-overrides", null, approverAToken));
    }

    @Test
    void aHiringManagerLevelIsForTheHeadOfTheDepartmentOrOfADepartmentAboveIt() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HIRING_MANAGER"));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);

        // The head of IT created and submitted it; the head of SALES is a sibling.
        api.forbidden(api.decide(id, "REJECT", "Tự từ chối", 1, itHeadToken));
        api.forbidden(api.request("GET", BASE + id, null, siblingHeadToken));
        api.forbidden(api.decide(id, "REJECT", "Không phải phòng mình", 1, siblingHeadToken));
        api.expect(api.request("GET", BASE + id, null, parentHeadToken), 200);
        api.expect(api.decide(id, "REJECT", "Chưa cần tuyển", 1, parentHeadToken), 200);

        // Whoever decided keeps reading the requisition, even once IT no longer sits under PARENT.
        jdbc.update("UPDATE departments SET parent_id = NULL WHERE id = ?", it);
        api.expect(api.request("GET", BASE + id, null, parentHeadToken), 200);
    }

    @Test
    void theCreatorTheSubmitterAndOthersThanTheLevelNamesMayNotDecide() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"));
        // HR created and submitted this one: only the other HR manager decides it.
        UUID byHr = api.submitted(position, it, 22_000_000L, hrToken, hrToken);
        api.forbidden(api.decide(byHr, "REJECT", "Tự duyệt", 1, hrToken));
        api.forbidden(api.decide(byHr, "REJECT", "Không phải cấp của mình", 1, adminToken));
        api.forbidden(api.decide(byHr, "REJECT", "Không phải cấp của mình", 1, approverBToken));
        assertThat(api.events(byHr)).isOne();
        api.expect(api.decide(byHr, "REJECT", "Không phù hợp", 1, hr2Token), 200);
    }

    @Test
    void aLevelNamingSomeoneElseIsNotForHrOrAdminAndANamedPersonTakesNoRoleLevelOfTheChain() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "APPROVER"), step(2, 20_000_000L, approverA, null));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);

        // Level 1 is for APPROVER holders, but A is named by level 2 and keeps to it; HR and ADMIN have no override.
        api.forbidden(api.decide(id, "REJECT", "Không", 1, approverAToken));
        api.forbidden(api.decide(id, "REJECT", "Không", 1, hrToken));
        api.forbidden(api.decide(id, "REJECT", "Không", 1, adminToken));
        api.expect(api.decide(id, "REJECT", "Không phù hợp", 1, approverBToken), 200);
    }

    @Test
    void aLevelNotReachedYetOpensNothing() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"), step(2, 20_000_000L, approverA, null));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);

        api.forbidden(api.request("GET", BASE + id, null, approverAToken));
        api.forbidden(api.decide(id, "REJECT", "Chưa tới lượt", 1, approverAToken));
        // Someone who may not read it learns nothing about its progress, not even that a revision is outdated.
        api.forbidden(api.decide(id, "REJECT", "Chưa tới lượt", 99, approverAToken));
        api.expect(api.decide(id, "REJECT", "Không phù hợp", 1, hrToken), 200);
        // The round ended at level 1: level 2 was never reached.
        api.forbidden(api.request("GET", BASE + id, null, approverAToken));
    }

    @Test
    void invalidDecisionsAreFormErrorsAndWriteNothing() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);

        // A comment without any visible character is no comment: only non-breaking spaces (the history CHECK counts
        // them as spaces, so storing it failed with 500) or only zero-width characters (stored, they show nothing).
        String noBreakSpace = String.valueOf((char) 0x00A0);
        String zeroWidth = new String(new int[]{0x200B, 0xFEFF}, 0, 2);
        String otherSpaces = new String(new int[]{0x2007, 0x202F, 0x0085, 0x180E, '\n'}, 0, 5);
        for (String comment : new String[]{null, " \n ", noBreakSpace, zeroWidth, otherSpaces}) {
            JsonNode error = api.expect(api.decide(id, "REJECT", comment, 1, hrToken), 400);
            assertThat(error.path("code").asText()).isEqualTo("APPROVAL_COMMENT_REQUIRED");
            assertThat(error.path("fieldErrors").path("comment").asText())
                    .isEqualTo("Vui lòng nhập nhận xét khi từ chối hoặc yêu cầu bổ sung.");
        }
        assertField(api.decide(id, "DELEGATE", "Chuyển", 1, hrToken), "action");
        assertField(api.decide(id, "reject", "Chuyển", 1, hrToken), "action");
        assertField(api.decide(id, null, "Chuyển", 1, hrToken), "action");
        assertField(api.decide(id, "REJECT", "Không", null, hrToken), "expectedRevision");
        assertField(api.decide(id, "REJECT", "Không", 0, hrToken), "expectedRevision");
        assertField(api.decide(id, "REJECT", "a".repeat(2_001), 1, hrToken), "comment");
        assertThat(api.code(api.request("POST", BASE + id + "/approvals",
                "{\"action\":\"REJECT\",\"reason\":\"Không\",\"expectedRevision\":1}", hrToken), 400))
                .isEqualTo("INVALID_JSON");
        assertThat(api.events(id)).isOne();
        assertThat(api.status(id)).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void aDecisionOnAnOutdatedViewOrOutsideApprovalIsAConflict() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);
        UUID draft = api.draft(position, it, 22_000_000L, itHeadToken);

        assertThat(api.code(api.decide(id, "REJECT", "Không", 2, hrToken), 409)).isEqualTo("APPROVAL_STATE_CHANGED");
        assertThat(api.code(api.decide(draft, "REJECT", "Không", 1, hrToken), 409))
                .isEqualTo("REQUISITION_NOT_PENDING_APPROVAL");
        assertThat(api.code(api.decide(UUID.randomUUID(), "REJECT", "Không", 1, hrToken), 404))
                .isEqualTo("REQUISITION_NOT_FOUND");
        assertThat(api.events(id)).isOne();
    }

    @Test
    void writingAloneIsNotEnoughToDecideOrToLearnWhetherARequisitionExists() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' AND permission_code = 'REQUISITIONS_READ_ALL'");
        try {
            api.forbidden(api.decide(UUID.randomUUID(), "REJECT", "Không", 1, hrToken));
            api.forbidden(api.decide(id, "REJECT", "Không", 1, hrToken));
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'REQUISITIONS_READ_ALL')");
        }
        assertThat(api.events(id)).isOne();
        assertThat(api.request("POST", BASE + id + "/approvals", "{}", null).statusCode()).isEqualTo(401);
    }

    private void assertField(java.net.http.HttpResponse<String> response, String field) {
        JsonNode error = api.expect(response, 400);
        assertThat(error.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.path("fieldErrors").has(field)).as(error.toString()).isTrue();
    }

    private UUID account(String email, String name, Role... roles) {
        return accounts.saveAndFlush(new Account(email, name, passwordHash, Set.of(roles), START)).getId();
    }
}
