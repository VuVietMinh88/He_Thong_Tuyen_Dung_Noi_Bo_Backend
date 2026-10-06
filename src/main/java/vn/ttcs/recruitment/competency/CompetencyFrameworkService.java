package vn.ttcs.recruitment.competency;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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
// ORGANIZATION_WRITE_ALL (ADMIN, HR_MANAGER).
// Jira 213: a complete (ACTIVE) framework must have criteria weights that total exactly 100%; a DRAFT may still
// be incomplete.
// Jira 214: positions share a framework (PositionService assigns it); the detail lists the positions using it.
// Jira 220: interview questions point to criteria (V9), so PUT may not drop a criterion that still has questions.
@Service
public class CompetencyFrameworkService {
    // The weights of an ACTIVE framework add up to this many percent.
    private static final BigDecimal FULL_WEIGHT = new BigDecimal("100");
    // Plain SQL on the positions table, so this package does not depend on the position package
    // (the position package already uses CompetencyFrameworkRepository to assign frameworks).
    private static final String POSITIONS_USING_QUERY = """
            SELECT id, code, name, level, active FROM positions
            WHERE competency_framework_id = ?
            ORDER BY code, id
            """;
    // The criteria of one framework that have at least one interview question, active or not (V9 ON DELETE RESTRICT
    // counts every question). Plain SQL for the same reason: the interview question package depends on this one.
    private static final String CRITERIA_WITH_QUESTIONS_QUERY = """
            SELECT c.id FROM competency_criteria c
            WHERE c.framework_id = ?
              AND EXISTS (SELECT 1 FROM interview_questions q WHERE q.criterion_id = c.id)
            """;

    private final CompetencyFrameworkRepository frameworks;
    private final CompetencyCriterionRepository criteria;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final AuthService auth;
    private final PermissionService permissions;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CompetencyFrameworkService(CompetencyFrameworkRepository frameworks, CompetencyCriterionRepository criteria,
                                      AccountRepository accounts, AuthSessionRepository sessions, AuthService auth,
                                      PermissionService permissions, JdbcTemplate jdbc, Clock clock) {
        this.frameworks = frameworks;
        this.criteria = criteria;
        this.accounts = accounts;
        this.sessions = sessions;
        this.auth = auth;
        this.permissions = permissions;
        this.jdbc = jdbc;
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

    // REPEATABLE_READ: the framework, its criteria and its positions come from the same snapshot, never half of
    // an edit.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public CompetencyFrameworkView get(Jwt jwt, UUID id) {
        requireReadAccess(jwt);
        var framework = frameworks.findById(id).orElseThrow(CompetencyFrameworkService::notFound);
        return CompetencyFrameworkView.from(framework, criteria.findByFrameworkIdOrderBySortOrderAsc(id),
                positionsUsing(id));
    }

    @Transactional
    public CompetencyFrameworkView create(Jwt jwt, CompetencyFrameworkRequest request) {
        requireWriteAccess(jwt);
        // A new framework has no criteria yet, so no criterion id can belong to it.
        requireValidCriteria(request.criteria(), Set.of());
        // Without a status the new framework is a DRAFT, which may still be incomplete.
        boolean active = request.status() == CompetencyFrameworkStatus.ACTIVE;
        if (active) {
            requireCompleteWeights(request.criteria());
        }
        if (frameworks.existsByCode(request.code())) {
            throw duplicateCode();
        }
        var framework = new CompetencyFramework(request.code(), request.name(), request.description(), now());
        if (active) {
            framework.activate();
        }
        try {
            // Saved first, so the criteria rows below have their framework row (foreign key).
            framework = frameworks.saveAndFlush(framework);
        } catch (DataIntegrityViolationException exception) {
            throw translateConstraintViolation(exception);
        }
        var saved = replaceCriteria(framework.getId(), request.criteria(), List.of());
        checkCriteriaNow();
        // A framework that did not exist a moment ago has no positions yet.
        return CompetencyFrameworkView.from(framework, saved, List.of());
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
        requireNoQuestionsOnRemovedCriteria(id, current, request.criteria());
        // The status was read under the lock too, so an activation committed meanwhile is seen here and an
        // edit prepared while the framework was still a DRAFT must now also keep the total at 100%.
        CompetencyFrameworkStatus status = statusAfterEdit(framework, request.status());
        if (status == CompetencyFrameworkStatus.ACTIVE) {
            requireCompleteWeights(request.criteria());
        }
        if (frameworks.existsByCodeAndIdNot(request.code(), id)) {
            throw duplicateCode();
        }
        framework.update(request.code(), request.name(), request.description(), now());
        if (status == CompetencyFrameworkStatus.ACTIVE) {
            framework.activate();
        }
        var saved = replaceCriteria(id, request.criteria(), current);
        // Step 3: also flushes the framework change, so a code taken meanwhile is reported here too.
        checkCriteriaNow();
        // The positions keep pointing to this framework, so they see the new criteria at once (nothing was copied).
        return CompetencyFrameworkView.from(framework, saved, positionsUsing(id));
    }

