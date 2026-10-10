package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Task 284: HR assigns, hands over and removes the recruiters of a requisition; readers of the requisition see them.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionRecruiterAssignmentIntegrationTest {
    private static final String BASE = "/api/v1/requisitions/";
    private static final String PASSWORD = "TestingOnly123!";
    // 10:00 on 10 Oct 2026 in Vietnam.
    private static final Instant START = Instant.parse("2026-10-10T03:00:00Z");
    private static final List<String> RECRUITER_FIELDS = List.of("recruiterId", "fullName", "eligible", "assignedById",
            "assignedBy", "assignedAt");

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
    private String adminToken;
    private String headToken;
    private UUID itId;
    private UUID salesId;
    private UUID salesHeadId;
    private UUID positionId;
    private UUID requisitionId;
    // Recruiters r[0] … r[11], all active.
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
        jdbc.update("DELETE FROM requisition_headcount_overrides");
        jdbc.update("DELETE FROM headcount_plans");
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
        adminToken = token("admin@example.test");
        hrId = account("hr@example.test", "Nguyễn Thị Hoa", Set.of(Role.HR_MANAGER));
        hrToken = token("hr@example.test");
        UUID headId = account("head@example.test", "Trưởng IT", Set.of(Role.HIRING_MANAGER));
        headToken = token("head@example.test");
        salesHeadId = account("sales@example.test", "Trưởng Kinh doanh", Set.of(Role.HIRING_MANAGER));
        r.clear();
        for (int i = 0; i < 12; i++) {
            r.add(account("r" + i + "@example.test", "Recruiter " + i, Set.of(Role.RECRUITER)));
        }
        itId = department("IT", headId);
        salesId = department("SALES", salesHeadId);
        positionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id, code, name, level, salary_min, salary_max, active, created_at, updated_at)
                VALUES (?, 'DEV', 'Lập trình viên', 'Junior', 15000000, 25000000, TRUE, ?, ?)
                """, positionId, Timestamp.from(START), Timestamp.from(START));
        requisitionId = UUID.fromString(expect(request("POST", "/api/v1/requisitions", json.writeValueAsString(
                Map.of("positionId", positionId, "departmentId", itId, "headcount", 2, "reason", "NEW_HEADCOUNT")),
                headToken), 201).path("id").asText());
    }

    @Test
    void hrAssignsAPrimaryWithTheBodyTheFrontendSends() throws Exception {
        clock.set(START.plusNanos(123_456_789));
        Instant expected = START.plusNanos(123_456_000);

        var response = assign(requisitionId, Map.of("recruiterId", r.get(0), "note", "Phụ trách đợt tuyển quý 4"), hrToken);
        JsonNode team = expect(response, 200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(team.size()).isEqualTo(3);
        assertThat(team.path("requisitionId").asText()).isEqualTo(requisitionId.toString());
        JsonNode primary = team.path("primaryRecruiter");
        assertThat(primary.size()).isEqualTo(RECRUITER_FIELDS.size());
        RECRUITER_FIELDS.forEach(field -> assertThat(primary.has(field)).as(field).isTrue());
        assertThat(primary.path("recruiterId").asText()).isEqualTo(r.get(0).toString());
        assertThat(primary.path("fullName").asText()).isEqualTo("Recruiter 0");
        assertThat(primary.path("eligible").asBoolean()).isTrue();
        assertThat(primary.path("assignedById").asText()).isEqualTo(hrId.toString());
        assertThat(primary.path("assignedBy").asText()).isEqualTo("Nguyễn Thị Hoa");
        assertThat(Instant.parse(primary.path("assignedAt").asText())).isEqualTo(expected);
        assertThat(team.path("supportingRecruiters").isArray()).isTrue();
        assertThat(team.path("supportingRecruiters").isEmpty()).isTrue();

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM requisition_recruiters");
        assertThat(row.get("recruiter_id")).isEqualTo(r.get(0));
        assertThat(row.get("assignment_role")).isEqualTo("PRIMARY");
        assertThat(row.get("assigned_by")).isEqualTo(hrId);
        assertThat(((Timestamp) row.get("assigned_at")).toInstant()).isEqualTo(expected);
        // GET shows the same team.
        assertThat(expect(get(requisitionId, "assignment", hrToken), 200)).isEqualTo(team);
    }

    @Test
    void anUnassignedRequisitionHasNoRecruitersAndAdminMayAssignToo() throws Exception {
        JsonNode empty = expect(get(requisitionId, "assignment", hrToken), 200);
        assertThat(empty.has("primaryRecruiter")).isTrue();
        assertThat(empty.path("primaryRecruiter").isNull()).isTrue();
        assertThat(empty.path("supportingRecruiters").isEmpty()).isTrue();

        JsonNode team = expect(assign(requisitionId, Map.of("recruiterId", r.get(0)), adminToken), 200);
        assertThat(team.path("primaryRecruiter").path("recruiterId").asText()).isEqualTo(r.get(0).toString());
    }

    @Test
    void aHandoverReplacesThePrimaryAndSavingTheSamePrimaryAgainChangesNothing() throws Exception {
        expect(assign(requisitionId, Map.of("recruiterId", r.get(0)), hrToken), 200);
        clock.set(START.plusSeconds(60));
        JsonNode same = expect(assign(requisitionId, Map.of("recruiterId", r.get(0), "role", "PRIMARY"), hrToken), 200);
        assertThat(Instant.parse(same.path("primaryRecruiter").path("assignedAt").asText())).isEqualTo(START);

        clock.set(START.plusSeconds(120));
        JsonNode handedOver = expect(assign(requisitionId, Map.of("recruiterId", r.get(1), "note", "Nghỉ phép"),
                hrToken), 200);
        assertThat(handedOver.path("primaryRecruiter").path("recruiterId").asText()).isEqualTo(r.get(1).toString());
        assertThat(Instant.parse(handedOver.path("primaryRecruiter").path("assignedAt").asText()))
                .isEqualTo(START.plusSeconds(120));
        // The previous primary leaves the requisition.
        assertThat(handedOver.path("supportingRecruiters").isEmpty()).isTrue();
        assertThat(rows()).isEqualTo(1);
    }

    @Test
    void promotingASupportingRecruiterRemovesItFromSupporting() throws Exception {
        expect(assign(requisitionId, Map.of("recruiterId", r.get(0)), hrToken), 200);
        expect(assign(requisitionId, Map.of("recruiterId", r.get(1), "role", "SUPPORTING"), hrToken), 200);
        expect(assign(requisitionId, Map.of("recruiterId", r.get(2), "role", "SUPPORTING"), hrToken), 200);

        JsonNode team = expect(assign(requisitionId, Map.of("recruiterId", r.get(1), "role", "PRIMARY"), hrToken), 200);
        assertThat(team.path("primaryRecruiter").path("recruiterId").asText()).isEqualTo(r.get(1).toString());
        assertThat(ids(team.path("supportingRecruiters"))).containsExactly(r.get(2));
        assertThat(rows()).isEqualTo(2);
    }

    @Test
    void addsAndRemovesSupportingRecruitersWithinTheRules() throws Exception {
        assertThat(conflict(assign(requisitionId, Map.of("recruiterId", r.get(1), "role", "SUPPORTING"), hrToken)))
                .isEqualTo("REQUISITION_PRIMARY_RECRUITER_REQUIRED");
        expect(assign(requisitionId, Map.of("recruiterId", r.get(0)), hrToken), 200);
        assertThat(conflict(assign(requisitionId, Map.of("recruiterId", r.get(0), "role", "SUPPORTING"), hrToken)))
                .isEqualTo("REQUISITION_RECRUITER_ALREADY_PRIMARY");
        for (int i = 1; i <= 10; i++) {
            clock.set(START.plusSeconds(i));
            expect(assign(requisitionId, Map.of("recruiterId", r.get(i), "role", "SUPPORTING"), hrToken), 200);
        }
        assertThat(conflict(assign(requisitionId, Map.of("recruiterId", r.get(11), "role", "SUPPORTING"), hrToken)))
                .isEqualTo("REQUISITION_SUPPORTING_RECRUITER_LIMIT");
        // Supporting recruiters are listed in the order they were added.
        JsonNode team = expect(get(requisitionId, "assignment", hrToken), 200);
        assertThat(ids(team.path("supportingRecruiters"))).containsExactlyElementsOf(r.subList(1, 11));

        JsonNode removed = expect(unassign(requisitionId, Map.of("recruiterId", r.get(5), "note", "Chuyển dự án"),
                hrToken), 200);
        assertThat(ids(removed.path("supportingRecruiters"))).doesNotContain(r.get(5)).hasSize(9);
        // Removing someone who is not assigned changes nothing; the primary is never removed.
        assertThat(expect(unassign(requisitionId, Map.of("recruiterId", r.get(11)), hrToken), 200)).isEqualTo(removed);
        assertThat(conflict(unassign(requisitionId, Map.of("recruiterId", r.get(0)), hrToken)))
                .isEqualTo("REQUISITION_PRIMARY_RECRUITER_REQUIRED");
        assertThat(rows()).isEqualTo(10);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "{}|recruiterId",
            "{\"recruiterId\":\"RID\",\"role\":\"primary\"}|role",
            "{\"recruiterId\":\"RID\",\"role\":\"LEAD\"}|role",
            "{\"recruiterId\":\"RID\",\"note\":\"LONG\"}|note",
            "{\"recruiterId\":\"RID\",\"note\":\"Ghi chú\\u0000\"}|note"
    })
    void invalidBodiesAreFormErrorsAndWriteNothing(String body, String field) throws Exception {
        String raw = body.replace("RID", r.get(0).toString()).replace("LONG", "a".repeat(1_001));

        JsonNode error = expect(request("POST", BASE + requisitionId + "/assign", raw, hrToken), 400);
        assertThat(error.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.path("fieldErrors").has(field)).as(error.toString()).isTrue();
        assertThat(rows()).isZero();
    }

    // The body of /unassign follows the same rules as the body of /assign; a refused removal removes nobody.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"recruiterId missing", "note too long", "note with NUL"})
    void invalidUnassignBodiesAreFormErrorsAndRemoveNobody(String invalid) throws Exception {
        expect(assign(requisitionId, Map.of("recruiterId", r.get(0)), hrToken), 200);
        expect(assign(requisitionId, Map.of("recruiterId", r.get(1), "role", "SUPPORTING"), hrToken), 200);
        Map<String, Object> body = switch (invalid) {
            case "recruiterId missing" -> Map.of("note", "Chuyển dự án");
            case "note too long" -> Map.of("recruiterId", r.get(1), "note", "a".repeat(1_001));
            default -> Map.of("recruiterId", r.get(1), "note", "Ghi chú" + (char) 0);
        };

        JsonNode error = expect(unassign(requisitionId, body, hrToken), 400);
        assertThat(error.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.path("fieldErrors").has(invalid.startsWith("recruiterId") ? "recruiterId" : "note"))
                .as(error.toString()).isTrue();
        assertThat(team()).containsExactly(r.get(0) + ":PRIMARY", r.get(1) + ":SUPPORTING");
        // Task 286: the two assignments are the whole history; the refused removal adds nothing.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM requisition_recruiter_changes", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void malformedBodiesAreInvalidJson() throws Exception {
        String rid = r.get(0).toString();
        for (String[] call : List.of(
                new String[] {"assign", "{\"recruiterId\":\"HR01\"}"},
                new String[] {"assign", "{\"recruiterId\":\"" + rid + "\",\"assignedBy\":\"" + hrId + "\"}"},
                new String[] {"assign", "{\"recruiterId\":\"" + rid + "\",\"role\":{\"name\":\"PRIMARY\"}}"},
                new String[] {"unassign", "{\"recruiterId\":\"" + rid + "\",\"role\":\"SUPPORTING\"}"},
                new String[] {"assign", null})) {
            JsonNode error = expect(request("POST", BASE + requisitionId + "/" + call[0], call[1], hrToken), 400);
            assertThat(error.path("code").asText()).as(call[1]).isEqualTo("INVALID_JSON");
        }
        assertThat(rows()).isZero();
    }

    @Test
    void onlyActiveRecruitersMayBeGivenARole() throws Exception {
        // The person is checked before the team rules: as SUPPORTING with no primary yet, the answer is still the
        // form error on recruiterId (400), not REQUISITION_PRIMARY_RECRUITER_REQUIRED (409).
        UUID interviewer = account("interviewer@example.test", "Người phỏng vấn", Set.of(Role.INTERVIEWER));
        for (UUID wrong : List.of(UUID.randomUUID(), interviewer, salesHeadId)) {
            for (String role : List.of("PRIMARY", "SUPPORTING")) {
                JsonNode error = expect(assign(requisitionId, Map.of("recruiterId", wrong, "role", role), hrToken), 400);
                assertThat(error.path("code").asText()).isEqualTo("INVALID_REQUISITION_RECRUITER");
                assertThat(error.path("fieldErrors").path("recruiterId").asText())
                        .isEqualTo("Người được phân công phải là tài khoản tồn tại và có vai trò Recruiter.");
            }
        }
        UUID adminLocked = account("locked@example.test", "Bị khóa", Set.of(Role.RECRUITER));
        jdbc.update("UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                Timestamp.from(START), hrId, adminLocked);
        UUID pending = account("pending@example.test", "Chưa kích hoạt", Set.of(Role.RECRUITER));
        jdbc.update("UPDATE user_accounts SET enabled = FALSE WHERE id = ?", pending);
        for (UUID inactive : List.of(adminLocked, pending)) {
            for (String role : List.of("PRIMARY", "SUPPORTING")) {
                JsonNode error = expect(assign(requisitionId, Map.of("recruiterId", inactive, "role", role), hrToken), 400);
                assertThat(error.path("code").asText()).isEqualTo("REQUISITION_RECRUITER_INACTIVE");
                assertThat(error.path("fieldErrors").has("recruiterId")).isTrue();
            }
        }
        assertThat(rows()).isZero();

        // A temporary lockout after wrong passwords ends by itself; an HR manager who is also a recruiter is valid.
        UUID tempLocked = account("temp@example.test", "Tạm khóa", Set.of(Role.RECRUITER));
        jdbc.update("UPDATE user_accounts SET locked_until = ? WHERE id = ?",
                Timestamp.from(START.plus(Duration.ofMinutes(10))), tempLocked);
        UUID hrRecruiter = account("hr-recruiter@example.test", "HR kiêm recruiter",
                Set.of(Role.HR_MANAGER, Role.RECRUITER));
        expect(assign(requisitionId, Map.of("recruiterId", tempLocked), hrToken), 200);
        expect(assign(requisitionId, Map.of("recruiterId", hrRecruiter, "role", "SUPPORTING"), hrToken), 200);
        assertThat(rows()).isEqualTo(2);
    }

    @Test
    void aPrimaryLockedLaterStaysUntilHandedOverAndIsShownAsNotEligible() throws Exception {
        expect(assign(requisitionId, Map.of("recruiterId", r.get(0)), hrToken), 200);
        expect(assign(requisitionId, Map.of("recruiterId", r.get(1), "role", "SUPPORTING"), hrToken), 200);
        jdbc.update("UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Nghỉ việc', admin_locked_by = ? WHERE id IN (?, ?)",
                Timestamp.from(START), hrId, r.get(0), r.get(1));

        JsonNode team = expect(get(requisitionId, "assignment", hrToken), 200);
        assertThat(team.path("primaryRecruiter").path("eligible").asBoolean()).isFalse();
        // Keeping the role one already has is not checked again.
        expect(assign(requisitionId, Map.of("recruiterId", r.get(0)), hrToken), 200);
        // Promoting a locked supporting recruiter gives them a new role, so it is checked.
        assertThat(expect(assign(requisitionId, Map.of("recruiterId", r.get(1)), hrToken), 400).path("code").asText())
                .isEqualTo("REQUISITION_RECRUITER_INACTIVE");
        // Handing over to an active recruiter works, and so does removing the locked supporting recruiter.
        expect(assign(requisitionId, Map.of("recruiterId", r.get(2)), hrToken), 200);
        JsonNode after = expect(unassign(requisitionId, Map.of("recruiterId", r.get(1)), hrToken), 200);
        assertThat(after.path("primaryRecruiter").path("recruiterId").asText()).isEqualTo(r.get(2).toString());
        assertThat(after.path("supportingRecruiters").isEmpty()).isTrue();
    }

    @Test
    void anUnknownRequisitionIs404ForAllThreeEndpoints() throws Exception {
        UUID unknown = UUID.randomUUID();
        for (var response : List.of(get(unknown, "assignment", hrToken),
                assign(unknown, Map.of("recruiterId", r.get(0)), hrToken),
                assign(unknown, Map.of("recruiterId", UUID.randomUUID()), hrToken),
                unassign(unknown, Map.of("recruiterId", r.get(0)), hrToken))) {
            assertThat(expect(response, 404).path("code").asText()).isEqualTo("REQUISITION_NOT_FOUND");
        }
        assertThat(expect(request("GET", BASE + "not-a-uuid/assignment", null, hrToken), 400).path("code").asText())
                .isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void aDepartmentHeadReadsTheTeamWithoutEligibilityButCannotAssign() throws Exception {
        expect(assign(requisitionId, Map.of("recruiterId", r.get(0)), hrToken), 200);

        JsonNode team = expect(get(requisitionId, "assignment", headToken), 200);
        assertThat(team.path("primaryRecruiter").path("fullName").asText()).isEqualTo("Recruiter 0");
        assertThat(team.path("primaryRecruiter").has("eligible")).isFalse();
        forbidden(assign(requisitionId, Map.of("recruiterId", r.get(1)), headToken));
        forbidden(unassign(requisitionId, Map.of("recruiterId", r.get(0)), headToken));
        assertThat(rows()).isOne();
    }

    @Test
    void readersOutsideTheScopeAndOtherRolesAreRefused() throws Exception {
        expect(assign(requisitionId, Map.of("recruiterId", r.get(0)), hrToken), 200);
        String salesToken = token("sales@example.test");
        String recruiterToken = token("r0@example.test");
        account("interviewer@example.test", "Người phỏng vấn", Set.of(Role.INTERVIEWER));
        String interviewerToken = token("interviewer@example.test");

        forbidden(get(requisitionId, "assignment", salesToken));
        // Being assigned does not open the requisition yet: REQUISITIONS_*_SCOPED still means managed departments.
        forbidden(get(requisitionId, "assignment", recruiterToken));
        forbidden(get(requisitionId, "assignment", interviewerToken));
        forbidden(assign(requisitionId, Map.of("recruiterId", r.get(1)), interviewerToken));
        forbidden(assign(requisitionId, Map.of("recruiterId", r.get(1)), recruiterToken));
        assertThat(get(requisitionId, "assignment", null).statusCode()).isEqualTo(401);
        assertThat(rows()).isOne();
    }

    // A write answers with the whole team, so the writer must also be allowed to read the requisition, like copy.
    // Without any READ permission even an unknown id is 403, so a caller who may only write learns nothing, not even
    // through a call that would change nothing. A SCOPED reader must manage the requisition's department.
    @Test
    void writersMustAlsoBeAllowedToReadTheRequisition() throws Exception {
        expect(assign(requisitionId, Map.of("recruiterId", r.get(0)), hrToken), 200);
        expect(assign(requisitionId, Map.of("recruiterId", r.get(1), "role", "SUPPORTING"), hrToken), 200);
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' AND permission_code = 'REQUISITIONS_READ_ALL'");
        try {
            forbidden(assign(requisitionId, Map.of("recruiterId", r.get(2)), hrToken));
            forbidden(assign(requisitionId, Map.of("recruiterId", r.get(0)), hrToken));
            forbidden(unassign(requisitionId, Map.of("recruiterId", r.get(1)), hrToken));
            forbidden(unassign(requisitionId, Map.of("recruiterId", r.get(11)), hrToken));
            forbidden(assign(UUID.randomUUID(), Map.of("recruiterId", r.get(2)), hrToken));
            forbidden(get(requisitionId, "assignment", hrToken));

            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'REQUISITIONS_READ_SCOPED')");
            assertThat(expect(assign(UUID.randomUUID(), Map.of("recruiterId", r.get(2)), hrToken), 404)
                    .path("code").asText()).isEqualTo("REQUISITION_NOT_FOUND");
            forbidden(assign(requisitionId, Map.of("recruiterId", r.get(2)), hrToken));
            forbidden(unassign(requisitionId, Map.of("recruiterId", r.get(1)), hrToken));
            assertThat(team()).containsExactly(r.get(0) + ":PRIMARY", r.get(1) + ":SUPPORTING");

            jdbc.update("UPDATE departments SET manager_user_id = ? WHERE id = ?", hrId, itId);
            JsonNode team = expect(assign(requisitionId, Map.of("recruiterId", r.get(2)), hrToken), 200);
            assertThat(team.path("primaryRecruiter").path("recruiterId").asText()).isEqualTo(r.get(2).toString());
        } finally {
            jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' AND permission_code = 'REQUISITIONS_READ_SCOPED'");
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'REQUISITIONS_READ_ALL')");
        }
    }

    // eligible follows the permission to assign, not the permission to read every requisition (the seed always grants
    // both together, so only removing one of them tells them apart).
    @Test
    void eligibilityIsShownOnlyToCallersWhoMayAssign() throws Exception {
        expect(assign(requisitionId, Map.of("recruiterId", r.get(0)), hrToken), 200);
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'HR_MANAGER' AND permission_code = 'REQUISITIONS_WRITE_ALL'");
        try {
            JsonNode team = expect(get(requisitionId, "assignment", hrToken), 200);
            assertThat(team.path("primaryRecruiter").path("recruiterId").asText()).isEqualTo(r.get(0).toString());
            assertThat(team.path("primaryRecruiter").has("eligible")).isFalse();
        } finally {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', 'REQUISITIONS_WRITE_ALL')");
        }
    }

    @Test
    void aCopyStartsUnassignedAndTheTeamFollowsADraftMovedToAnotherDepartment() throws Exception {
        // Assigning does not touch the requisition itself, not even updated_at: the assignment runs 5 minutes after
        // the draft was saved, and the row is compared right after it.
        Map<String, Object> saved = jdbc.queryForMap("SELECT * FROM recruitment_requisitions WHERE id = ?", requisitionId);
        clock.set(START.plus(Duration.ofMinutes(5)));
        expect(assign(requisitionId, Map.of("recruiterId", r.get(0)), hrToken), 200);
        assertThat(jdbc.queryForMap("SELECT * FROM recruitment_requisitions WHERE id = ?", requisitionId))
                .isEqualTo(saved);

        UUID copy = UUID.fromString(expect(request("POST", BASE + requisitionId + "/copy", null, hrToken), 201)
                .path("id").asText());
        assertThat(expect(get(copy, "assignment", hrToken), 200).path("primaryRecruiter").isNull()).isTrue();

        expect(request("PUT", BASE + requisitionId, json.writeValueAsString(Map.of("positionId", positionId,
                "departmentId", salesId, "headcount", 2, "reason", "NEW_HEADCOUNT")), hrToken), 200);
        JsonNode forSales = expect(get(requisitionId, "assignment", token("sales@example.test")), 200);
        assertThat(forSales.path("primaryRecruiter").path("recruiterId").asText()).isEqualTo(r.get(0).toString());
    }

    private int rows() {
        return jdbc.queryForObject("SELECT count(*) FROM requisition_recruiters", Integer.class);
    }

    // "id:ROLE" for every recruiter assigned, primary first.
    private List<String> team() {
        return jdbc.queryForList("SELECT recruiter_id::text || ':' || assignment_role FROM requisition_recruiters "
                + "ORDER BY assignment_role, assigned_at, recruiter_id", String.class);
    }

    private static List<UUID> ids(JsonNode recruiters) {
        List<UUID> result = new ArrayList<>();
        recruiters.forEach(node -> result.add(UUID.fromString(node.path("recruiterId").asText())));
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

    private HttpResponse<String> assign(UUID requisition, Map<String, Object> body, String token) throws Exception {
        return request("POST", BASE + requisition + "/assign", json.writeValueAsString(new LinkedHashMap<>(body)), token);
    }

    private HttpResponse<String> unassign(UUID requisition, Map<String, Object> body, String token) throws Exception {
        return request("POST", BASE + requisition + "/unassign", json.writeValueAsString(new LinkedHashMap<>(body)), token);
    }

    private HttpResponse<String> get(UUID requisition, String suffix, String token) throws Exception {
        return request("GET", BASE + requisition + "/" + suffix, null, token);
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

    private String conflict(HttpResponse<String> response) {
        return expect(response, 409).path("code").asText();
    }

    private void forbidden(HttpResponse<String> response) {
        assertThat(expect(response, 403).path("code").asText()).isEqualTo("FORBIDDEN");
    }
}
