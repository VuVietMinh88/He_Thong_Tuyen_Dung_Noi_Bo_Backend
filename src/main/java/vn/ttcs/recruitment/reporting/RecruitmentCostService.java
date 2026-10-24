package vn.ttcs.recruitment.reporting;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionService;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Task 573 (story S8-08): recruitment costs entered by hand: job advertising and referral bonuses, per requisition and
 * date. Writers need REPORTS write plus REPORTS and REQUISITIONS read (seeded: HR_MANAGER and ADMIN, ALL); a SCOPED
 * writer could only write for requisitions in its report scope. Every write follows the house lock order (the
 * caller's account, then its session, then the requisition FOR SHARE, which every change of the requisition's team or
 * department takes in a conflicting mode, then the cost row) and checks the session and the grants again after the
 * locks. Entries are never edited nor deleted (V67_1 triggers); a wrong entry is voided with a reason and entered again.
 * Costs belong only to a requisition that was finally approved once (its workflow history holds the APPROVED event
 * that made it APPROVED), whatever its status now: a requisition cancelled before approval never had a posting. A write
 * that runs out of time (lock wait, statement or transaction timeout) answers 503 COST_BUSY.
 */
@Service
public class RecruitmentCostService {
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final ReportingAccess access;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final PermissionService permissions;
    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcTemplate plain;
    private final BusinessCalendar calendar;
    private final Clock clock;
    private final TransactionTemplate writes;

    public RecruitmentCostService(ReportingAccess access, AccountRepository accounts, AuthSessionRepository sessions,
                                  PermissionService permissions, NamedParameterJdbcTemplate jdbc, JdbcTemplate plain,
                                  BusinessCalendar calendar, Clock clock, PlatformTransactionManager transactions) {
        this.access = access;
        this.accounts = accounts;
        this.sessions = sessions;
        this.permissions = permissions;
        this.jdbc = jdbc;
        this.plain = plain;
        this.calendar = calendar;
        this.clock = clock;
        writes = new TransactionTemplate(transactions);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(5);
    }

