package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.RecruitmentApplication;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.common.ApiError;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

/**
 * Independent authorization specification for every HTTP API. Each request goes straight to the server,
 * exactly as a user who bypasses the UI would send it. The frontend builds its menu from
 * GET /api/v1/auth/permissions, but hiding a menu item never replaces these server-side checks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true",
        "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiAuthorizationMatrixIntegrationTest {
    private static final String PASSWORD = "TestingOnly123!";
    private static final Instant START = Instant.parse("2026-10-06T00:00:00Z");
    private static final UUID UNKNOWN_ID = UUID.randomUUID();
    private static final String INVALID_BODY = "{}";
    // A real catalog type, so permitted calls reach the item lookup or body validation.
    private static final String CATALOG_TYPE = "CANDIDATE_SOURCE";
    private static final String CATALOG_ITEMS = "/api/v1/recruitment-catalogs/" + CATALOG_TYPE + "/items";
    private static final ApiError FORBIDDEN_ERROR = ApiError.of("FORBIDDEN", "Bạn không có quyền thực hiện thao tác này.");

    private static final String SELF_PROFILE_READ = "SELF_PROFILE_READ";
    private static final String SELF_PROFILE_WRITE = "SELF_PROFILE_WRITE";
    private static final String SELF_SECURITY_WRITE = "SELF_SECURITY_WRITE";
    private static final String USER_ADMIN_READ_ALL = "USER_ADMIN_READ_ALL";
    private static final String USER_ADMIN_WRITE_ALL = "USER_ADMIN_WRITE_ALL";
    private static final String ORGANIZATION_READ_ALL = "ORGANIZATION_READ_ALL";
    private static final String ORGANIZATION_WRITE_ALL = "ORGANIZATION_WRITE_ALL";
    private static final String SALARY_RANGES_READ_ALL = "SALARY_RANGES_READ_ALL";
    private static final String SALARY_RANGES_WRITE_ALL = "SALARY_RANGES_WRITE_ALL";
    private static final String REQUISITIONS_READ_ALL = "REQUISITIONS_READ_ALL";
    private static final String REQUISITIONS_READ_SCOPED = "REQUISITIONS_READ_SCOPED";
    private static final String REQUISITIONS_WRITE_ALL = "REQUISITIONS_WRITE_ALL";
    private static final String REQUISITIONS_WRITE_SCOPED = "REQUISITIONS_WRITE_SCOPED";

    private static final Rule PUBLIC = new Rule(List.of(), false, false);

    // One row per handler mapping. Path variables get an unknown id, and write samples carry an invalid body,
    // so a call that passes authorization stops at 404 or validation instead of changing data.
    private static final List<Endpoint> ENDPOINTS = List.of(
            endpoint("GET", "/api/health", PUBLIC, null, 200),
            endpoint("GET", "/api/v1/health", PUBLIC, null, 200),
            endpoint("POST", "/api/v1/auth/login", PUBLIC, INVALID_BODY, 400),
            endpoint("POST", "/api/v1/auth/refresh", PUBLIC, INVALID_BODY, 400),
            endpoint("POST", "/api/v1/auth/forgot-password", PUBLIC, INVALID_BODY, 400),
            endpoint("POST", "/api/v1/auth/reset-password", PUBLIC, INVALID_BODY, 400),
            endpoint("POST", "/api/v1/auth/activate-account", PUBLIC, INVALID_BODY, 400),
            endpoint("GET", "/api/v1/auth/me", permission(SELF_PROFILE_READ), null, 200),
            endpoint("GET", "/api/v1/auth/permissions", permission(SELF_PROFILE_READ), null, 200),
            endpoint("GET", "/api/v1/profile", permission(SELF_PROFILE_READ), null, 200),
            endpoint("PUT", "/api/v1/profile", permission(SELF_PROFILE_WRITE), INVALID_BODY, 400),
            // A permitted logout revokes the session it uses, so every call logs in again first.
            new Endpoint("POST", "/api/v1/auth/logout", permission(SELF_SECURITY_WRITE), null, 204, true),
            endpoint("POST", "/api/v1/auth/change-password", permission(SELF_SECURITY_WRITE), INVALID_BODY, 400),
            endpoint("GET", "/api/v1/accounts", permission(USER_ADMIN_READ_ALL), null, 200),
            endpoint("GET", "/api/v1/accounts/{id}", permission(USER_ADMIN_READ_ALL), null, 404),
            endpoint("POST", "/api/v1/accounts", adminWith(USER_ADMIN_WRITE_ALL), INVALID_BODY, 400),
            endpoint("PUT", "/api/v1/accounts/{id}", adminWith(USER_ADMIN_WRITE_ALL), INVALID_BODY, 400),
            endpoint("PUT", "/api/v1/accounts/{id}/roles/{role}", adminWith(USER_ADMIN_WRITE_ALL), null, 404),
            endpoint("DELETE", "/api/v1/accounts/{id}/roles/{role}", adminWith(USER_ADMIN_WRITE_ALL), null, 404),
            endpoint("PUT", "/api/v1/accounts/{id}/lock", adminWith(USER_ADMIN_WRITE_ALL), INVALID_BODY, 400),
            endpoint("DELETE", "/api/v1/accounts/{id}/lock", adminWith(USER_ADMIN_WRITE_ALL), null, 404),
            endpoint("GET", "/api/v1/departments", permission(ORGANIZATION_READ_ALL), null, 200),
            endpoint("GET", "/api/v1/departments/tree", permission(ORGANIZATION_READ_ALL), null, 200),
            endpoint("GET", "/api/v1/departments/{id}", permission(ORGANIZATION_READ_ALL), null, 404),
            endpoint("POST", "/api/v1/departments", permission(ORGANIZATION_WRITE_ALL), INVALID_BODY, 400),
            endpoint("PUT", "/api/v1/departments/{id}", permission(ORGANIZATION_WRITE_ALL), INVALID_BODY, 400),
            endpoint("GET", "/api/v1/positions", permission(ORGANIZATION_READ_ALL), null, 200),
            endpoint("GET", "/api/v1/positions/{id}", permission(ORGANIZATION_READ_ALL), null, 404),
            endpoint("POST", "/api/v1/positions", allOf(ORGANIZATION_WRITE_ALL, SALARY_RANGES_WRITE_ALL), INVALID_BODY, 400),
            endpoint("PUT", "/api/v1/positions/{id}", allOf(ORGANIZATION_WRITE_ALL, SALARY_RANGES_WRITE_ALL), INVALID_BODY, 400),
            endpoint("GET", "/api/v1/competency-frameworks", permission(ORGANIZATION_READ_ALL), null, 200),
            endpoint("GET", "/api/v1/competency-frameworks/{id}", permission(ORGANIZATION_READ_ALL), null, 404),
            endpoint("POST", "/api/v1/competency-frameworks", permission(ORGANIZATION_WRITE_ALL), INVALID_BODY, 400),
            endpoint("PUT", "/api/v1/competency-frameworks/{id}", permission(ORGANIZATION_WRITE_ALL), INVALID_BODY, 400),
            endpoint("PUT", "/api/v1/positions/{id}/competency-framework", permission(ORGANIZATION_WRITE_ALL), INVALID_BODY, 400),
            endpoint("DELETE", "/api/v1/positions/{id}/competency-framework", permission(ORGANIZATION_WRITE_ALL), null, 404),
            endpoint("GET", "/api/v1/positions/{id}/evaluation-criteria", permission(ORGANIZATION_READ_ALL), null, 404),
            endpoint("GET", "/api/v1/interview-questions/{id}", permission(ORGANIZATION_READ_ALL), null, 404),
            endpoint("POST", "/api/v1/interview-questions", permission(ORGANIZATION_WRITE_ALL), INVALID_BODY, 400),
            endpoint("PUT", "/api/v1/interview-questions/{id}", permission(ORGANIZATION_WRITE_ALL), INVALID_BODY, 400),
            endpoint("GET", "/api/v1/interview-questions", permission(ORGANIZATION_READ_ALL), null, 200),
            // Requisitions: ALL and SCOPED writers both pass the matcher; RequisitionService applies the scope.
            endpoint("POST", "/api/v1/requisitions", anyOf(REQUISITIONS_WRITE_ALL, REQUISITIONS_WRITE_SCOPED), INVALID_BODY, 400),
            endpoint("GET", "/api/v1/requisitions", anyOf(REQUISITIONS_READ_ALL, REQUISITIONS_READ_SCOPED), null, 200),
            endpoint("GET", "/api/v1/requisitions/{id}", anyOf(REQUISITIONS_READ_ALL, REQUISITIONS_READ_SCOPED), null, 404),
            endpoint("PUT", "/api/v1/requisitions/{id}", anyOf(REQUISITIONS_WRITE_ALL, REQUISITIONS_WRITE_SCOPED), INVALID_BODY, 400),
            // Task 197: deleting a department. The unknown id makes a permitted call stop at 404.
            endpoint("DELETE", "/api/v1/departments/{id}", permission(ORGANIZATION_WRITE_ALL), null, 404),
            endpoint("GET", "/api/v1/recruitment-catalogs/{type}/items", permission(ORGANIZATION_READ_ALL), null, 200),
            endpoint("GET", "/api/v1/recruitment-catalogs/{type}/items/{id}", permission(ORGANIZATION_READ_ALL), null, 404),
            endpoint("POST", "/api/v1/recruitment-catalogs/{type}/items", permission(ORGANIZATION_WRITE_ALL), INVALID_BODY, 400),
            endpoint("PUT", "/api/v1/recruitment-catalogs/{type}/items/{id}", permission(ORGANIZATION_WRITE_ALL), INVALID_BODY, 400));

    private static final List<String> STATE_TABLES = List.of("user_accounts", "user_roles", "departments",
            "auth_sessions", "account_activation_tokens", "password_reset_tokens", "role_permissions", "positions",
            "competency_frameworks", "competency_criteria", "interview_questions", "recruitment_requisitions", "recruitment_catalog_items");

    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping handlerMapping;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<Identity, Actor> actors = new EnumMap<>(Identity.class);
    private List<Map<String, Object>> permissionSeed;
    private String fixturePasswordHash;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void createOneLoggedInAccountPerIdentity() throws Exception {
        permissionSeed = jdbc.queryForList("SELECT role_code, permission_code FROM role_permissions");
        clock.set(START);
        // Requisitions reference departments, positions and accounts (ON DELETE RESTRICT), so they go first.
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET department_id = NULL, admin_locked_at = NULL, admin_lock_reason = NULL, admin_locked_by = NULL");
        jdbc.update("UPDATE departments SET parent_id = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM competency_frameworks");
        jdbc.update("DELETE FROM recruitment_catalog_items");
        jdbc.update("DELETE FROM user_accounts");
        bootstrap.run(new DefaultApplicationArguments());
        fixturePasswordHash = jdbc.queryForObject("SELECT password_hash FROM user_accounts WHERE email = ?",
                String.class, Identity.ADMIN.email());
        for (Identity identity : Identity.values()) {
            if (identity != Identity.ADMIN) {
                accounts.saveAndFlush(new Account(identity.email(), identity.name(), fixturePasswordHash,
                        identity.role == null ? Set.of() : Set.of(identity.role), START));
            }
            actors.put(identity, login(identity));
        }
    }

    @AfterEach
    void restorePermissionSeed() {
        if (permissionSeed == null) { return; }
        jdbc.update("DELETE FROM role_permissions");
        jdbc.batchUpdate("INSERT INTO role_permissions (role_code, permission_code) VALUES (?, ?)", permissionSeed.stream()
                .map(row -> new Object[] {row.get("role_code"), row.get("permission_code")}).toList());
    }

    @Test
    void everyApplicationApiIsDeclaredInTheAuthorizationTable() {
        String applicationPackage = RecruitmentApplication.class.getPackageName();
        Set<String> mapped = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, handler) -> {
            String handlerPackage = handler.getBeanType().getPackageName();
            if (!handlerPackage.equals(applicationPackage) && !handlerPackage.startsWith(applicationPackage + ".")) {
                return;
            }
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : info.getPatternValues()) {
                if (methods.isEmpty()) { mapped.add("ANY " + pattern); }
                methods.forEach(method -> mapped.add(method.name() + " " + pattern));
            }
        });
        Set<String> declared = ENDPOINTS.stream().map(Endpoint::key).collect(Collectors.toCollection(TreeSet::new));
        assertThat(declared).as("ENDPOINTS contains duplicate rows").hasSameSizeAs(ENDPOINTS);

        Set<String> undeclared = new TreeSet<>(mapped);
        undeclared.removeAll(declared);
        Set<String> stale = new TreeSet<>(declared);
        stale.removeAll(mapped);
        assertThat(undeclared).as("APIs without an authorization row; declare their rule in ENDPOINTS and SecurityConfiguration")
                .isEmpty();
        assertThat(stale).as("ENDPOINTS rows that match no API; update or remove them").isEmpty();
    }

    @TestFactory
    Stream<DynamicTest> everyIdentityIsAllowedOrForbiddenExactlyAsTheRoleSeedSpecifies() {
        return ENDPOINTS.stream().flatMap(endpoint -> Arrays.stream(Identity.values()).map(identity ->
                dynamicTest(identity + ": " + endpoint, () -> {
                    var response = call(endpoint, identity);
                    if (endpoint.rule().allows(identity.roles(), identity.expectedGrants())) {
                        assertAllowed(endpoint, response);
                    } else {
                        assertForbidden(endpoint.toString(), response);
                    }
                })));
    }

    @TestFactory
    Stream<DynamicTest> anonymousCallersAreRejectedFromProtectedApisButReachPublicOnes() {
        Stream<DynamicTest> anonymous = ENDPOINTS.stream().map(endpoint -> dynamicTest("anonymous: " + endpoint, () -> {
            var response = request(endpoint.method(), endpoint.samplePath(), endpoint.body(), null);
            if (endpoint.rule().isPublic()) {
                assertAllowed(endpoint, response);
            } else {
                assertUnauthorized(endpoint, response);
            }
        }));
        // These endpoints authenticate their body, so an expired or forged bearer must not block them.
        Stream<DynamicTest> staleBearer = ENDPOINTS.stream()
                .filter(endpoint -> endpoint.rule().isPublic() && endpoint.method().equals("POST"))
                .map(endpoint -> dynamicTest("stale bearer: " + endpoint, () ->
                        assertAllowed(endpoint, request(endpoint.method(), endpoint.samplePath(), endpoint.body(), "not-a-jwt"))));
        return Stream.concat(anonymous, staleBearer);
    }

    @TestFactory
    Stream<DynamicTest> serverAllowsACallOnlyWhenThePermissionListBehindTheMenuShowsIt() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (Identity identity : Identity.values()) {
            Actor actor = actors.get(identity);
            Set<String> menuPermissions = menuPermissions(actor);
            for (Endpoint endpoint : ENDPOINTS) {
                if (endpoint.rule().isPublic()) { continue; }
                boolean shown = endpoint.rule().allows(actor.roles(), menuPermissions);
                tests.add(dynamicTest(identity + (shown ? " sees " : " does not see ") + endpoint, () -> {
                    var response = call(endpoint, identity);
                    if (shown) {
                        assertAllowed(endpoint, response);
                    } else {
                        assertForbidden(endpoint.toString(), response);
                    }
                }));
            }
        }
        return tests.stream();
    }

    @TestFactory
    Stream<DynamicTest> forbiddenCallsWithValidBodiesLeaveTheDatabaseUnchanged() throws Exception {
        UUID admin = actors.get(Identity.ADMIN).id();
        UUID hrManager = actors.get(Identity.HR_MANAGER).id();
        UUID recruiter = actors.get(Identity.RECRUITER).id();
        UUID hiringManager = actors.get(Identity.HIRING_MANAGER).id();
        UUID interviewer = actors.get(Identity.INTERVIEWER).id();
        UUID department = UUID.fromString(expect(request("POST", "/api/v1/departments", json.writeValueAsString(Map.of(
                "code", "TARGET", "name", "Target", "managerUserId", interviewer, "active", true)),
                actors.get(Identity.ADMIN).token()), 201).path("id").asText());
        UUID position = UUID.fromString(expect(request("POST", "/api/v1/positions", json.writeValueAsString(
                positionBody("TARGET", "Target")), actors.get(Identity.HR_MANAGER).token()), 201).path("id").asText());
        UUID framework = UUID.fromString(expect(request("POST", "/api/v1/competency-frameworks", json.writeValueAsString(
                competencyFrameworkBody("TARGET", "Target")), actors.get(Identity.HR_MANAGER).token()), 201).path("id").asText());
        Map<String, Object> requisitionBody = Map.of("positionId", position, "departmentId", department,
                "headcount", 1, "reason", "NEW_HEADCOUNT");
        UUID requisition = UUID.fromString(expect(request("POST", "/api/v1/requisitions",
                json.writeValueAsString(requisitionBody), actors.get(Identity.HR_MANAGER).token()), 201).path("id").asText());
        Map<String, Object> changedRequisition = Map.of("positionId", position, "departmentId", department,
                "headcount", 9, "reason", "REPLACEMENT");
        // Task 197: nothing uses this department, so a permitted DELETE would remove it.
        UUID unused = UUID.fromString(expect(request("POST", "/api/v1/departments", json.writeValueAsString(Map.of(
                "code", "UNUSED", "name", "Unused", "managerUserId", interviewer, "active", true)),
                actors.get(Identity.ADMIN).token()), 201).path("id").asText());
        UUID catalogItem = UUID.fromString(expect(request("POST", CATALOG_ITEMS, json.writeValueAsString(
                catalogItemBody("TARGET", "Target")), actors.get(Identity.ADMIN).token()), 201).path("id").asText());
        UUID locked = accounts.saveAndFlush(new Account("locked@example.test", "Locked", fixturePasswordHash,
                Set.of(Role.RECRUITER), START)).getId();
        jdbc.update("UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Review', admin_locked_by = ? WHERE id = ?",
                Timestamp.from(START), admin, locked);

        List<Attack> attacks = List.of(
                new Attack(Identity.RECRUITER, "POST", "/api/v1/accounts",
                        Map.of("email", "intruder@example.test", "fullName", "Intruder", "roles", List.of("ADMIN"))),
                new Attack(Identity.INTERVIEWER, "POST", "/api/v1/departments",
                        Map.of("code", "SHADOW", "name", "Shadow", "managerUserId", interviewer, "active", true)),
                new Attack(Identity.HIRING_MANAGER, "PUT", "/api/v1/departments/" + department,
                        Map.of("code", "TARGET", "name", "Taken over", "managerUserId", hiringManager, "active", false)),
                new Attack(Identity.HR_MANAGER, "PUT", "/api/v1/accounts/" + hrManager + "/roles/ADMIN", null),
                new Attack(Identity.APPROVER, "DELETE", "/api/v1/accounts/" + admin + "/roles/ADMIN", null),
                new Attack(Identity.HR_MANAGER, "PUT", "/api/v1/accounts/" + recruiter + "/lock", Map.of("reason", "Không được phép")),
                new Attack(Identity.HR_MANAGER, "DELETE", "/api/v1/accounts/" + locked + "/lock", null),
                new Attack(Identity.HR_MANAGER, "PUT", "/api/v1/accounts/" + recruiter,
                        Map.of("fullName", "Renamed by HR", "departmentId", department)),
                new Attack(Identity.NO_ROLE, "GET", "/api/v1/accounts", null),
                new Attack(Identity.INTERVIEWER, "GET", "/api/v1/accounts/" + admin, null),
                new Attack(Identity.NO_ROLE, "PUT", "/api/v1/profile", Map.of("fullName", "Renamed")),
                new Attack(Identity.NO_ROLE, "POST", "/api/v1/auth/change-password",
                        Map.of("currentPassword", PASSWORD, "newPassword", "ChangedPassword123")),
                new Attack(Identity.INTERVIEWER, "POST", "/api/v1/positions", positionBody("SHADOW", "Shadow")),
                new Attack(Identity.HIRING_MANAGER, "PUT", "/api/v1/positions/" + position,
                        positionBody("TARGET", "Taken over")),
                // ADMIN has ORGANIZATION_WRITE_ALL but not SALARY_RANGES_WRITE_ALL, and every position write sets salaries.
                new Attack(Identity.ADMIN, "POST", "/api/v1/positions", positionBody("SHADOW", "Shadow")),
                new Attack(Identity.ADMIN, "PUT", "/api/v1/positions/" + position, positionBody("TARGET", "Taken over")),
                new Attack(Identity.INTERVIEWER, "POST", "/api/v1/competency-frameworks",
                        competencyFrameworkBody("SHADOW", "Shadow")),
                new Attack(Identity.HIRING_MANAGER, "PUT", "/api/v1/competency-frameworks/" + framework,
                        competencyFrameworkBody("TARGET", "Taken over")),
                // INTERVIEWER holds no REQUISITIONS permission, even for the department it manages.
                new Attack(Identity.INTERVIEWER, "POST", "/api/v1/requisitions", requisitionBody),
                new Attack(Identity.INTERVIEWER, "PUT", "/api/v1/requisitions/" + requisition, changedRequisition),
                // HIRING_MANAGER passes the matcher (REQUISITIONS_WRITE_SCOPED) but does not manage TARGET,
                // so RequisitionService refuses after locking the row.
                new Attack(Identity.HIRING_MANAGER, "PUT", "/api/v1/requisitions/" + requisition, changedRequisition),
                // Task 249: the same SCOPED writers cannot create a requisition for a department they do not manage.
                new Attack(Identity.HIRING_MANAGER, "POST", "/api/v1/requisitions", requisitionBody),
                new Attack(Identity.RECRUITER, "POST", "/api/v1/requisitions", requisitionBody),
                new Attack(Identity.APPROVER, "POST", "/api/v1/requisitions", requisitionBody),
                // Task 197: ORGANIZATION_READ_ALL alone cannot delete a department, not even the one the caller manages.
                new Attack(Identity.INTERVIEWER, "DELETE", "/api/v1/departments/" + unused, null),
                new Attack(Identity.RECRUITER, "DELETE", "/api/v1/departments/" + unused, null),
                new Attack(Identity.RECRUITER, "POST", CATALOG_ITEMS, catalogItemBody("SHADOW", "Shadow")),
                new Attack(Identity.APPROVER, "PUT", CATALOG_ITEMS + "/" + catalogItem,
                        catalogItemBody("TARGET", "Taken over")));

        return attacks.stream().map(attack -> dynamicTest(attack.toString(), () -> {
            Map<String, List<Map<String, Object>>> before = snapshot();
            var response = request(attack.method(), attack.path(),
                    attack.body() == null ? null : json.writeValueAsString(attack.body()), actors.get(attack.attacker()).token());
            assertForbidden(attack.toString(), response);
            Map<String, List<Map<String, Object>>> after = snapshot();
            STATE_TABLES.forEach(table -> assertThat(after.get(table)).as(table).isEqualTo(before.get(table)));
        }));
    }

    @Test
    void userAdminWriteGrantedToHrManagerStillCannotReplaceTheAdminRole() throws Exception {
        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('HR_MANAGER', ?)", USER_ADMIN_WRITE_ALL);
        Actor hrManager = actors.get(Identity.HR_MANAGER);
        // A menu built from this list would now show account administration; the server must still refuse it.
        assertThat(menuPermissions(hrManager)).contains(USER_ADMIN_WRITE_ALL);

        List<Endpoint> adminOnly = ENDPOINTS.stream().filter(endpoint -> endpoint.rule().requiresAdminRole()).toList();
        assertThat(adminOnly).isNotEmpty();
        for (Endpoint endpoint : adminOnly) {
            assertForbidden(endpoint.toString(), call(endpoint, Identity.HR_MANAGER));
        }
        Map<String, List<Map<String, Object>>> before = snapshot();
        assertForbidden("self-grant ADMIN", request("PUT", "/api/v1/accounts/" + hrManager.id() + "/roles/ADMIN", null, hrManager.token()));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void removedPermissionIsEnforcedOnTheNextRequestWithTheSameAccessToken() throws Exception {
        List<Endpoint> organizationReads = ENDPOINTS.stream()
                .filter(endpoint -> endpoint.rule().equals(permission(ORGANIZATION_READ_ALL))).toList();
        assertThat(organizationReads).isNotEmpty();
        for (Endpoint endpoint : organizationReads) {
            assertAllowed(endpoint, call(endpoint, Identity.RECRUITER));
        }

        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'RECRUITER' AND permission_code = ?", ORGANIZATION_READ_ALL);
        assertThat(menuPermissions(actors.get(Identity.RECRUITER))).doesNotContain(ORGANIZATION_READ_ALL);
        for (Endpoint endpoint : organizationReads) {
            assertForbidden(endpoint.toString(), call(endpoint, Identity.RECRUITER));
            assertAllowed(endpoint, call(endpoint, Identity.INTERVIEWER));
        }

        jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES ('RECRUITER', ?)", ORGANIZATION_READ_ALL);
        for (Endpoint endpoint : organizationReads) {
            assertAllowed(endpoint, call(endpoint, Identity.RECRUITER));
        }
    }

    @Test
    void positionSalaryBandIsReturnedOnlyToIdentitiesGrantedSalaryRangeRead() throws Exception {
        UUID position = UUID.fromString(expect(request("POST", "/api/v1/positions", json.writeValueAsString(
                positionBody("BAND", "Band")), actors.get(Identity.HR_MANAGER).token()), 201).path("id").asText());
        for (Identity identity : Identity.values()) {
            String token = actors.get(identity).token();
            if (!identity.expectedGrants().contains(ORGANIZATION_READ_ALL)) {
                assertForbidden(identity + " GET /api/v1/positions/{id}", request("GET", "/api/v1/positions/" + position, null, token));
                continue;
            }
            // Field-level rule: the server drops the band, so a caller who skips the UI still cannot read it.
            boolean seesBand = identity.expectedGrants().contains(SALARY_RANGES_READ_ALL);
            JsonNode detail = expect(request("GET", "/api/v1/positions/" + position, null, token), 200);
            JsonNode item = expect(request("GET", "/api/v1/positions", null, token), 200).path("items").path(0);
            for (JsonNode view : List.of(detail, item)) {
                assertThat(view.path("code").asText()).as(identity.name()).isEqualTo("BAND");
                assertThat(view.has("salaryMin")).as(identity + " salaryMin").isEqualTo(seesBand);
                assertThat(view.has("salaryMax")).as(identity + " salaryMax").isEqualTo(seesBand);
            }
        }
    }

    @Test
    void routesWithoutADeclaredRuleAreDeniedEvenForTheAdministrator() throws Exception {
        String token = actors.get(Identity.ADMIN).token();
        for (String route : List.of("PATCH /api/v1/departments/" + UNKNOWN_ID, "DELETE /api/v1/accounts/" + UNKNOWN_ID,
                "DELETE /api/v1/positions/" + UNKNOWN_ID, "PATCH /api/v1/profile", "GET /api/v1/roles",
                "GET /api/v1/internal/accounts")) {
            String[] parts = route.split(" ");
            assertForbidden(route, request(parts[0], parts[1], null, token));
        }
    }

    @Test
    void everyIdentityExpectsExactlyThePermissionsTheServerReportsForIt() throws Exception {
        for (Identity identity : Identity.values()) {
            assertThat(menuPermissions(actors.get(identity))).as(identity.name()).isEqualTo(identity.expectedGrants());
        }
    }

    @Test
    void ruleFactoriesCombinePermissionsAndTheAdminRoleAsDocumented() {
        Set<String> noRoles = Set.of();
        Set<String> readOnly = Set.of(ORGANIZATION_READ_ALL);
        Set<String> readWrite = Set.of(ORGANIZATION_READ_ALL, ORGANIZATION_WRITE_ALL);

        assertThat(PUBLIC.allows(noRoles, Set.of())).isTrue();
        assertThat(permission(ORGANIZATION_READ_ALL).allows(noRoles, readOnly)).isTrue();
        assertThat(permission(ORGANIZATION_WRITE_ALL).allows(Set.of("ADMIN"), readOnly)).isFalse();
        assertThat(allOf(ORGANIZATION_READ_ALL, ORGANIZATION_WRITE_ALL).allows(noRoles, readOnly)).isFalse();
        assertThat(allOf(ORGANIZATION_READ_ALL, ORGANIZATION_WRITE_ALL).allows(noRoles, readWrite)).isTrue();
        assertThat(anyOf(ORGANIZATION_WRITE_ALL, USER_ADMIN_READ_ALL).allows(noRoles, readOnly)).isFalse();
        assertThat(anyOf(ORGANIZATION_WRITE_ALL, ORGANIZATION_READ_ALL).allows(noRoles, readOnly)).isTrue();
        assertThat(adminWith(ORGANIZATION_READ_ALL).allows(Set.of("HR_MANAGER"), readOnly)).isFalse();
        assertThat(adminWith(ORGANIZATION_READ_ALL).allows(Set.of("ADMIN"), readOnly)).isTrue();
        assertThat(adminWith(ORGANIZATION_WRITE_ALL).allows(Set.of("ADMIN"), readOnly)).isFalse();
        assertThat(List.of(PUBLIC, permission(ORGANIZATION_READ_ALL), allOf(ORGANIZATION_READ_ALL, ORGANIZATION_WRITE_ALL),
                anyOf(ORGANIZATION_READ_ALL, ORGANIZATION_WRITE_ALL), adminWith(USER_ADMIN_WRITE_ALL)))
                .extracting(Rule::toString).containsExactly("PUBLIC", "ORGANIZATION_READ_ALL",
                        "ORGANIZATION_READ_ALL + ORGANIZATION_WRITE_ALL", "ORGANIZATION_READ_ALL | ORGANIZATION_WRITE_ALL",
                        "ROLE_ADMIN + USER_ADMIN_WRITE_ALL");
    }

    private static Map<String, Object> positionBody(String code, String name) {
        return Map.of("code", code, "name", name, "level", "Junior", "salaryMin", 15_000_000L,
                "salaryMax", 25_000_000L, "active", true);
    }

    private static Map<String, Object> competencyFrameworkBody(String code, String name) {
        return Map.of("code", code, "name", name, "criteria", List.of(Map.of("name", "Kỹ năng chuyên môn", "weight", 100)));
    }

    private static Map<String, Object> catalogItemBody(String code, String name) {
        return Map.of("code", code, "name", name, "active", true);
    }

    private HttpResponse<String> call(Endpoint endpoint, Identity identity) throws Exception {
        String token = endpoint.endsSession() ? login(identity).token() : actors.get(identity).token();
        return request(endpoint.method(), endpoint.samplePath(), endpoint.body(), token);
    }

    private Actor login(Identity identity) throws Exception {
        JsonNode result = expect(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(Map.of("email", identity.email(), "password", PASSWORD)), null), 200);
        Set<String> roles = new HashSet<>();
        result.path("user").path("roles").forEach(role -> roles.add(role.asText()));
        return new Actor(UUID.fromString(result.path("user").path("id").asText()),
                result.path("accessToken").asText(), Set.copyOf(roles));
    }

    private Set<String> menuPermissions(Actor actor) throws Exception {
        var response = request("GET", "/api/v1/auth/permissions", null, actor.token());
        if (response.statusCode() == 403) {
            // Without SELF_PROFILE_READ the account cannot even load its menu, so it shows nothing.
            assertForbidden("GET /api/v1/auth/permissions", response);
            return Set.of();
        }
        Set<String> permissions = new HashSet<>();
        expect(response, 200).path("permissions").forEach(permission -> permissions.add(permission.asText()));
        return permissions;
    }

    private Map<String, List<Map<String, Object>>> snapshot() {
        Map<String, List<Map<String, Object>>> state = new LinkedHashMap<>();
        STATE_TABLES.forEach(table -> state.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY 1, 2")));
        return state;
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

    private void assertAllowed(Endpoint endpoint, HttpResponse<String> response) {
        assertThat(response.statusCode()).as("%s should pass authorization: %s", endpoint, response.body())
                .isNotIn(401, 403).isEqualTo(endpoint.allowedStatus());
    }

    private void assertForbidden(String call, HttpResponse<String> response) {
        assertThat(response.statusCode()).as("%s should be forbidden: %s", call, response.body()).isEqualTo(403);
        assertThat(json.readTree(response.body())).as("%s must return only the standard forbidden error", call)
                .isEqualTo(json.valueToTree(FORBIDDEN_ERROR));
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private void assertUnauthorized(Endpoint endpoint, HttpResponse<String> response) {
        assertThat(response.statusCode()).as("%s should require login: %s", endpoint, response.body()).isEqualTo(401);
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow()).startsWith("Bearer");
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    }

    private static Endpoint endpoint(String method, String template, Rule rule, String body, int allowedStatus) {
        return new Endpoint(method, template, rule, body, allowedStatus, false);
    }

    private static Rule permission(String code) { return new Rule(List.of(code), false, false); }

    private static Rule allOf(String... codes) { return new Rule(List.of(codes), false, false); }

    private static Rule anyOf(String... codes) { return new Rule(List.of(codes), true, false); }

    private static Rule adminWith(String code) { return new Rule(List.of(code), false, true); }

    // PUBLIC has no codes. Otherwise the caller needs every code (any one with anyOf), plus ROLE_ADMIN when required.
    private record Rule(List<String> permissions, boolean anyOf, boolean requiresAdminRole) {
        boolean isPublic() { return permissions.isEmpty(); }

        boolean allows(Set<String> roles, Set<String> granted) {
            if (isPublic()) { return true; }
            boolean permitted = anyOf ? permissions.stream().anyMatch(granted::contains) : granted.containsAll(permissions);
            return permitted && (!requiresAdminRole || roles.contains("ADMIN"));
        }

        @Override
        public String toString() {
            if (isPublic()) { return "PUBLIC"; }
            return (requiresAdminRole ? "ROLE_ADMIN + " : "") + String.join(anyOf ? " | " : " + ", permissions);
        }
    }

    private record Endpoint(String method, String template, Rule rule, String body, int allowedStatus, boolean endsSession) {
        String key() { return method + " " + template; }

        String samplePath() {
            return template.replace("{id}", UNKNOWN_ID.toString()).replace("{role}", "RECRUITER")
                    .replace("{type}", CATALOG_TYPE);
        }

        @Override
        public String toString() { return key() + " [" + rule + "]"; }
    }

    private record Actor(UUID id, String token, Set<String> roles) { }

    private record Attack(Identity attacker, String method, String path, Map<String, Object> body) {
        @Override
        public String toString() { return attacker + " " + method + " " + path; }
    }

    // One account per internal role plus one without roles. Expected grants are written only once, in
    // RolePermissionSeedMigrationTest.EXPECTED_GRANTS, so a seed change updates this spec automatically.
    private enum Identity {
        ADMIN(Role.ADMIN),
        HR_MANAGER(Role.HR_MANAGER),
        RECRUITER(Role.RECRUITER),
        HIRING_MANAGER(Role.HIRING_MANAGER),
        INTERVIEWER(Role.INTERVIEWER),
        APPROVER(Role.APPROVER),
        NO_ROLE(null);

        private final Role role;

        Identity(Role role) {
            this.role = role;
        }

        Set<String> expectedGrants() {
            return role == null ? Set.of() : RolePermissionSeedMigrationTest.EXPECTED_GRANTS.get(role.name());
        }

        Set<String> roles() { return role == null ? Set.of() : Set.of(role.name()); }

        String email() {
            return this == ADMIN ? "admin@example.test" : name().toLowerCase(Locale.ROOT).replace('_', '-') + "@example.test";
        }
    }
}
