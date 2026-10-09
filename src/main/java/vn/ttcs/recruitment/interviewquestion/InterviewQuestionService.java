package vn.ttcs.recruitment.interviewquestion;

import org.springframework.dao.DataIntegrityViolationException;
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
import vn.ttcs.recruitment.competency.CompetencyCriterion;
import vn.ttcs.recruitment.competency.CompetencyCriterionRepository;
import vn.ttcs.recruitment.competency.CompetencyFramework;
import vn.ttcs.recruitment.competency.CompetencyFrameworkRepository;
import vn.ttcs.recruitment.security.PermissionService;

import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

// Jira 221: create, edit and read the questions of the interview question bank (story S2-07). Every question belongs
// to one criterion of a competency framework, so it is checked against that criterion.
// The question bank is organization data like the frameworks: reading needs ORGANIZATION_READ_ALL (every internal
// role, interviewers included), writing needs ORGANIZATION_WRITE_ALL (ADMIN, HR_MANAGER).
// Jira 222: InterviewQuestionRequest checks each field on its own; this service checks the criterion and that the
// criterion does not already hold the same question.
// Jira 223: search and filter the bank by text, position, criterion, difficulty and active.
@Service
public class InterviewQuestionService {
    // A run of characters Unicode calls white space: spaces (non-breaking ones too), tabs and line breaks.
    private static final Pattern SPACES = Pattern.compile("\\p{IsWhite_Space}+");
    // Unicode category Cc (U+0000-U+001F, U+007F-U+009F). PostgreSQL cannot take U+0000 in a text parameter.
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cc}");
    // Same limits as the other lists (departments, positions, competency frameworks).
    private static final int MAX_SEARCH_TEXT = 255;
    private static final int MAX_PAGE_SIZE = 100;

    private final InterviewQuestionRepository questions;
    private final InterviewQuestionSearchRepository search;
    private final CompetencyCriterionRepository criteria;
    private final CompetencyFrameworkRepository frameworks;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final AuthService auth;
    private final PermissionService permissions;
    private final Clock clock;

    public InterviewQuestionService(InterviewQuestionRepository questions, InterviewQuestionSearchRepository search,
                                    CompetencyCriterionRepository criteria, CompetencyFrameworkRepository frameworks,
                                    AccountRepository accounts, AuthSessionRepository sessions, AuthService auth,
                                    PermissionService permissions, Clock clock) {
        this.questions = questions;
        this.search = search;
        this.criteria = criteria;
        this.frameworks = frameworks;
        this.accounts = accounts;
        this.sessions = sessions;
        this.auth = auth;
        this.permissions = permissions;
        this.clock = clock;
    }

    // Jira 223: one page of the questions that match every filter given; a filter left out (null) matches all.
    // Checks in order: read permission (403), the search text and paging (400 VALIDATION_ERROR), the position
    // (400 INVALID_POSITION), the criterion (400 INVALID_COMPETENCY_CRITERION).
    // REPEATABLE_READ: the checks, the total and the page all come from the same snapshot, so totalElements always
    // matches the items even while HR is saving questions. Nothing is locked, so a search never waits for an edit.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InterviewQuestionPage list(Jwt jwt, String query, UUID positionId, UUID criterionId,
                                      InterviewQuestionDifficulty difficulty, Boolean active, int page, int size) {
        requireReadAccess(jwt);
        String text = searchText(query);
        requireValidSearch(text, page, size);
        // An unknown id is an error rather than an empty page, so a screen can tell "this position or criterion no
        // longer exists" from "it has no questions yet".
        if (positionId != null && !search.positionExists(positionId)) {
            throw unknownPosition();
        }
        if (criterionId != null && !criteria.existsById(criterionId)) {
            throw unknownCriterion();
        }
        return search.search(text, positionId, criterionId, difficulty, active, page, size);
    }

    // REPEATABLE_READ: the question, its criterion and its framework come from the same snapshot, never from the
    // middle of an edit.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InterviewQuestionView get(Jwt jwt, UUID id) {
        requireReadAccess(jwt);
        var question = questions.findById(id).orElseThrow(InterviewQuestionService::notFound);
        // The V9 foreign key keeps the criterion of a question, and the V8 foreign key the framework of a criterion,
        // so both rows always exist.
        var criterion = criteria.findById(question.getCriterionId()).orElseThrow();
        var framework = frameworks.findById(criterion.getFrameworkId()).orElseThrow();
        return InterviewQuestionView.from(question, criterion, framework);
    }

    @Transactional
    public InterviewQuestionView create(Jwt jwt, InterviewQuestionRequest request) {
        requireWriteAccess(jwt);
        Target target = lockCriterion(request.criterionId());
        requireNewText(request.criterionId(), request.content(), null);
        // Without active in the request, a new question is in use.
        boolean active = request.active() == null || request.active();
        var question = new InterviewQuestion(request.criterionId(), request.content(), request.difficultyLevel(),
                request.answerHint(), active, now());
        try {
            question = questions.saveAndFlush(question);
        } catch (DataIntegrityViolationException exception) {
            throw translateConstraintViolation(exception);
        }
        return InterviewQuestionView.from(question, target.criterion(), target.framework());
    }

    // Checks in order: the question exists (404), then the criterion it should point to (400), then that the
    // criterion has no other question with the same text (409).
    @Transactional
    public InterviewQuestionView update(Jwt jwt, UUID id, InterviewQuestionRequest request) {
        requireWriteAccess(jwt);
        // Locked before the framework, so two edits of the same question run one after the other.
        InterviewQuestion question = questions.findByIdForUpdate(id).orElseThrow(InterviewQuestionService::notFound);
        Target target = lockCriterion(request.criterionId());
        requireNewText(request.criterionId(), request.content(), question);
        // Without active in the request, the question stays in use or out of use as it is.
        boolean active = request.active() == null ? question.isActive() : request.active();
        question.update(request.criterionId(), request.content(), request.difficultyLevel(), request.answerHint(),
                active, now());
        try {
            questions.flush();
        } catch (DataIntegrityViolationException exception) {
            throw translateConstraintViolation(exception);
        }
        return InterviewQuestionView.from(question, target.criterion(), target.framework());
    }

    // Finds the criterion a question will point to and locks the row of its framework with SELECT ... FOR SHARE
    // (the same lock PositionService takes to assign a framework, Jira 214):
    // - an edit of the framework (CompetencyFrameworkService locks it FOR UPDATE and may delete criteria) waits until
    //   this write commits, and then sees the question, so it refuses to delete its criterion with 409;
    // - if such an edit is running now, this waits for it and then reads the criterion again, so a criterion the edit
    //   has just deleted becomes a 400 here instead of an error from the foreign key;
    // - question writes on the same framework share the lock and do not wait for each other.
    // Jira 222: then the criterion row itself is locked with SELECT ... FOR UPDATE. Two question writes on the same
    // criterion run one after the other, so requireNewText of the second one sees the question the first one saved.
    // A criterion of a DRAFT framework is accepted: HR may prepare the questions while the framework is still drafted
    // (the framework only has to be ACTIVE to be assigned to a position, Jira 214).
    private Target lockCriterion(UUID criterionId) {
        UUID frameworkId = criteria.findFrameworkIdById(criterionId)
                .orElseThrow(InterviewQuestionService::unknownCriterion);
        CompetencyFramework framework = frameworks.findByIdForShare(frameworkId)
                .orElseThrow(InterviewQuestionService::unknownCriterion);
        // Read with the lock, so this is the latest committed criterion row: it may have been deleted or renamed
        // while this request waited. A criterion deleted by direct SQL that has not committed yet makes this wait
        // and then find nothing.
        CompetencyCriterion criterion = criteria.findByIdForUpdate(criterionId)
                .orElseThrow(InterviewQuestionService::unknownCriterion);
        return new Target(criterion, framework);
    }

    // Jira 222: a criterion may not hold the same question twice (409), active or not, otherwise interviewers would
    // find the same question twice under the criterion. The same text on another criterion is allowed: one question
    // may check several criteria. Runs after lockCriterion, so no other write of this criterion can add the text
    // meanwhile.
    // edited is the question a PUT changes (null for POST); it never counts as its own duplicate.
    private void requireNewText(UUID criterionId, String content, InterviewQuestion edited) {
        String text = comparableText(content);
        // An edit that keeps the criterion and the text is not checked again. A text repeated before this check
        // existed (or written by direct SQL) can then still be corrected or taken out of use.
        if (edited != null && edited.getCriterionId().equals(criterionId)
                && comparableText(edited.getContent()).equals(text)) {
            return;
        }
        List<InterviewQuestion> sameText = questions.findByCriterionIdOrderByCreatedAtAscIdAsc(criterionId).stream()
                .filter(other -> edited == null || !other.getId().equals(edited.getId()))
                .filter(other -> comparableText(other.getContent()).equals(text))
                .toList();
        if (sameText.isEmpty()) {
            return;
        }
        if (sameText.stream().anyMatch(InterviewQuestion::isActive)) {
            throw duplicateText("Câu hỏi này đã có trong tiêu chí đã chọn.");
        }
        // Only questions out of use have this text: HR should put one of them back in use instead of writing it again.
        throw duplicateText("Câu hỏi này đã có trong tiêu chí đã chọn nhưng đang ngừng dùng; "
                + "hãy dùng lại câu hỏi đó (active = true).");
    }

    // The form in which two question texts are compared. Upper/lower case, the spaces and line breaks between the
    // words and the way a Vietnamese letter is encoded (see InterviewQuestionRequest) do not make another question;
    // any other difference does, punctuation included.
    private static String comparableText(String text) {
        String composed = Normalizer.normalize(text, Normalizer.Form.NFC);
        return SPACES.matcher(composed).replaceAll(" ").toLowerCase(Locale.ROOT);
    }

    // Jira 223: the search text as it is compared with the content. Like a saved question it is put in Unicode form
    // NFC, so a Vietnamese letter typed as a base letter plus combining marks still finds the stored text; each run
    // of spaces, tabs and line breaks becomes one space and the spaces around it are removed. "" means no search.
    // Upper/lower case is left to lower() in the SQL, so both sides use the same rule.
    private static String searchText(String query) {
        if (query == null) {
            return "";
        }
        String composed = Normalizer.normalize(query, Normalizer.Form.NFC);
        return SPACES.matcher(composed).replaceAll(" ").strip();
    }

    // Every wrong parameter is reported at once in fieldErrors, named like the query parameter.
    private static void requireValidSearch(String text, int page, int size) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (text.length() > MAX_SEARCH_TEXT) {
            errors.put("q", "Từ khóa tìm kiếm tối đa 255 ký tự.");
        } else if (CONTROL_CHARACTERS.matcher(text).find()) {
            errors.put("q", "Từ khóa tìm kiếm không được chứa ký tự điều khiển.");
        }
        if (page < 0) {
            errors.put("page", "Số trang phải từ 0 trở lên.");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            errors.put("size", "Số câu hỏi mỗi trang phải từ 1 đến 100.");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Trang, số lượng hoặc từ khóa tìm kiếm câu hỏi phỏng vấn không hợp lệ.", errors);
        }
    }

    private void requireReadAccess(Jwt jwt) {
        requireUnexpiredToken(jwt, clock.instant());
        Account actor = auth.requireActiveAccount(jwt);
        if (!permissions.forUser(actor.getId()).contains("ORGANIZATION_READ_ALL")) {
            throw new AccessDeniedException("Interview question access requires ORGANIZATION_READ_ALL");
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
        // The question and framework rows are locked only after these.
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
            throw new AccessDeniedException("Interview question management requires ORGANIZATION_WRITE_ALL");
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

    // The framework lock stops deletions made through the framework API, and since Jira 222 the criterion row lock
    // also makes a deletion by direct SQL wait for this write. The V9 foreign key (23503) is kept as a safety net: if
    // it still refuses the criterion, the caller gets the same 400, not a 500.
    // InterviewQuestionRequest removes the spaces around the texts, so the V9 text CHECKs (23514) should not fail.
    // Which characters PostgreSQL counts as spaces depends on the database locale, though, so if one is still
    // refused the caller gets a 400 that names the field, not a 500.
    private RuntimeException translateConstraintViolation(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (!(cause instanceof SQLException sql) || sql.getMessage() == null) {
                continue;
            }
            String message = sql.getMessage();
            if ("23503".equals(sql.getSQLState()) && message.contains("interview_questions_criterion_id_fkey")) {
                return unknownCriterion();
            }
            if ("23514".equals(sql.getSQLState()) && message.contains("valid_interview_question_content")) {
                return refusedText("content", "Nội dung câu hỏi không được bắt đầu hoặc kết thúc bằng khoảng trắng.");
            }
            if ("23514".equals(sql.getSQLState()) && message.contains("valid_interview_question_answer_hint")) {
                return refusedText("answerHint",
                        "Gợi ý câu trả lời không được bắt đầu hoặc kết thúc bằng khoảng trắng.");
            }
        }
        return exception;
    }

    // Same code and message as the other field errors of the body (ApiExceptionHandler, VALIDATION_ERROR).
    private static ApiException refusedText(String field, String error) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra dữ liệu đã nhập.",
                Map.of(field, error));
    }

    private static ApiException duplicateText(String error) {
        return new ApiException(HttpStatus.CONFLICT, "INTERVIEW_QUESTION_DUPLICATE",
                "Tiêu chí này đã có câu hỏi phỏng vấn cùng nội dung.", Map.of("content", error));
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "INTERVIEW_QUESTION_NOT_FOUND",
                "Không tìm thấy câu hỏi phỏng vấn.");
    }

    // 400 like other unknown ids sent in a body (INVALID_COMPETENCY_FRAMEWORK): the URL itself was found.
    private static ApiException unknownCriterion() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_COMPETENCY_CRITERION",
                "Tiêu chí đánh giá không tồn tại.",
                Map.of("criterionId", "Không tìm thấy tiêu chí này trong khung năng lực nào."));
    }

    // Jira 223: the positionId filter names no position. 400 like an unknown criterion: the URL itself was found.
    private static ApiException unknownPosition() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_POSITION", "Chức danh không tồn tại.",
                Map.of("positionId", "Không tìm thấy chức danh này."));
    }

    // The criterion a question points to, with its framework for the response.
    private record Target(CompetencyCriterion criterion, CompetencyFramework framework) { }
}
