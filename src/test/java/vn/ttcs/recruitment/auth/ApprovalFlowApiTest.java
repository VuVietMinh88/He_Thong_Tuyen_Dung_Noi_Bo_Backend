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
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true", "app.bootstrap.email=admin@example.test",
        "app.bootstrap.password=TestingOnly123!", "app.cors.allowed-origins=http://localhost:5173",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApprovalFlowApiTest {
    private static final String BASE = "/api/v1/approval-flows";
    private static final Instant START = Instant.parse("2026-10-08T00:00:00Z");
    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AccountRepository accounts;
    @Autowired BootstrapAdmin bootstrap;
    @Autowired AuthIntegrationTest.MutableClock clock;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private String token;
    private UUID department, actor;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        r.add("app.auth.jwt-secret", () -> Base64.getEncoder().encodeToString(key));
    }
    @BeforeEach
    void setup() throws Exception {
        clock.set(START);
        jdbc.execute("TRUNCATE approval_flow_steps, approval_flow_versions, approval_flows");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET department_id=NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM user_accounts");
        jdbc.update("INSERT INTO role_permissions VALUES ('ADMIN','REQUISITIONS_WRITE_ALL') ON CONFLICT DO NOTHING");
        bootstrap.run(new DefaultApplicationArguments());
        var login = expect(send("POST", "/api/v1/auth/login", Map.of("email", "admin@example.test", "password", "TestingOnly123!"), null), 200);
        token = login.path("accessToken").asText(); actor = UUID.fromString(login.path("user").path("id").asText());
        department = UUID.randomUUID();
        jdbc.update("INSERT INTO departments(id,code,name,manager_user_id) VALUES (?,'HR','HR',?)", department, actor);
    }

    @Test
    void completeApiContractReturnsVersionsAndReadOnlyPreview() throws Exception {
        var response = send("POST", BASE, payload(), token);
        var first = expect(response, 201);
        String id = first.path("id").asText();
        assertThat(response.headers().firstValue("Location")).contains(BASE + "/" + id);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(first.path("version").asInt()).isEqualTo(1);
        assertThat(first.path("createdBy").asText()).isEqualTo(actor.toString());
        assertThat(first.toString()).doesNotContain("password", "token", "sealed");
        assertThat(expect(send("GET", BASE + "/" + id, null, token), 200)).isEqualTo(first);
        var page = expect(send("GET", BASE + "?departmentId=" + department, null, token), 200);
        assertThat(page.path("size").asInt()).isEqualTo(20);
        assertThat(page.path("totalElements").asInt()).isEqualTo(1);
        assertThat(expect(send("GET", BASE + "?page=1&size=1", null, token), 200).path("items").isEmpty()).isTrue();
        var preview = expect(send("POST", BASE + "/preview", Map.of("departmentId", department, "proposedSalary", 20000000), token), 200);
        assertThat(preview.path("steps").size()).isEqualTo(1);
        assertThat(expect(send("POST", BASE + "/preview", Map.of("departmentId", department, "proposedSalary", 20000001), token), 200)
                .path("steps").size()).isEqualTo(2);
        var changed = payload(); changed.put("name", "Version 2"); changed.put("expectedVersion", 1);
        assertThat(expect(send("PUT", BASE + "/" + id, changed, token), 200).path("version").asInt()).isEqualTo(2);
        assertThat(expect(send("GET", BASE + "/" + id + "?version=1", null, token), 200)).isEqualTo(first);
        error(send("PUT", BASE + "/" + id, changed, token), 409, "APPROVAL_VERSION_CONFLICT");
        error(send("POST", BASE, payload(), token), 409, "APPROVAL_FLOW_EXISTS");
        error(send("GET", BASE + "/" + id + "?version=999", null, token), 404, "APPROVAL_FLOW_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_flow_versions", Integer.class)).isEqualTo(2);
    }

    @Test
    void validationRejectsUnknownFieldsMalformedTypesAndMissingBaseChain() throws Exception {
        var body = payload(); body.put("createdBy", UUID.randomUUID());
        error(send("POST", BASE, body, token), 400, "INVALID_JSON");
        body = payload(); body.put("steps", List.of(Map.of("position", 1, "approverRole", "ADMIN", "extra", true)));
        error(send("POST", BASE, body, token), 400, "INVALID_JSON");
        body = payload(); body.put("steps", List.of(Map.of("position", 1, "approverRole", "ADMIN", "salaryThreshold", 10)));
        var invalid = expect(send("POST", BASE, body, token), 400);
        assertThat(invalid.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(invalid.path("fieldErrors").has("steps[0].salaryThreshold")).isTrue();
        body = payload(); body.put("steps", Collections.singletonList(null));
        error(send("POST", BASE, body, token), 400, "VALIDATION_ERROR");
        body = payload(); body.put("departmentId", "invalid");
        error(send("POST", BASE, body, token), 400, "INVALID_JSON");
        for (String path : List.of(BASE + "?size=0", BASE + "?page=-1", BASE + "/bad-id", BASE + "/" + UUID.randomUUID() + "?version=0"))
            error(send("GET", path, null, token), 400, "VALIDATION_ERROR");
        error(send("POST", BASE + "/preview", Map.of("departmentId", department, "proposedSalary", -1), token), 400, "VALIDATION_ERROR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_flows", Integer.class)).isZero();
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    void onlyAdminAndHrManagerCanManageConfiguration(Role role) throws Exception {
        var account = accounts.saveAndFlush(new Account("role@example.test", "Role", accounts.findById(actor).orElseThrow().getPasswordHash(), Set.of(role), START));
        var roleToken = expect(send("POST", "/api/v1/auth/login", Map.of("email", account.getEmail(), "password", "TestingOnly123!"), null), 200)
                .path("accessToken").asText();
        boolean allowed = role == Role.ADMIN || role == Role.HR_MANAGER;
        expect(send("POST", BASE, payload(), roleToken), allowed ? 201 : 403);
        expect(send("GET", BASE, null, roleToken), allowed ? 200 : 403);
        expect(send("POST", BASE + "/preview", Map.of("departmentId", department, "proposedSalary", 10), roleToken), allowed ? 200 : 403);
        if (!allowed) {
            // A broad grant cannot replace the explicit management role restriction.
            jdbc.update("INSERT INTO role_permissions VALUES (?,'REQUISITIONS_WRITE_ALL') ON CONFLICT DO NOTHING", role.name());
            try { expect(send("POST", BASE, payload(), roleToken), 403); }
            finally { jdbc.update("DELETE FROM role_permissions WHERE role_code=? AND permission_code='REQUISITIONS_WRITE_ALL'", role.name()); }
        }
    }

    @Test
    void missingExpiredRevokedSessionsAndLostWritePermissionAreRejected() throws Exception {
        error(send("POST", BASE, payload(), null), 401, "UNAUTHORIZED");
        error(send("GET", BASE, null, null), 401, "UNAUTHORIZED");
        jdbc.update("DELETE FROM role_permissions WHERE role_code='ADMIN' AND permission_code='REQUISITIONS_WRITE_ALL'");
        error(send("POST", BASE, payload(), token), 403, "FORBIDDEN");
        jdbc.update("INSERT INTO role_permissions VALUES ('ADMIN','REQUISITIONS_WRITE_ALL')");
        clock.set(START.plusSeconds(900));
        error(send("POST", BASE, payload(), token), 401, "UNAUTHORIZED");
        clock.set(START);
        expect(send("POST", "/api/v1/auth/logout", null, token), 204);
        error(send("POST", BASE, payload(), token), 401, "UNAUTHORIZED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_flows", Integer.class)).isZero();
    }

    @Test
    void missingConfigurationAndInactiveDepartmentHaveDefinedErrors() throws Exception {
        error(send("GET", BASE + "/" + UUID.randomUUID(), null, token), 404, "APPROVAL_FLOW_NOT_FOUND");
        error(send("POST", BASE + "/preview", Map.of("departmentId", department, "proposedSalary", 0), token), 404, "APPROVAL_FLOW_NOT_FOUND");
        jdbc.update("UPDATE departments SET active=false WHERE id=?", department);
        error(send("POST", BASE, payload(), token), 400, "VALIDATION_ERROR");
    }

    @Test
    void trustedFrontendCanPreflightPutButUntrustedOriginCannot() throws Exception {
        for (String origin : List.of("http://localhost:5173", "https://untrusted.example")) {
            var request = HttpRequest.newBuilder(uri(BASE + "/" + UUID.randomUUID()))
                    .header("Origin", origin).header("Access-Control-Request-Method", "PUT")
                    .header("Access-Control-Request-Headers", "Authorization,Content-Type")
                    .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(origin.startsWith("http://localhost") ? 200 : 403);
        }
    }

    private Map<String,Object> payload() {
        var body = new LinkedHashMap<String,Object>();
        body.put("departmentId", department); body.put("name", "Flow");
        body.put("steps", List.of(Map.of("position", 1, "approverRole", "HIRING_MANAGER"),
                Map.of("position", 2, "salaryThreshold", 20000000, "approverRole", "HR_MANAGER")));
        return body;
    }
    private URI uri(String path) { return URI.create("http://localhost:" + env.getProperty("local.server.port") + path); }
    private HttpResponse<String> send(String method, String path, Object body, String bearer) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(15));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode expect(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    }
    private void error(HttpResponse<String> response, int status, String code) {
        assertThat(expect(response, status).path("code").asText()).isEqualTo(code);
    }
}
