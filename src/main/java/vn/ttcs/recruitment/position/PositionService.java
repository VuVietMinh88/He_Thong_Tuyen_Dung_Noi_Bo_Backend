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
import vn.ttcs.recruitment.competency.CompetencyFramework;
import vn.ttcs.recruitment.competency.CompetencyFrameworkRepository;
import vn.ttcs.recruitment.competency.CompetencyFrameworkStatus;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PositionService {
    // Every catalog write carries a salary band (both salaries are required), so it also needs the salary permission.
    private static final List<String> CATALOG_WRITE = List.of("ORGANIZATION_WRITE_ALL", "SALARY_RANGES_WRITE_ALL");
    // Choosing the competency framework of a position never touches the salary band (Jira 214).
    private static final List<String> FRAMEWORK_WRITE = List.of("ORGANIZATION_WRITE_ALL");

    private final PositionRepository positions;
    private final CompetencyFrameworkRepository frameworks;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final AuthService auth;
    private final PermissionService permissions;
    private final Clock clock;

    public PositionService(PositionRepository positions, CompetencyFrameworkRepository frameworks,
                           AccountRepository accounts, AuthSessionRepository sessions, AuthService auth,
                           PermissionService permissions, Clock clock) {
        this.positions = positions;
        this.frameworks = frameworks;
        this.accounts = accounts;
        this.sessions = sessions;
        this.auth = auth;
        this.permissions = permissions;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PositionPage list(Jwt jwt, String query, Boolean active, int page, int size) {
        boolean showSalaryBand = canSeeSalaryBand(requireReadAccess(jwt));
        if (page < 0 || size < 1 || size > 100 || (query != null && query.length() > 255)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Trang, số lượng hoặc từ khóa tìm kiếm chức danh không hợp lệ.");
        }
        List<Boolean> activeValues = active == null ? List.of(true, false) : List.of(active);
        var result = positions.search(containsPattern(query), activeValues,
                PageRequest.of(page, size, Sort.by("code", "id")));
        return new PositionPage(result.getContent().stream()
                .map(position -> PositionView.from(position, showSalaryBand)).toList(),
                page, size, result.getTotalElements(), result.getTotalPages());
    }

    @Transactional(readOnly = true)
    public PositionView get(Jwt jwt, UUID id) {
        boolean showSalaryBand = canSeeSalaryBand(requireReadAccess(jwt));
        return positions.findById(id).map(position -> PositionView.from(position, showSalaryBand))
                .orElseThrow(PositionService::notFound);
    }

    @Transactional
    public PositionView create(Jwt jwt, PositionRequest request) {
        Set<String> granted = requireWriteAccess(jwt, CATALOG_WRITE);
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
        return PositionView.from(position, canSeeSalaryBand(granted));
    }

    @Transactional
    public PositionView update(Jwt jwt, UUID id, PositionRequest request) {
        Set<String> granted = requireWriteAccess(jwt, CATALOG_WRITE);
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
        return PositionView.from(position, canSeeSalaryBand(granted));
    }

    // Jira 214: many positions may point to the same framework. Only positions.competency_framework_id is written,
    // so the criteria rows stay in the framework and are never copied per position.
    // Checks in order: position exists (404), framework exists (400), framework is ACTIVE (409).
    @Transactional
    public PositionView useCompetencyFramework(Jwt jwt, UUID id, UUID frameworkId) {
        Set<String> granted = requireWriteAccess(jwt, FRAMEWORK_WRITE);
        // Same row lock as update(), so a catalog edit of the same position runs before or after this one.
        Position position = positions.findByIdForUpdate(id).orElseThrow(PositionService::notFound);
        // FOR SHARE: the status read here cannot change before this transaction commits (see findByIdForShare).
        CompetencyFramework framework = frameworks.findByIdForShare(frameworkId)
                .orElseThrow(PositionService::unknownFramework);
        // Interview evaluation forms are scored with this framework, so it must be complete (weights total 100%).
        // An ACTIVE framework never goes back to DRAFT and every edit keeps its total at 100%
        // (CompetencyFrameworkService), so a framework in use by positions always stays complete.
        if (framework.getStatus() != CompetencyFrameworkStatus.ACTIVE) {
            throw new ApiException(HttpStatus.CONFLICT, "COMPETENCY_FRAMEWORK_NOT_ACTIVE",
                    "Chỉ gán được khung năng lực đã hoàn chỉnh (ACTIVE) cho chức danh.",
                    Map.of("frameworkId", "Khung năng lực này còn là bản nháp (DRAFT)."));
        }
        position.useCompetencyFramework(framework.getId(), now());
        return PositionView.from(position, canSeeSalaryBand(granted));
    }

    // Removes the link to the framework; the framework itself stays for the other positions using it.
    // A position without a framework simply stays without one, so calling this twice is harmless.
    @Transactional
    public PositionView removeCompetencyFramework(Jwt jwt, UUID id) {
        Set<String> granted = requireWriteAccess(jwt, FRAMEWORK_WRITE);
        Position position = positions.findByIdForUpdate(id).orElseThrow(PositionService::notFound);
        position.useCompetencyFramework(null, now());
        return PositionView.from(position, canSeeSalaryBand(granted));
    }

    // Returns the caller's current permission codes, which also decide whether the salary band is shown.
    private Set<String> requireReadAccess(Jwt jwt) {
        requireUnexpiredToken(jwt, clock.instant());
        Account actor = auth.requireActiveAccount(jwt);
        Set<String> granted = permissions.forUser(actor.getId());
        if (!granted.contains("ORGANIZATION_READ_ALL")) {
            throw new AccessDeniedException("Position access requires ORGANIZATION_READ_ALL");
        }
        return granted;
    }

    // required: the permission codes this write needs, all of them (CATALOG_WRITE or FRAMEWORK_WRITE).
    // Returns the caller's current permission codes, which also decide whether the salary band is shown.
    private Set<String> requireWriteAccess(Jwt jwt, List<String> required) {
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
        Set<String> granted = permissions.forUser(actorId);
        if (!granted.containsAll(required)) {
            throw new AccessDeniedException("This position write requires " + String.join(" and ", required));
        }
        return granted;
    }

    // Jira 205: only callers with SALARY_RANGES_READ_ALL receive salaryMin/salaryMax, also in write responses,
    // because WRITE never implies READ. SALARY_RANGES_READ_SCOPED has no defined scope yet and no role holds it,
    // so it shows nothing.
    private static boolean canSeeSalaryBand(Set<String> granted) {
        return AccessScope.read(granted, PermissionModule.SALARY_RANGES) == AccessScope.ALL;
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

    // Package-private: SalaryBandService reports an unknown position with the same error.
    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "POSITION_NOT_FOUND", "Không tìm thấy chức danh.");
    }

    private static ApiException duplicateCode() {
        return new ApiException(HttpStatus.CONFLICT, "POSITION_CODE_EXISTS", "Mã chức danh đã được sử dụng.");
    }

    // 400 like other unknown ids sent in a body (INVALID_DEPARTMENT_PARENT): the URL itself was found.
    private static ApiException unknownFramework() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_COMPETENCY_FRAMEWORK",
                "Khung năng lực không tồn tại.", Map.of("frameworkId", "Không tìm thấy khung năng lực này."));
    }
}
