package vn.ttcs.recruitment.competency;

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

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// Jira 212: create, edit and read competency frameworks together with their criteria.
// Reading needs ORGANIZATION_READ_ALL (every internal role, including interviewers); writing needs
// ORGANIZATION_WRITE_ALL (ADMIN, HR_MANAGER). The 100% weight rule is not checked here.
@Service
public class CompetencyFrameworkService {
    private final CompetencyFrameworkRepository frameworks;
    private final CompetencyCriterionRepository criteria;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final AuthService auth;
    private final PermissionService permissions;
    private final Clock clock;

    public CompetencyFrameworkService(CompetencyFrameworkRepository frameworks, CompetencyCriterionRepository criteria,
                                      AccountRepository accounts, AuthSessionRepository sessions, AuthService auth,
                                      PermissionService permissions, Clock clock) {
        this.frameworks = frameworks;
        this.criteria = criteria;
        this.accounts = accounts;
        this.sessions = sessions;
        this.auth = auth;
        this.permissions = permissions;
        this.clock = clock;
    }

    // REPEATABLE_READ: the page, its total and the criterion counts all come from the same snapshot.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CompetencyFrameworkPage list(Jwt jwt, String query, CompetencyFrameworkStatus status, int page, int size) {
        requireReadAccess(jwt);
        if (page < 0 || size < 1 || size > 100 || (query != null && query.length() > 255)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Trang, số lượng hoặc từ khóa tìm kiếm khung năng lực không hợp lệ.");
        }
        List<CompetencyFrameworkStatus> statuses = status == null
                ? List.of(CompetencyFrameworkStatus.values()) : List.of(status);
        var result = frameworks.search(containsPattern(query), statuses,
                PageRequest.of(page, size, Sort.by("code", "id")));
        Map<UUID, Long> counts = new HashMap<>();
        if (result.hasContent()) {
            var ids = result.getContent().stream().map(CompetencyFramework::getId).toList();
            criteria.countByFrameworkIds(ids)
                    .forEach(count -> counts.put(count.getFrameworkId(), count.getCriterionCount()));
        }
        return new CompetencyFrameworkPage(result.getContent().stream()
                .map(framework -> CompetencyFrameworkSummary.from(framework, counts.getOrDefault(framework.getId(), 0L)))
                .toList(), page, size, result.getTotalElements(), result.getTotalPages());
    }

    // REPEATABLE_READ: the framework and its criteria come from the same snapshot, never half of an edit.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CompetencyFrameworkView get(Jwt jwt, UUID id) {
        requireReadAccess(jwt);
        var framework = frameworks.findById(id).orElseThrow(CompetencyFrameworkService::notFound);
        return CompetencyFrameworkView.from(framework, criteria.findByFrameworkIdOrderBySortOrderAsc(id));
    }

    @Transactional
    public CompetencyFrameworkView create(Jwt jwt, CompetencyFrameworkRequest request) {
        requireWriteAccess(jwt);
        // A new framework has no criteria yet, so no criterion id can belong to it.
        requireValidCriteria(request.criteria(), Set.of());
        if (frameworks.existsByCode(request.code())) {
            throw duplicateCode();
        }
        var framework = new CompetencyFramework(request.code(), request.name(), request.description(), now());
        try {
            // Saved first, so the criteria rows below have their framework row (foreign key).
            framework = frameworks.saveAndFlush(framework);
        } catch (DataIntegrityViolationException exception) {
            throw translateDuplicate(exception);
        }
        var saved = replaceCriteria(framework.getId(), request.criteria(), List.of());
        checkCriteriaNow();
        return CompetencyFrameworkView.from(framework, saved);
    }

    @Transactional
    public CompetencyFrameworkView update(Jwt jwt, UUID id, CompetencyFrameworkRequest request) {
        requireWriteAccess(jwt);
        // Step 1 of the V8 rules (docs/database/README.md): lock the framework row before reading its criteria.
        // Another edit of the same framework waits here until this transaction ends, so the criteria read
        // below are the latest committed ones and nobody changes them before this edit is saved.
        CompetencyFramework framework = frameworks.findByIdForUpdate(id)
                .orElseThrow(CompetencyFrameworkService::notFound);
        List<CompetencyCriterion> current = criteria.findByFrameworkIdOrderBySortOrderAsc(id);
        Set<UUID> currentIds = new HashSet<>();
        current.forEach(criterion -> currentIds.add(criterion.getId()));
        // Step 2: check the final criteria list.
        requireValidCriteria(request.criteria(), currentIds);
        if (frameworks.existsByCodeAndIdNot(request.code(), id)) {
            throw duplicateCode();
        }
        framework.update(request.code(), request.name(), request.description(), now());
        var saved = replaceCriteria(id, request.criteria(), current);
        // Step 3: also flushes the framework change, so a code taken meanwhile is reported here too.
        checkCriteriaNow();
        return CompetencyFrameworkView.from(framework, saved);
    }

    // Makes the stored criteria equal to the requested list (plain replace):
    // - an item with an id updates that criterion and keeps its id, so whatever points to it later
    //   (interview questions) keeps working;
    // - an item without an id becomes a new criterion with a new id;
    // - a stored criterion missing from the list is deleted.
    // The position in the list becomes sortOrder 1, 2, 3... Returns the criteria in that order.
    private List<CompetencyCriterion> replaceCriteria(UUID frameworkId, List<CompetencyCriterionRequest> requested,
                                                      List<CompetencyCriterion> current) {
        Map<UUID, CompetencyCriterion> notRequested = new HashMap<>();
        current.forEach(criterion -> notRequested.put(criterion.getId(), criterion));
        List<CompetencyCriterion> result = new ArrayList<>();
        for (int index = 0; index < requested.size(); index++) {
            var item = requested.get(index);
            int sortOrder = index + 1;
            // The request allows at most two decimals, so setScale(2) only adds zeros (40 becomes 40.00) and
            // the response shows the same value a later GET reads from NUMERIC(5,2).
            BigDecimal weight = item.weight().setScale(2);
            if (item.id() == null) {
                result.add(criteria.save(new CompetencyCriterion(frameworkId, item.name(), item.description(),
                        weight, sortOrder)));
            } else {
                var existing = notRequested.remove(item.id());
                existing.update(item.name(), item.description(), weight, sortOrder);
                result.add(existing);
            }
        }
        criteria.deleteAll(notRequested.values());
        return result;
    }

    // Rules for the whole list, checked before anything is written:
    // 1. an id must be a criterion of this framework, sent at most once (400 INVALID_COMPETENCY_CRITERION);
    // 2. names must be unique inside the framework, compared exactly like the V8 UNIQUE constraint, so
    //    "Giao tiếp" and "giao tiếp" are different names (409 COMPETENCY_CRITERION_NAME_DUPLICATE).
    // fieldErrors names each wrong row, e.g. "criteria[2].name", so a form can mark it.
    private static void requireValidCriteria(List<CompetencyCriterionRequest> requested, Set<UUID> currentIds) {
        Map<String, String> invalidIds = new LinkedHashMap<>();
        Map<String, String> duplicateNames = new LinkedHashMap<>();
        Set<UUID> seenIds = new HashSet<>();
        Set<String> seenNames = new HashSet<>();
        for (int index = 0; index < requested.size(); index++) {
            var item = requested.get(index);
            String field = "criteria[" + index + "]";
            if (item.id() != null && !currentIds.contains(item.id())) {
                invalidIds.put(field + ".id", "Tiêu chí không thuộc khung năng lực này.");
            } else if (item.id() != null && !seenIds.add(item.id())) {
                invalidIds.put(field + ".id", "Mỗi tiêu chí chỉ được gửi một lần.");
            }
            if (!seenNames.add(item.name())) {
                duplicateNames.put(field + ".name", "Tên tiêu chí đã có ở dòng khác trong khung.");
            }
        }
        if (!invalidIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_COMPETENCY_CRITERION",
                    "Danh sách tiêu chí có tiêu chí không hợp lệ.", invalidIds);
        }
        if (!duplicateNames.isEmpty()) {
            throw duplicateCriterionName(duplicateNames);
        }
    }

    // The last step of every write. It flushes all pending changes and checks the V8 deferred UNIQUE constraints
    // now instead of at COMMIT, so a duplicate becomes a 409 here. With the framework row locked and the names
    // checked above, this is a safety net for writes that do not go through this service.
    private void checkCriteriaNow() {
        try {
            criteria.checkUniqueConstraintsNow();
        } catch (DataIntegrityViolationException exception) {
            throw translateDuplicate(exception);
        }
    }

    private void requireReadAccess(Jwt jwt) {
        requireUnexpiredToken(jwt, clock.instant());
        Account actor = auth.requireActiveAccount(jwt);
        if (!permissions.forUser(actor.getId()).contains("ORGANIZATION_READ_ALL")) {
            throw new AccessDeniedException("Competency framework access requires ORGANIZATION_READ_ALL");
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
        // The framework row is locked only after these, in update().
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
            throw new AccessDeniedException("Competency framework management requires ORGANIZATION_WRITE_ALL");
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

    // A blank query becomes "%", which matches every framework.
    private static String containsPattern(String query) {
        if (query == null || query.isBlank()) {
            return "%";
        }
        String literal = query.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + literal + "%";
    }

    // The checks above miss rows another transaction has not committed yet; the constraints still catch them.
    private RuntimeException translateDuplicate(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState()) && sql.getMessage() != null) {
                if (sql.getMessage().contains("competency_frameworks_code_key")) {
                    return duplicateCode();
                }
                if (sql.getMessage().contains("competency_criteria_framework_name_key")) {
                    return duplicateCriterionName(Map.of());
                }
            }
        }
        return exception;
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "COMPETENCY_FRAMEWORK_NOT_FOUND",
                "Không tìm thấy khung năng lực.");
    }

    private static ApiException duplicateCode() {
        return new ApiException(HttpStatus.CONFLICT, "COMPETENCY_FRAMEWORK_CODE_EXISTS",
                "Mã khung năng lực đã được sử dụng.");
    }

    private static ApiException duplicateCriterionName(Map<String, String> fieldErrors) {
        return new ApiException(HttpStatus.CONFLICT, "COMPETENCY_CRITERION_NAME_DUPLICATE",
                "Tên tiêu chí trong một khung năng lực không được trùng nhau.", fieldErrors);
    }
}
