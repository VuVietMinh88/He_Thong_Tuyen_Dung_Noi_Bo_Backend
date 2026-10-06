package vn.ttcs.recruitment.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.assertj.core.groups.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

// Restates sections 1-2 and 5 of docs/architecture/role-permission-matrix.md, which is not read here and must
// be kept in step by hand.
class RolePermissionSeedMigrationTest {
    private static final Timestamp CREATED_AT = Timestamp.from(Instant.parse("2026-10-06T00:00:00Z"));
    private static final List<String> MODULES = List.of("ORGANIZATION", "REQUISITIONS", "JOB_POSTINGS",
            "CANDIDATES", "INTERVIEWS", "EVALUATIONS", "OFFERS", "NOTIFICATIONS", "REPORTS", "USER_ADMIN");

    private static final Set<String> SELF_SERVICE = Set.of(
            "SELF_PROFILE_READ", "SELF_PROFILE_WRITE", "SELF_SECURITY_WRITE");

    // Hand-written instead of derived like V3, so a migration that changes grants fails until this spec is updated.
    static final Map<String, Set<String>> EXPECTED_GRANTS = Map.of(
            "ADMIN", internalRole(
                    "ORGANIZATION_READ_ALL", "ORGANIZATION_WRITE_ALL",
                    "REQUISITIONS_READ_ALL", "REQUISITIONS_WRITE_ALL",
                    "JOB_POSTINGS_READ_ALL", "JOB_POSTINGS_WRITE_ALL",
                    "CANDIDATES_READ_ALL", "CANDIDATES_WRITE_ALL",
                    "INTERVIEWS_READ_ALL", "INTERVIEWS_WRITE_ALL",
                    "EVALUATIONS_READ_ALL", "EVALUATIONS_WRITE_ALL",
                    "OFFERS_READ_ALL", "OFFERS_WRITE_ALL",
                    "NOTIFICATIONS_READ_ALL", "NOTIFICATIONS_WRITE_ALL",
                    "REPORTS_READ_ALL", "REPORTS_WRITE_ALL",
                    "USER_ADMIN_READ_ALL", "USER_ADMIN_WRITE_ALL"),
            "HR_MANAGER", internalRole(
                    "ORGANIZATION_READ_ALL", "ORGANIZATION_WRITE_ALL",
                    "REQUISITIONS_READ_ALL", "REQUISITIONS_WRITE_ALL",
                    "JOB_POSTINGS_READ_ALL", "JOB_POSTINGS_WRITE_ALL",
                    "CANDIDATES_READ_ALL", "CANDIDATES_WRITE_ALL",
                    "INTERVIEWS_READ_ALL", "INTERVIEWS_WRITE_ALL",
                    "EVALUATIONS_READ_ALL", "EVALUATIONS_WRITE_ALL",
                    "OFFERS_READ_ALL", "OFFERS_WRITE_ALL",
                    "NOTIFICATIONS_READ_ALL", "NOTIFICATIONS_WRITE_ALL",
                    "REPORTS_READ_ALL", "REPORTS_WRITE_ALL",
                    "USER_ADMIN_READ_ALL"),
            "RECRUITER", internalRole(
                    "ORGANIZATION_READ_ALL",
                    "REQUISITIONS_READ_SCOPED", "REQUISITIONS_WRITE_SCOPED",
                    "JOB_POSTINGS_READ_SCOPED", "JOB_POSTINGS_WRITE_SCOPED",
                    "CANDIDATES_READ_SCOPED", "CANDIDATES_WRITE_SCOPED",
                    "INTERVIEWS_READ_ALL", "INTERVIEWS_WRITE_ALL",
                    "EVALUATIONS_READ_ALL",
                    "OFFERS_READ_SCOPED", "OFFERS_WRITE_SCOPED",
                    "NOTIFICATIONS_READ_ALL", "NOTIFICATIONS_WRITE_ALL",
                    "REPORTS_READ_SCOPED"),
            "HIRING_MANAGER", internalRole(
                    "ORGANIZATION_READ_ALL",
                    "REQUISITIONS_READ_SCOPED", "REQUISITIONS_WRITE_SCOPED",
                    "JOB_POSTINGS_READ_ALL",
                    "CANDIDATES_READ_SCOPED",
                    "INTERVIEWS_READ_SCOPED",
                    "EVALUATIONS_READ_SCOPED",
                    "OFFERS_READ_SCOPED",
                    "NOTIFICATIONS_READ_SCOPED",
                    "REPORTS_READ_SCOPED"),
            "INTERVIEWER", internalRole(
                    "ORGANIZATION_READ_ALL",
                    "CANDIDATES_READ_SCOPED",
                    "INTERVIEWS_READ_SCOPED",
                    "EVALUATIONS_READ_SCOPED", "EVALUATIONS_WRITE_SCOPED",
                    "NOTIFICATIONS_READ_SCOPED"),
            "APPROVER", internalRole(
                    "ORGANIZATION_READ_ALL",
                    "REQUISITIONS_READ_SCOPED", "REQUISITIONS_WRITE_SCOPED",
                    "JOB_POSTINGS_READ_ALL",
                    "CANDIDATES_READ_ALL",
                    "EVALUATIONS_READ_ALL",
                    "OFFERS_READ_SCOPED", "OFFERS_WRITE_SCOPED",
                    "REPORTS_READ_ALL"),
            "CANDIDATE", Set.of(
                    "JOB_POSTINGS_READ_ALL",
                    "CANDIDATES_READ_SCOPED",
                    "INTERVIEWS_READ_SCOPED",
                    "OFFERS_READ_SCOPED",
                    "NOTIFICATIONS_READ_SCOPED"));

