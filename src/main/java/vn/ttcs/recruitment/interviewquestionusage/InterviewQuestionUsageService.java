package vn.ttcs.recruitment.interviewquestionusage;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthSession;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import vn.ttcs.recruitment.security.PermissionService;

import java.sql.SQLException;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Questions for the invited panel, with one immutable record per question asked in an interview. */
@Service
public class InterviewQuestionUsageService {
    private final InterviewQuestionUsageRepository data;
    private final AuthService auth;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final PermissionService permissions;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    public InterviewQuestionUsageService(InterviewQuestionUsageRepository data, AuthService auth, AccountRepository accounts,
            AuthSessionRepository sessions, PermissionService permissions, JdbcTemplate jdbc, Clock clock,
            PlatformTransactionManager transactions) {
        this.data = data; this.auth = auth; this.accounts = accounts; this.sessions = sessions;
        this.permissions = permissions; this.jdbc = jdbc; this.clock = clock;
        reads = new TransactionTemplate(transactions);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setReadOnly(true);
        writes = new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(5);
    }

    public InterviewQuestionUsageView.Page<InterviewQuestionUsageView> questions(Jwt jwt, UUID interviewId,
            UUID criterionId, int page, int size) {
        return execute(reads, () -> {
            UUID actor = reader(jwt);
            var interview = visible(interviewId, actor, false);
            page(page, size);
            var form = data.form(interview.id(), actor, false).orElseThrow(InterviewQuestionUsageService::formRequired);
            return data.questions(form.id(), interview.id(), criterionId, page, size);
        });
    }

    public InterviewQuestionUsageView.Page<InterviewQuestionUsageView.History> history(Jwt jwt, UUID interviewId,
            UUID criterionId, int page, int size) {
        return execute(reads, () -> {
            UUID actor = reader(jwt);
            var interview = visible(interviewId, actor, false);
            page(page, size);
            return data.history(interview, criterionId, page, size);
        });
    }

    public InterviewQuestionUsageView mark(Jwt jwt, UUID interviewId, UUID questionId, long expectedVersion) {
        if (expectedVersion < 0) throw invalid("expectedVersion", "Phiên bản lịch phải từ 0 trở lên.");
        return execute(writes, () -> {
            Writer writer = lockWriter(jwt);
            var initial = visible(interviewId, writer.actor(), false);
            // The same order as scheduling and application changes; the parent locks prevent a concurrent
            // rejection, withdrawal or requisition closure from racing with the final eligibility check.
            data.lockParents(initial);
            fresh(jwt, writer);
            var interview = visible(interviewId, writer.actor(), true);
            fresh(jwt, writer);
            active(interview);
            if (interview.version() != expectedVersion) throw conflict("INTERVIEW_VERSION_CHANGED", "Lịch vừa thay đổi. Vui lòng tải lại.");
            var form = data.form(interviewId, writer.actor(), true).orElseThrow(InterviewQuestionUsageService::formRequired);
            fresh(jwt, writer);
            if (!"DRAFT".equals(form.status()) || form.firstSubmittedAt() != null) {
                throw conflict("EVALUATION_LOCKED", "Không thay đổi câu hỏi đã hỏi sau khi đã nộp phiếu đánh giá.");
            }
            var existing = data.asked(interviewId, questionId);
            if (existing.isPresent()) return existing.get();
            var question = data.eligibleQuestion(form.id(), questionId).orElseThrow(InterviewQuestionUsageService::questionNotFound);
            fresh(jwt, writer);
            if (!data.participant(interviewId, writer.actor())) throw notFound();
            data.mark(interview, question, writer.actor(), clock.instant().truncatedTo(ChronoUnit.MICROS));
            return data.asked(interviewId, questionId).orElseThrow();
        });
    }

