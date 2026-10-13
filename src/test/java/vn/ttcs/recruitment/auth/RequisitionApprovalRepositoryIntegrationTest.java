package vn.ttcs.recruitment.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.requisition.ApprovalEvent;
import vn.ttcs.recruitment.requisition.ApprovalEventType;
import vn.ttcs.recruitment.requisition.ApprovalLevel;
import vn.ttcs.recruitment.requisition.ApprovalOutcome;
import vn.ttcs.recruitment.requisition.ChainLevelRow;
import vn.ttcs.recruitment.requisition.RequisitionApprovalRepository;
import vn.ttcs.recruitment.requisition.RequisitionStatus;
import vn.ttcs.recruitment.requisition.SelectedChain;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 261: the approval history stores who decided a level, when, how, and why.
class RequisitionApprovalRepositoryIntegrationTest {
    private static final Instant AT = Instant.parse("2026-10-11T03:00:00.123456Z");

    private EmbeddedPostgres postgres;
    private JdbcTemplate jdbc;
    private RequisitionApprovalRepository approvals;
    private UUID hr;
    private UUID head;
    private UUID director;
    private UUID flow;
    private UUID requisition;

    @BeforeEach
    void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().setPort(0).setServerConfig("listen_addresses", "127.0.0.1").start();
        var dataSource = postgres.getPostgresDatabase();
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        approvals = new RequisitionApprovalRepository(new NamedParameterJdbcTemplate(dataSource));
        hr = account("hr@example.test", "Nguyễn Thị Hoa", "HR_MANAGER");
        head = account("head@example.test", "Trưởng IT", "HIRING_MANAGER");
        director = account("director@example.test", "Phạm Minh Đức", "APPROVER");
        UUID department = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, code, name, manager_user_id) VALUES (?, 'IT', 'IT', ?)", department, head);
        UUID position = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id, code, name, level, salary_min, salary_max, created_at, updated_at)
                VALUES (?, 'DEV', 'Lập trình viên', 'Junior', 15000000, 25000000, ?, ?)
                """, position, Timestamp.from(AT), Timestamp.from(AT));
        requisition = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_requisitions (id, position_id, department_id, headcount, reason, created_by,
                    created_at, updated_at)
                VALUES (?, ?, ?, 1, 'NEW_HEADCOUNT', ?, ?, ?)
                """, requisition, position, department, head, Timestamp.from(AT), Timestamp.from(AT));
        flow = UUID.randomUUID();
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
            jdbc.update("INSERT INTO approval_flows (id, department_id, current_version) VALUES (?, ?, 1)", flow, department);
            jdbc.update("INSERT INTO approval_flow_versions (flow_id, version, name, created_by, created_at) VALUES (?, 1, 'IT', ?, ?)",
                    flow, hr, Timestamp.from(AT));
            jdbc.update("INSERT INTO approval_flow_steps (flow_id, version, position, approver_role) VALUES (?, 1, 1, 'HR_MANAGER')", flow);
            jdbc.update("INSERT INTO approval_flow_steps (flow_id, version, position, salary_threshold, approver_user_id) VALUES (?, 1, 2, 20000000, ?)",
                    flow, director);
            jdbc.update("INSERT INTO approval_flow_steps (flow_id, version, position, salary_threshold, approver_role) VALUES (?, 1, 3, 50000000, 'APPROVER')", flow);
            jdbc.update("UPDATE approval_flow_versions SET sealed = TRUE WHERE flow_id = ?", flow);
        });
    }

    @AfterEach
    void stopDatabase() throws Exception {
        postgres.close();
    }

    @Test
    void aDecisionKeepsItsActorTimeOutcomeAndCommentWordForWord() {
        submit(1, RequisitionStatus.DRAFT, 2);
        approvals.appendDecision(requisition, 1, new ApprovalOutcome(ApprovalEventType.APPROVED, 1,
                RequisitionStatus.PENDING_APPROVAL, RequisitionStatus.PENDING_APPROVAL), hr, AT.plusSeconds(60), null);
        approvals.appendDecision(requisition, 1, new ApprovalOutcome(ApprovalEventType.INFO_REQUESTED, 2,
                RequisitionStatus.PENDING_APPROVAL, RequisitionStatus.RETURNED), director, AT.plusSeconds(120),
                "Bổ sung mô tả\ncông việc chi tiết");

        List<ApprovalEvent> events = approvals.findEvents(requisition);
        assertThat(events).extracting(ApprovalEvent::revision).containsExactly(1, 2, 3);
        var approved = events.get(1);
        assertThat(approved.type()).isEqualTo(ApprovalEventType.APPROVED);
        assertThat(approved.round()).isEqualTo(1);
        assertThat(approved.level()).isEqualTo(1);
        assertThat(approved.actorId()).isEqualTo(hr);
        assertThat(approved.actorName()).isEqualTo("Nguyễn Thị Hoa");
        assertThat(approved.occurredAt()).isEqualTo(AT.plusSeconds(60));
        assertThat(approved.comment()).isNull();
        assertThat(approved.flowId()).isNull();
        var returned = events.get(2);
        assertThat(returned.statusBefore()).isEqualTo(RequisitionStatus.PENDING_APPROVAL);
        assertThat(returned.statusAfter()).isEqualTo(RequisitionStatus.RETURNED);
        assertThat(returned.comment()).isEqualTo("Bổ sung mô tả\ncông việc chi tiết");
        assertThat(returned.occurredAt()).isEqualTo(AT.plusSeconds(120));
    }

    @Test
    void revisionsContinueAcrossRoundsAndOrderEventsWithTheSameTime() {
        submit(1, RequisitionStatus.DRAFT, 1);
        approvals.appendDecision(requisition, 1, new ApprovalOutcome(ApprovalEventType.INFO_REQUESTED, 1,
                RequisitionStatus.PENDING_APPROVAL, RequisitionStatus.RETURNED), hr, AT, "Thiếu yêu cầu ứng viên");
        submit(2, RequisitionStatus.RETURNED, 1);
        // A renamed account shows its new name.
        jdbc.update("UPDATE user_accounts SET full_name = 'Trưởng phòng IT' WHERE id = ?", head);

        List<ApprovalEvent> events = approvals.findEvents(requisition);
        assertThat(events).extracting(ApprovalEvent::revision).containsExactly(1, 2, 3);
        assertThat(events).extracting(ApprovalEvent::round).containsExactly(1, 1, 2);
        assertThat(events).extracting(ApprovalEvent::occurredAt).containsOnly(AT);
        assertThat(events.get(2).actorName()).isEqualTo("Trưởng phòng IT");
        assertThat(approvals.nextRound(requisition)).isEqualTo(3);
    }

    @Test
    void aLevelOfARoundIsDecidedOnlyOnce() {
        submit(1, RequisitionStatus.DRAFT, 2);
        approvals.appendDecision(requisition, 1, new ApprovalOutcome(ApprovalEventType.APPROVED, 1,
                RequisitionStatus.PENDING_APPROVAL, RequisitionStatus.PENDING_APPROVAL), hr, AT, null);

        assertThatThrownBy(() -> approvals.appendDecision(requisition, 1, new ApprovalOutcome(ApprovalEventType.REJECTED,
                1, RequisitionStatus.PENDING_APPROVAL, RequisitionStatus.REJECTED), director, AT, "Lần hai"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("requisition_workflow_events_one_decision_idx");
        assertThat(approvals.findEvents(requisition)).hasSize(2);
    }

    @Test
    void theChainOfARoundIsItsFirstLevelsWithTheirNames() {
        List<ChainLevelRow> levels = approvals.findChainLevels(flow, 1, 2);

        assertThat(levels).extracting(ChainLevelRow::level).containsExactly(
                new ApprovalLevel(1, null, "HR_MANAGER", false), new ApprovalLevel(2, director, null, true));
        assertThat(levels).extracting(ChainLevelRow::stepName)
                .containsExactly("Cấp 1 · Trưởng phòng Nhân sự", "Cấp 2 · Phạm Minh Đức");
        assertThat(approvals.findChainLevels(flow, 1, 3)).hasSize(3);
    }

    private void submit(int round, RequisitionStatus before, int levelCount) {
        var levels = List.of(new ApprovalLevel(1, null, "HR_MANAGER", false), new ApprovalLevel(2, director, null, true),
                new ApprovalLevel(3, null, "APPROVER", true)).subList(0, levelCount);
        approvals.appendSubmitted(requisition, round, before, new SelectedChain(flow, 1, "IT", levels), 30_000_000L,
                head, AT, null);
    }

    private UUID account(String email, String name, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id, email, full_name, password_hash, created_at) VALUES (?, ?, ?, 'hash', ?)",
                id, email, name, Timestamp.from(AT));
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role);
        return id;
    }
}
