package vn.ttcs.recruitment.screening;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.auth.AuthSession;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.candidate.ApplicationAccess;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.requisition.RecruiterAssignmentAccess;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Reads use the candidate scope. Writes require both read and write, checked again after every blocking lock. */
@Service
public class ScreeningService {
    private final ScreeningRepository data;
    private final CandidateAccess access;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final PermissionService permissions;
    private final RecruiterAssignmentAccess assignments;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    public ScreeningService(ScreeningRepository data, CandidateAccess access, AccountRepository accounts,
            AuthSessionRepository sessions, PermissionService permissions, RecruiterAssignmentAccess assignments,
            JdbcTemplate jdbc, Clock clock, PlatformTransactionManager manager) {
        this.data = data;
        this.access = access;
        this.accounts = accounts;
        this.sessions = sessions;
        this.permissions = permissions;
        this.assignments = assignments;
        this.jdbc = jdbc;
        this.clock = clock;
        reads = new TransactionTemplate(manager);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        writes = new TransactionTemplate(manager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(5);
    }

    public ScreeningView get(Jwt jwt, UUID applicationId, int page, int size) {
        if (page < 0 || page > 1000000 || size < 1 || size > 100) {
            throw badRequest("SCREENING_PAGE_INVALID", "Trang phải từ 0; kích thước trang từ 1 đến 100.");
        }
        CandidateReader reader = access.requireReader(jwt);
        Read result = Objects.requireNonNull(reads.execute(status -> {
            ApplicationAccess found = access.findApplication(reader, applicationId).orElseThrow(ScreeningService::notFound);
            if (!found.readable()) {
                status.setRollbackOnly();
                return new Read(null, found.candidateId());
            }
            ScreeningView view = data.view(applicationId, page, size);
            access.recordGranted(reader, CandidateAccessKind.PROFILE, found.candidateId(), List.of(applicationId));
            return new Read(view, null);
        }));
        if (result.deniedCandidate() != null) {
            access.recordDenied(reader, CandidateAccessKind.PROFILE, result.deniedCandidate(), applicationId);
            throw new AccessDeniedException("Application screening is outside the reader's scope");
        }
        return result.view();
    }
    private record Read(ScreeningView view, UUID deniedCandidate) { }

    public ScreeningView tags(Jwt jwt, UUID id, ScreeningTagsRequest request) {
        return change(jwt, id, request.tagIds(), (actor, now) -> data.replaceTags(id, request.tagIds(), actor, now));
    }
    public ScreeningView rating(Jwt jwt, UUID id, ScreeningRatingRequest request) {
        return change(jwt, id, List.of(), (actor, now) -> data.rate(id, request.rating(), actor, now));
    }
    public ScreeningView note(Jwt jwt, UUID id, ScreeningNoteRequest request) {
        return change(jwt, id, List.of(), (actor, now) -> data.addNote(UUID.randomUUID(), id, request.content(), actor, now));
    }
    private interface Mutation { void apply(UUID actor, Instant now); }
    private record Writer(UUID id, AuthSession session) { }
    private record Scopes(AccessScope read, AccessScope write) { }

    private ScreeningView change(Jwt jwt, UUID id, List<UUID> tags, Mutation mutation) {
        try {
            return Objects.requireNonNull(writes.execute(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '2s'");
                jdbc.execute("SET LOCAL statement_timeout = '3s'");
                Writer writer = lockWriter(jwt);
                freshScopes(jwt, writer);
                UUID requisition = data.requisitionOf(id);
                if (requisition == null || !data.lockRequisition(requisition)) {
                    throw notFound();
                }
                inScope(writer, freshScopes(jwt, writer), requisition, id);
                if (!data.lockApplication(id)) {
                    throw notFound();
                }
                ApplicationAccess found = inScope(writer, freshScopes(jwt, writer), requisition, id);
                // Stable order avoids deadlocks when requests select the same tags in a different order.
                for (UUID tag : tags.stream().sorted().toList()) {
                    boolean active = data.lockActiveTag(tag);
                    // A catalog update may have held this lock. Recheck permissions and record scope after it.
                    found = inScope(writer, freshScopes(jwt, writer), requisition, id);
                    if (!active) {
                        throw badRequest("SCREENING_TAG_INVALID", "Chỉ được chọn nhãn CANDIDATE_TAG đang hoạt động.");
                    }
                }
                Scopes scopes = freshScopes(jwt, writer);
                found = inScope(writer, scopes, requisition, id);
                mutation.apply(writer.id(), clock.instant().truncatedTo(ChronoUnit.MICROS));
                ScreeningView view = data.view(id, 0, 20);
                access.recordGranted(new CandidateReader(writer.id(), scopes.read()), CandidateAccessKind.PROFILE,
                        found.candidateId(), List.of(id));
                return view;
            }));
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof TransientDataAccessException || cause instanceof TransactionTimedOutException
                        || cause instanceof SQLException sql && Set.of("55P03", "57014", "40P01").contains(sql.getSQLState())) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SCREENING_BUSY", "Hồ sơ đang được xử lý. Vui lòng thử lại.");
                }
            }
            throw failure;
        }
    }

    private Writer lockWriter(Jwt jwt) {
        UUID actor;
        UUID sessionId;
        try {
            actor = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getId());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        accounts.findByIdForUpdate(actor).filter(Account::isAccessAllowed)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        AuthSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        return new Writer(actor, session);
    }
    private Scopes freshScopes(Jwt jwt, Writer writer) {
        Instant now = clock.instant();
        if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)
                || !writer.session().getUserId().equals(writer.id()) || !writer.session().isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        var granted = permissions.forUser(writer.id());
        return new Scopes(AccessScope.read(granted, PermissionModule.CANDIDATES).orDeny(),
                AccessScope.write(granted, PermissionModule.CANDIDATES).orDeny());
    }
    private ApplicationAccess inScope(Writer writer, Scopes scopes, UUID requisition, UUID application) {
        var found = access.findApplication(new CandidateReader(writer.id(), scopes.read()), application)
                .orElseThrow(ScreeningService::notFound);
        if (!found.readable() || scopes.write() != AccessScope.ALL && !assignments.isAssigned(requisition, writer.id())) {
            throw new AccessDeniedException("Application screening is outside the writer's scope");
        }
        return found;
    }
    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND", "Không tìm thấy hồ sơ ứng tuyển.");
    }
    private static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