    // Jira 214: the positions that use the framework. They all read the same criteria rows of the framework.
    private List<CompetencyFrameworkPositionView> positionsUsing(UUID frameworkId) {
        return jdbc.query(POSITIONS_USING_QUERY, (row, number) -> new CompetencyFrameworkPositionView(
                row.getObject("id", UUID.class), row.getString("code"), row.getString("name"),
                row.getString("level"), row.getBoolean("active")), frameworkId);
    }

    // Makes the stored criteria equal to the requested list (plain replace):
    // - an item with an id updates that criterion and keeps its id, so whatever points to it later
    //   (interview questions) keeps working;
    // - an item without an id becomes a new criterion with a new id;
    // - a stored criterion missing from the list is deleted (update() has already refused this for a criterion
    //   that has interview questions).
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

    // Jira 220: an interview question points to its criterion by id and V9 forbids deleting a criterion that still
    // has questions (ON DELETE RESTRICT). PUT deletes every stored criterion left out of the list, so such a
    // criterion must be sent back with its id; otherwise the edit is refused with 409 and nothing changes.
    // Sending the same name without the id counts as leaving it out, because it would create a new row.
    // The framework row is locked, but a question added by a transaction that has not committed yet is invisible
    // here; the foreign key still stops that deletion and translateConstraintViolation() gives the same 409.
    private void requireNoQuestionsOnRemovedCriteria(UUID frameworkId, List<CompetencyCriterion> current,
                                                     List<CompetencyCriterionRequest> requested) {
        Set<UUID> keptIds = new HashSet<>();
        for (var item : requested) {
            if (item.id() != null) {
                keptIds.add(item.id());
            }
        }
        List<CompetencyCriterion> removed = current.stream()
                .filter(criterion -> !keptIds.contains(criterion.getId()))
                .toList();
        if (removed.isEmpty()) {
            return;
        }
        Set<UUID> withQuestions = new HashSet<>(jdbc.queryForList(CRITERIA_WITH_QUESTIONS_QUERY, UUID.class,
                frameworkId));
        // current is in sortOrder, so the names are listed in the order the user sees them.
        List<String> names = removed.stream()
                .filter(criterion -> withQuestions.contains(criterion.getId()))
                .map(CompetencyCriterion::getName)
                .toList();
        if (!names.isEmpty()) {
            throw criterionInUse(names);
        }
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

    // The status the framework has after an edit. Without a status in the request the current one is kept.
    // DRAFT can become ACTIVE, but an ACTIVE framework never goes back to DRAFT (409): positions and interview
    // evaluation forms rely on it staying complete.
    private static CompetencyFrameworkStatus statusAfterEdit(CompetencyFramework framework,
                                                             CompetencyFrameworkStatus requested) {
        if (requested == null) {
            return framework.getStatus();
        }
        if (framework.getStatus() == CompetencyFrameworkStatus.ACTIVE
                && requested == CompetencyFrameworkStatus.DRAFT) {
            throw new ApiException(HttpStatus.CONFLICT, "COMPETENCY_FRAMEWORK_ALREADY_ACTIVE",
                    "Khung năng lực đã hoàn chỉnh (ACTIVE) không thể chuyển lại thành bản nháp (DRAFT).",
                    Map.of("status", "Khung đang ACTIVE chỉ có thể giữ trạng thái ACTIVE."));
        }
        return requested;
    }

    // Jira 213: interview evaluation forms are scored with a complete (ACTIVE) framework, so the weights of its
    // criteria must total exactly 100%. Each weight has at most two decimals (CompetencyCriterionRequest), so the
    // BigDecimal sum is exact: 33.33 + 33.33 + 33.34 is exactly 100.00, with no rounding error as with double.
    // An empty list totals 0, so a complete framework always has at least one criterion.
    private static void requireCompleteWeights(List<CompetencyCriterionRequest> requested) {
        BigDecimal total = BigDecimal.ZERO;
        for (var item : requested) {
            total = total.add(item.weight());
        }
        // compareTo ignores the number of decimals, so 100 and 100.00 are equal (equals would say they differ).
        if (total.compareTo(FULL_WEIGHT) != 0) {
            // Shown with two decimals like every weight, e.g. 90.00 or 100.01.
            String shown = total.setScale(2).toPlainString();
            throw new ApiException(HttpStatus.BAD_REQUEST, "COMPETENCY_FRAMEWORK_WEIGHT_TOTAL_INVALID",
                    "Khung năng lực hoàn chỉnh (ACTIVE) cần tổng trọng số các tiêu chí đúng 100%; tổng hiện tại là "
                            + shown + "%.",
                    Map.of("criteria", "Tổng trọng số hiện tại là " + shown + "%, cần đúng 100%."));
        }
    }

    // The last step of every write. It flushes all pending changes and checks the V8 deferred UNIQUE constraints
    // now instead of at COMMIT, so a duplicate becomes a 409 here. With the framework row locked and the names
    // checked above, this is a safety net for writes that do not go through this service.
    // The flush also runs the criterion deletions, so a deletion refused by the V9 foreign key ends here as a 409.
    private void checkCriteriaNow() {
        try {
            criteria.checkUniqueConstraintsNow();
        } catch (DataIntegrityViolationException exception) {
            throw translateConstraintViolation(exception);
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
    // 23505 is a duplicate (UNIQUE), 23503 a foreign key refusal: here, deleting a criterion that a question
    // committed meanwhile points to (V9).
    private RuntimeException translateConstraintViolation(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getMessage() != null) {
                if ("23505".equals(sql.getSQLState())) {
                    if (sql.getMessage().contains("competency_frameworks_code_key")) {
                        return duplicateCode();
                    }
                    if (sql.getMessage().contains("competency_criteria_framework_name_key")) {
                        return duplicateCriterionName(Map.of());
                    }
                }
                if ("23503".equals(sql.getSQLState())
                        && sql.getMessage().contains("interview_questions_criterion_id_fkey")) {
                    // PostgreSQL reports only the criterion id, not its name, so the response lists no names.
                    return criterionInUse(List.of());
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

    // names: the criteria that still have questions, or empty when they are not known.
    private static ApiException criterionInUse(List<String> names) {
        String detail = "Hãy giữ lại (gửi kèm id) các tiêu chí đang có câu hỏi phỏng vấn"
                + (names.isEmpty() ? "." : ": " + String.join(", ", names) + ".");
        return new ApiException(HttpStatus.CONFLICT, "COMPETENCY_CRITERION_IN_USE",
                "Không thể xóa tiêu chí đang có câu hỏi phỏng vấn khỏi khung năng lực.",
                Map.of("criteria", detail));
    }

    private static ApiException duplicateCriterionName(Map<String, String> fieldErrors) {
        return new ApiException(HttpStatus.CONFLICT, "COMPETENCY_CRITERION_NAME_DUPLICATE",
                "Tên tiêu chí trong một khung năng lực không được trùng nhau.", fieldErrors);
    }
}
