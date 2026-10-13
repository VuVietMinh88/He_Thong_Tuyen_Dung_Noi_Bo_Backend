package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.requisition.ApprovalChainSelector;
import vn.ttcs.recruitment.requisition.ApprovalLevel;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 256: the approval levels of a requisition, read from the department's current sealed flow version.
@SpringBootTest(properties = {
        "spring.config.import=", "app.bootstrap.enabled=false",
        "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApprovalChainSelectorIntegrationTest {
    private static final Instant START = Instant.parse("2026-10-11T03:00:00Z");

    @Autowired private ApprovalChainSelector selector;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountRepository accounts;
    @Autowired private PlatformTransactionManager transactions;

    private UUID parent;
    private UUID it;
    private UUID sibling;
    private UUID hr;
    private UUID parentHead;
    private UUID itHead;
    private UUID approverA;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        registry.add("app.auth.jwt-secret", () -> Base64.getEncoder().encodeToString(secret));
    }

    @BeforeEach
    void resetFixture() {
        // Sealed flow versions cannot be deleted (V18), but TRUNCATE does not fire row triggers.
        jdbc.execute("TRUNCATE approval_flow_steps, approval_flow_versions, approval_flows");
        jdbc.update("UPDATE departments SET parent_id = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM user_accounts");
        hr = account("hr@example.test", "Nguyễn Thị Hoa", Role.HR_MANAGER);
        parentHead = account("parent@example.test", "Trưởng khối", Role.HIRING_MANAGER);
        itHead = account("it@example.test", "Trưởng IT", Role.HIRING_MANAGER);
        UUID siblingHead = account("sibling@example.test", "Trưởng Kinh doanh", Role.HIRING_MANAGER);
        approverA = account("a@example.test", "Giám đốc A", Role.APPROVER);
        // A second APPROVER, not named by any level.
        account("b@example.test", "Giám đốc B", Role.APPROVER);
        parent = department("PARENT", parentHead, null);
        it = department("IT", itHead, parent);
        sibling = department("SALES", siblingHead, parent);
    }

    @Test
    void aDepartmentWithoutItsOwnFlowCannotBeSubmittedEvenWhenItsParentHasOne() {
        flow(parent, step(1, null, null, "HR_MANAGER"));

        assertThatThrownBy(() -> selector.select(it, 20_000_000L, itHead, itHead))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("APPROVAL_FLOW_NOT_CONFIGURED");
                });
    }

    @Test
    void aSalaryAboveAThresholdAddsTheHigherLevels() {
        UUID flow = flow(it, step(1, null, null, "HR_MANAGER"), step(2, 20_000_000L, approverA, null),
                step(3, 50_000_000L, null, "APPROVER"));

        var base = selector.select(it, 20_000_000L, itHead, itHead);
        assertThat(base.flowId()).isEqualTo(flow);
        assertThat(base.version()).isEqualTo(1);
        assertThat(base.levels()).containsExactly(new ApprovalLevel(1, null, "HR_MANAGER", false));
        assertThat(selector.select(it, 20_000_001L, itHead, itHead).levelCount()).isEqualTo(2);
        // Level 3 (APPROVER) is decided by approver B: A is named by level 2 and decides only that one.
        assertThat(selector.select(it, 60_000_000L, itHead, itHead).levels()).containsExactly(
                new ApprovalLevel(1, null, "HR_MANAGER", false), new ApprovalLevel(2, approverA, null, true),
                new ApprovalLevel(3, null, "APPROVER", true));
    }

    @Test
    void theCurrentVersionIsUsed() {
        UUID flow = flow(it, step(1, null, null, "HR_MANAGER"));
        publish(flow, 2, step(1, null, null, "APPROVER"), step(2, 10_000_000L, null, "HR_MANAGER"));

        var chain = selector.select(it, 30_000_000L, itHead, itHead);
        assertThat(chain.version()).isEqualTo(2);
        assertThat(chain.levels()).extracting(ApprovalLevel::approverRole).containsExactly("APPROVER", "HR_MANAGER");
    }

    @Test
    void theCreatorAndTheSubmitterDoNotDecideTheirOwnRequisition() {
        flow(it, step(1, null, itHead, null));
        unavailable(() -> selector.select(it, 1L, itHead, hr), "cấp 1 (Trưởng IT)");

        flow(sibling, step(1, null, null, "HR_MANAGER"));
        // HR is the only HR manager and submits the requisition.
        unavailable(() -> selector.select(sibling, 1L, itHead, hr), "cấp 1 (Trưởng phòng Nhân sự)");
    }

    @Test
    void aHiringManagerLevelIsDecidedByAManagerOfTheDepartmentOrAboveIt() {
        flow(it, step(1, null, null, "HIRING_MANAGER"));

        // The head of IT created it, so the head of the parent department decides; the head of SALES never does.
        assertThat(selector.select(it, 1L, itHead, itHead).levelCount()).isEqualTo(1);
        // HR now manages the parent department but is not a hiring manager, and nobody else manages IT's parents.
        jdbc.update("UPDATE departments SET manager_user_id = ? WHERE id = ?", hr, parent);
        unavailable(() -> selector.select(it, 1L, itHead, itHead), "cấp 1 (Trưởng bộ phận)");
    }

    @Test
    void aNamedApproverLockedAfterTheFlowWasSavedMakesTheLevelUnavailable() {
        flow(it, step(1, null, approverA, null));
        assertThat(selector.select(it, 1L, itHead, itHead).levelCount()).isEqualTo(1);

        jdbc.update("UPDATE user_accounts SET admin_locked_at = ?, admin_lock_reason = 'Nghỉ việc', admin_locked_by = ? WHERE id = ?",
                Timestamp.from(START), hr, approverA);
        unavailable(() -> selector.select(it, 1L, itHead, itHead), "cấp 1 (Giám đốc A)");
    }

    @Test
    void onePersonCannotBeTheOnlyApproverOfTwoLevels() {
        UUID both = account("both@example.test", "Kiêm nhiệm", Role.APPROVER, Role.ADMIN);
        jdbc.update("DELETE FROM user_roles WHERE role = 'APPROVER' AND user_id <> ?", both);
        flow(it, step(1, null, null, "APPROVER"), step(2, null, null, "ADMIN"));

        unavailable(() -> selector.select(it, 1L, itHead, itHead), "cấp 1 (Người duyệt), cấp 2 (Quản trị hệ thống)");
        account("admin2@example.test", "Quản trị 2", Role.ADMIN);
        assertThat(selector.select(it, 1L, itHead, itHead).levelCount()).isEqualTo(2);
    }

    @Test
    void aVersionWhoseFirstLevelHasAThresholdCannotBeSaved() {
        // S3-01: a configuration with no level for some salary is blocked (the V18 seal check).
        assertThatThrownBy(() -> flow(it, step(1, 10_000_000L, null, "HR_MANAGER")))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_flows", Integer.class)).isZero();
    }

    private void unavailable(Runnable call, String levels) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(409);
            assertThat(e.getCode()).isEqualTo("APPROVAL_LEVEL_UNAVAILABLE");
            assertThat(e.getMessage()).contains(levels);
        });
    }

    private record Step(int position, Long threshold, UUID user, String role) { }

    private static Step step(int position, Long threshold, UUID user, String role) {
        return new Step(position, threshold, user, role);
    }

    private UUID flow(UUID department, Step... steps) {
        UUID id = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO approval_flows (id, department_id, current_version) VALUES (?, ?, 1)", id, department);
            version(id, 1, steps);
        });
        return id;
    }

    private void publish(UUID flow, int version, Step... steps) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            version(flow, version, steps);
            jdbc.update("UPDATE approval_flows SET current_version = ? WHERE id = ?", version, flow);
        });
    }

    private void version(UUID flow, int version, Step... steps) {
        jdbc.update("INSERT INTO approval_flow_versions (flow_id, version, name, created_by, created_at) VALUES (?, ?, ?, ?, ?)",
                flow, version, "Luồng " + version, hr, Timestamp.from(START));
        for (Step step : steps) {
            jdbc.update("""
                    INSERT INTO approval_flow_steps (flow_id, version, position, salary_threshold, approver_user_id, approver_role)
                    VALUES (?, ?, ?, ?, ?, ?)""", flow, version, step.position(), step.threshold(), step.user(), step.role());
        }
        jdbc.update("UPDATE approval_flow_versions SET sealed = TRUE WHERE flow_id = ? AND version = ?", flow, version);
    }

    private UUID department(String code, UUID manager, UUID parentId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, code, name, manager_user_id, parent_id, active, created_at) VALUES (?, ?, ?, ?, ?, TRUE, ?)",
                id, code, "Phòng " + code, manager, parentId, Timestamp.from(START));
        return id;
    }

    private UUID account(String email, String name, Role... roles) {
        return accounts.saveAndFlush(new Account(email, name, "hash", Set.of(roles), START)).getId();
    }
}
