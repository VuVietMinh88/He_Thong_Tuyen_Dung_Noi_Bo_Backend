package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.CvValidator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000", "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationBoardPositionsIntegrationTest {
    private static final String PATH = "/api/v1/application-board/positions";
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private Environment environment;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationStorageService storage;
    @Autowired private CvValidator validator;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private TokenService tokens;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private ApplicationTestSupport applications;
    private int port;
    private UUID hr, recruiter, supporting, outsider, manager, noRole, firstRequisition, secondRequisition;
    private List<Map<String, Object>> permissions;

    @BeforeEach
    void fixture() {
        clock.set(ApplicationTestSupport.NOW);
        port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();
        permissions = jdbc.queryForList("SELECT role_code, permission_code FROM role_permissions");
        firstRequisition = applications.requisitionOf(applications.publishedPosting("Java"));
        secondRequisition = applications.requisitionOf(applications.publishedPosting("Finance"));
        jdbc.update("UPDATE positions SET name = 'Java' WHERE id = (SELECT position_id FROM recruitment_requisitions WHERE id = ?)", firstRequisition);
        jdbc.update("UPDATE positions SET name = 'Finance' WHERE id = (SELECT position_id FROM recruitment_requisitions WHERE id = ?)", secondRequisition);
        hr = account(Role.HR_MANAGER);
        recruiter = account(Role.RECRUITER);
        supporting = account(Role.RECRUITER);
        outsider = account(Role.RECRUITER);
        manager = account(Role.HIRING_MANAGER);
        noRole = account(null);
        applications.assign(firstRequisition, recruiter, "PRIMARY", hr);
        applications.assign(firstRequisition, supporting, "SUPPORTING", hr);
        jdbc.update("UPDATE departments SET manager_user_id = ? WHERE id = (SELECT department_id FROM recruitment_requisitions WHERE id = ?)",
                manager, firstRequisition);
    }

    @AfterEach
    void restorePermissions() {
        if (permissions == null) return;
        jdbc.update("DELETE FROM role_permissions");
        jdbc.batchUpdate("INSERT INTO role_permissions(role_code, permission_code) VALUES (?, ?)", permissions.stream()
                .map(row -> new Object[]{row.get("role_code"), row.get("permission_code")}).toList());
    }

    @Test
    void discoveryIncludesEmptyOpeningsButNeverAnOutsideOpening() throws Exception {
        assertThat(positions(hr, "").path("total").asLong()).isEqualTo(2);
        for (UUID actor : List.of(recruiter, supporting, manager)) {
            JsonNode page = positions(actor, "");
            assertThat(page.path("total").asLong()).isEqualTo(1);
            assertThat(page.path("items").get(0).path("requisitionId").asText()).isEqualTo(firstRequisition.toString());
        }
        JsonNode empty = positions(outsider, "");
        assertThat(empty.path("total").asLong()).isZero();
        assertThat(empty.path("items").size()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_access_events", Long.class)).isZero();
    }

    @Test
    void flagsTellFrontendWhichAccessibleOpeningsBelongToDefaultSelection() throws Exception {
        JsonNode assigned = positions(recruiter, "").path("items").get(0);
        assertThat(assigned.path("assignedToMe").asBoolean()).isTrue();
        assertThat(assigned.path("defaultSelected").asBoolean()).isTrue();
        JsonNode managed = positions(manager, "").path("items").get(0);
        assertThat(managed.path("assignedToMe").asBoolean()).isFalse();
        assertThat(managed.path("defaultSelected").asBoolean()).isFalse();
        positions(hr, "").path("items").forEach(item -> {
            assertThat(item.path("defaultSelected").asBoolean()).isTrue();
            assertThat(item.path("assignedToMe").asBoolean()).isFalse();
            assertThat(item.propertyNames()).containsExactlyInAnyOrder("requisitionId", "positionId", "positionName",
                    "departmentId", "departmentName", "assignedToMe", "defaultSelected");
        });
    }

    @Test
    void paginationIsBoundedWithFullCountAndStableSort() throws Exception {
        JsonNode first = positions(hr, "?size=1");
        JsonNode second = positions(hr, "?size=1&page=1");
        assertThat(first.path("total").asLong()).isEqualTo(2);
        assertThat(first.path("items").size()).isEqualTo(1);
        assertThat(first.path("hasNext").asBoolean()).isTrue();
        assertThat(first.path("items").get(0).path("positionName").asText()).isEqualTo("Finance");
        assertThat(second.path("items").get(0).path("positionName").asText()).isEqualTo("Java");
        assertThat(second.path("hasNext").asBoolean()).isFalse();
        assertThat(positions(hr, "?page=2&size=1").path("items").size()).isZero();
        for (String query : List.of("?page=-1", "?size=0", "?size=101", "?page=1000001", "?page=x")) {
            assertThat(get(query, token(hr)).statusCode()).as(query).isEqualTo(400);
        }
    }

    @Test
    void samePositionStillProducesSeparateScopedOpeningOptions() throws Exception {
        jdbc.update("UPDATE recruitment_requisitions SET position_id = (SELECT position_id FROM recruitment_requisitions WHERE id = ?) WHERE id = ?",
                firstRequisition, secondRequisition);
        JsonNode all = positions(hr, "");
        assertThat(all.path("items").size()).isEqualTo(2);
        assertThat(all.path("items").get(0).path("positionId").asText()).isEqualTo(all.path("items").get(1).path("positionId").asText());
        assertThat(positions(recruiter, "").path("items").size()).isEqualTo(1);
    }

    @Test
    void departmentAncestorsUseTheSameScopeAsCandidateProfiles() throws Exception {
        UUID firstDepartment = department(firstRequisition), secondDepartment = department(secondRequisition);
        jdbc.update("UPDATE departments SET manager_user_id = ?, parent_id = ? WHERE id = ?", hr, secondDepartment, firstDepartment);
        jdbc.update("UPDATE departments SET manager_user_id = ? WHERE id = ?", manager, secondDepartment);
        assertThat(positions(manager, "").path("total").asLong()).isEqualTo(2);
    }

    @Test
    void removingAssignmentsImmediatelyUpdatesDiscoveryAndDefaultFlags() throws Exception {
        assertThat(positions(recruiter, "").path("total").asLong()).isEqualTo(1);
        jdbc.update("DELETE FROM requisition_recruiters WHERE recruiter_id = ?", recruiter);
        assertThat(positions(recruiter, "").path("total").asLong()).isZero();
        assertThat(positions(supporting, "").path("items").get(0).path("defaultSelected").asBoolean()).isTrue();
    }

    @Test
    void endpointRequiresReadPermissionAndValidSession() throws Exception {
        assertThat(get("", null).statusCode()).isEqualTo(401);
        assertThat(get("", token(noRole)).statusCode()).isEqualTo(403);
        String token = token(recruiter);
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'RECRUITER' AND permission_code LIKE 'CANDIDATES_READ_%'");
        assertThat(get("", token).statusCode()).isEqualTo(403);
        jdbc.update("DELETE FROM auth_sessions WHERE user_id = ?", hr);
        String revoked = token(hr);
        jdbc.update("DELETE FROM auth_sessions WHERE user_id = ?", hr);
        assertThat(get("", revoked).statusCode()).isEqualTo(401);
    }

    private UUID department(UUID requisition) {
        return jdbc.queryForObject("SELECT department_id FROM recruitment_requisitions WHERE id = ?", UUID.class, requisition);
    }

    private UUID account(Role role) {
        UUID id = applications.staff("Người xử lý", UUID.randomUUID() + "@example.test");
        if (role != null) jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, ?)", id, role.name());
        return id;
    }

    private String token(UUID actor) {
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO auth_sessions(id,user_id,refresh_token_hash,created_at,expires_at) VALUES(?,?,?,?,?)",
                session, actor, (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""),
                Timestamp.from(ApplicationTestSupport.NOW), Timestamp.from(ApplicationTestSupport.NOW.plus(Duration.ofDays(7))));
        return tokens.createAccessToken(actor, session);
    }

    private HttpResponse<String> get(String query, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH + query)).timeout(Duration.ofSeconds(20)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().allValues("Cache-Control")).anyMatch(value -> value.contains("no-store"));
        return response;
    }

    private JsonNode positions(UUID actor, String query) throws Exception {
        var response = get(query, token(actor));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
}
