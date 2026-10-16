package vn.ttcs.recruitment.competency;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

// Jira 215: the evaluation criteria of a position, i.e. the criteria and weights of the competency framework the
// position uses (Jira 214). Sprint 6 generates the interview evaluation forms from them.
// - get(): GET /api/v1/positions/{id}/evaluation-criteria. Needs ORGANIZATION_READ_ALL, which every internal role
//   has, interviewers included.
// - forPosition(): for other server-side services, such as the later evaluation form module. There is no Jwt here,
//   so it checks no permission: the calling service checks its own module permission first.
// Neither ever returns the salary band of the position.
@Service
public class EvaluationCriteriaService {
    // Plain SQL, like CompetencyFrameworkService.positionsUsing: this package does not depend on the position package.
    // It also always reads the database, never an older entity the calling service has already loaded.
    private static final String POSITION_QUERY =
            "SELECT id, code, name, level, active, competency_framework_id FROM positions WHERE id = ?";
    private static final String FRAMEWORK_QUERY =
            "SELECT id, code, name, status FROM competency_frameworks WHERE id = ?";
    private static final String CRITERIA_QUERY = """
            SELECT id, name, description, weight, sort_order FROM competency_criteria
            WHERE framework_id = ?
            ORDER BY sort_order
            """;

    private final JdbcTemplate jdbc;
    private final AuthService auth;
    private final PermissionService permissions;
    private final Clock clock;

    public EvaluationCriteriaService(JdbcTemplate jdbc, AuthService auth, PermissionService permissions, Clock clock) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.permissions = permissions;
        this.clock = clock;
    }

    // REPEATABLE_READ: the position, its framework and the criteria come from the same snapshot, never from the
    // middle of a framework edit. The transaction is read only, so forPosition() takes no lock and never waits.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EvaluationCriteria get(Jwt jwt, UUID positionId) {
        requireReadAccess(jwt);
        // A call inside the same class skips Spring's proxy, so forPosition() simply runs in this transaction.
        return forPosition(positionId);
    }

    // Checks in order: the position exists (404 POSITION_NOT_FOUND), it has a framework
    // (409 POSITION_COMPETENCY_FRAMEWORK_NOT_ASSIGNED), the framework is ACTIVE (409 COMPETENCY_FRAMEWORK_NOT_ACTIVE).
    // A position HR stopped using (active=false) still has its criteria; position.active tells the caller.
    //
    // Locks, the same way as SalaryBandService:
    // - Inside the caller's write transaction both rows are read with FOR SHARE and stay locked until that
    //   transaction ends. Changing the framework of the position (PositionService locks the position FOR UPDATE)
    //   and editing the framework (CompetencyFrameworkService locks it FOR UPDATE) wait until then, so what this
    //   method returned is still true when the caller commits, e.g. an evaluation form saved with these criterion
    //   ids. Readers do not wait for each other. If an edit holds a row, this method waits and then reads the
    //   committed values; that needs the caller's write transaction to use the default READ COMMITTED isolation
    //   (under REPEATABLE_READ or SERIALIZABLE PostgreSQL fails with SQLSTATE 40001 instead).
    // - The position is locked before the framework, the same order as PositionService.useCompetencyFramework, so
    //   the two never wait for each other in a circle (deadlock).
    // - PostgreSQL refuses FOR SHARE in a READ ONLY transaction. Such a caller saves nothing, so it reads unlocked.
    // - Called without a transaction, @Transactional starts one, so the three reads still belong together.
    @Transactional
    public EvaluationCriteria forPosition(UUID positionId) {
        Objects.requireNonNull(positionId, "positionId");
        String lock = TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? "" : " FOR SHARE";

        StoredPosition position = jdbc.query(POSITION_QUERY + lock, (row, number) -> new StoredPosition(
                        new CompetencyFrameworkPositionView(row.getObject("id", UUID.class), row.getString("code"),
                                row.getString("name"), row.getString("level"), row.getBoolean("active")),
                        row.getObject("competency_framework_id", UUID.class)), positionId)
                .stream().findFirst().orElseThrow(EvaluationCriteriaService::positionNotFound);
        if (position.frameworkId() == null) {
            throw frameworkNotAssigned();
        }

        // The foreign key keeps the framework of a position, so this row always exists.
        StoredFramework framework = jdbc.queryForObject(FRAMEWORK_QUERY + lock, (row, number) -> new StoredFramework(
                new EvaluationCriteria.Framework(row.getObject("id", UUID.class), row.getString("code"),
                        row.getString("name")),
                CompetencyFrameworkStatus.valueOf(row.getString("status"))), position.frameworkId());
        // Only an ACTIVE framework can be assigned and it never goes back to DRAFT (Jira 213, 214), so this only
        // fails after a direct SQL change. Checked anyway: a form must never be scored with weights that may not
        // total 100%.
        if (framework.status() != CompetencyFrameworkStatus.ACTIVE) {
            throw frameworkNotActive();
        }

        // NUMERIC(5,2) comes back with two decimals, e.g. 40.00, like the other competency responses.
        List<CompetencyCriterionView> criteria = jdbc.query(CRITERIA_QUERY, (row, number) ->
                new CompetencyCriterionView(row.getObject("id", UUID.class), row.getString("name"),
                        row.getString("description"), row.getBigDecimal("weight"), row.getInt("sort_order")),
                position.frameworkId());
        return new EvaluationCriteria(position.view(), framework.view(), criteria);
    }

    private void requireReadAccess(Jwt jwt) {
        requireUnexpiredToken(jwt, clock.instant());
        Account actor = auth.requireActiveAccount(jwt);
        if (!permissions.forUser(actor.getId()).contains("ORGANIZATION_READ_ALL")) {
            throw new AccessDeniedException("Evaluation criteria access requires ORGANIZATION_READ_ALL");
        }
    }

    private void requireUnexpiredToken(Jwt jwt, Instant now) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
    }

    // Same error as the position API, so a client handles an unknown position the same way everywhere.
    private static ApiException positionNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "POSITION_NOT_FOUND", "Không tìm thấy chức danh.");
    }

    // 409, not 404: the position exists, HR has only not chosen its competency framework yet.
    private static ApiException frameworkNotAssigned() {
        return new ApiException(HttpStatus.CONFLICT, "POSITION_COMPETENCY_FRAMEWORK_NOT_ASSIGNED",
                "Chức danh chưa được gán khung năng lực nên chưa có tiêu chí đánh giá.");
    }

    private static ApiException frameworkNotActive() {
        return new ApiException(HttpStatus.CONFLICT, "COMPETENCY_FRAMEWORK_NOT_ACTIVE",
                "Khung năng lực của chức danh chưa hoàn chỉnh (ACTIVE) nên chưa dùng để đánh giá được.");
    }

    private record StoredPosition(CompetencyFrameworkPositionView view, UUID frameworkId) { }

    private record StoredFramework(EvaluationCriteria.Framework view, CompetencyFrameworkStatus status) { }
}