    private UUID reader(Jwt jwt) {
        validJwt(jwt);
        UUID actor = auth.requireActiveAccount(jwt).getId();
        grants(actor);
        return actor;
    }
    private void grants(UUID actor) {
        var granted = permissions.forUser(actor);
        AccessScope.read(granted, PermissionModule.INTERVIEWS).orDeny();
        AccessScope.read(granted, PermissionModule.EVALUATIONS).orDeny();
        AccessScope.write(granted, PermissionModule.EVALUATIONS).orDeny();
    }
    private InterviewQuestionUsageRepository.Interview visible(UUID id, UUID actor, boolean lock) {
        var interview = data.interview(id, lock).orElseThrow(InterviewQuestionUsageService::notFound);
        // An uninvited caller learns neither the existence, application nor cancellation state of an interview.
        if (!data.participant(id, actor)) throw notFound();
        if (!"SCHEDULED".equals(interview.status())) throw conflict("INTERVIEW_CANCELLED", "Lịch phỏng vấn đã bị hủy.");
        return interview;
    }
    private static void active(InterviewQuestionUsageRepository.Interview interview) {
        if (!"APPROVED".equals(interview.requisitionStatus()) || Set.of("REJECTED", "WITHDRAWN", "HIRED").contains(interview.stage())) {
            throw conflict("INTERVIEW_APPLICATION_INACTIVE", "Hồ sơ hoặc yêu cầu tuyển dụng không còn hoạt động.");
        }
    }
    private record Writer(UUID actor, AuthSession session) { }
    private Writer lockWriter(Jwt jwt) {
        jdbc.execute("SET LOCAL lock_timeout='2s'"); jdbc.execute("SET LOCAL statement_timeout='3s'");
        validJwt(jwt);
        UUID actor; UUID session;
        try { actor = UUID.fromString(jwt.getSubject()); session = UUID.fromString(jwt.getId()); }
        catch (IllegalArgumentException | NullPointerException failure) { throw AuthenticationFailureException.sessionInvalid(); }
        accounts.findByIdForUpdate(actor).filter(Account::isAccessAllowed).orElseThrow(AuthenticationFailureException::sessionInvalid);
        AuthSession current = sessions.findByIdForUpdate(session).orElseThrow(AuthenticationFailureException::sessionInvalid);
        Writer writer = new Writer(actor, current); fresh(jwt, writer); return writer;
    }
    private void fresh(Jwt jwt, Writer writer) {
        validJwt(jwt);
        if (!writer.session().getUserId().equals(writer.actor()) || !writer.session().isActive(clock.instant())) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        grants(writer.actor());
    }
    private void validJwt(Jwt jwt) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant())) {
            throw AuthenticationFailureException.sessionInvalid();
        }
    }
    private static void page(int page, int size) {
        if (page < 0 || page > 100000) throw invalid("page", "Số trang phải từ 0 đến 100000.");
        if (size < 1 || size > 100) throw invalid("size", "Số dòng mỗi trang phải từ 1 đến 100.");
    }
    private <T> T execute(TransactionTemplate transaction, Supplier<T> action) {
        try { return Objects.requireNonNull(transaction.execute(status -> action.get())); }
        catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof TransientDataAccessException || cause instanceof TransactionTimedOutException
                        || cause instanceof SQLException sql && Set.of("55P03", "57014", "40P01").contains(sql.getSQLState())) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "INTERVIEW_QUESTIONS_BUSY", "Câu hỏi đang được xử lý. Vui lòng thử lại.");
                }
            }
            throw failure;
        }
    }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "INTERVIEW_NOT_FOUND", "Không tìm thấy lịch phỏng vấn."); }
    private static ApiException questionNotFound() { return new ApiException(HttpStatus.NOT_FOUND, "INTERVIEW_QUESTION_NOT_FOUND", "Không tìm thấy câu hỏi trong phiếu đánh giá của buổi phỏng vấn."); }
    private static ApiException formRequired() { return conflict("QUESTION_EVALUATION_REQUIRED", "Hãy tạo phiếu đánh giá của bạn trước khi sử dụng câu hỏi."); }
    private static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    static ApiException invalid(String field, String message) { return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra dữ liệu đã nhập.", Map.of(field, message)); }
}
