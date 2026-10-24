package vn.ttcs.recruitment.reporting;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.department.DepartmentRepository;
import vn.ttcs.recruitment.requisition.RecruiterAssignmentAccess;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Stories 81-85: the one authorization and row-scope rule of every report.
 * <p>
 * requireReader: a valid unexpired token of an active session and account (401), then the grants read again from the
 * database (403 without a read grant of REPORTS, REQUISITIONS and CANDIDATES). Reports run in one REPEATABLE READ
 * snapshot, so the grants, the assignments, the department tree and the numbers all come from the same moment.
 * <p>
 * SCOPE is a constant SQL fragment over the alias r (recruitment_requisitions): unrestricted viewers see every row; the
 * others see the requisitions they are now the primary or a supporting recruiter of (RecruiterAssignmentAccess) and the
 * requisitions of the departments they manage, with every department below them (DepartmentRepository). The creator of
 * a requisition, the assignment history and the approval history never count, so a handover changes the next report.
 */
@Component
public class ReportingAccess {
    static final String UNRESTRICTED = "reportUnrestricted";
    static final String INTERVIEWS_UNRESTRICTED = "reportInterviewsUnrestricted";
    static final String OFFERS_UNRESTRICTED = "reportOffersUnrestricted";

    static final String SCOPE = scope(UNRESTRICTED);
    static final String INTERVIEW_SCOPE = scope(INTERVIEWS_UNRESTRICTED);
    static final String OFFER_SCOPE = scope(OFFERS_UNRESTRICTED);

    private final AuthService auth;
    private final PermissionService permissions;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final Clock clock;
    private final TransactionTemplate reads;
    private final TransactionTemplate auditedReads;

    public ReportingAccess(AuthService auth, PermissionService permissions, JdbcTemplate jdbc,
                           NamedParameterJdbcTemplate named, Clock clock, PlatformTransactionManager transactions) {
        this.auth = auth;
        this.permissions = permissions;
        this.jdbc = jdbc;
        this.named = named;
        this.clock = clock;
        reads = new TransactionTemplate(transactions);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setReadOnly(true);
        reads.setTimeout(15);
        // A report that shows candidate names writes the disclosure log (CandidateAccess.recordGranted) in the same
        // snapshot; PostgreSQL refuses that insert in a read-only transaction.
        auditedReads = new TransactionTemplate(transactions);
        auditedReads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        auditedReads.setTimeout(15);
    }

    private static String scope(String flag) {
        return "(CAST(:" + flag + " AS boolean)"
                + " OR r.id IN (" + RecruiterAssignmentAccess.ASSIGNED_REQUISITION_IDS + ")"
                + " OR r.department_id IN (" + DepartmentRepository.MANAGED_DEPARTMENT_IDS + "))";
    }

    /**
     * Runs a report in one read-only REPEATABLE READ snapshot, with the viewer resolved inside it. A report that runs
     * out of time (statement or transaction timeout, lock wait) answers 503 REPORT_BUSY.
     */
    <T> T read(Jwt jwt, Function<ReportViewer, T> report) {
        return guarded(() -> reads.execute(status -> {
            boundStatements();
            return report.apply(requireReader(jwt));
        }));
    }

    /** Like read, for a report that records candidate disclosures. */
    <T> T auditedRead(Jwt jwt, Function<ReportViewer, T> report) {
        return guarded(() -> auditedReads.execute(status -> {
            boundStatements();
            return report.apply(requireReader(jwt));
        }));
    }

    private static <T> T guarded(Supplier<T> report) {
        try {
            return Objects.requireNonNull(report.get());
        } catch (RuntimeException failure) {
            if (ReportingBusy.busy(failure)) {
                throw ReportingBusy.reportBusy();
            }
            throw failure;
        }
    }

    ReportViewer requireReader(Jwt jwt) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant())) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        Account account = auth.requireActiveAccount(jwt);
        ReportViewer viewer = ReportViewer.of(account.getId(), permissions.forUser(account.getId()));
        if (!viewer.readsReports()) {
            throw new AccessDeniedException("Reports need REPORTS, REQUISITIONS and CANDIDATES read");
        }
        return viewer;
    }

    /** The named parameters of SCOPE, INTERVIEW_SCOPE and OFFER_SCOPE for this viewer. */
    static MapSqlParameterSource parameters(ReportViewer viewer) {
        return new MapSqlParameterSource()
                .addValue(UNRESTRICTED, viewer.unrestricted())
                .addValue(INTERVIEWS_UNRESTRICTED, viewer.interviewsUnrestricted())
                .addValue(OFFERS_UNRESTRICTED, viewer.offersUnrestricted())
                .addValue(RecruiterAssignmentAccess.ASSIGNEE, viewer.id())
                .addValue(DepartmentRepository.MANAGER, viewer.id());
    }

    /**
     * Task 557: a filter must name something that exists (404) and that the viewer may report on (403), checked in
     * the report's snapshot after the permission check. A requisition must be in SCOPE; a department must be managed
     * by the viewer or hold a requisition assigned to them; a position must have a requisition in SCOPE. An
     * unrestricted viewer may filter on anything that exists.
     */
    void requireFilters(ReportViewer viewer, ReportFilters filters) {
        var parameters = parameters(viewer);
        if (filters.requisitionId() != null) {
            List<Boolean> visible = named.query("SELECT " + SCOPE + " AS visible FROM recruitment_requisitions r"
                            + " WHERE r.id = :target", parameters.addValue("target", filters.requisitionId()),
                    (row, number) -> row.getBoolean("visible"));
            if (visible.isEmpty()) {
                throw notFound("REQUISITION_NOT_FOUND", "Không tìm thấy yêu cầu tuyển dụng.");
            }
            if (!visible.getFirst()) {
                throw new AccessDeniedException("The requisition is outside the report scope");
            }
        }
        if (filters.departmentId() != null) {
            requireExisting("departments", filters.departmentId(), "DEPARTMENT_NOT_FOUND", "Không tìm thấy phòng ban.");
            if (!viewer.unrestricted() && !Boolean.TRUE.equals(named.queryForObject("""
                    SELECT CAST(:target AS uuid) IN (""" + DepartmentRepository.MANAGED_DEPARTMENT_IDS + """
                    ) OR EXISTS (SELECT 1 FROM recruitment_requisitions r WHERE r.department_id = :target
                        AND r.id IN (""" + RecruiterAssignmentAccess.ASSIGNED_REQUISITION_IDS + "))",
                    parameters.addValue("target", filters.departmentId()), Boolean.class))) {
                throw new AccessDeniedException("The department is outside the report scope");
            }
        }
        if (filters.positionId() != null) {
            requireExisting("positions", filters.positionId(), "POSITION_NOT_FOUND", "Không tìm thấy chức danh.");
            if (!viewer.unrestricted() && !Boolean.TRUE.equals(named.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM recruitment_requisitions r WHERE r.position_id = :target AND "
                            + SCOPE + ")", parameters.addValue("target", filters.positionId()), Boolean.class))) {
                throw new AccessDeniedException("No requisition of the position is in the report scope");
            }
        }
    }

    // The table name is one of three constants of this class, never client text.
    private void requireExisting(String table, UUID id, String code, String message) {
        if (!Boolean.TRUE.equals(named.queryForObject("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE id = :id)",
                new MapSqlParameterSource("id", id), Boolean.class))) {
            throw notFound(code, message);
        }
    }

    private static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    // A report is a handful of aggregate statements; one that runs away is stopped instead of holding a connection.
    private void boundStatements() {
        jdbc.execute("SET LOCAL statement_timeout = '5s'");
    }
}
