package vn.ttcs.recruitment.onboarding;

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
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.offer.OfferAccess;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Department templates are copied once. A completed checklist and the hired application finish together. */
@Service
public class OnboardingService {
    private static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(2100, 12, 31);
    private static final Set<String> BUSY_STATES = Set.of("55P03", "57014", "40P01");
    private static final Set<String> TEMPLATE_READ = Set.of("ORGANIZATION_READ_ALL");
    private static final Set<String> TEMPLATE_WRITE = Set.of("ORGANIZATION_READ_ALL", "ORGANIZATION_WRITE_ALL");
    private static final Set<String> CHECKLIST_READ = Set.of("OFFERS_READ_ALL", "CANDIDATES_READ_ALL");
    private static final Set<String> CHECKLIST_WRITE = Set.of("OFFERS_READ_ALL", "OFFERS_WRITE_ALL", "CANDIDATES_READ_ALL");

    private final OnboardingRepository data;
    private final OfferAccess access;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    public OnboardingService(OnboardingRepository data, OfferAccess access, AccountRepository accounts,
                             AuthSessionRepository sessions, JdbcTemplate jdbc, Clock clock,
                             PlatformTransactionManager transactions) {
        this.data = data;
        this.access = access;
        this.accounts = accounts;
        this.sessions = sessions;
        this.jdbc = jdbc;
        this.clock = clock;
        reads = new TransactionTemplate(transactions);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(5);
        // Reading a candidate writes its access audit, so this is deliberately not a read-only transaction.
        writes = new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(5);
    }

    public OnboardingTemplateView.Page templates(Jwt jwt, UUID departmentId, int page, int size) {
        return execute(reads, () -> {
            reader(jwt, TEMPLATE_READ);
            if (page < 0 || size < 1 || size > 100) {
                throw invalid("ONBOARDING_PAGE_INVALID", "Trang hoặc số lượng mẫu tiếp nhận không hợp lệ.");
            }
            return data.templates(departmentId, page, size);
        });
    }

    public OnboardingTemplateView template(Jwt jwt, UUID id) {
        return execute(reads, () -> {
            reader(jwt, TEMPLATE_READ);
            return findTemplate(id, false);
        });
    }

    public OnboardingTemplateView createTemplate(Jwt jwt, OnboardingRequests.CreateTemplate request) {
        return execute(writes, () -> {
            var writer = writer(jwt, TEMPLATE_WRITE);
            department(request.departmentId(), true, true);
            var assignees = request.items().stream().map(OnboardingRequests.TemplateItem::assigneeId).toList();
            assignees(assignees, true);
            fresh(jwt, writer, TEMPLATE_WRITE);
            UUID id = UUID.randomUUID();
            data.createTemplate(id, request, writer.id(), now());
            // Key-share protects references without creating a cycle with another actor's account lock.
            // Re-read access flags after all inserts, because those flags are non-key account columns.
            assignees(assignees, false);
            department(request.departmentId(), true, false);
            fresh(jwt, writer, TEMPLATE_WRITE);
            return findTemplate(id, false);
        });
    }

    public OnboardingTemplateView updateTemplate(Jwt jwt, UUID id, OnboardingRequests.UpdateTemplate request) {
        return execute(writes, () -> {
            var writer = writer(jwt, TEMPLATE_WRITE);
            var found = data.template(id, true);
            fresh(jwt, writer, TEMPLATE_WRITE);
            var template = found.orElseThrow(OnboardingService::templateNotFound);
            version(template.version(), request.expectedVersion());
            department(template.departmentId(), request.active(), true);
            var assignees = request.items().stream().map(OnboardingRequests.TemplateItem::assigneeId).toList();
            assignees(assignees, true);
            fresh(jwt, writer, TEMPLATE_WRITE);
            data.updateTemplate(id, request, now());
            assignees(assignees, false);
            department(template.departmentId(), request.active(), false);
            fresh(jwt, writer, TEMPLATE_WRITE);
            return findTemplate(id, false);
        });
    }

    public OnboardingChecklistView checklist(Jwt jwt, UUID applicationId) {
        return execute(reads, () -> {
            UUID actor = reader(jwt, CHECKLIST_READ);
            access.scope(actor, applicationId, false);
            var result = findChecklist(applicationId, false);
            access.audit(actor, access.context(applicationId));
            return result;
        });
    }

