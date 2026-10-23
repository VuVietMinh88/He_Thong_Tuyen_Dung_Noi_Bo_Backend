package vn.ttcs.recruitment.evaluation;

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
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthSession;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.ApplicationAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.competency.EvaluationCriteria;
import vn.ttcs.recruitment.competency.EvaluationCriteriaService;
import vn.ttcs.recruitment.messaging.CandidateNoticeEmails;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Own scorecards, server-calculated weighted results, blind peer review and auditable recruiter reopening. */
@Service
public class EvaluationService {
    private final EvaluationRepository data;
    private final EvaluationCriteriaService criteria;
    private final AuthService auth;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final PermissionService permissions;
    private final CandidateAccess candidates;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ObjectMapper json;
    private final TransactionTemplate reads;
    private final TransactionTemplate auditedReads;
    private final TransactionTemplate writes;
    private final CandidateNoticeEmails notices;

    // Task 533: notices decides the candidate's thank-you email when an interview is completed.
    public EvaluationService(EvaluationRepository data, EvaluationCriteriaService criteria, AuthService auth,
            AccountRepository accounts, AuthSessionRepository sessions, PermissionService permissions,
            CandidateAccess candidates, JdbcTemplate jdbc, Clock clock, ObjectMapper json,
            PlatformTransactionManager transactions, CandidateNoticeEmails notices) {
        this.notices = notices;
        this.data = data; this.criteria = criteria; this.auth = auth; this.accounts = accounts;
        this.sessions = sessions; this.permissions = permissions; this.candidates = candidates;
        this.jdbc = jdbc; this.clock = clock; this.json = json;
        reads = new TransactionTemplate(transactions);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setReadOnly(true);
        auditedReads = new TransactionTemplate(transactions);
        auditedReads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        // Status and scope share one snapshot. Only the granted access audit writes; no scorecard is mutated.
        writes = new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(5);
    }

    /** Explicit and idempotent: GET never generates a form, repeating POST keeps the original snapshot. */
    public EvaluationView create(Jwt jwt, UUID interviewId) {
        return write(() -> {
            Writer writer = lockWriter(jwt);
            evaluationWriter(jwt, writer);
            var interview = data.interview(interviewId, true).orElseThrow(EvaluationService::interviewNotFound);
            panelWriter(jwt, writer, interview);
            var existing = data.own(interviewId, writer.account().getId(), true);
            panelWriter(jwt, writer, interview);
            if (existing.isPresent()) return existing.get();
            EvaluationCriteria snapshot = criteria.forPosition(interview.positionId());
            // forPosition can wait for position/framework editors; recheck identity, current grants and panel after it.
            panelWriter(jwt, writer, interview);
            validateSnapshot(snapshot);
            UUID id = data.create(interview, writer.account().getId(), snapshot, now());
            return data.find(interviewId, id, false).orElseThrow();
        });
    }

    public EvaluationView get(Jwt jwt, UUID interviewId) {
        return read(() -> {
            Account actor = reader(jwt);
            var interview = data.interview(interviewId, false).orElseThrow(EvaluationService::interviewNotFound);
            panel(interview, actor.getId());
            return data.own(interviewId, actor.getId(), false).orElseThrow(EvaluationService::formNotFound);
        });
    }

    public EvaluationView save(Jwt jwt, UUID interviewId, EvaluationSaveRequest request, boolean submit) {
        return write(() -> {
            Writer writer = lockWriter(jwt);
            evaluationWriter(jwt, writer);
            var interview = data.interview(interviewId, true).orElseThrow(EvaluationService::interviewNotFound);
            panelWriter(jwt, writer, interview);
            var form = data.own(interviewId, writer.account().getId(), true).orElseThrow(EvaluationService::formNotFound);
            panelWriter(jwt, writer, interview);
            version(form, request.expectedVersion());
            if (!"DRAFT".equals(form.status())) throw conflict("EVALUATION_LOCKED", "Phiếu đã nộp và không thể chỉnh sửa.");
            Instant now = now();
            if (submit && now.isBefore(interview.endsAt())) {
                throw conflict("EVALUATION_NOT_FINISHED", "Chỉ nộp đánh giá sau khi buổi phỏng vấn kết thúc.");
            }
            List<EvaluationAnswerRequest> answers = answers(form, request, submit);
            BigDecimal total = total(form, answers);
            data.save(form, answers, request.conclusion(), total, submit, now);
            EvaluationView result = data.find(interviewId, form.id(), false).orElseThrow();
            if (submit) {
                data.event(result, "EVALUATION_SUBMITTED", writer.account().getId(), now, null,
                        json.writeValueAsString(result));
                // Task 533: a submitted evaluation proves the interview took place; the first one decides the
                // candidate's thank-you email (later ones record nothing more).
                notices.interviewCompleted(interviewId, interview.applicationId());
            }
            return result;
        });
    }

