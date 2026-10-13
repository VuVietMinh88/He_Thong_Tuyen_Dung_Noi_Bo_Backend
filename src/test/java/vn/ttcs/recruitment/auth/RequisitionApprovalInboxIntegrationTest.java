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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.ttcs.recruitment.auth.ApprovalTestSupport.step;

// Task 267: the approver inbox lists exactly the requisitions whose current level the caller may decide.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionApprovalInboxIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");
    private static final String INBOX = "/api/v1/requisitions/approval-inbox";

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
    private String itHeadToken;
    private String parentHeadToken;
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
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('APPROVER', 'REQUISITIONS_WRITE_SCOPED') ON CONFLICT DO NOTHING");
        api.reset();
        bootstrap.run(new DefaultApplicationArguments());
        UUID adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        passwordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = account("hr@example.test", "Nguyễn Thị Hoa", Role.HR_MANAGER);
        UUID parentHead = account("parent@example.test", "Trưởng khối", Role.HIRING_MANAGER);
        UUID itHead = account("it@example.test", "Trưởng IT", Role.HIRING_MANAGER);
        approverA = account("a@example.test", "Giám đốc A", Role.APPROVER);
        account("b@example.test", "Giám đốc B", Role.APPROVER);
        hrToken = api.token("hr@example.test");
        itHeadToken = api.token("it@example.test");
        parentHeadToken = api.token("parent@example.test");
        approverAToken = api.token("a@example.test");
        approverBToken = api.token("b@example.test");
        UUID parent = api.department("PARENT", parentHead, null, START);
        it = api.department("IT", itHead, parent, START);
        position = api.position(START);
    }

    @Test
    void eachApproverSeesTheRequisitionsTheyMayDecideNowOldestWaitingFirst() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"), step(2, 20_000_000L, approverA, null),
                step(3, 50_000_000L, null, "APPROVER"));
        clock.set(START.plusSeconds(10));
        UUID older = api.submitted(position, it, 60_000_000L, itHeadToken, itHeadToken);
        clock.set(START.plusSeconds(20));
        UUID newer = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);
        api.draft(position, it, 22_000_000L, itHeadToken);

        assertThat(inbox(hrToken)).containsExactly(older, newer);
        assertThat(inbox(approverAToken)).isEmpty();
        // The creator and submitter never sees their own.
        assertThat(inbox(itHeadToken)).isEmpty();

        clock.set(START.plusSeconds(30));
        api.expect(api.decide(newer, "APPROVE", null, 1, hrToken), 200);
        assertThat(inbox(hrToken)).containsExactly(older);
        assertThat(inbox(approverAToken)).containsExactly(newer);
        // newer has waited for level 2 since 30 s, older since 40 s.
        clock.set(START.plusSeconds(40));
        api.expect(api.decide(older, "APPROVE", null, 1, hrToken), 200);
        assertThat(inbox(approverAToken)).containsExactly(newer, older);
        api.expect(api.decide(older, "APPROVE", null, 2, approverAToken), 200);
        assertThat(inbox(approverAToken)).containsExactly(newer);
        assertThat(inbox(approverBToken)).containsExactly(older);
        api.expect(api.decide(older, "APPROVE", null, 3, approverBToken), 200);
        assertThat(inbox(approverBToken)).isEmpty();
    }

    @Test
    void aHiringManagerLevelReachesTheHeadOfADepartmentAbove() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HIRING_MANAGER"));
        UUID id = api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);

        assertThat(inbox(parentHeadToken)).containsExactly(id);
        assertThat(inbox(itHeadToken)).isEmpty();
        assertThat(inbox(hrToken)).isEmpty();
    }

    @Test
    void theInboxIsPagedAndAgreesWithCanDecide() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "HR_MANAGER"));
        List<UUID> submitted = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            clock.set(START.plusSeconds(i));
            submitted.add(api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken));
        }

        JsonNode page = api.expect(api.request("GET", INBOX + "?page=1&size=2", null, hrToken), 200);
        assertThat(page.path("totalElements").asLong()).isEqualTo(3);
        assertThat(page.path("totalPages").asLong()).isEqualTo(2);
        assertThat(page.path("page").asInt()).isEqualTo(1);
        assertThat(ids(page)).containsExactly(submitted.get(2));
        for (String bad : new String[]{"?page=-1", "?size=0", "?size=101"}) {
            assertThat(api.code(api.request("GET", INBOX + bad, null, hrToken), 400)).isEqualTo("VALIDATION_ERROR");
        }
        for (UUID id : submitted) {
            for (String token : new String[]{hrToken, itHeadToken, parentHeadToken}) {
                boolean canDecide = api.expect(api.request("GET", "/api/v1/requisitions/" + id + "/approvals", null,
                        token), 200).path("actions").path("canDecide").asBoolean();
                assertThat(inbox(token).contains(id)).isEqualTo(canDecide);
            }
        }
    }

    @Test
    void aReaderWhoMayNotWriteDecidesNothingAndGetsAnEmptyInbox() throws Exception {
        api.flow(it, hrId, START, step(1, null, null, "APPROVER"));
        api.submitted(position, it, 22_000_000L, itHeadToken, itHeadToken);
        assertThat(inbox(approverAToken)).hasSize(1);

        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'APPROVER' AND permission_code = 'REQUISITIONS_WRITE_SCOPED'");
        assertThat(inbox(approverAToken)).isEmpty();
        assertThat(api.request("GET", INBOX, null, null).statusCode()).isEqualTo(401);
    }

    private List<UUID> inbox(String token) throws Exception {
        var response = api.request("GET", INBOX + "?size=100", null, token);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        return ids(api.expect(response, 200));
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.path("items").forEach(item -> ids.add(UUID.fromString(item.path("id").asText())));
        return ids;
    }

    private UUID account(String email, String name, Role... roles) {
        return accounts.saveAndFlush(new Account(email, name, passwordHash, Set.of(roles), START)).getId();
    }
}
