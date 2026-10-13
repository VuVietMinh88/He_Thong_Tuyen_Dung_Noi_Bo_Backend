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
import vn.ttcs.recruitment.jobposting.JobPosting;
import vn.ttcs.recruitment.jobposting.JobPostingContent;
import vn.ttcs.recruitment.jobposting.JobPostingRepository;
import vn.ttcs.recruitment.jobposting.JobPostingStatus;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 302: Hibernate runs with ddl-auto=validate, so this context only starts when JobPosting matches V21.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class JobPostingRepositoryIntegrationTest {
    private static final Instant REQUISITION_CREATED_AT = Instant.parse("2026-10-11T00:00:00Z");
    // PostgreSQL keeps microseconds, so this instant must come back unchanged.
    private static final Instant CREATED_AT = Instant.parse("2026-10-11T02:15:30.123456Z");
    private static final LocalDate DEADLINE = LocalDate.of(2026, 11, 30);
    private static final String JOB_DESCRIPTION = "  Phát triển API tuyển dụng.\n\n- Spring Boot\n- PostgreSQL\n";
    private static final String REQUIREMENTS = "Tối thiểu 2 năm kinh nghiệm Java.\n    Đọc hiểu tài liệu tiếng Anh.";
    private static final String BENEFITS = "Bảo hiểm đầy đủ.\n13 tháng lương.";

    @Autowired private JobPostingRepository postings;
    @Autowired private JdbcTemplate jdbc;

    private UUID creatorId;
    private UUID requisitionId;
    private UUID workLocationId;
    private UUID employmentTypeId;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void createApprovedRequisitionAndCatalogValues() {
        jdbc.update("DELETE FROM job_posting_authors");
        jdbc.update("DELETE FROM job_postings");
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("DELETE FROM recruitment_catalog_items");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM user_accounts");
        UUID hrId = account("hr@example.test");
        creatorId = account("recruiter@example.test");
        UUID departmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id,code,name,manager_user_id) VALUES (?,?,?,?)",
                departmentId, "IT", "Công nghệ thông tin", hrId);
        UUID positionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id,code,name,level,salary_min,salary_max,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, positionId, "DEV", "Lập trình viên Java", "Junior", 15_000_000L, 25_000_000L,
                Timestamp.from(REQUISITION_CREATED_AT), Timestamp.from(REQUISITION_CREATED_AT));
        requisitionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_requisitions (id,position_id,department_id,headcount,reason,proposed_salary_min,
                    proposed_salary_max,needed_by,job_description,candidate_requirements,status,created_by,
                    created_at,updated_at)
                VALUES (?,?,?,2,'NEW_HEADCOUNT',20000000,22000000,?,?,?,'APPROVED',?,?,?)
                """, requisitionId, positionId, departmentId, LocalDate.of(2026, 12, 1), JOB_DESCRIPTION,
                REQUIREMENTS, hrId, Timestamp.from(REQUISITION_CREATED_AT), Timestamp.from(REQUISITION_CREATED_AT));
        workLocationId = catalogValue("WORK_LOCATION", "HN", "Hà Nội");
        employmentTypeId = catalogValue("EMPLOYMENT_TYPE", "FULL_TIME", "Toàn thời gian");
    }

    @Test
    void savesADraftAndReadsItBackExactly() {
        var requisitionBefore = jdbc.queryForMap("SELECT * FROM recruitment_requisitions WHERE id = ?",
                requisitionId);
        var content = new JobPostingContent("Lập trình viên Java (Hà Nội)", JOB_DESCRIPTION, REQUIREMENTS, BENEFITS,
                workLocationId, employmentTypeId, DEADLINE, true);
        JobPosting saved = postings.saveAndFlush(new JobPosting(requisitionId, content, creatorId, CREATED_AT));

        var row = jdbc.queryForMap("SELECT * FROM job_postings WHERE id = ?", saved.getId());
        assertThat(row.get("requisition_id")).isEqualTo(requisitionId);
        assertThat(row.get("status")).isEqualTo("DRAFT");
        assertThat(row.get("revision")).isEqualTo(1);
        assertThat(row.get("title")).isEqualTo("Lập trình viên Java (Hà Nội)");
        assertThat(row.get("job_description")).isEqualTo(JOB_DESCRIPTION);
        assertThat(row.get("candidate_requirements")).isEqualTo(REQUIREMENTS);
        assertThat(row.get("benefits")).isEqualTo(BENEFITS);
        assertThat(row.get("work_location_id")).isEqualTo(workLocationId);
        assertThat(row.get("employment_type_id")).isEqualTo(employmentTypeId);
        assertThat(row.get("show_salary")).isEqualTo(true);
        assertThat(row.get("created_by")).isEqualTo(creatorId);
        assertThat(row.get("created_at")).isEqualTo(Timestamp.from(CREATED_AT));
        assertThat(row.get("updated_at")).isEqualTo(Timestamp.from(CREATED_AT));
        // The date is stored as written, without a time-zone shift (the JVM writes timestamps in UTC).
        assertThat(jdbc.queryForObject("SELECT application_deadline FROM job_postings WHERE id = ?",
                LocalDate.class, saved.getId())).isEqualTo(DEADLINE);

        var reloaded = postings.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getRequisitionId()).isEqualTo(requisitionId);
        assertThat(reloaded.getStatus()).isEqualTo(JobPostingStatus.DRAFT);
        assertThat(reloaded.getRevision()).isEqualTo(1);
        assertThat(reloaded.getTitle()).isEqualTo("Lập trình viên Java (Hà Nội)");
        assertThat(reloaded.getJobDescription()).isEqualTo(JOB_DESCRIPTION);
        assertThat(reloaded.getCandidateRequirements()).isEqualTo(REQUIREMENTS);
        assertThat(reloaded.getBenefits()).isEqualTo(BENEFITS);
        assertThat(reloaded.getWorkLocationId()).isEqualTo(workLocationId);
        assertThat(reloaded.getEmploymentTypeId()).isEqualTo(employmentTypeId);
        assertThat(reloaded.getApplicationDeadline()).isEqualTo(DEADLINE);
        assertThat(reloaded.isShowSalary()).isTrue();
        assertThat(reloaded.getCreatedBy()).isEqualTo(creatorId);
        assertThat(reloaded.getCreatedAt()).hasToString("2026-10-11T02:15:30.123456Z");
        assertThat(reloaded.getUpdatedAt()).isEqualTo(CREATED_AT);
        // Saving a posting never writes the requisition it was made from.
        assertThat(jdbc.queryForMap("SELECT * FROM recruitment_requisitions WHERE id = ?", requisitionId))
                .isEqualTo(requisitionBefore);
    }

    @Test
    void savesADraftWithoutTheOptionalDetails() {
        var content = new JobPostingContent("Lập trình viên Java", JOB_DESCRIPTION, REQUIREMENTS, null, null, null,
                null, false);
        JobPosting saved = postings.saveAndFlush(new JobPosting(requisitionId, content, creatorId, CREATED_AT));

        var reloaded = postings.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getBenefits()).isNull();
        assertThat(reloaded.getWorkLocationId()).isNull();
        assertThat(reloaded.getEmploymentTypeId()).isNull();
        assertThat(reloaded.getApplicationDeadline()).isNull();
        assertThat(reloaded.isShowSalary()).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT benefits IS NULL AND work_location_id IS NULL AND employment_type_id IS NULL
                    AND application_deadline IS NULL AND NOT show_salary
                FROM job_postings WHERE id = ?
                """, Boolean.class, saved.getId())).isTrue();
    }

    @Test
    void databaseConstraintsRejectInvalidPostingsSavedThroughJpa() {
        assertThatThrownBy(() -> postings.saveAndFlush(posting("  \n", null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("valid_job_posting_title");
        assertThatThrownBy(() -> postings.saveAndFlush(posting("Lập trình viên Java", UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("job_postings_work_location_id_fkey");
        postings.saveAndFlush(posting("Lập trình viên Java", workLocationId));
        assertThatThrownBy(() -> postings.saveAndFlush(posting("Tin thứ hai", workLocationId)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("job_postings_requisition_id_key");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_postings", Integer.class)).isOne();
    }

    private JobPosting posting(String title, UUID workLocation) {
        return new JobPosting(requisitionId, new JobPostingContent(title, JOB_DESCRIPTION, REQUIREMENTS, null,
                workLocation, null, null, false), creatorId, CREATED_AT);
    }

    private UUID account(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id,email,full_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                id, email, "Tài khoản kiểm thử", "unused-password-hash", Timestamp.from(REQUISITION_CREATED_AT));
        return id;
    }

    private UUID catalogValue(String type, String code, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_catalog_items (id,catalog_type,code,name,sort_order,created_at,updated_at)
                VALUES (?,?,?,?,0,?,?)
                """, id, type, code, name, Timestamp.from(REQUISITION_CREATED_AT),
                Timestamp.from(REQUISITION_CREATED_AT));
        return id;
    }
}
