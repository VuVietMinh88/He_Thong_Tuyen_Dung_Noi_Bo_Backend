package vn.ttcs.recruitment.headcount;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import vn.ttcs.recruitment.common.BusinessCalendar;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Task 272: headcount plans (V14) and what requisitions already use of them.
@Repository
public class HeadcountPlanRepository {
    // Requisition statuses whose people still count against the plan of their department and year. V13 only
    // allows DRAFT, and a draft already reserves its people: task 274 checks the plan when a requisition is saved.
    // When the approval workflow or closing a requisition (story S3-07) adds statuses, list here every status whose
    // people are still needed (for example approved) and leave out those that give them back (rejected, cancelled).
    // RequisitionStatus says so too; HeadcountPlanRepositoryIntegrationTest does not compile until a new status is
    // given its group there.
    static final List<String> COUNTED_REQUISITION_STATUSES = List.of("DRAFT");

    private static final String SELECT = """
            SELECT id, department_id, plan_year, headcount_limit, salary_budget, created_at, updated_at, updated_by
            FROM headcount_plans
            """;

    // Task 273: lists show the newest year first, then the departments by code.
    private static final String WITH_DEPARTMENT = """
            SELECT p.id, p.department_id, p.plan_year, p.headcount_limit, p.salary_budget, p.created_at,
                   p.updated_at, p.updated_by, d.code AS department_code, d.name AS department_name
            FROM headcount_plans p JOIN departments d ON d.id = p.department_id
            """;
    private static final String NEWEST_YEAR_FIRST = " ORDER BY p.plan_year DESC, d.code, p.id ";

    // Task 273: a plan with the code and name of its department, as lists and details show it.
    public record PlanWithDepartment(HeadcountPlan plan, String departmentCode, String departmentName) { }

    private final NamedParameterJdbcTemplate jdbc;
    private final BusinessCalendar calendar;

    public HeadcountPlanRepository(NamedParameterJdbcTemplate jdbc, BusinessCalendar calendar) {
        this.jdbc = jdbc;
        this.calendar = calendar;
    }

