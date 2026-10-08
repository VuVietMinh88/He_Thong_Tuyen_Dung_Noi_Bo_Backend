package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.approval.*;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=true", "app.bootstrap.email=admin@example.test",
        "app.bootstrap.password=TestingOnly123!", "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApprovalFlowServiceTest {
    @Autowired ApprovalFlowService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired AccountRepository accounts;
    @Autowired BootstrapAdmin bootstrap;
    @Autowired AuthService auth;
    @Autowired JwtDecoder decoder;
    @Autowired AuthIntegrationTest.MutableClock clock;
    private UUID department;
    private UUID actor;
    private Jwt jwt;
    private static final Instant START = Instant.parse("2026-10-08T00:00:00Z");
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        r.add("app.auth.jwt-secret", () -> Base64.getEncoder().encodeToString(key));
    }
    @BeforeEach
    void setup() {
        clock.set(START);
        // This context owns an ephemeral PostgreSQL instance; published versions deliberately resist DELETE.
        jdbc.execute("TRUNCATE approval_flow_steps, approval_flow_versions, approval_flows");
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("UPDATE user_accounts SET department_id=NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM user_accounts");
        jdbc.update("INSERT INTO role_permissions VALUES ('ADMIN','REQUISITIONS_WRITE_ALL') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO role_permissions VALUES ('ADMIN','REQUISITIONS_READ_ALL') ON CONFLICT DO NOTHING");
        bootstrap.run(new DefaultApplicationArguments());
        jwt = login("admin@example.test"); actor = UUID.fromString(jwt.getSubject());
        department = UUID.randomUUID();
        jdbc.update("INSERT INTO departments(id,code,name,manager_user_id) VALUES (?,'HR','HR',?)", department, actor);
    }

    @Test
    void publishesNewVersionsPreservesHistoryAndPreviewsStrictThresholds() {
        var first = service.create(jwt, payload(null));
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.name()).isEqualTo("Luồng duyệt");
        assertThat(service.preview(jwt, new ApprovalFlowPreviewRequest(department, new BigDecimal("20000000"))).steps()).hasSize(1);
        assertThat(service.preview(jwt, new ApprovalFlowPreviewRequest(department, new BigDecimal("20000001"))).steps()).hasSize(2);
        var changed = new ApprovalFlowRequest(department, "New", 1, List.of(step(1, null, "ADMIN")));
        var second = service.update(jwt, first.id(), changed);
        assertThat(second.version()).isEqualTo(2);
        assertThat(service.get(jwt, first.id(), 1)).isEqualTo(first);
        assertThat(service.get(jwt, first.id(), null)).isEqualTo(second);
        assertThat(service.list(jwt, department, 0, 20).totalElements()).isEqualTo(1);
        assertThat(service.list(jwt, department, 1, 20).items()).isEmpty();
        assertThatThrownBy(() -> service.update(jwt, first.id(), changed)).isInstanceOfSatisfying(ApprovalFlowException.class,
                e -> assertThat(e.getCode()).isEqualTo("APPROVAL_VERSION_CONFLICT"));
    }

    @Test
    void rejectsInvalidChainsAndUnknownTargetsWithoutPublishingAnything() {
        var invalid = List.of(
                List.of(step(1, "1", "ADMIN")),
                List.of(step(2, null, "ADMIN")),
                List.of(step(1, null, "ADMIN"), step(2, null, "ADMIN")),
                List.of(step(1, null, "ADMIN"), step(2, "200", "HR_MANAGER"), step(3, "100", "APPROVER")),
                List.of(step(1, null, "CANDIDATE")),
                List.of(step(1, null, "INTERVIEWER")),
                List.of(new ApprovalFlowRequest.Step(1, null, actor, "ADMIN")),
                List.of(new ApprovalFlowRequest.Step(1, null, UUID.randomUUID(), null)),
                List.of(step(1, null, "ADMIN"), step(2, "1.1", "HR_MANAGER")));
        for (var steps : invalid) {
            assertThatThrownBy(() -> service.create(jwt, new ApprovalFlowRequest(department, "Flow", null, steps)))
                    .isInstanceOfSatisfying(ApprovalFlowException.class, e -> assertThat(e.getFieldErrors()).isNotEmpty());
        }
        assertThatThrownBy(() -> service.create(jwt, new ApprovalFlowRequest(department, "Flow", null, Arrays.asList((ApprovalFlowRequest.Step) null))))
                .isInstanceOf(ApprovalFlowException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_flows", Integer.class)).isZero();
    }

    @Test
    void invalidUpdateKeepsCurrentVersionAndInactiveDepartmentsAreRejected() {
        var first = service.create(jwt, payload(null));
        assertThatThrownBy(() -> service.update(jwt, first.id(), new ApprovalFlowRequest(department, "", 1, first.steps())))
                .isInstanceOf(ApprovalFlowException.class);
        jdbc.update("UPDATE departments SET active=false WHERE id=?", department);
        assertThatThrownBy(() -> service.update(jwt, first.id(), payload(1))).isInstanceOf(ApprovalFlowException.class);
        assertThat(service.get(jwt, first.id(), null)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_flow_versions", Integer.class)).isEqualTo(1);
    }

    @Test
    void requiresManagerRoleAndLivePermissionsAndChecksExplicitApprover() {
        String hash = accounts.findById(actor).orElseThrow().getPasswordHash();
        UUID hrId = accounts.saveAndFlush(new Account("hr@example.test", "HR", hash, Set.of(Role.HR_MANAGER), START)).getId();
        accounts.saveAndFlush(new Account("recruiter@example.test", "Recruiter", hash, Set.of(Role.RECRUITER), START));
        Jwt hr = login("hr@example.test"), recruiter = login("recruiter@example.test");
        assertThatThrownBy(() -> service.create(recruiter, payload(null))).isInstanceOf(AccessDeniedException.class);
        var body = new ApprovalFlowRequest(department, "Explicit", null,
                List.of(new ApprovalFlowRequest.Step(1, null, hrId, null)));
        var flow = service.create(hr, body);
        jdbc.update("UPDATE user_accounts SET enabled=false WHERE id=?", hrId);
        assertThatThrownBy(() -> service.update(jwt, flow.id(), new ApprovalFlowRequest(department, "Explicit", 1, body.steps())))
                .isInstanceOf(ApprovalFlowException.class);
        jdbc.update("DELETE FROM role_permissions WHERE role_code='ADMIN' AND permission_code='REQUISITIONS_WRITE_ALL'");
        assertThatThrownBy(() -> service.update(jwt, flow.id(), payload(1))).isInstanceOf(AccessDeniedException.class);
        jdbc.update("DELETE FROM role_permissions WHERE role_code='ADMIN' AND permission_code='REQUISITIONS_READ_ALL'");
        assertThatThrownBy(() -> service.get(jwt, flow.id(), null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void simultaneousWritersCannotOverwriteOrCreateTwoCurrentVersions() throws Exception {
        var initial = service.create(jwt, payload(null));
        String hash = accounts.findById(actor).orElseThrow().getPasswordHash();
        accounts.saveAndFlush(new Account("other@example.test", "Other", hash, Set.of(Role.HR_MANAGER), START));
        Jwt other = login("other@example.test");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> outcome(() -> service.update(jwt, initial.id(), payload(1))));
            var b = executor.submit(() -> outcome(() -> service.update(other, initial.id(), payload(1))));
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("ok", "APPROVAL_VERSION_CONFLICT");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_flow_versions", Integer.class)).isEqualTo(2);
        assertThat(service.get(jwt, initial.id(), null).version()).isEqualTo(2);
    }

    @Test
    void rechecksTokenExpiryAfterWaitingForDepartmentLock() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor(); var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM departments WHERE id=? FOR UPDATE")) {
                statement.setObject(1, department); statement.executeQuery().close();
            }
            int blocker;
            try (var s = connection.createStatement(); var result = s.executeQuery("SELECT pg_backend_pid()")) {
                result.next(); blocker = result.getInt(1);
            }
            var future = executor.submit(() -> { service.create(jwt, payload(null)); return "unexpected"; });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid))", Integer.class, blocker) == 0
                    && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ?=ANY(pg_blocking_pids(pid))", Integer.class, blocker)).isPositive();
            clock.set(jwt.getExpiresAt());
            connection.commit();
            assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(AuthenticationFailureException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_flows", Integer.class)).isZero();
        }
    }

    private String outcome(Runnable action) {
        try { action.run(); return "ok"; } catch (ApprovalFlowException ex) { return ex.getCode(); }
    }
    private Jwt login(String email) { return decoder.decode(auth.login(new LoginRequest(email, "TestingOnly123!")).accessToken()); }
    private ApprovalFlowRequest payload(Integer expected) {
        return new ApprovalFlowRequest(department, "  Luồng duyệt  ", expected,
                List.of(step(1, null, "HIRING_MANAGER"), step(2, "20000000", "HR_MANAGER")));
    }
    private ApprovalFlowRequest.Step step(int position, String threshold, String role) {
        return new ApprovalFlowRequest.Step(position, threshold == null ? null : new BigDecimal(threshold), null, role);
    }
}
