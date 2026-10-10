package vn.ttcs.recruitment.headcount;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

// Task 275: the HR exceptions to the headcount plan (V15). Rows are only added, never changed or removed.
@Repository
public class HeadcountOverrideRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public HeadcountOverrideRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(HeadcountOverride override) {
        jdbc.update("""
                INSERT INTO requisition_headcount_overrides (id, requisition_id, department_id, plan_year,
                    headcount_limit, salary_budget, headcount_used, salary_cost_used, requested_headcount,
                    requested_salary_cost, headcount_exceeded, salary_budget_exceeded, reason, overridden_by,
                    overridden_at)
                VALUES (:id, :requisitionId, :departmentId, :year, :headcountLimit, :salaryBudget, :headcountUsed,
                    :salaryCostUsed, :requestedHeadcount, :requestedSalaryCost, :headcountExceeded,
                    :salaryBudgetExceeded, :reason, :overriddenBy, :overriddenAt)
                """, new MapSqlParameterSource("id", override.id())
                .addValue("requisitionId", override.requisitionId())
                .addValue("departmentId", override.departmentId())
                .addValue("year", override.year())
                .addValue("headcountLimit", override.headcountLimit())
                .addValue("salaryBudget", override.salaryBudget())
                .addValue("headcountUsed", override.headcountUsed())
                .addValue("salaryCostUsed", override.salaryCostUsed())
                .addValue("requestedHeadcount", override.requestedHeadcount())
                .addValue("requestedSalaryCost", override.requestedSalaryCost())
                .addValue("headcountExceeded", override.headcountExceeded())
                .addValue("salaryBudgetExceeded", override.salaryBudgetExceeded())
                .addValue("reason", override.reason())
                .addValue("overriddenBy", override.overriddenBy())
                // OffsetDateTime in UTC is sent as TIMESTAMPTZ, independent of the session or JVM time zone.
                .addValue("overriddenAt", override.overriddenAt().atOffset(ZoneOffset.UTC)));
    }

    // The exceptions of one requisition, newest first; the id keeps the order stable for equal times.
    public List<HeadcountOverride> findByRequisition(UUID requisitionId) {
        return jdbc.query("""
                SELECT * FROM requisition_headcount_overrides
                WHERE requisition_id = :requisitionId
                ORDER BY overridden_at DESC, id DESC
                """, new MapSqlParameterSource("requisitionId", requisitionId), (row, number) -> map(row));
    }

    private static HeadcountOverride map(ResultSet row) throws SQLException {
        return new HeadcountOverride(row.getObject("id", UUID.class), row.getObject("requisition_id", UUID.class),
                row.getObject("department_id", UUID.class), row.getInt("plan_year"), row.getInt("headcount_limit"),
                row.getObject("salary_budget", Long.class), row.getLong("headcount_used"),
                row.getLong("salary_cost_used"), row.getInt("requested_headcount"),
                row.getLong("requested_salary_cost"), row.getBoolean("headcount_exceeded"),
                row.getBoolean("salary_budget_exceeded"), row.getString("reason"),
                row.getObject("overridden_by", UUID.class),
                row.getObject("overridden_at", OffsetDateTime.class).toInstant());
    }
}
