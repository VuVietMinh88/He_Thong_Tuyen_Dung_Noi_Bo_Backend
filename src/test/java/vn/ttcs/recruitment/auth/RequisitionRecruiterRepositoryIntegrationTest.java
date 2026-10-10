package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.requisition.RecruiterChange;
import vn.ttcs.recruitment.requisition.RecruiterChangeType;
import vn.ttcs.recruitment.requisition.RequisitionRecruiter;
import vn.ttcs.recruitment.requisition.RequisitionRecruiterRepository;
import vn.ttcs.recruitment.requisition.RequisitionRecruiterRole;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 283: reading and changing the recruiters of a requisition (V16).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RequisitionRecruiterRepositoryIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-10T00:00:00Z");
    private static final Instant FIRST = Instant.parse("2026-10-10T02:15:30.123456Z");
    private static final Instant SECOND = Instant.parse("2026-10-10T03:00:00.654321Z");

    @Autowired private RequisitionRecruiterRepository recruiters;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactions;

    private UUID hrId;
    private UUID otherHrId;
    private UUID anna;
    private UUID binh;
    private UUID chi;
    private UUID requisitionId;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void createRequisition() {
        jdbc.update("DELETE FROM requisition_recruiters");
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("UPDATE departments SET parent_id = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM user_accounts");
        hrId = account("hr@example.test");
        otherHrId = account("hr2@example.test");
        anna = account("anna@example.test");
        binh = account("binh@example.test");
        chi = account("chi@example.test");
        UUID departmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id,code,name,manager_user_id) VALUES (?,?,?,?)",
                departmentId, "IT", "Phòng IT", hrId);
        UUID positionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id,code,name,level,salary_min,salary_max,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, positionId, "DEV", "Lập trình viên", "Junior", 15_000_000L, 25_000_000L,
                Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));
        requisitionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_requisitions (id,position_id,department_id,headcount,reason,created_by,
                    created_at,updated_at)
                VALUES (?,?,?,1,'NEW_HEADCOUNT',?,?,?)
                """, requisitionId, positionId, departmentId, hrId, Timestamp.from(CREATED_AT),
                Timestamp.from(CREATED_AT));
    }

    @Test
    void anUnassignedRequisitionHasNoRecruiters() {
        var assignment = recruiters.findByRequisition(requisitionId);

        assertThat(assignment.requisitionId()).isEqualTo(requisitionId);
        assertThat(assignment.primary()).isNull();
        assertThat(assignment.supporting()).isEmpty();
    }

    @Test
    void appliesAFirstAssignmentAHandoverAPromotionAndSupportingChanges() {
        apply(new RecruiterChange(RecruiterChangeType.PRIMARY_ASSIGNED, anna, null), hrId, FIRST);
        assertThat(recruiters.findByRequisition(requisitionId).primary())
                .isEqualTo(new RequisitionRecruiter(anna, RequisitionRecruiterRole.PRIMARY, hrId, FIRST));

        apply(new RecruiterChange(RecruiterChangeType.SUPPORTING_ADDED, binh, null), hrId, FIRST);
        apply(new RecruiterChange(RecruiterChangeType.SUPPORTING_ADDED, chi, null), hrId, SECOND);
        var team = recruiters.findByRequisition(requisitionId);
        assertThat(team.supporting()).containsExactly(
                new RequisitionRecruiter(binh, RequisitionRecruiterRole.SUPPORTING, hrId, FIRST),
                new RequisitionRecruiter(chi, RequisitionRecruiterRole.SUPPORTING, hrId, SECOND));

        // Binh is promoted: Anna leaves, Binh's supporting row becomes the primary row.
        apply(new RecruiterChange(RecruiterChangeType.PRIMARY_HANDED_OVER, binh, anna), otherHrId, SECOND);
        team = recruiters.findByRequisition(requisitionId);
        assertThat(team.primary())
                .isEqualTo(new RequisitionRecruiter(binh, RequisitionRecruiterRole.PRIMARY, otherHrId, SECOND));
        // Chi keeps who assigned her and when.
        assertThat(team.supporting()).containsExactly(
                new RequisitionRecruiter(chi, RequisitionRecruiterRole.SUPPORTING, hrId, SECOND));

        // A handover to someone not on the team.
        apply(new RecruiterChange(RecruiterChangeType.PRIMARY_HANDED_OVER, anna, binh), hrId, SECOND);
        apply(new RecruiterChange(RecruiterChangeType.SUPPORTING_REMOVED, chi, null), hrId, SECOND);
        team = recruiters.findByRequisition(requisitionId);
        assertThat(team.primary().recruiterId()).isEqualTo(anna);
        assertThat(team.supporting()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM requisition_recruiters", Integer.class)).isOne();
    }

    // A @Repository translates the IllegalStateException; the cause still says what went wrong.
    @Test
    void aStaleChangeThrowsAndWritesNothing() {
        apply(new RecruiterChange(RecruiterChangeType.PRIMARY_ASSIGNED, anna, null), hrId, FIRST);

        // Another writer assigned a primary in between: a first assignment no longer fits the rows.
        assertThatThrownBy(() -> apply(new RecruiterChange(RecruiterChangeType.PRIMARY_ASSIGNED, binh, null),
                hrId, SECOND)).isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
        // Removing a supporting recruiter who is not there.
        assertThatThrownBy(() -> apply(new RecruiterChange(RecruiterChangeType.SUPPORTING_REMOVED, chi, null),
                hrId, SECOND)).isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(recruiters.findByRequisition(requisitionId).primary())
                .isEqualTo(new RequisitionRecruiter(anna, RequisitionRecruiterRole.PRIMARY, hrId, FIRST));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM requisition_recruiters", Integer.class)).isOne();
    }

    @Test
    void theDatabaseStillRefusesASecondPrimaryWrittenBesideTheRules() {
        apply(new RecruiterChange(RecruiterChangeType.PRIMARY_ASSIGNED, anna, null), hrId, FIRST);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO requisition_recruiters (requisition_id, recruiter_id, assignment_role, assigned_by,
                    assigned_at)
                VALUES (?, ?, 'PRIMARY', ?, ?)
                """, requisitionId, binh, hrId, Timestamp.from(FIRST)))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("requisition_recruiters_one_primary_idx");
    }

    @Test
    void rowsBrokenBySqlByHandAreReportedInsteadOfShown() {
        jdbc.update("""
                INSERT INTO requisition_recruiters (requisition_id, recruiter_id, assignment_role, assigned_by,
                    assigned_at)
                VALUES (?, ?, 'SUPPORTING', ?, ?)
                """, requisitionId, binh, hrId, Timestamp.from(FIRST));

        assertThatThrownBy(() -> recruiters.findByRequisition(requisitionId))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    private void apply(RecruiterChange change, UUID by, Instant at) {
        transactions.executeWithoutResult(status -> recruiters.apply(requisitionId, change, by, at));
    }

    private UUID account(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id,email,full_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                id, email, "Tài khoản kiểm thử", "unused-password-hash", Timestamp.from(CREATED_AT));
        return id;
    }
}