    public OnboardingChecklistView createChecklist(Jwt jwt, UUID applicationId,
                                                    OnboardingRequests.CreateChecklist request) {
        return execute(writes, () -> {
            var writer = writer(jwt, CHECKLIST_WRITE);
            var context = lockApplication(jwt, writer, applicationId);
            if (data.checklist(applicationId, true).isPresent()) {
                fresh(jwt, writer, CHECKLIST_WRITE);
                throw conflict("ONBOARDING_ALREADY_EXISTS", "Hồ sơ đã có danh sách công việc tiếp nhận.");
            }
            hired(context);
            var found = data.template(request.templateId(), true);
            fresh(jwt, writer, CHECKLIST_WRITE);
            var template = found.orElseThrow(OnboardingService::templateNotFound);
            // Reading a template version does not consume its counter.
            if (template.version() != request.expectedTemplateVersion()) throw stale();
            if (!template.active()) throw conflict("ONBOARDING_TEMPLATE_INACTIVE", "Mẫu tiếp nhận đã ngừng áp dụng.");
            UUID department = data.departmentOf(context.requisitionId());
            if (!template.departmentId().equals(department)) {
                throw conflict("ONBOARDING_DEPARTMENT_MISMATCH", "Mẫu tiếp nhận phải thuộc phòng ban của hồ sơ tuyển dụng.");
            }
            department(department, true, true);
            date(request.startDate());
            if (template.items().isEmpty() || template.items().size() > 100) {
                throw conflict("ONBOARDING_TEMPLATE_INVALID", "Mẫu tiếp nhận cần từ 1 đến 100 công việc.");
            }
            for (var item : template.items()) date(request.startDate().plusDays(item.dueOffsetDays()));
            var assignees = template.items().stream().map(OnboardingTemplateView.Item::assigneeId).toList();
            assignees(assignees, true);
            fresh(jwt, writer, CHECKLIST_WRITE);
            data.createChecklist(UUID.randomUUID(), applicationId, template, request.startDate(), writer.id(), now());
            assignees(assignees, false);
            department(department, true, false);
            fresh(jwt, writer, CHECKLIST_WRITE);
            access.audit(writer, context);
            return findChecklist(applicationId, false);
        });
    }

    public OnboardingChecklistView updateItem(Jwt jwt, UUID applicationId, UUID itemId,
                                               OnboardingRequests.UpdateItem request) {
        return execute(writes, () -> {
            var writer = writer(jwt, CHECKLIST_WRITE);
            var context = lockApplication(jwt, writer, applicationId);
            var found = data.checklist(applicationId, true);
            fresh(jwt, writer, CHECKLIST_WRITE);
            var checklist = found.orElseThrow(OnboardingService::checklistNotFound);
            if ("COMPLETED".equals(checklist.status())) {
                throw conflict("ONBOARDING_ALREADY_COMPLETED", "Danh sách đã hoàn tất và không thể sửa lại.");
            }
            hired(context);
            version(checklist.version(), request.expectedVersion());
            if (checklist.items().stream().noneMatch(item -> item.id().equals(itemId))) {
                throw missing("ONBOARDING_ITEM_NOT_FOUND", "Không tìm thấy công việc trong danh sách tiếp nhận này.");
            }
            date(request.dueDate());
            if (request.dueDate().isBefore(checklist.startDate().minusDays(30))
                    || request.dueDate().isAfter(checklist.startDate().plusDays(365))) {
                throw invalid("ONBOARDING_DATE_INVALID", "Hạn công việc phải từ 30 ngày trước đến 365 ngày sau ngày nhận việc.");
            }
            assignees(Set.of(request.assigneeId()), true);
            fresh(jwt, writer, CHECKLIST_WRITE);
            Instant at = now();
            data.updateItem(checklist.id(), itemId, request, writer.id(), at);
            var updated = findChecklist(applicationId, false);
            boolean complete = OnboardingProgress.canComplete(updated.completedItems(), updated.totalItems());
            data.advanceChecklist(checklist.id(), complete, writer.id(), at);
            if (complete) data.onboard(applicationId, writer.id(), at);
            assignees(Set.of(request.assigneeId()), false);
            fresh(jwt, writer, CHECKLIST_WRITE);
            access.audit(writer, context);
            return findChecklist(applicationId, false);
        });
    }

    private OfferAccess.Context lockApplication(Jwt jwt, OfferAccess.Writer writer, UUID applicationId) {
        access.scope(writer.id(), applicationId, true);
        var context = access.lockParents(applicationId);
        fresh(jwt, writer, CHECKLIST_WRITE);
        access.scope(writer.id(), applicationId, true);
        return context;
    }

    private UUID reader(Jwt jwt, Set<String> grants) {
        var actor = access.reader(jwt);
        authorize(actor.getId(), actor.getRoles(), grants);
        return actor.getId();
    }

