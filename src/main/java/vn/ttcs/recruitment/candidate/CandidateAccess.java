package vn.ttcs.recruitment.candidate;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.department.DepartmentRepository;
import vn.ttcs.recruitment.requisition.RecruiterAssignmentAccess;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 363 (story S4-09, "Chỉ recruiter phụ trách và hiring manager của vị trí đó mở được; mọi truy cập được ghi
 * nhật ký"): who may read a candidate's profile and files, and the log of every such read.
 * <p>
 * ALL (CANDIDATES_READ_ALL: ADMIN, HR_MANAGER) reads every application. SCOPED (CANDIDATES_READ_SCOPED: RECRUITER,
 * HIRING_MANAGER, INTERVIEWER and, from V33_1, APPROVER) reads an application only when the reader is now the PRIMARY
 * or a SUPPORTING recruiter of its requisition (RecruiterAssignmentAccess, task 285), or manages the requisition's
 * department or a department above it (DepartmentRepository.MANAGED_DEPARTMENT_IDS). The requisition's creator never
 * counts, nor does the assignment history: after a handover only the new recruiter reads. Decided by permission code,
 * never by role name, so an INTERVIEWER who manages the department reads its candidates. One SQL fragment, READABLE,
 * serves lists and single records, so they can never disagree. The criterion is therefore met for every SCOPED holder,
 * but not for the ALL holders HR_MANAGER and ADMIN until BA/PO answer questions 6 and 25(4).
 * <p>
 * Every candidate endpoint checks in this order: requireReader (401 for the token or account, 403 without a
 * CANDIDATES read permission, before any lookup; neither is logged, nothing was looked up), then 404 for an unknown
 * record (not logged), then 403 for an existing record outside the scope (logged with recordDenied after the read
 * transaction ended). A granted read logs one row per application it disclosed with recordGranted, in its own read
 * transaction. The teammate's CV suggestions (task 334) must use the same four calls, so the rule and the log stay one.
 * <p>
 * The reads take no lock and run in the caller's transaction (MANDATORY), so a list sees the team and the department
 * tree of its own snapshot.
 */
@Component
public class CandidateAccess {
    // The named parameter that says whether the reader holds CANDIDATES_READ_ALL (parameters sets it).
    public static final String ALL_CANDIDATES = "allCandidates";
    // May the reader read this application? A constant boolean expression over the aliases a (job_applications) and r
    // (recruitment_requisitions); the inner fragments resolve their own columns. Bind it with parameters(reader).
    // Both ids are NOT NULL and x IN (no rows) is false, so the expression is never null.
    public static final String READABLE = "(CAST(:" + ALL_CANDIDATES + " AS boolean)"
            + " OR a.requisition_id IN (" + RecruiterAssignmentAccess.ASSIGNED_REQUISITION_IDS + ")"
            + " OR r.department_id IN (" + DepartmentRepository.MANAGED_DEPARTMENT_IDS + "))";
    // One row of the append-only log (V33). Ids, kinds and the time only: no personal data.
    private static final String INSERT = """
            INSERT INTO candidate_access_events
                (actor_id, candidate_id, application_id, access_kind, access_scope, outcome, occurred_at)
            VALUES (:actor, :candidate, :application, :kind, :scope, :outcome, :at)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AuthService auth;
    private final PermissionService permissions;
    private final TransactionTemplate refusals;
    private final Clock clock;

    public CandidateAccess(NamedParameterJdbcTemplate jdbc, AuthService auth, PermissionService permissions,
                           PlatformTransactionManager transactions, Clock clock) {
        this.jdbc = jdbc;
        this.auth = auth;
        this.permissions = permissions;
        this.clock = clock;
        // A refusal is logged alone, after the request's read transaction: one short committed insert.
        this.refusals = new TransactionTemplate(transactions);
        refusals.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        refusals.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        refusals.setTimeout(5);
    }

    // Who reads, and how far: an unexpired token of an active session and account (401 otherwise), then a CANDIDATES
    // read permission read again from the database, so a grant removed from role_permissions counts at the next call
    // (403 otherwise, through AccessDeniedException). Call it before any lookup.
    public CandidateReader requireReader(Jwt jwt) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant())) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        Account account = auth.requireActiveAccount(jwt);
        AccessScope scope = AccessScope.read(permissions.forUser(account.getId()), PermissionModule.CANDIDATES).orDeny();
        return new CandidateReader(account.getId(), scope);
    }

    // The three named parameters of READABLE for this reader; add the query's own parameters to it.
    public MapSqlParameterSource parameters(CandidateReader reader) {
        return new MapSqlParameterSource(ALL_CANDIDATES, reader.scope() == AccessScope.ALL)
                .addValue(RecruiterAssignmentAccess.ASSIGNEE, reader.id())
                .addValue(DepartmentRepository.MANAGER, reader.id());
    }

    // One application with its profile and whether the reader may read it; empty when the application does not exist
    // (404 for the caller). Built on READABLE, like every list.
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ApplicationAccess> findApplication(CandidateReader reader, UUID applicationId) {
        return jdbc.query("SELECT a.id, ca.candidate_id, " + READABLE + " AS readable"
                        + " FROM job_applications a"
                        + " JOIN recruitment_requisitions r ON r.id = a.requisition_id"
                        + " JOIN candidate_applications ca ON ca.application_id = a.id"
                        + " WHERE a.id = :application",
                parameters(reader).addValue("application", applicationId),
                (row, number) -> new ApplicationAccess(row.getObject("id", UUID.class),
                        row.getObject("candidate_id", UUID.class), row.getBoolean("readable")))
                .stream().findFirst();
    }

    // A granted read: one GRANTED row per application it disclosed, in the read's own transaction, so the rows commit
    // with a read that completed and roll back with one that failed. The scope cannot be rebuilt later (department
    // managers have no history), so the rows say what was shown.
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordGranted(CandidateReader reader, CandidateAccessKind kind, UUID candidateId,
                              List<UUID> applicationIds) {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(candidateId, "candidateId");
        // List.of(...).contains(null) would throw, so nulls are looked for one by one.
        if (applicationIds == null || applicationIds.isEmpty() || applicationIds.stream().anyMatch(Objects::isNull)
                || new HashSet<>(applicationIds).size() != applicationIds.size()) {
            throw new IllegalArgumentException("A granted read discloses one or more distinct applications");
        }
        Timestamp at = Timestamp.from(now());
        jdbc.batchUpdate(INSERT, applicationIds.stream()
                .map(application -> event(reader, kind, candidateId, application, "GRANTED", at))
                .toArray(MapSqlParameterSource[]::new));
    }

    // A refused read of an existing record (403): one DENIED row, the application's id for a CV, null for a profile
    // with nothing readable. Its own short REQUIRES_NEW transaction, and only once the caller's read transaction has
    // ended (rolled back): inside a transaction it throws IllegalStateException, so a request never holds two pool
    // connections at once. A reader with CANDIDATES_READ_ALL is never refused.
    public void recordDenied(CandidateReader reader, CandidateAccessKind kind, UUID candidateId,
                             UUID applicationIdOrNull) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Log a refused read after the read transaction has ended");
        }
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(candidateId, "candidateId");
        if (reader.scope() != AccessScope.SCOPED) {
            throw new IllegalArgumentException("Only a SCOPED reader is refused");
        }
        if (kind == CandidateAccessKind.CV && applicationIdOrNull == null) {
            throw new IllegalArgumentException("A refused CV read names its application");
        }
        Timestamp at = Timestamp.from(now());
        refusals.executeWithoutResult(status ->
                jdbc.update(INSERT, event(reader, kind, candidateId, applicationIdOrNull, "DENIED", at)));
    }

    private static MapSqlParameterSource event(CandidateReader reader, CandidateAccessKind kind, UUID candidateId,
                                               UUID applicationId, String outcome, Timestamp at) {
        return new MapSqlParameterSource("actor", reader.id()).addValue("candidate", candidateId)
                .addValue("application", applicationId).addValue("kind", kind.name())
                .addValue("scope", reader.logScope()).addValue("outcome", outcome).addValue("at", at);
    }

    // PostgreSQL TIMESTAMPTZ keeps microseconds, so the stored time is the time read back.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