    public Optional<HeadcountPlan> findById(UUID id) {
        return one(SELECT + " WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    // Holds the plan row until commit, so two changes of the same plan run one after the other.
    public Optional<HeadcountPlan> findByIdForUpdate(UUID id) {
        return one(SELECT + " WHERE id = :id FOR UPDATE", new MapSqlParameterSource("id", id));
    }

    public Optional<HeadcountPlan> findByDepartmentAndYear(UUID departmentId, int year) {
        return one(SELECT + " WHERE department_id = :departmentId AND plan_year = :year",
                departmentAndYear(departmentId, year));
    }

    // Holds the plan row until commit. Every save that is checked against this plan takes this lock first, so two
    // requisitions saved at the same time for the same department and year are counted one after the other.
    // Must be called in a read-write transaction (PostgreSQL refuses FOR UPDATE in a read-only one).
    public Optional<HeadcountPlan> findByDepartmentAndYearForUpdate(UUID departmentId, int year) {
        return one(SELECT + " WHERE department_id = :departmentId AND plan_year = :year FOR UPDATE",
                departmentAndYear(departmentId, year));
    }

    public Optional<PlanWithDepartment> findWithDepartment(UUID id) {
        return jdbc.query(WITH_DEPARTMENT + " WHERE p.id = :id", new MapSqlParameterSource("id", id),
                (row, number) -> mapWithDepartment(row)).stream().findFirst();
    }

    // Task 273: one page of plans, optionally only one year and/or one department. Returns the page and the total.
    public PlanSearch search(Integer year, UUID departmentId, int page, int size) {
        var parameters = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1 ");
        if (year != null) {
            parameters.addValue("year", year);
            where.append(" AND p.plan_year = :year ");
        }
        if (departmentId != null) {
            parameters.addValue("departmentId", departmentId);
            where.append(" AND p.department_id = :departmentId ");
        }
        long total = jdbc.queryForObject("""
                SELECT count(*) FROM headcount_plans p JOIN departments d ON d.id = p.department_id
                """ + where, parameters, Long.class);
        parameters.addValue("size", size).addValue("offset", (long) page * size);
        var items = jdbc.query(WITH_DEPARTMENT + where + NEWEST_YEAR_FIRST + " LIMIT :size OFFSET :offset",
                parameters, (row, number) -> mapWithDepartment(row));
        return new PlanSearch(items, total);
    }

    public record PlanSearch(List<PlanWithDepartment> items, long total) { }

    // headcount_plans_department_year_key rejects a second plan for the same department and year (SQLState 23505).
    public void insert(HeadcountPlan plan) {
        jdbc.update("""
                INSERT INTO headcount_plans (id, department_id, plan_year, headcount_limit, salary_budget,
                    created_at, updated_at, updated_by)
                VALUES (:id, :departmentId, :year, :headcountLimit, :salaryBudget, :createdAt, :updatedAt, :updatedBy)
                """, new MapSqlParameterSource("id", plan.id())
                .addValue("departmentId", plan.departmentId())
                .addValue("year", plan.year())
                .addValue("headcountLimit", plan.headcountLimit())
                .addValue("salaryBudget", plan.salaryBudget())
                .addValue("createdAt", timestamp(plan.createdAt()))
                .addValue("updatedAt", timestamp(plan.updatedAt()))
                .addValue("updatedBy", plan.updatedBy()));
    }

    // The department and the year of a plan never change; HR declares another plan for another year.
    public void update(UUID id, int headcountLimit, Long salaryBudget, Instant updatedAt, UUID updatedBy) {
        jdbc.update("""
                UPDATE headcount_plans
                SET headcount_limit = :headcountLimit, salary_budget = :salaryBudget,
                    updated_at = :updatedAt, updated_by = :updatedBy
                WHERE id = :id
                """, new MapSqlParameterSource("id", id)
                .addValue("headcountLimit", headcountLimit)
                .addValue("salaryBudget", salaryBudget)
                .addValue("updatedAt", timestamp(updatedAt))
                .addValue("updatedBy", updatedBy));
    }

    /**
     * What the saved requisitions of this department use in this plan year, with the rules of {@link HeadcountUsage}:
     * only requisitions of this very department (not of its sub-departments, which have their own plans), in a
     * counted status, whose needed-by date falls in the year, or which have no date and were created in the year
     * (business date). excludedRequisitionId leaves one requisition out (the one being saved again), or null.
     */
    public HeadcountUsage usage(UUID departmentId, int year, UUID excludedRequisitionId) {
        LocalDate firstDay = LocalDate.of(year, 1, 1);
        LocalDate nextFirstDay = firstDay.plusYears(1);
        var parameters = departmentAndYear(departmentId, year)
                .addValue("statuses", COUNTED_REQUISITION_STATUSES)
                .addValue("firstDay", firstDay)
                .addValue("nextFirstDay", nextFirstDay)
                // The year boundaries as moments, so PostgreSQL never has to know the business time zone.
                .addValue("yearStart", timestamp(calendar.startOf(firstDay)))
                .addValue("nextYearStart", timestamp(calendar.startOf(nextFirstDay)))
                .addValue("months", HeadcountUsage.MONTHS_PER_YEAR);
        String exclusion = "";
        if (excludedRequisitionId != null) {
            parameters.addValue("excluded", excludedRequisitionId);
            exclusion = " AND r.id <> :excluded ";
        }
        // numeric keeps the sum exact; like HeadcountUsage.salaryCost it stops at the largest BIGINT.
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(r.headcount), 0) AS headcount,
                       LEAST(COALESCE(SUM(r.headcount::numeric
                                 * COALESCE(r.proposed_salary_max, r.proposed_salary_min, 0)), 0) * :months,
                             9223372036854775807)::bigint AS salary_cost
                FROM recruitment_requisitions r
                WHERE r.department_id = :departmentId
                  AND r.status IN (:statuses)
                  AND ((r.needed_by >= :firstDay AND r.needed_by < :nextFirstDay)
                       OR (r.needed_by IS NULL AND r.created_at >= :yearStart AND r.created_at < :nextYearStart))
                """ + exclusion, parameters,
                (row, number) -> new HeadcountUsage(row.getLong("headcount"), row.getLong("salary_cost")));
    }

    private Optional<HeadcountPlan> one(String sql, MapSqlParameterSource parameters) {
        return jdbc.query(sql, parameters, (row, number) -> map(row)).stream().findFirst();
    }

    private static MapSqlParameterSource departmentAndYear(UUID departmentId, int year) {
        return new MapSqlParameterSource("departmentId", departmentId).addValue("year", year);
    }

    // OffsetDateTime in UTC is sent as TIMESTAMPTZ, independent of the session or JVM time zone.
    private static OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static PlanWithDepartment mapWithDepartment(ResultSet row) throws SQLException {
        return new PlanWithDepartment(map(row), row.getString("department_code"), row.getString("department_name"));
    }

    private static HeadcountPlan map(ResultSet row) throws SQLException {
        Long budget = row.getObject("salary_budget", Long.class);
        return new HeadcountPlan(row.getObject("id", UUID.class), row.getObject("department_id", UUID.class),
                row.getInt("plan_year"), row.getInt("headcount_limit"), budget,
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_by", UUID.class));
    }
}