    private OfferAccess.Writer writer(Jwt jwt, Set<String> grants) {
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        jdbc.execute("SET LOCAL statement_timeout='3s'");
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant())) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        UUID actorId;
        UUID sessionId;
        try {
            actorId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getId());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        // Account administration takes FOR UPDATE, so a lock, role change or logout still waits for this writer.
        // NO KEY UPDATE also allows an assignee FK/key-share from another onboarding writer: two HR managers
        // may assign work to each other without each waiting for the other's actor lock.
        if (jdbc.queryForList("SELECT id FROM user_accounts WHERE id=? FOR NO KEY UPDATE", UUID.class, actorId).isEmpty()) {
            throw AuthenticationFailureException.sessionInvalid();
        }
        var actor = accounts.findById(actorId).filter(Account::isAccessAllowed)
                .orElseThrow(AuthenticationFailureException::sessionInvalid);
        var session = sessions.findByIdForUpdate(sessionId).orElseThrow(AuthenticationFailureException::sessionInvalid);
        var writer = new OfferAccess.Writer(actorId, actor.getFullName(), actor.getRoles(), session);
        fresh(jwt, writer, grants);
        return writer;
    }

    private void fresh(Jwt jwt, OfferAccess.Writer writer, Set<String> grants) {
        access.fresh(jwt, writer);
        authorize(writer.id(), writer.roles(), grants);
    }

    private void authorize(UUID actor, Set<Role> roles, Set<String> grants) {
        if ((!roles.contains(Role.ADMIN) && !roles.contains(Role.HR_MANAGER)) || !access.grants(actor).containsAll(grants)) {
            throw new AccessDeniedException("Onboarding requires an HR manager or admin and all requested grants");
        }
    }

    private void department(UUID id, boolean requireActive, boolean lock) {
        boolean active = data.activeDepartment(id, lock).orElseThrow(() ->
                missing("ONBOARDING_DEPARTMENT_NOT_FOUND", "Không tìm thấy phòng ban của mẫu tiếp nhận."));
        if (requireActive && !active) throw conflict("ONBOARDING_DEPARTMENT_INACTIVE", "Phòng ban đã ngừng hoạt động.");
    }

    private void assignees(Collection<UUID> ids, boolean lock) {
        for (UUID id : ids.stream().distinct().sorted().toList()) {
            if (!data.activeAssignee(id, lock)) {
                throw conflict("ONBOARDING_ASSIGNEE_INACTIVE", "Người phụ trách phải là tài khoản đang hoạt động.");
            }
        }
    }

    private OnboardingTemplateView findTemplate(UUID id, boolean lock) {
        return data.template(id, lock).orElseThrow(OnboardingService::templateNotFound);
    }

    private OnboardingChecklistView findChecklist(UUID applicationId, boolean lock) {
        return data.checklist(applicationId, lock).orElseThrow(OnboardingService::checklistNotFound);
    }

    private static void hired(OfferAccess.Context context) {
        if (!"HIRED".equals(context.stage())) {
            throw conflict("ONBOARDING_APPLICATION_INELIGIBLE", "Chỉ bắt đầu hoặc cập nhật tiếp nhận cho hồ sơ đã tuyển dụng (HIRED).");
        }
    }

    private static void version(long actual, long expected) {
        if (actual != expected) throw stale();
        if (actual == Long.MAX_VALUE) throw conflict("ONBOARDING_VERSION_LIMIT", "Dữ liệu đã đạt giới hạn số phiên bản.");
    }

    private static void date(LocalDate date) {
        if (date.isBefore(MIN_DATE) || date.isAfter(MAX_DATE)) {
            throw invalid("ONBOARDING_DATE_INVALID", "Ngày tiếp nhận và hạn công việc phải nằm trong khoảng năm 2000 đến 2100.");
        }
    }

    private <T> T execute(TransactionTemplate transaction, Supplier<T> operation) {
        try {
            return Objects.requireNonNull(transaction.execute(status -> operation.get()));
        } catch (OfferAccess.ScopeDenied denied) {
            access.auditDenied(denied);
            throw denied;
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof TransientDataAccessException || cause instanceof TransactionTimedOutException
                        || cause instanceof SQLException sql && BUSY_STATES.contains(sql.getSQLState())) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ONBOARDING_BUSY",
                            "Dữ liệu tiếp nhận đang được cập nhật. Vui lòng thử lại sau ít phút.");
                }
                if (cause instanceof SQLException sql && sql.getMessage() != null && sql.getMessage().contains("onboarding")) {
                    if ("23505".equals(sql.getSQLState()) && sql.getMessage().contains("application")) {
                        throw conflict("ONBOARDING_ALREADY_EXISTS", "Hồ sơ đã có danh sách công việc tiếp nhận.");
                    }
                    if (Set.of("23503", "23514").contains(sql.getSQLState())) {
                        throw conflict("ONBOARDING_CONTEXT_CHANGED", "Dữ liệu tiếp nhận vừa thay đổi. Vui lòng tải lại trước khi thực hiện.");
                    }
                }
            }
            throw failure;
        }
    }

    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static ApiException stale() { return conflict("ONBOARDING_VERSION_CHANGED", "Dữ liệu vừa thay đổi. Vui lòng tải lại phiên bản mới nhất."); }
    private static ApiException templateNotFound() { return missing("ONBOARDING_TEMPLATE_NOT_FOUND", "Không tìm thấy mẫu tiếp nhận."); }
    private static ApiException checklistNotFound() { return missing("ONBOARDING_CHECKLIST_NOT_FOUND", "Hồ sơ chưa có danh sách công việc tiếp nhận."); }
    private static ApiException conflict(String code, String text) { return new ApiException(HttpStatus.CONFLICT, code, text); }
    private static ApiException invalid(String code, String text) { return new ApiException(HttpStatus.BAD_REQUEST, code, text); }
    private static ApiException missing(String code, String text) { return new ApiException(HttpStatus.NOT_FOUND, code, text); }
}