    public List<EvaluationView> peers(Jwt jwt, UUID interviewId) {
        return read(() -> {
            Account actor = reader(jwt);
            var interview = data.interview(interviewId, false).orElseThrow(EvaluationService::interviewNotFound);
            panel(interview, actor.getId());
            var own = data.own(interviewId, actor.getId(), false);
            if (own.isEmpty() || !"SUBMITTED".equals(own.get().status())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "EVALUATION_BLIND_REVIEW",
                        "Bạn cần nộp phiếu đánh giá của mình trước khi xem đánh giá của người khác.");
            }
            return data.peers(interviewId, actor.getId());
        });
    }

    /** Gives an authorized recruiter the current optimistic version without exposing a blind scorecard. */
    public EvaluationReopenView status(Jwt jwt, UUID interviewId, UUID formId) {
        return execute(auditedReads, () -> {
            validJwt(jwt);
            Account actor = auth.requireActiveAccount(jwt);
            recruiterPermissions(actor);
            CandidateReader scope = recruiterScopeReader(jwt, actor.getId());
            var interview = data.interview(interviewId, false).orElseThrow(EvaluationService::interviewNotFound);
            ApplicationAccess application = scopedApplication(scope, interview.applicationId());
            EvaluationReopenView result = data.status(interviewId, formId).orElseThrow(EvaluationService::formNotFound);
            candidates.recordGranted(scope, CandidateAccessKind.PROFILE, application.candidateId(),
                    List.of(application.applicationId()));
            return result;
        });
    }

    public EvaluationReopenView reopen(Jwt jwt, UUID interviewId, UUID formId, EvaluationReopenRequest request) {
        String reason = text(request.reason(), true, "reason");
        return write(() -> {
            Writer writer = lockWriter(jwt);
            recruiter(jwt, writer);
            var existingInterview = data.interview(interviewId, false).orElseThrow(EvaluationService::interviewNotFound);
            // Team handovers take the same requisition lock, so the scope remains valid until reopening commits.
            data.lockRequisition(existingInterview.requisitionId());
            reopenScope(jwt, writer, existingInterview.applicationId());
            var interview = data.interview(interviewId, true).orElseThrow(EvaluationService::interviewNotFound);
            reopenScope(jwt, writer, interview.applicationId());
            activeInterview(interview);
            var form = data.find(interviewId, formId, true).orElseThrow(EvaluationService::formNotFound);
            reopenScope(jwt, writer, interview.applicationId());
            version(form, request.expectedVersion());
            if (!"SUBMITTED".equals(form.status()) || form.firstSubmittedAt() == null) {
                throw conflict("EVALUATION_NOT_SUBMITTED", "Chỉ mở lại phiếu đã nộp.");
            }
            Instant now = now();
            if (!now.isBefore(form.firstSubmittedAt().plus(24, ChronoUnit.HOURS))) {
                throw conflict("EVALUATION_REOPEN_EXPIRED", "Đã hết 24 giờ tính từ lần nộp đánh giá đầu tiên.");
            }
            data.reopen(form.id(), now);
            data.event(form, "EVALUATION_REOPENED", writer.account().getId(), now, reason, null);
            var result = data.status(interviewId, form.id()).orElseThrow();
            CandidateReader scope = reopenReader(jwt, writer);
            var application = candidates.findApplication(scope, interview.applicationId()).orElseThrow();
            candidates.recordGranted(scope, CandidateAccessKind.PROFILE, application.candidateId(), List.of(application.applicationId()));
            return result;
        });
    }

    private Account reader(Jwt jwt) {
        validJwt(jwt);
        Account actor = auth.requireActiveAccount(jwt);
        AccessScope.read(permissions.forUser(actor.getId()), PermissionModule.EVALUATIONS).orDeny();
        return actor;
    }
    private record Writer(Account account, AuthSession session) { }
    private Writer lockWriter(Jwt jwt) {
        jdbc.execute("SET LOCAL lock_timeout='2s'"); jdbc.execute("SET LOCAL statement_timeout='3s'");
        validJwt(jwt);
        UUID actor; UUID session;
        try { actor = UUID.fromString(jwt.getSubject()); session = UUID.fromString(jwt.getId()); }
        catch (IllegalArgumentException | NullPointerException failure) { throw AuthenticationFailureException.sessionInvalid(); }
        Account account = accounts.findByIdForUpdate(actor).filter(Account::isAccessAllowed)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        AuthSession locked = sessions.findByIdForUpdate(session).orElseThrow(AuthenticationFailureException::sessionInvalid);
        Writer writer = new Writer(account, locked); fresh(jwt, writer); return writer;
    }
    private void fresh(Jwt jwt, Writer writer) {
        validJwt(jwt);
        if (!writer.session().getUserId().equals(writer.account().getId()) || !writer.session().isActive(clock.instant())) {
            throw AuthenticationFailureException.sessionInvalid();
        }
    }
    private void evaluationWriter(Jwt jwt, Writer writer) {
        fresh(jwt, writer);
        var granted = permissions.forUser(writer.account().getId());
        AccessScope.read(granted, PermissionModule.EVALUATIONS).orDeny();
        AccessScope.write(granted, PermissionModule.EVALUATIONS).orDeny();
    }
    private void panelWriter(Jwt jwt, Writer writer, EvaluationRepository.Interview interview) {
        evaluationWriter(jwt, writer); panel(interview, writer.account().getId());
    }
    private void panel(EvaluationRepository.Interview interview, UUID actor) {
        if (!data.participant(interview.id(), actor)) throw new AccessDeniedException("Only active invited panel members may access evaluations");
        activeInterview(interview);
    }
    private static void activeInterview(EvaluationRepository.Interview interview) {
        if (!"SCHEDULED".equals(interview.status())) throw conflict("INTERVIEW_CANCELLED", "Lịch phỏng vấn đã bị hủy.");
    }
    private void recruiter(Jwt jwt, Writer writer) {
        fresh(jwt, writer);
        recruiterPermissions(writer.account());
    }
    private void recruiterPermissions(Account actor) {
        if (!actor.getRoles().contains(Role.RECRUITER)) throw new AccessDeniedException("Only a recruiter may manage evaluation reopening");
        var granted = permissions.forUser(actor.getId());
        AccessScope.read(granted, PermissionModule.INTERVIEWS).orDeny();
        AccessScope.write(granted, PermissionModule.INTERVIEWS).orDeny();
    }
    private CandidateReader reopenReader(Jwt jwt, Writer writer) {
        recruiter(jwt, writer);
        return recruiterScopeReader(jwt, writer.account().getId());
    }
    private CandidateReader recruiterScopeReader(Jwt jwt, UUID actorId) {
        CandidateReader reader = candidates.requireReader(jwt);
        var granted = permissions.forUser(actorId);
        boolean scoped = reader.scope() == AccessScope.SCOPED
                || AccessScope.read(granted, PermissionModule.INTERVIEWS) == AccessScope.SCOPED
                || AccessScope.write(granted, PermissionModule.INTERVIEWS) == AccessScope.SCOPED;
        return new CandidateReader(reader.id(), scoped ? AccessScope.SCOPED : AccessScope.ALL);
    }
    private void reopenScope(Jwt jwt, Writer writer, UUID applicationId) {
        CandidateReader reader = reopenReader(jwt, writer);
        scopedApplication(reader, applicationId);
    }
    private ApplicationAccess scopedApplication(CandidateReader reader, UUID applicationId) {
        var application = candidates.findApplication(reader, applicationId).orElseThrow(EvaluationService::interviewNotFound);
        if (!application.readable()) throw new ScopeDenied(reader, application.candidateId(), applicationId);
        return application;
    }

    private static void validateSnapshot(EvaluationCriteria snapshot) {
        BigDecimal total = snapshot.criteria().stream().map(item -> item.weight()).reduce(BigDecimal.ZERO, BigDecimal::add);
        var ids = new HashSet<UUID>();
        boolean invalid = snapshot.criteria().isEmpty() || snapshot.criteria().size() > 50
                || total.compareTo(new BigDecimal("100")) != 0
                || snapshot.criteria().stream().anyMatch(item -> !ids.add(item.id()) || item.weight().signum() <= 0);
        if (invalid) throw conflict("EVALUATION_FRAMEWORK_INVALID", "Khung năng lực cần từ 1 đến 50 tiêu chí, tổng trọng số đúng 100%.");
    }
    private static List<EvaluationAnswerRequest> answers(EvaluationView form, EvaluationSaveRequest request, boolean submit) {
        if (request.items() == null || request.items().size() > 50) throw invalid("items", "Danh sách điểm không hợp lệ.");
        Set<UUID> expected = new HashSet<>(); form.items().forEach(item -> expected.add(item.criterionId()));
        Set<UUID> supplied = new HashSet<>();
        var normalized = new java.util.ArrayList<EvaluationAnswerRequest>();
        for (var item : request.items()) {
            if (item == null || item.criterionId() == null || !expected.contains(item.criterionId()) || !supplied.add(item.criterionId())) {
                throw invalid("items", "Mỗi tiêu chí trong phiếu chỉ được xuất hiện một lần.");
            }
            if (item.score() != null && (item.score() < 1 || item.score() > 5)) throw invalid("items", "Điểm phải là số nguyên từ 1 đến 5.");
            if (submit && item.score() == null) throw invalid("items", "Cần chấm điểm tất cả tiêu chí trước khi nộp.");
            normalized.add(new EvaluationAnswerRequest(item.criterionId(), item.score(), text(item.comment(), false, "comment")));
        }
        if (submit && (!supplied.equals(expected) || request.conclusion() == null)) {
            throw invalid("items", "Cần chấm đầy đủ tiêu chí và chọn kết luận trước khi nộp.");
        }
        return List.copyOf(normalized);
    }
    private static BigDecimal total(EvaluationView form, List<EvaluationAnswerRequest> answers) {
        if (answers.size() != form.items().size() || answers.stream().anyMatch(item -> item.score() == null)) return null;
        Map<UUID, BigDecimal> weights = new HashMap<>(); form.items().forEach(item -> weights.put(item.criterionId(), item.weight()));
        BigDecimal sum = BigDecimal.ZERO;
        for (var answer : answers) sum = sum.add(weights.get(answer.criterionId()).multiply(BigDecimal.valueOf(answer.score())));
        return sum.divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    }
    private static void version(EvaluationView form, Long expected) {
        if (expected == null || expected < 0) throw invalid("expectedVersion", "Cần phiên bản phiếu đang hiển thị.");
        if (form.version() != expected) throw conflict("EVALUATION_VERSION_CHANGED", "Phiếu vừa thay đổi. Vui lòng tải lại.");
    }
    private static String text(String value, boolean required, String field) {
        String result = value == null ? null : Normalizer.normalize(value, Normalizer.Form.NFC).replaceAll("(?U)^\\s+|\\s+$", "");
        if (result != null && result.isEmpty()) result = null;
        if (required && result == null || result != null && (result.length() > 2000
                || result.codePoints().anyMatch(code -> Character.isISOControl(code) && code != '\n' && code != '\r' && code != '\t'))) {
            throw invalid(field, "Nội dung phải có chữ, không quá 2000 ký tự và không có ký tự điều khiển.");
        }
        return result;
    }
    private void validJwt(Jwt jwt) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant())) throw AuthenticationFailureException.sessionInvalid();
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private <T> T read(Supplier<T> action) { return execute(reads, action); }
    private <T> T write(Supplier<T> action) { return execute(writes, action); }
    private <T> T execute(TransactionTemplate transaction, Supplier<T> action) {
        try { return Objects.requireNonNull(transaction.execute(status -> action.get())); }
        catch (ScopeDenied denied) {
            candidates.recordDenied(denied.reader, CandidateAccessKind.PROFILE, denied.candidate, denied.application);
            throw new AccessDeniedException("Application is outside the recruiter's scope");
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof TransientDataAccessException || cause instanceof TransactionTimedOutException
                        || cause instanceof SQLException sql && Set.of("55P03", "57014", "40P01").contains(sql.getSQLState())) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EVALUATION_BUSY", "Phiếu đang được xử lý. Vui lòng thử lại.");
                }
            }
            throw failure;
        }
    }
    private static final class ScopeDenied extends RuntimeException {
        final CandidateReader reader; final UUID candidate; final UUID application;
        ScopeDenied(CandidateReader reader, UUID candidate, UUID application) { this.reader = reader; this.candidate = candidate; this.application = application; }
    }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    private static ApiException invalid(String field, String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra dữ liệu đã nhập.", Map.of(field, message)); }
    private static ApiException interviewNotFound() { return new ApiException(HttpStatus.NOT_FOUND, "INTERVIEW_NOT_FOUND", "Không tìm thấy lịch phỏng vấn."); }
    private static ApiException formNotFound() { return new ApiException(HttpStatus.NOT_FOUND, "EVALUATION_NOT_FOUND", "Chưa có phiếu đánh giá cho người phỏng vấn này."); }
}
