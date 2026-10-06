package vn.ttcs.recruitment.position;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.security.PermissionService;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PositionService {
    private final PositionRepository positions;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final AuthService auth;
    private final PermissionService permissions;
    private final Clock clock;

    public PositionService(PositionRepository positions, AccountRepository accounts, AuthSessionRepository sessions,
                           AuthService auth, PermissionService permissions, Clock clock) {
        this.positions = positions;
        this.accounts = accounts;
        this.sessions = sessions;
        this.auth = auth;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PositionPage list(Jwt jwt, String query, Boolean active, int page, int size) {
        requireReadAccess(jwt);
        if (page < 0 || size < 1 || size > 100 || (query != null && query.length() > 255)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Trang, số lượng hoặc từ khóa tìm kiếm chức danh không hợp lệ.");
        }
        List<Boolean> activeValues = active == null ? List.of(true, false) : List.of(active);
        var result = positions.search(containsPattern(query), activeValues,
                PageRequest.of(page, size, Sort.by("code", "id")));
        return new PositionPage(result.getContent().stream().map(PositionView::from).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public PositionView get(Jwt jwt, UUID id) {
        requireReadAccess(jwt);
        return positions.findById(id).map(PositionView::from).orElseThrow(PositionService::notFound);
    }

    @Transactional
    public PositionView create(Jwt jwt, PositionRequest request) {
        requireWriteAccess(jwt);
        requireValidSalaryBand(request);
        if (positions.existsByCode(request.code())) {
            throw duplicateCode();
        }
        var position = new Position(request.code(), request.name(), request.level(),
                request.salaryMin(), request.salaryMax(), request.active(), now());
        try {
            position = positions.saveAndFlush(position);
        } catch (DataIntegrityViolationException exception) {
            throw translateDuplicateCode(exception);
        }
        return PositionView.from(position);
    }

    @Transactional
    public PositionView update(Jwt jwt, UUID id, PositionRequest request) {
        requireWriteAccess(jwt);
        requireValidSalaryBand(request);
        Position position = positions.findByIdForUpdate(id).orElseThrow(PositionService::notFound);
        if (positions.existsByCodeAndIdNot(request.code(), id)) {
            throw duplicateCode();
        }
        position.update(request.code(), request.name(), request.level(),
                request.salaryMin(), request.salaryMax(), request.active(), now());
        try {
            positions.flush();
        } catch (DataIntegrityViolationException exception) {
            throw translateDuplicateCode(exception);
        }
        return PositionView.from(position);
    }

    private void requireReadAccess(Jwt jwt) {
        requireUnexpiredToken(jwt, clock.instant());
        Account actor = auth.requireActiveAccount(jwt);
        if (!permissions.forUser(actor.getId()).contains("ORGANIZATION_READ_ALL")) {
            throw new AccessDeniedException("Position access requires ORGANIZATION_READ_ALL");
        }
    }

    private void requireWriteAccess(Jwt jwt) {
        UUID actorId;
        UUID sessionId;
        try {
            actorId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getId());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        // Same lock order as the other write services: the actor's account first, then the actor's session.
        // Role, lock and logout changes wait for these locks, so they cannot interleave with this write.
        accounts.findByIdForUpdate(actorId).filter(Account::isAccessAllowed)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        var session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);

        // The request may have waited for those locks. Recheck the token, session and permission now.
        var now = clock.instant();
        requireUnexpiredToken(jwt, now);
        if (!session.getUserId().equals(actorId) || !session.isActive(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        if (!permissions.forUser(actorId).contains("ORGANIZATION_WRITE_ALL")) {
            throw new AccessDeniedException("Position management requires ORGANIZATION_WRITE_ALL");
        }
    }

    // PositionRequest has already checked each salary on its own (whole VND, 0 to MAX_SALARY_VND).
    // Equal values are a valid band. The V7 CHECK stays as the last line of defence, but an inverted band
    // never reaches it: that would surface as a 500 instead of an error the form can show on salaryMax.
    private static void requireValidSalaryBand(PositionRequest request) {
        if (request.salaryMin() > request.salaryMax()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "POSITION_SALARY_RANGE_INVALID",
                    "Lương tối thiểu không được lớn hơn lương tối đa.",
                    Map.of("salaryMax", "Lương tối đa phải lớn hơn hoặc bằng lương tối thiểu."));
        }
    }

    private void requireUnexpiredToken(Jwt jwt, Instant now) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
    }

    // PostgreSQL TIMESTAMPTZ keeps microseconds, so write responses show the same time a later GET reads.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    // A blank query becomes "%", which matches every position.
    private static String containsPattern(String query) {
        if (query == null || query.isBlank()) {
            return "%";
        }
        String literal = query.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + literal + "%";
    }

    // The existence check above misses a concurrent insert; the unique constraint still catches it here.
    private RuntimeException translateDuplicateCode(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())
                    && sql.getMessage() != null && sql.getMessage().contains("positions_code_key")) {
                return duplicateCode();
            }
        }
        return exception;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "POSITION_NOT_FOUND", "Không tìm thấy chức danh.");
    }

    private static ApiException duplicateCode() {
        return new ApiException(HttpStatus.CONFLICT, "POSITION_CODE_EXISTS", "Mã chức danh đã được sử dụng.");
    }
}
