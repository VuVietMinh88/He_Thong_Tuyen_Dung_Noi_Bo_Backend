package vn.ttcs.recruitment.reporting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.department.DepartmentRepository;
import vn.ttcs.recruitment.requisition.RecruiterAssignmentAccess;
import vn.ttcs.recruitment.security.PermissionService;

import java.time.Clock;
import java.util.Objects;
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
    private final Clock clock;
    private final TransactionTemplate reads;
    private final TransactionTemplate auditedReads;

    public ReportingAccess(AuthService auth, PermissionService permissions, JdbcTemplate jdbc, Clock clock,
                           PlatformTransactionManager transactions) {
        this.auth = auth;
        this.permissions = permissions;
        this.jdbc = jdbc;
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

    // A report is a handful of aggregate statements; one that runs away is stopped instead of holding a connection.
    private void boundStatements() {
        jdbc.execute("SET LOCAL statement_timeout = '5s'");
    }
}
