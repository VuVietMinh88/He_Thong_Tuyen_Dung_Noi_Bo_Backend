package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import vn.ttcs.recruitment.requisition.RecruitmentRequisition;
import vn.ttcs.recruitment.requisition.RecruitmentRequisitionRepository;
import vn.ttcs.recruitment.requisition.RequisitionReason;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Hibernate runs with ddl-auto=validate, so this context only starts when the entity matches V13.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecruitmentRequisitionRepositoryIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-07T00:00:00Z");
    // Larger than Integer.MAX_VALUE, so the salary fields must stay Long/BIGINT end to end.
    private static final long THREE_BILLION_VND = 3_000_000_000L;
    private static final String JOB_DESCRIPTION = "Phát triển API tuyển dụng.\n\n- Spring Boot\n- PostgreSQL\n";
    private static final String CANDIDATE_REQUIREMENTS = "Tối thiểu 2 năm kinh nghiệm Java.";

    @Autowired private RecruitmentRequisitionRepository requisitions;
    @Autowired private JdbcTemplate jdbc;

    private UUID creatorId;
    private UUID departmentId;
    private UUID positionId;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void createDepartmentAndPosition() {
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM positions");
        creatorId = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id,email,full_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                creatorId, creatorId + "@requisition.test", "Trưởng bộ phận", "unused-password-hash",
                Timestamp.from(CREATED_AT));
        departmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id,code,name,manager_user_id) VALUES (?,?,?,?)",
                departmentId, "IT", "Công nghệ thông tin", creatorId);
        positionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id,code,name,level,salary_min,salary_max,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, positionId, "DEV_JUNIOR", "Lập trình viên", "Junior", 15_000_000L, 25_000_000L,
                Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));
    }

    @Test
    void savesAndReloadsCompleteDraft() {
        var requisition = new RecruitmentRequisition(positionId, departmentId, 2, RequisitionReason.NEW_HEADCOUNT,
                2_000_000_000L, THREE_BILLION_VND, "Cần kinh nghiệm quản lý dự án lớn.", LocalDate.of(2026, 12, 31),
                JOB_DESCRIPTION, CANDIDATE_REQUIREMENTS, creatorId, CREATED_AT);
        requisitions.saveAndFlush(requisition);

        var row = jdbc.queryForMap("SELECT * FROM recruitment_requisitions WHERE id=?", requisition.getId());
        assertThat(row.get("position_id")).isEqualTo(positionId);
        assertThat(row.get("department_id")).isEqualTo(departmentId);
        assertThat(row.get("headcount")).isEqualTo(2);
        assertThat(row.get("reason")).isEqualTo("NEW_HEADCOUNT");
        assertThat(row.get("proposed_salary_min")).isEqualTo(2_000_000_000L);
        assertThat(row.get("proposed_salary_max")).isEqualTo(THREE_BILLION_VND);
        assertThat(row.get("salary_justification")).isEqualTo("Cần kinh nghiệm quản lý dự án lớn.");
        assertThat(row.get("job_description")).isEqualTo(JOB_DESCRIPTION);
        assertThat(row.get("candidate_requirements")).isEqualTo(CANDIDATE_REQUIREMENTS);
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("created_by")).isEqualTo(creatorId);
        assertThat(row.get("created_at")).isEqualTo(Timestamp.from(CREATED_AT));
        assertThat(row.get("updated_at")).isEqualTo(Timestamp.from(CREATED_AT));
        // The date is stored as written, without a time-zone shift (the JVM writes timestamps in UTC).
        assertThat(jdbc.queryForObject("SELECT needed_by FROM recruitment_requisitions WHERE id=?",
                LocalDate.class, requisition.getId())).isEqualTo(LocalDate.of(2026, 12, 31));

        var reloaded = requisitions.findById(requisition.getId()).orElseThrow();
        assertThat(reloaded.getPositionId()).isEqualTo(positionId);
        assertThat(reloaded.getDepartmentId()).isEqualTo(departmentId);
        assertThat(reloaded.getHeadcount()).isEqualTo(2);
        assertThat(reloaded.getReason()).isEqualTo(RequisitionReason.NEW_HEADCOUNT);
        assertThat(reloaded.getProposedSalaryMin()).isEqualTo(2_000_000_000L);
        assertThat(reloaded.getProposedSalaryMax()).isEqualTo(THREE_BILLION_VND);
        assertThat(reloaded.getSalaryJustification()).isEqualTo("Cần kinh nghiệm quản lý dự án lớn.");
        assertThat(reloaded.getNeededBy()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(reloaded.getJobDescription()).isEqualTo(JOB_DESCRIPTION);
        assertThat(reloaded.getCandidateRequirements()).isEqualTo(CANDIDATE_REQUIREMENTS);
        assertThat(reloaded.getStatus()).isEqualTo(RequisitionStatus.DRAFT);
        assertThat(reloaded.getCreatedBy()).isEqualTo(creatorId);
        assertThat(reloaded.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void savesDraftWithOnlyRequiredFields() {
        var requisition = new RecruitmentRequisition(positionId, departmentId, 1, RequisitionReason.REPLACEMENT,
                null, null, null, null, null, null, creatorId, CREATED_AT);
        requisitions.saveAndFlush(requisition);

        var reloaded = requisitions.findById(requisition.getId()).orElseThrow();
        assertThat(reloaded.getHeadcount()).isEqualTo(1);
        assertThat(reloaded.getReason()).isEqualTo(RequisitionReason.REPLACEMENT);
        assertThat(reloaded.getStatus()).isEqualTo(RequisitionStatus.DRAFT);
        assertThat(reloaded.getProposedSalaryMin()).isNull();
        assertThat(reloaded.getProposedSalaryMax()).isNull();
        assertThat(reloaded.getSalaryJustification()).isNull();
        assertThat(reloaded.getNeededBy()).isNull();
        assertThat(reloaded.getJobDescription()).isNull();
        assertThat(reloaded.getCandidateRequirements()).isNull();
        assertThat(jdbc.queryForObject("SELECT job_description IS NULL AND proposed_salary_min IS NULL"
                + " FROM recruitment_requisitions WHERE id=?", Boolean.class, requisition.getId())).isTrue();
    }

    @Test
    void databaseConstraintsRejectInvalidDraftsSavedThroughJpa() {
        assertThatThrownBy(() -> requisitions.saveAndFlush(draft(positionId, departmentId, 0, null, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("positive_requisition_headcount");
        assertThatThrownBy(() -> requisitions.saveAndFlush(draft(positionId, departmentId, 1,
                25_000_000L, 15_000_000L, null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("valid_requisition_salary_range");
        assertThatThrownBy(() -> requisitions.saveAndFlush(draft(positionId, departmentId, 1, null, null, "  \n")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("valid_requisition_job_description");
        assertThatThrownBy(() -> requisitions.saveAndFlush(draft(positionId, UUID.randomUUID(), 1, null, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("recruitment_requisitions_department_id_fkey");
        assertThatThrownBy(() -> requisitions.saveAndFlush(draft(UUID.randomUUID(), departmentId, 1, null, null, null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("recruitment_requisitions_position_id_fkey");

        assertThat(requisitions.count()).isZero();
    }

    private RecruitmentRequisition draft(UUID position, UUID department, int headcount, Long salaryMin,
                                         Long salaryMax, String jobDescription) {
        return new RecruitmentRequisition(position, department, headcount, RequisitionReason.REPLACEMENT,
                salaryMin, salaryMax, null, null, jobDescription, null, creatorId, CREATED_AT);
    }
}
