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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Task 286: every recruiter change of a requisition is recorded (who, when, before/after, note) and can be read back.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionRecruiterHistoryIntegrationTest {
    private static final String BASE = "/api/v1/requisitions/";
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-10T03:00:00Z");
    private static final List<String> FIELDS = List.of("id", "revision", "changeType", "recruiterId", "assignedTo",
            "previousRecruiterId", "previousAssignedTo", "assignedById", "assignedBy", "assignedAt", "note");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String fixturePasswordHash;
    private UUID hrId;
    private String hrToken;
    private String headToken;
    private UUID requisitionId;
    private final List<UUID> r = new ArrayList<>();

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
        jdbc.update("DELETE FROM requisition_recruiter_changes");
        jdbc.update("DELETE FROM requisition_recruiters");
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET department_id = NULL, admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("UPDATE departments SET parent_id = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        UUID adminId = jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'admin@example.test'", UUID.class);
        fixturePasswordHash = accounts.findById(adminId).orElseThrow().getPasswordHash();
        hrId = account("hr@example.test", "Nguyễn Thị Hoa", Set.of(Role.HR_MANAGER));
        hrToken = token("hr@example.test");
        UUID headId = account("head@example.test", "Trưởng IT", Set.of(Role.HIRING_MANAGER));
        headToken = token("head@example.test");
        account("sales@example.test", "Trưởng Kinh doanh", Set.of(Role.HIRING_MANAGER));
        r.clear();
        for (int i = 0; i < 4; i++) {
            r.add(account("r" + i + "@example.test", "Recruiter " + i, Set.of(Role.RECRUITER)));
        }
        UUID itId = department("IT", headId);
        department("SALES", jdbc.queryForObject("SELECT id FROM user_accounts WHERE email = 'sales@example.test'",
                UUID.class));
        UUID positionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id, code, name, level, salary_min, salary_max, active, created_at, updated_at)
                VALUES (?, 'DEV', 'Lập trình viên', 'Junior', 15000000, 25000000, TRUE, ?, ?)
                """, positionId, Timestamp.from(START), Timestamp.from(START));
        requisitionId = UUID.fromString(expect(request("POST", "/api/v1/requisitions", json.writeValueAsString(
                Map.of("positionId", positionId, "departmentId", itId, "headcount", 2, "reason", "NEW_HEADCOUNT")),
                headToken), 201).path("id").asText());
    }

    @Test
    void everyChangeIsRecordedNewestFirstWithWhoWhenBeforeAfterAndNote() throws Exception {
        clock.set(START.plusNanos(123_456_789));
        expect(assign(Map.of("recruiterId", r.get(0), "note", "Phụ trách đợt tuyển quý 4"), hrToken), 200);
        clock.set(START.plusSeconds(60));
        expect(assign(Map.of("recruiterId", r.get(1), "role", "SUPPORTING"), hrToken), 200);
        clock.set(START.plusSeconds(120));
        expect(assign(Map.of("recruiterId", r.get(2), "note", "Nghỉ phép"), hrToken), 200);
        clock.set(START.plusSeconds(180));
        expect(unassign(Map.of("recruiterId", r.get(1), "note", "Chuyển dự án"), hrToken), 200);

        var response = history(hrToken);
        JsonNode items = expect(response, 200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(items.isArray()).isTrue();
        assertThat(items.size()).isEqualTo(4);
        items.forEach(item -> {
            assertThat(item.size()).isEqualTo(FIELDS.size());
            FIELDS.forEach(field -> assertThat(item.has(field)).as(field).isTrue());
            assertThat(item.path("assignedById").asText()).isEqualTo(hrId.toString());
            assertThat(item.path("assignedBy").asText()).isEqualTo("Nguyễn Thị Hoa");
        });
        assertThat(revisions(items)).containsExactly(4, 3, 2, 1);

        JsonNode removed = items.get(0);
        assertThat(removed.path("changeType").asText()).isEqualTo("SUPPORTING_REMOVED");
        assertThat(removed.path("recruiterId").asText()).isEqualTo(r.get(1).toString());
        assertThat(removed.path("assignedTo").asText()).isEqualTo("Recruiter 1");
        assertThat(removed.path("note").asText()).isEqualTo("Chuyển dự án");
        JsonNode handover = items.get(1);
        assertThat(handover.path("changeType").asText()).isEqualTo("PRIMARY_HANDED_OVER");
        assertThat(handover.path("recruiterId").asText()).isEqualTo(r.get(2).toString());
        assertThat(handover.path("previousRecruiterId").asText()).isEqualTo(r.get(0).toString());
        assertThat(handover.path("previousAssignedTo").asText()).isEqualTo("Recruiter 0");
        assertThat(Instant.parse(handover.path("assignedAt").asText())).isEqualTo(START.plusSeconds(120));
        JsonNode added = items.get(2);
        assertThat(added.path("changeType").asText()).isEqualTo("SUPPORTING_ADDED");
        assertThat(added.path("note").isNull()).isTrue();
        assertThat(added.path("previousRecruiterId").isNull()).isTrue();
        JsonNode first = items.get(3);
        assertThat(first.path("changeType").asText()).isEqualTo("PRIMARY_ASSIGNED");
        assertThat(first.path("note").asText()).isEqualTo("Phụ trách đợt tuyển quý 4");
        // The history and the team record the same moment.
        assertThat(Instant.parse(first.path("assignedAt").asText())).isEqualTo(START.plusNanos(123_456_000));
    }

    @Test
    void aPromotionIsOneHandoverAndReplayingTheHistoryGivesTheCurrentTeam() throws Exception {
        // The clock never moves: the revision, not the time, orders the history.
        expect(assign(Map.of("recruiterId", r.get(0)), hrToken), 200);
        expect(assign(Map.of("recruiterId", r.get(1), "role", "SUPPORTING"), hrToken), 200);
        expect(assign(Map.of("recruiterId", r.get(2), "role", "SUPPORTING"), hrToken), 200);
        expect(assign(Map.of("recruiterId", r.get(1), "role", "PRIMARY"), hrToken), 200);
        expect(assign(Map.of("recruiterId", r.get(3)), hrToken), 200);
        expect(unassign(Map.of("recruiterId", r.get(2)), hrToken), 200);

        JsonNode items = expect(history(hrToken), 200);
        assertThat(revisions(items)).containsExactly(6, 5, 4, 3, 2, 1);
        assertThat(items.get(2).path("changeType").asText()).isEqualTo("PRIMARY_HANDED_OVER");
        assertThat(items.get(2).path("previousRecruiterId").asText()).isEqualTo(r.get(0).toString());

        UUID primary = null;
        Set<UUID> supporting = new LinkedHashSet<>();
        for (int i = items.size() - 1; i >= 0; i--) {
            JsonNode item = items.get(i);
            UUID who = UUID.fromString(item.path("recruiterId").asText());
            switch (item.path("changeType").asText()) {
                case "PRIMARY_ASSIGNED", "PRIMARY_HANDED_OVER" -> { primary = who; supporting.remove(who); }
                case "SUPPORTING_ADDED" -> supporting.add(who);
                default -> supporting.remove(who);
            }
        }
        JsonNode team = expect(request("GET", BASE + requisitionId + "/assignment", null, hrToken), 200);
        assertThat(primary).hasToString(team.path("primaryRecruiter").path("recruiterId").asText());
        List<String> current = new ArrayList<>();
        team.path("supportingRecruiters").forEach(node -> current.add(node.path("recruiterId").asText()));
        assertThat(supporting.stream().map(UUID::toString).toList()).containsExactlyInAnyOrderElementsOf(current);
    }

    @Test
    void notesAreKeptExactlyAndBlankNotesAreStoredAsNull() throws Exception {
        String longNote = "Dòng 1\n  Dòng 2\t";
        longNote += "x".repeat(1_000 - longNote.length());
        assertThat(longNote).hasSize(1_000);
        expect(assign(Map.of("recruiterId", r.get(0), "note", longNote), hrToken), 200);
        expect(assign(Map.of("recruiterId", r.get(1), "role", "SUPPORTING", "note", " \n\t "), hrToken), 200);

        assertThat(jdbc.queryForObject("SELECT note FROM requisition_recruiter_changes WHERE revision = 1",
                String.class)).isEqualTo(longNote);
        assertThat(jdbc.queryForObject("SELECT note IS NULL FROM requisition_recruiter_changes WHERE revision = 2",
                Boolean.class)).isTrue();
    }

    @Test
    void nothingIsRecordedForNoOpsOrRefusedRequests() throws Exception {
        expect(assign(Map.of("recruiterId", r.get(0)), hrToken), 200);
        // No-ops.
        expect(assign(Map.of("recruiterId", r.get(0), "note", "Không đổi"), hrToken), 200);
        expect(unassign(Map.of("recruiterId", r.get(3)), hrToken), 200);
        // Refused: 400, 403, 404, 409.
        expect(assign(Map.of("recruiterId", UUID.randomUUID()), hrToken), 400);
        expect(assign(Map.of("recruiterId", r.get(1)), headToken), 403);
        expect(request("POST", BASE + UUID.randomUUID() + "/assign",
                json.writeValueAsString(Map.of("recruiterId", r.get(1))), hrToken), 404);
        expect(unassign(Map.of("recruiterId", r.get(0)), hrToken), 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM requisition_recruiter_changes", Integer.class)).isOne();
    }

    @Test
    void namesAreReadWhenTheHistoryIsShown() throws Exception {
        expect(assign(Map.of("recruiterId", r.get(0)), hrToken), 200);
        jdbc.update("UPDATE user_accounts SET full_name = 'Recruiter đổi tên' WHERE id = ?", r.get(0));

        assertThat(expect(history(hrToken), 200).get(0).path("assignedTo").asText()).isEqualTo("Recruiter đổi tên");
    }

    @Test
    void theHistoryIsReadLikeTheRequisitionAndACopyHasNone() throws Exception {
        assertThat(expect(history(hrToken), 200).isEmpty()).isTrue();
        expect(assign(Map.of("recruiterId", r.get(0)), hrToken), 200);

        assertThat(expect(history(headToken), 200).size()).isOne();
        assertThat(expect(history(token("sales@example.test")), 403).path("code").asText()).isEqualTo("FORBIDDEN");
        account("interviewer@example.test", "Người phỏng vấn", Set.of(Role.INTERVIEWER));
        assertThat(history(token("interviewer@example.test")).statusCode()).isEqualTo(403);
        assertThat(expect(request("GET", BASE + UUID.randomUUID() + "/assignment-history", null, hrToken), 404)
                .path("code").asText()).isEqualTo("REQUISITION_NOT_FOUND");
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' AND permission_code = 'REQUISITIONS_READ_ALL'");
        try {
            assertThat(history(hrToken).statusCode()).isEqualTo(403);
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'REQUISITIONS_READ_ALL')");
        }

        UUID copy = UUID.fromString(expect(request("POST", BASE + requisitionId + "/copy", null, hrToken), 201)
                .path("id").asText());
        assertThat(expect(request("GET", BASE + copy + "/assignment-history", null, hrToken), 200).isEmpty()).isTrue();
    }

    private static List<Integer> revisions(JsonNode items) {
        List<Integer> result = new ArrayList<>();
        items.forEach(item -> result.add(item.path("revision").asInt()));
        return result;
    }

    private UUID account(String email, String name, Set<Role> roles) {
        return accounts.saveAndFlush(new Account(email, name, fixturePasswordHash, roles, START)).getId();
    }

    private UUID department(String code, UUID manager) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, code, name, manager_user_id, active, created_at) VALUES (?, ?, ?, ?, TRUE, ?)",
                id, code, "Phòng " + code, manager, Timestamp.from(START));
        return id;
    }

    private String token(String email) throws Exception {
        return expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", email, "password", PASSWORD)), null), 200)
                .path("accessToken").asText();
    }

    private HttpResponse<String> assign(Map<String, Object> body, String token) throws Exception {
        return request("POST", BASE + requisitionId + "/assign", json.writeValueAsString(new LinkedHashMap<>(body)), token);
    }

    private HttpResponse<String> unassign(Map<String, Object> body, String token) throws Exception {
        return request("POST", BASE + requisitionId + "/unassign", json.writeValueAsString(new LinkedHashMap<>(body)), token);
    }

    private HttpResponse<String> history(String token) throws Exception {
        return request("GET", BASE + requisitionId + "/assignment-history", null, token);
    }

    private HttpResponse<String> request(String method, String path, String payload, String token) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + environment.getRequiredProperty("local.server.port") + path);
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode expect(HttpResponse<String> response, int expected) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return json.readTree(response.body());
    }
}
