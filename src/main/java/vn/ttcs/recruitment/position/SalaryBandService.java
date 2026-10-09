package vn.ttcs.recruitment.position;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.ttcs.recruitment.common.ApiException;

import java.util.Objects;
import java.util.UUID;

/**
 * Jira 206: gives other server-side services (later offer approval and requisition checks) the standard salary
 * band of a position. There is no endpoint and no Jwt here, so this class checks no permission: each calling
 * service checks its own module permission first and returns salaryMin/salaryMax only to users with
 * SALARY_RANGES_READ_ALL.
 */
@Service
public class SalaryBandService {
    private static final String BAND_QUERY = "SELECT active, salary_min, salary_max FROM positions WHERE id = ?";

    private final JdbcTemplate jdbc;

    public SalaryBandService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The band of an active position. Unknown positions fail with 404 POSITION_NOT_FOUND. Positions HR has
     * stopped using (active=false) fail with 409 POSITION_INACTIVE: their band is no longer a company-approved
     * limit, so no new or pending offer is checked against it until HR turns the position back on.
     */
    // Deliberately not @Transactional, so the query joins the caller's transaction. There, FOR SHARE keeps the row
    // locked until that transaction ends: PositionService.update (FOR UPDATE) waits, so the band cannot change or
    // be deactivated between the check and the caller's commit, while other band checks still run in parallel.
    // If an update holds the row, this query waits and then reads the committed values. That needs the caller's
    // write transaction to use the default READ COMMITTED isolation: under REPEATABLE_READ or SERIALIZABLE,
    // PostgreSQL rejects FOR SHARE on a row changed after the caller's snapshot with a serialization failure
    // (SQLSTATE 40001) instead of returning the new values. Called without a transaction, it is a plain read that
    // keeps no lock.
    // PostgreSQL refuses FOR SHARE in a READ ONLY transaction. Such a caller saves nothing based on the check,
    // so it reads without the lock.
    // Plain SQL instead of JPA, so the values never come from an older Position already loaded by the caller.
    public SalaryBand standardBand(UUID positionId) {
        Objects.requireNonNull(positionId, "positionId");
        String sql = TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ? BAND_QUERY : BAND_QUERY + " FOR SHARE";
        var rows = jdbc.query(sql, (row, number) -> new StoredBand(row.getBoolean("active"),
                row.getLong("salary_min"), row.getLong("salary_max")), positionId);
        if (rows.isEmpty()) {
            throw PositionService.notFound();
        }
        StoredBand stored = rows.getFirst();
        if (!stored.active()) {
            throw inactive();
        }
        return new SalaryBand(positionId, stored.salaryMin(), stored.salaryMax());
    }

    /** For checks that only need BELOW/WITHIN/ABOVE, so the caller never handles the salary amounts. */
    public SalaryBandComparison compare(UUID positionId, long proposedSalary) {
        return standardBand(positionId).compare(proposedSalary);
    }

    private static ApiException inactive() {
        return new ApiException(HttpStatus.CONFLICT, "POSITION_INACTIVE",
                "Chức danh đã ngừng áp dụng nên không dùng dải lương của chức danh này để kiểm tra.");
    }

    private record StoredBand(boolean active, long salaryMin, long salaryMax) { }
}
