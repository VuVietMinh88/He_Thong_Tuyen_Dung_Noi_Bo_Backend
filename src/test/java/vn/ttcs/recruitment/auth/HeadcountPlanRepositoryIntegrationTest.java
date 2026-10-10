package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.headcount.HeadcountPlan;
import vn.ttcs.recruitment.headcount.HeadcountPlanRepository;
import vn.ttcs.recruitment.headcount.HeadcountUsage;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Task 272: storing headcount plans and summing what the requisitions of a department use in one plan year.
// The business zone is the default Asia/Ho_Chi_Minh (UTC+7), so 2026-12-31T17:00:00Z is already 1 Jan 2027.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "logging.level.io.zonky.test.db.postgres.embedded=warn"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HeadcountPlanRepositoryIntegrationTest {
    private static final Instant CREATED_AT = Instant.parse("2026-10-10T00:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-10-10T08:15:30.123456Z");
    private static final long FIVE_BILLION_VND = 5_000_000_000L;

    @Autowired private HeadcountPlanRepository plans;
    @Autowired private BusinessCalendar calendar;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private TransactionTemplate transactions;

    private UUID hrId;
    private UUID otherHrId;
    private UUID itId;
    private UUID itDevId;
    private UUID salesId;
    private UUID positionId;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String key = Base64.getEncoder().encodeToString(secret);
        registry.add("app.auth.jwt-secret", () -> key);
    }

    @BeforeEach
    void createDepartmentsAndPosition() {
        jdbc.update("DELETE FROM headcount_plans");
        jdbc.update("DELETE FROM recruitment_requisitions");
        jdbc.update("UPDATE departments SET parent_id = NULL");
        jdbc.update("DELETE FROM departments");
        jdbc.update("DELETE FROM positions");
        jdbc.update("DELETE FROM user_accounts");
        hrId = account("hr@example.test");
        otherHrId = account("hr2@example.test");
        itId = department("IT", null);
        // A sub-department has its own plan: its requisitions never count in the plan of IT.
        itDevId = department("IT_DEV", itId);
        salesId = department("SALES", null);
        positionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO positions (id,code,name,level,salary_min,salary_max,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, positionId, "DEV_JUNIOR", "Lập trình viên", "Junior", 15_000_000L, 25_000_000L,
                Timestamp.from(CREATED_AT), Timestamp.from(CREATED_AT));
    }

    @Test
    void insertsFindsAndUpdatesAPlanKeepingItsDepartmentYearAndCreationTime() {
        var plan = new HeadcountPlan(UUID.randomUUID(), itId, 2026, 5, FIVE_BILLION_VND, CREATED_AT, CREATED_AT, hrId);
        plans.insert(plan);

        assertThat(plans.findById(plan.id())).contains(plan);
        assertThat(plans.findByDepartmentAndYear(itId, 2026)).contains(plan);
        assertThat(plans.findByDepartmentAndYear(itId, 2027)).isEmpty();
        assertThat(plans.findByDepartmentAndYear(salesId, 2026)).isEmpty();
        assertThat(plans.findById(UUID.randomUUID())).isEmpty();
        transactions.executeWithoutResult(status -> {
            assertThat(plans.findByIdForUpdate(plan.id())).contains(plan);
            assertThat(plans.findByDepartmentAndYearForUpdate(itId, 2026)).contains(plan);
            assertThat(plans.findByDepartmentAndYearForUpdate(itId, 2027)).isEmpty();
        });

        // Microseconds survive the round trip, and removing the budget stores NULL.
        plans.update(plan.id(), 0, null, UPDATED_AT, otherHrId);
        var updated = new HeadcountPlan(plan.id(), itId, 2026, 0, null, CREATED_AT, UPDATED_AT, otherHrId);
        assertThat(plans.findById(plan.id())).contains(updated);
        assertThat(jdbc.queryForObject("SELECT salary_budget IS NULL FROM headcount_plans WHERE id = ?",
                Boolean.class, plan.id())).isTrue();
    }

    @Test
    void aSecondPlanForTheSameDepartmentAndYearIsRefusedByTheUniqueConstraint() {
        plans.insert(new HeadcountPlan(UUID.randomUUID(), itId, 2026, 5, null, CREATED_AT, CREATED_AT, hrId));

        assertThatThrownBy(() -> plans.insert(
                new HeadcountPlan(UUID.randomUUID(), itId, 2026, 7, null, CREATED_AT, CREATED_AT, hrId)))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("headcount_plans_department_year_key");
        assertThat(jdbc.queryForObject("SELECT headcount_limit FROM headcount_plans", Integer.class)).isEqualTo(5);
    }

    @Test
    void theForUpdateLookupHoldsThePlanRowUntilTheTransactionEnds() throws Exception {
        var plan = new HeadcountPlan(UUID.randomUUID(), itId, 2026, 5, null, CREATED_AT, CREATED_AT, hrId);
        plans.insert(plan);

        transactions.executeWithoutResult(status -> {
            assertThat(plans.findByDepartmentAndYearForUpdate(itId, 2026)).isPresent();
            assertThat(lockedByAnotherTransaction(plan.id())).isTrue();
        });
        assertThat(lockedByAnotherTransaction(plan.id())).isFalse();
        // The plain lookups take no lock.
        transactions.executeWithoutResult(status -> {
            assertThat(plans.findByDepartmentAndYear(itId, 2026)).isPresent();
            assertThat(plans.findById(plan.id())).isPresent();
            assertThat(lockedByAnotherTransaction(plan.id())).isFalse();
        });
    }

    @Test
    void usageIsZeroWithoutRequisitions() {
        assertThat(plans.usage(itId, 2026, null)).isEqualTo(HeadcountUsage.NONE);
    }

    @Test
    void usageSumsHeadcountAndYearlySalaryCostOfTheDepartmentInThePlanYear() {
        // 2 × 25 000 000 × 12 = 600 000 000 (the upper end of the proposal).
        UUID both = requisition(itId, 2, 15_000_000L, 25_000_000L, LocalDate.of(2026, 11, 1), CREATED_AT);
        // 3 × 15 000 000 × 12 = 540 000 000 (only the lower end is filled in).
        requisition(itId, 3, 15_000_000L, null, LocalDate.of(2026, 12, 31), CREATED_AT);
        // No proposed salary yet: the people count, the cost does not.
        requisition(itId, 1, null, null, null, CREATED_AT);
        // Not counted in IT 2026: another department, a sub-department, another year.
        requisition(salesId, 4, null, 30_000_000L, LocalDate.of(2026, 11, 1), CREATED_AT);
        requisition(itDevId, 5, null, 30_000_000L, LocalDate.of(2026, 11, 1), CREATED_AT);
        requisition(itId, 6, null, 30_000_000L, LocalDate.of(2027, 1, 1), CREATED_AT);

        assertThat(plans.usage(itId, 2026, null)).isEqualTo(new HeadcountUsage(6, 1_140_000_000L));
        // Saving one requisition again: it is left out, so its new values can be added instead of its old ones.
        assertThat(plans.usage(itId, 2026, both)).isEqualTo(new HeadcountUsage(4, 540_000_000L));
        assertThat(plans.usage(itId, 2026, UUID.randomUUID())).isEqualTo(new HeadcountUsage(6, 1_140_000_000L));
        assertThat(plans.usage(itId, 2027, null)).isEqualTo(new HeadcountUsage(6, 2_160_000_000L));
        assertThat(plans.usage(itDevId, 2026, null)).isEqualTo(new HeadcountUsage(5, 1_800_000_000L));
        assertThat(plans.usage(salesId, 2027, null)).isEqualTo(HeadcountUsage.NONE);
    }

    @Test
    void theNeededByDateDecidesTheYearAndOtherwiseTheBusinessDateOfCreation() {
        // Created in October 2026 for January 2027: counted in 2027 only.
        requisition(itId, 1, null, null, LocalDate.of(2027, 1, 1), CREATED_AT);
        // Created in 2025 for 2026: counted in 2026.
        requisition(itId, 2, null, null, LocalDate.of(2026, 1, 1), Instant.parse("2025-06-01T00:00:00Z"));
        // No date: 23:59:59 on 31 Dec 2026 in Vietnam is still 2026.
        requisition(itId, 4, null, null, null, Instant.parse("2026-12-31T16:59:59Z"));
        // No date: 00:00 on 1 Jan 2027 in Vietnam, while UTC is still on 31 Dec 2026.
        requisition(itId, 8, null, null, null, Instant.parse("2026-12-31T17:00:00Z"));
        // No date: 00:00 on 1 Jan 2026 in Vietnam, while UTC is still in 2025.
        requisition(itId, 16, null, null, null, Instant.parse("2025-12-31T17:00:00Z"));
        // No date: 23:59:59 on 31 Dec 2025 in Vietnam.
        requisition(itId, 32, null, null, null, Instant.parse("2025-12-31T16:59:59Z"));

        assertThat(plans.usage(itId, 2025, null).headcount()).isEqualTo(32);
        assertThat(plans.usage(itId, 2026, null).headcount()).isEqualTo(2 + 4 + 16);
        assertThat(plans.usage(itId, 2027, null).headcount()).isEqualTo(1 + 8);

        // The SQL and the Java rule (used for the requisition being saved) put every row in the same year.
        var expected = new long[3];
        for (var row : jdbc.queryForList("SELECT headcount, needed_by, created_at FROM recruitment_requisitions")) {
            LocalDate neededBy = row.get("needed_by") == null ? null : ((Date) row.get("needed_by")).toLocalDate();
            LocalDate createdOn = calendar.dateOf(((Timestamp) row.get("created_at")).toInstant());
            expected[HeadcountUsage.planYear(neededBy, createdOn) - 2025] += (Integer) row.get("headcount");
        }
        for (int year = 2025; year <= 2027; year++) {
            assertThat(plans.usage(itId, year, null).headcount()).as("year " + year).isEqualTo(expected[year - 2025]);
        }
    }

    @Test
    void theCostOfRowsWrittenOutsideTheApiStopsAtTheLargestBigint() {
        requisition(itId, Integer.MAX_VALUE, null, Long.MAX_VALUE, LocalDate.of(2026, 5, 1), CREATED_AT);
        requisition(itId, Integer.MAX_VALUE, null, Long.MAX_VALUE, LocalDate.of(2026, 5, 1), CREATED_AT);

        var usage = plans.usage(itId, 2026, null);
        assertThat(usage.headcount()).isEqualTo(2L * Integer.MAX_VALUE);
        assertThat(usage.salaryCost()).isEqualTo(Long.MAX_VALUE);
    }

    // A new RequisitionStatus makes this switch fail to compile until someone decides whether its people still count
    // against the plan (HeadcountPlanRepository.COUNTED_REQUISITION_STATUSES).
    @ParameterizedTest
    @EnumSource(RequisitionStatus.class)
    void everyRequisitionStatusIsCountedOrNotAccordingToItsGroup(RequisitionStatus status) {
        boolean counted = switch (status) {
            case DRAFT -> true;
        };
        UUID id = requisition(itId, 3, null, 20_000_000L, LocalDate.of(2026, 6, 1), CREATED_AT);
        jdbc.update("UPDATE recruitment_requisitions SET status = ? WHERE id = ?", status.name(), id);

        assertThat(plans.usage(itId, 2026, null))
                .isEqualTo(counted ? new HeadcountUsage(3, 720_000_000L) : HeadcountUsage.NONE);
    }

    // Tries to lock the plan row from a separate connection without waiting.
    private boolean lockedByAnotherTransaction(UUID planId) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(
                    "SELECT id FROM headcount_plans WHERE id = ? FOR UPDATE NOWAIT")) {
                statement.setObject(1, planId);
                statement.executeQuery().close();
                return false;
            } catch (SQLException exception) {
                assertThat(exception.getSQLState()).isEqualTo("55P03");
                return true;
            } finally {
                connection.rollback();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private UUID account(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts (id,email,full_name,password_hash,created_at) VALUES (?,?,?,?,?)",
                id, email, "Trưởng phòng Nhân sự", "unused-password-hash", Timestamp.from(CREATED_AT));
        return id;
    }

    private UUID department(String code, UUID parent) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id,code,name,parent_id,manager_user_id) VALUES (?,?,?,?,?)",
                id, code, "Phòng " + code, parent, hrId);
        return id;
    }

    private UUID requisition(UUID department, int headcount, Long min, Long max, LocalDate neededBy, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_requisitions (id,position_id,department_id,headcount,reason,
                    proposed_salary_min,proposed_salary_max,needed_by,created_by,created_at,updated_at)
                VALUES (?,?,?,?,'NEW_HEADCOUNT',?,?,?,?,?,?)
                """, id, positionId, department, headcount, min, max, neededBy, hrId,
                Timestamp.from(createdAt), Timestamp.from(createdAt));
        return id;
    }
}