    public RecruitmentCostView record(Jwt jwt, RecruitmentCostRequests.Create request) {
        return guarded(() -> writes.execute(status -> {
            ReportViewer writer = lockWriter(jwt);
            if (request.incurredOn().isBefore(ReportPeriod.EARLIEST) || request.incurredOn().isAfter(calendar.today())) {
                throw ReportPaging.invalid(Map.of("incurredOn",
                        "Ngày phát sinh phải từ 2000-01-01 đến hôm nay, không được ở tương lai."));
            }
            lockRequisition(writer, request.requisitionId());
            if (!finallyApproved(request.requisitionId())) {
                throw new ApiException(HttpStatus.CONFLICT, "COST_REQUISITION_NOT_APPROVED",
                        "Chỉ ghi chi phí cho yêu cầu tuyển dụng đã được phê duyệt.");
            }
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO recruitment_costs(id, requisition_id, cost_type, amount_vnd, incurred_on, description,
                        recorded_by, recorded_at)
                    VALUES (:id, :requisitionId, :costType, :amount, :incurredOn, :description, :writer, :at)
                    """, new MapSqlParameterSource("id", id).addValue("requisitionId", request.requisitionId())
                    .addValue("costType", request.costType()).addValue("amount", request.amountVnd())
                    .addValue("incurredOn", Date.valueOf(request.incurredOn())).addValue("description", request.description())
                    .addValue("writer", writer.id()).addValue("at", Timestamp.from(now())));
            return find(id);
        }));
    }

    public RecruitmentCostView voidCost(Jwt jwt, UUID costId, RecruitmentCostRequests.VoidCost request) {
        return guarded(() -> writes.execute(status -> {
            ReportViewer writer = lockWriter(jwt);
            UUID requisitionId = jdbc.query("SELECT requisition_id FROM recruitment_costs WHERE id = :id",
                    new MapSqlParameterSource("id", costId), (row, number) -> row.getObject("requisition_id", UUID.class))
                    .stream().findFirst().orElseThrow(RecruitmentCostService::notFound);
            lockRequisition(writer, requisitionId);
            Boolean voided = jdbc.query("SELECT voided_at IS NOT NULL AS voided FROM recruitment_costs WHERE id = :id FOR UPDATE",
                    new MapSqlParameterSource("id", costId), (row, number) -> row.getBoolean("voided"))
                    .stream().findFirst().orElseThrow(RecruitmentCostService::notFound);
            if (voided) {
                throw new ApiException(HttpStatus.CONFLICT, "COST_ALREADY_VOIDED", "Khoản chi phí này đã bị hủy trước đó.");
            }
            jdbc.update("UPDATE recruitment_costs SET voided_at = :at, voided_by = :writer, void_reason = :reason WHERE id = :id",
                    new MapSqlParameterSource("id", costId).addValue("at", Timestamp.from(now()))
                            .addValue("writer", writer.id()).addValue("reason", request.reason()));
            return find(costId);
        }));
    }

    public RecruitmentCostPage list(Jwt jwt, UUID requisitionId, UUID departmentId, UUID positionId, LocalDate from,
                                    LocalDate to, String costType, Boolean includeVoided, Integer page, Integer size) {
        ReportPaging paging = ReportPaging.of(page, size, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        ReportPeriod period = ReportPeriod.explicit(from, to);
        if (costType != null && !costType.equals("ADVERTISING") && !costType.equals("REFERRAL_BONUS")) {
            throw ReportPaging.invalid(Map.of("costType", "Loại chi phí phải là ADVERTISING hoặc REFERRAL_BONUS."));
        }
        ReportFilters filters = new ReportFilters(requisitionId, departmentId, positionId);
        boolean withVoided = Boolean.TRUE.equals(includeVoided);
        return access.read(jwt, viewer -> {
            access.requireFilters(viewer, filters);
            ReportQuery query = ReportQuery.of(viewer, filters);
            StringBuilder where = new StringBuilder(" WHERE ").append(query.rows());
            if (period != null) {
                where.append(" AND c.incurred_on BETWEEN :fromDate AND :toDate");
                query.with("fromDate", Date.valueOf(period.from())).with("toDate", Date.valueOf(period.to()));
            }
            if (costType != null) {
                where.append(" AND c.cost_type = :costType");
                query.with("costType", costType);
            }
            String active = where + " AND c.voided_at IS NULL";
            String listed = withVoided ? where.toString() : active;
            var totals = jdbc.queryForObject("SELECT count(*) AS entries, coalesce(sum(c.amount_vnd), 0) AS total"
                            + FROM + active, query.parameters(),
                    (row, number) -> new long[] {row.getLong("entries"), row.getLong("total")});
            long listedCount = Objects.requireNonNull(jdbc.queryForObject("SELECT count(*)" + FROM + listed,
                    query.parameters(), Long.class));
            query.with("limit", paging.size()).with("offset", paging.offset());
            List<RecruitmentCostView> items = jdbc.query(SELECT + FROM + listed
                    + " ORDER BY c.incurred_on DESC, c.recorded_at DESC, c.id LIMIT :limit OFFSET :offset",
                    query.parameters(), (row, number) -> view(row));
            return new RecruitmentCostPage(viewer.scopeName(), filters, period == null ? null : period.from(),
                    period == null ? null : period.to(), costType, withVoided, totals[0], totals[1], items,
                    paging.page(), paging.size(), listedCount, paging.totalPages(listedCount));
        });
    }

    private static RecruitmentCostView guarded(Supplier<RecruitmentCostView> write) {
        try {
            return Objects.requireNonNull(write.get());
        } catch (RuntimeException failure) {
            if (ReportingBusy.busy(failure)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "COST_BUSY",
                        "Sổ chi phí đang được cập nhật. Vui lòng thử lại sau ít giây.");
            }
            throw failure;
        }
    }

    // The final approval: the APPROVED event that made the requisition APPROVED (an intermediate level leaves it
    // PENDING_APPROVAL; RESUMED after a pause is not an approval). requisition_workflow_events is append-only.
    private boolean finallyApproved(UUID requisitionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM requisition_workflow_events
                               WHERE requisition_id = :id AND event_type = 'APPROVED' AND status_after = 'APPROVED')
                """, new MapSqlParameterSource("id", requisitionId), Boolean.class));
    }

    // The house write order: the account row, then the session row, then a fresh check of token, session and grants.
    private ReportViewer lockWriter(Jwt jwt) {
        plain.execute("SET LOCAL lock_timeout = '2s'");
        plain.execute("SET LOCAL statement_timeout = '3s'");
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant())) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        UUID userId;
        UUID sessionId;
        try {
            userId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getId());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        Account account = accounts.findByIdForUpdate(userId).filter(Account::isAccessAllowed)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        sessions.findByIdForUpdate(sessionId)
                .filter(value -> value.getUserId().equals(userId) && value.isActive(clock.instant()))
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        ReportViewer writer = ReportViewer.of(account.getId(), permissions.forUser(account.getId()));
        if (writer.reportsWrite() == AccessScope.NONE || writer.reports() == AccessScope.NONE
                || writer.requisitions() == AccessScope.NONE) {
            throw new AccessDeniedException("Recording a cost needs REPORTS write and REPORTS, REQUISITIONS read");
        }
        return writer;
    }

    // 404 for an unknown requisition, then 403 outside the writer's scope, checked while the row is locked.
    private void lockRequisition(ReportViewer writer, UUID requisitionId) {
        jdbc.query("SELECT id FROM recruitment_requisitions WHERE id = :id FOR SHARE",
                new MapSqlParameterSource("id", requisitionId), (row, number) -> row.getObject("id", UUID.class))
                .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REQUISITION_NOT_FOUND",
                        "Không tìm thấy yêu cầu tuyển dụng."));
        boolean unrestricted = writer.reportsWrite() == AccessScope.ALL && writer.reports() == AccessScope.ALL
                && writer.requisitions() == AccessScope.ALL;
        if (!unrestricted && !Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM"
                        + " recruitment_requisitions r WHERE r.id = :target AND " + ReportingAccess.SCOPED_ROWS + ")",
                ReportingAccess.parameters(writer).addValue("target", requisitionId), Boolean.class))) {
            throw new AccessDeniedException("The requisition is outside the writer's report scope");
        }
    }

    private RecruitmentCostView find(UUID id) {
        return jdbc.queryForObject(SELECT + FROM + " WHERE c.id = :id", new MapSqlParameterSource("id", id),
                (row, number) -> view(row));
    }

    private static final String SELECT = """
            SELECT c.id, c.requisition_id, r.position_id, p.name AS position_name, r.department_id,
                   d.name AS department_name, c.cost_type, c.amount_vnd, c.incurred_on, c.description,
                   c.recorded_by, recorder.full_name AS recorded_by_name, c.recorded_at,
                   c.voided_at, c.voided_by, voider.full_name AS voided_by_name, c.void_reason
            """;

    private static final String FROM = """
             FROM recruitment_costs c
             JOIN recruitment_requisitions r ON r.id = c.requisition_id
             JOIN positions p ON p.id = r.position_id
             JOIN departments d ON d.id = r.department_id
             JOIN user_accounts recorder ON recorder.id = c.recorded_by
             LEFT JOIN user_accounts voider ON voider.id = c.voided_by
            """;

    private static RecruitmentCostView view(ResultSet row) throws SQLException {
        Timestamp voidedAt = row.getTimestamp("voided_at");
        return new RecruitmentCostView(row.getObject("id", UUID.class), row.getObject("requisition_id", UUID.class),
                row.getObject("position_id", UUID.class), row.getString("position_name"),
                row.getObject("department_id", UUID.class), row.getString("department_name"), row.getString("cost_type"),
                row.getLong("amount_vnd"), row.getDate("incurred_on").toLocalDate(), row.getString("description"),
                row.getObject("recorded_by", UUID.class), row.getString("recorded_by_name"),
                row.getTimestamp("recorded_at").toInstant(), voidedAt != null,
                voidedAt == null ? null : voidedAt.toInstant(), row.getObject("voided_by", UUID.class),
                row.getString("voided_by_name"), row.getString("void_reason"));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "COST_NOT_FOUND", "Không tìm thấy khoản chi phí.");
    }
}