    // Every test reads the seed or inserts its own accounts, so one migrated database serves the class.
    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateAll() throws IOException {
        postgres = EmbeddedPostgres.builder().setPort(0)
                .setServerConfig("listen_addresses", "127.0.0.1").start();
        var dataSource = postgres.getPostgresDatabase();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stopPostgres() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    void seedsSevenRolesWithCandidateAsTheOnlyExternalRole() {
        assertThat(jdbc.queryForList("SELECT code, display_name, internal FROM roles"))
                .extracting("code", "display_name", "internal")
                .containsExactlyInAnyOrder(
                        tuple("ADMIN", "Quản trị hệ thống", true),
                        tuple("HR_MANAGER", "Trưởng phòng Nhân sự", true),
                        tuple("RECRUITER", "Nhân viên tuyển dụng", true),
                        tuple("HIRING_MANAGER", "Trưởng bộ phận", true),
                        tuple("INTERVIEWER", "Người phỏng vấn", true),
                        tuple("APPROVER", "Người duyệt", true),
                        tuple("CANDIDATE", "Ứng viên", false));
    }

    @Test
    void catalogsEveryModuleActionAndScopePlusSelfServicePermissions() {
        var expected = new ArrayList<Tuple>();
        for (String module : MODULES) {
            for (String action : List.of("READ", "WRITE")) {
                for (String scope : List.of("ALL", "SCOPED")) {
                    expected.add(tuple(module + "_" + action + "_" + scope, module, action, scope));
                }
            }
        }
        expected.add(tuple("SELF_PROFILE_READ", "SELF_PROFILE", "READ", "SCOPED"));
        expected.add(tuple("SELF_PROFILE_WRITE", "SELF_PROFILE", "WRITE", "SCOPED"));
        expected.add(tuple("SELF_SECURITY_WRITE", "SELF_SECURITY", "WRITE", "SCOPED"));

        assertThat(jdbc.queryForList("SELECT code, module_code, action_code, scope_code FROM permissions"))
                .extracting("code", "module_code", "action_code", "scope_code")
                .containsExactlyInAnyOrderElementsOf(expected);
        // AccessScope builds codes from this enum, so it must name exactly the seeded business modules.
        assertThat(Arrays.stream(PermissionModule.values()).map(Enum::name)).containsExactlyElementsOf(MODULES);
    }

    @Test
    void assignsOnlyInternalRolesToAccounts() {
        UUID everyRoleId = insertAccount("every-role@example.test");
        UUID noRoleId = insertAccount("no-role@example.test");

        for (var role : jdbc.queryForList("SELECT code, internal FROM roles")) {
            String code = (String) role.get("code");
            if ((Boolean) role.get("internal")) {
                assertThat(assignRole(everyRoleId, code)).as(code).isEqualTo(1);
            } else {
                assertThatThrownBy(() -> assignRole(everyRoleId, code), code)
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
        }
        assertThatThrownBy(() -> assignRole(everyRoleId, "GUEST"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("SELECT role FROM user_roles WHERE user_id=?", String.class, everyRoleId))
                .containsExactlyInAnyOrder("ADMIN", "HR_MANAGER", "RECRUITER", "HIRING_MANAGER",
                        "INTERVIEWER", "APPROVER");
        assertThat(new PermissionService(jdbc).forUser(noRoleId)).isEmpty();
    }

    @Test
    void grantsEachRoleExactlyTheDocumentedPermissions() {
        Map<String, Set<String>> actual = jdbc.queryForList(
                        "SELECT role_code, permission_code FROM role_permissions").stream()
                .collect(Collectors.groupingBy(row -> (String) row.get("role_code"),
                        Collectors.mapping(row -> (String) row.get("permission_code"), Collectors.toSet())));

        assertThat(actual.keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED_GRANTS.keySet());
        EXPECTED_GRANTS.forEach((role, permissions) -> assertThat(actual.get(role)).as(role)
                .containsExactlyInAnyOrderElementsOf(permissions));
    }

    @Test
    void resolvesTheUnionOfGrantsForAccountsWithSeveralRoles() {
        UUID multiRoleId = insertAccount("multi-role@example.test");
        assignRole(multiRoleId, "RECRUITER");
        assignRole(multiRoleId, "INTERVIEWER");

        var union = new HashSet<>(EXPECTED_GRANTS.get("RECRUITER"));
        union.addAll(EXPECTED_GRANTS.get("INTERVIEWER"));
        assertThat(new PermissionService(jdbc).forUser(multiRoleId)).isEqualTo(union);
    }

    private static Set<String> internalRole(String... modulePermissions) {
        var permissions = new HashSet<>(SELF_SERVICE);
        permissions.addAll(List.of(modulePermissions));
        return Set.copyOf(permissions);
    }

    private static UUID insertAccount(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id,email,full_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                id, email, "Tài khoản kiểm thử", "unchanged-password-hash", CREATED_AT);
        return id;
    }

    private static int assignRole(UUID userId, String role) {
        return jdbc.update("INSERT INTO user_roles (user_id,role) VALUES (?,?)", userId, role);
    }
}
