package vn.ttcs.recruitment.offer;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.mail.MailLayout;
import vn.ttcs.recruitment.position.SalaryBand;
import vn.ttcs.recruitment.position.SalaryBandService;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Drafts stay editable; submission freezes terms and chooses the exact people for each approval level. */
@Service
public class OfferService {
    private final OfferRepository data;
    private final OfferAccess access;
    private final SalaryBandService bands;
    private final AccountRepository accounts;
    private final BusinessCalendar calendar;
    private final MailLayout mail;
    private final ZoneId mailZone;
    private final Clock clock;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    public OfferService(OfferRepository data, OfferAccess access, SalaryBandService bands, AccountRepository accounts,
        BusinessCalendar calendar, MailLayout mail, Clock clock, PlatformTransactionManager transactions,
        @Value("${app.offers.time-zone:Asia/Ho_Chi_Minh}") String mailTimeZone) {
        this.data = data;
        this.access = access;
        this.bands = bands;
        this.accounts = accounts;
        this.calendar = calendar;
        this.mail = mail;
        this.mailZone = ZoneId.of(mailTimeZone);
        this.clock = clock;
        reads = new TransactionTemplate(transactions);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(5);
        writes = new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(5);
    }

    public OfferView create(Jwt jwt, UUID application, OfferRequests.Draft request) {
        if (request.expectedVersion() != null) throw invalid("expectedVersion", "Không gửi phiên bản khi tạo offer mới.");
        return execute(writes, () -> {
            var writer = access.lockWriter(jwt);
            access.requireWriteScope(jwt, writer, application);
            var context = access.lockParents(application);
            access.requireWriteScope(jwt, writer, application);
            access.requireActive(context);
            var band = bands.standardBand(context.positionId());
            access.requireWriteScope(jwt, writer, application);
            terms(request.salary(), request.explanation(), request.startDate(), request.responseDeadline(), band);
            UUID id = UUID.randomUUID();
            Instant now = now();
            data.create(id, context, request, band, writer.id(), now);
            // The candidate unique index may wait for an offer on another application. Recheck after that wait.
            access.requireWriteScope(jwt, writer, application);
            terms(request.salary(), request.explanation(), request.startDate(), request.responseDeadline(), band);
            var result = find(id, false);
            data.event(result, "CREATED", writer, null, now, true);
            access.audit(writer, context);
            return mask(result, writer.id());
        });
    }

    public OfferView update(Jwt jwt, UUID id, OfferRequests.Draft request) {
        return execute(writes, () -> {
            var locked = writer(jwt, id);
            editable(locked.offer());
            version(locked.offer(), request.expectedVersion());
            var band = bands.standardBand(locked.context().positionId());
            access.requireWriteScope(jwt, locked.actor(), locked.offer().applicationId());
            terms(request.salary(), request.explanation(), request.startDate(), request.responseDeadline(), band);
            Instant now = now();
            data.update(id, locked.context(), request, band, now);
            var result = find(id, false);
            data.event(result, "UPDATED", locked.actor(), null, now, true);
            access.audit(locked.actor(), locked.context());
            return mask(result, locked.actor().id());
        });
    }

    public OfferView get(Jwt jwt, UUID id) {
        return execute(reads, () -> {
            Account actor = access.reader(jwt);
            var result = readable(actor, id);
            access.audit(actor.getId(), access.context(result.applicationId()));
            return mask(result, actor.getId());
        });
    }

    public OfferView.Page list(Jwt jwt, UUID application, int page, int size) {
        return execute(reads, () -> {
            Account actor = access.reader(jwt);
            access.scope(actor.getId(), application, false);
            paging(page, size);
            var items = data.list(application, page, size).stream().map(v -> mask(v, actor.getId())).toList();
            long count = data.count(application);
            if (!items.isEmpty()) access.audit(actor.getId(), access.context(application));
            return new OfferView.Page(items, page, size, count, (count+size-1)/size);
        });
    }

    public OfferView.Preview preview(Jwt jwt, UUID id) {
        return execute(reads, () -> {
            Account actor = access.reader(jwt);
            var offer = readable(actor, id);
            access.audit(actor.getId(), access.context(offer.applicationId()));
            var payload = new OfferMailPayload(offer.email(), offer.candidateName(), offer.positionTitle(), offer.salary(), offer.allowance(),
                offer.startDate(), offer.responseDeadline(), null);
            var rendered = mail.render(OfferMailContent.body(payload, mailZone));
            return new OfferView.Preview(OfferMailContent.subject(payload), rendered.plainText(), rendered.html());
        });
    }

    public OfferView submit(Jwt jwt, UUID id, OfferRequests.Submit request) {
        return execute(writes, () -> {
            var locked = writer(jwt, id);
            var offer = locked.offer();
            editable(offer);
            version(offer, request.expectedVersion());
            var band = bands.standardBand(offer.positionId());
            access.requireWriteScope(jwt, locked.actor(), offer.applicationId());
            terms(offer.salary(), offer.explanation(), offer.startDate(), offer.responseDeadline(), band);
            boolean outside = OfferRepository.outside(offer.salary(), band);
            if (outside && request.higherApproverId() == null) throw invalid("higherApproverId", "Offer ngoài dải cần thêm người duyệt cấp cao.");
            if (!outside && request.higherApproverId() != null) throw invalid("higherApproverId", "Offer trong dải chỉ cần Trưởng phòng Nhân sự duyệt.");
            approver(request.hrApproverId(), Role.HR_MANAGER, offer, locked.actor().id());
            if (outside) {
                approver(request.higherApproverId(), Role.APPROVER, offer, locked.actor().id());
                if (request.hrApproverId().equals(request.higherApproverId())) throw invalid("higherApproverId", "Hai cấp phải do hai người khác nhau duyệt.");
            }
            access.requireWriteScope(jwt, locked.actor(), offer.applicationId());
            Instant now = now();
            data.submit(offer, locked.context(), band, request, locked.actor().id(), now);
            var result = find(id, false);
            data.event(result, "SUBMITTED", locked.actor(), null, now, true);
            access.audit(locked.actor(), locked.context());
            return mask(result, locked.actor().id());
        });
    }

    public OfferView decide(Jwt jwt, UUID id, OfferRequests.Decision request) {
        if (!"APPROVE".equals(request.action()) && request.comment() == null) throw invalid("comment", "Từ chối hoặc yêu cầu sửa phải có ý kiến.");
        return execute(writes, () -> {
            var actor = access.lockWriter(jwt);
            approvalGrants(actor.id());
            var initial = find(id, false);
            var context = access.lockParents(initial.applicationId());
            access.fresh(jwt, actor);
            var offer = find(id, true);
            access.fresh(jwt, actor);
            approvalGrants(actor.id());
            if (actor.id().equals(offer.createdBy()) || actor.id().equals(offer.submittedBy())) throw new AccessDeniedException("Self approval is not permitted");
            boolean hr = "PENDING_HR".equals(offer.status());
            if (!hr && !"PENDING_HIGHER".equals(offer.status())) throw conflict("OFFER_NOT_PENDING", "Offer không đang chờ duyệt.");
            UUID assigned = hr ? offer.hrApproverId() : offer.higherApproverId();
            Role role = hr ? Role.HR_MANAGER : Role.APPROVER;
            if (!actor.id().equals(assigned) || !actor.roles().contains(role)) throw new AccessDeniedException("Only the assigned current approval level may decide");
            if (data.approvedBy(id, offer.approvalRound(), actor.id())) throw new AccessDeniedException("Approval levels require distinct actors");
            version(offer, request.expectedVersion());
            access.requireActive(context);
            sameIdentity(offer, context);
            // The approved range is frozen, but an inactive position cannot receive a pending offer approval.
            bands.standardBand(offer.positionId());
            access.fresh(jwt, actor);
            approvalGrants(actor.id());
            dates(offer.startDate(), offer.responseDeadline());
            String next = switch (request.action()) {
                case "APPROVE" -> hr && offer.outsideBand() ? "PENDING_HIGHER" : "APPROVED";
                case "REJECT" -> "REJECTED";
                case "REQUEST_CHANGES" -> "CHANGES_REQUESTED";
                default -> throw invalid("action", "Quyết định không hợp lệ.");
            };
            String event = "APPROVE".equals(request.action()) ? (hr ? "HR_APPROVED" : "HIGHER_APPROVED") : next;
            Instant now = now();
            data.transition(id, next, now);
            var result = find(id, false);
            data.event(result, event, actor, request.comment(), now, false);
            access.audit(actor, context);
            return mask(result, actor.id());
        });
    }

    public OfferView cancel(Jwt jwt, UUID id, OfferRequests.Cancel request) {
        if (request.reason() == null) throw invalid("reason", "Cần lý do hủy offer.");
        return execute(writes, () -> {
            var locked = writer(jwt, id);
            version(locked.offer(), request.expectedVersion());
            if (!Set.of("DRAFT", "CHANGES_REQUESTED", "PENDING_HR", "PENDING_HIGHER", "APPROVED").contains(locked.offer().status())) {
                throw conflict("OFFER_NOT_CANCELLABLE", "Chỉ hủy offer chưa gửi ứng viên.");
            }
            Instant now = now();
            data.transition(id, "CANCELLED", now);
            var result = find(id, false);
            data.event(result, "CANCELLED", locked.actor(), request.reason(), now, false);
            access.audit(locked.actor(), locked.context());
            return mask(result, locked.actor().id());
        });
    }

    public OfferView.History history(Jwt jwt, UUID id, int page, int size) {
        return execute(reads, () -> {
            Account actor = access.reader(jwt);
            var offer = readable(actor, id);
            paging(page, size);
            access.audit(actor.getId(), access.context(offer.applicationId()));
            long count = data.eventCount(id);
            return new OfferView.History(data.history(id, page, size), page, size, count, (count+size-1)/size);
        });
    }

    public List<OfferView.Approver> approvers(Jwt jwt, String role) {
        return execute(reads, () -> {
            Account actor = access.reader(jwt);
            access.requireWriterRole(actor.getId(), actor.getRoles());
            var grants = access.grants(actor.getId());
            AccessScope.read(grants, PermissionModule.OFFERS).orDeny();
            AccessScope.read(grants, PermissionModule.CANDIDATES).orDeny();
            if (!Set.of("HR_MANAGER", "APPROVER").contains(role)) throw invalid("role", "Chỉ chọn HR_MANAGER hoặc APPROVER.");
            return data.approvers(role).stream().filter(a -> !a.id().equals(actor.getId())).filter(a -> {
                var permission = access.grants(a.id());
                return AccessScope.read(permission, PermissionModule.OFFERS) != AccessScope.NONE
                && AccessScope.write(permission, PermissionModule.OFFERS) != AccessScope.NONE;
            }).toList();
        });
    }

    private record Locked(OfferAccess.Writer actor, OfferAccess.Context context, OfferView offer) {
    }

    private Locked writer(Jwt jwt, UUID id) {
        var actor = access.lockWriter(jwt);
        access.requireWriterRole(actor.id(), actor.roles());
        var initial = find(id, false);
        access.requireWriteScope(jwt, actor, initial.applicationId());
        var context = access.lockParents(initial.applicationId());
        access.requireWriteScope(jwt, actor, initial.applicationId());
        var offer = find(id, true);
        access.requireWriteScope(jwt, actor, offer.applicationId());
        access.requireActive(context);
        sameIdentity(offer, context);
        return new Locked(actor, context, offer);
    }

    OfferView readable(Account actor, UUID id) {
        AccessScope.read(access.grants(actor.getId()), PermissionModule.OFFERS).orDeny();
        var offer = find(id, false);
        boolean assigned = actor.getId().equals(offer.hrApproverId()) && actor.getRoles().contains(Role.HR_MANAGER)
        || actor.getId().equals(offer.higherApproverId()) && actor.getRoles().contains(Role.APPROVER);
        if (!assigned) access.scope(actor.getId(), offer.applicationId(), false);
        return offer;
    }

    private void approver(UUID id, Role role, OfferView offer, UUID submitter) {
        if (id == null || id.equals(offer.createdBy()) || id.equals(submitter)) throw invalid("hrApproverId", "Người tạo hoặc trình offer không được tự duyệt.");
        Account account = accounts.findById(id).filter(Account::isAccessAllowed).filter(a -> a.getRoles().contains(role))
        .orElseThrow(() -> invalid("hrApproverId", "Người duyệt không còn hoạt động hoặc không có đúng vai trò."));
        var grants = access.grants(account.getId());
        if (AccessScope.read(grants, PermissionModule.OFFERS) == AccessScope.NONE || AccessScope.write(grants, PermissionModule.OFFERS) == AccessScope.NONE)
        throw invalid("hrApproverId", "Người duyệt không có quyền duyệt Offer.");
    }

    private void approvalGrants(UUID actor) {
        var grants = access.grants(actor);
        AccessScope.read(grants, PermissionModule.OFFERS).orDeny();
        AccessScope.write(grants, PermissionModule.OFFERS).orDeny();
    }

    void terms(long salary, String explanation, LocalDate start, Instant deadline, SalaryBand band) {
        dates(start, deadline);
        if (OfferRepository.outside(salary, band) && explanation == null) throw invalid("explanation", "Lương ngoài dải chuẩn cần giải trình.");
    }

    private void dates(LocalDate start, Instant deadline) {
        if (start.isBefore(calendar.today()) || start.getYear() > 9999) {
            throw invalid("startDate", "Ngày nhận việc không được ở quá khứ.");
        }
        // Comparing instants avoids converting malicious extended-year input to an unsupported local date.
        if (!deadline.isAfter(clock.instant()) || !deadline.isBefore(calendar.startOf(start.plusDays(1)))) {
            throw invalid("responseDeadline", "Hạn trả lời phải ở tương lai và không sau ngày nhận việc.");
        }
    }

    static void sameIdentity(OfferView offer, OfferAccess.Context context) {
        if (!offer.candidateId().equals(context.candidateId()) || !offer.positionId().equals(context.positionId())) {
            throw conflict("OFFER_CONTEXT_CHANGED", "Hồ sơ hoặc chức danh đã thay đổi. Hãy tạo lại offer.");
        }
        if (!Set.of("DRAFT", "CHANGES_REQUESTED").contains(offer.status())
                && (!offer.email().equals(context.email()) || !offer.candidateName().equals(context.candidateName()))) {
            throw conflict("OFFER_CONTEXT_CHANGED", "Thông tin người nhận đã thay đổi. Hãy tạo lại offer.");
        }
    }

    OfferView mask(OfferView view, UUID actor) {
        return access.grants(actor).contains("SALARY_RANGES_READ_ALL") ? view : view.withoutBand();
    }

    OfferView find(UUID id, boolean lock) {
        return data.find(id, lock).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "OFFER_NOT_FOUND", "Không tìm thấy offer."));
    }

    private static void editable(OfferView offer) {
        if (!Set.of("DRAFT", "CHANGES_REQUESTED").contains(offer.status())) throw conflict("OFFER_NOT_EDITABLE", "Chỉ sửa offer nháp hoặc được yêu cầu sửa.");
    }

    static void version(OfferView offer, Long expected) {
        if (expected == null || expected < 0) throw invalid("expectedVersion", "Cần phiên bản offer đang xem.");
        if (offer.version() != expected) throw conflict("OFFER_VERSION_CHANGED", "Offer vừa thay đổi. Vui lòng tải lại.");
    }

    static void paging(int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) {
            throw invalid("page", "Trang từ 0 đến 100000, số dòng từ 1 đến 100.");
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    <T> T execute(TransactionTemplate transaction, Supplier<T> action) {
        try {
            return Objects.requireNonNull(transaction.execute(s -> action.get()));
        } catch (OfferAccess.ScopeDenied denied) {
            access.auditDenied(denied);
            throw denied;
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())
                        && sql.getMessage().contains("offers_one_active_candidate")) {
                    throw conflict("OFFER_ALREADY_ACTIVE", "Ứng viên đang có một offer còn hiệu lực.");
                }
                if (cause instanceof TransientDataAccessException || cause instanceof TransactionTimedOutException
                        || cause instanceof SQLException sql && Set.of("55P03", "57014", "40P01").contains(sql.getSQLState())) {
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OFFER_BUSY", "Offer đang được xử lý. Vui lòng thử lại.");
                }
            }
            throw failure;
        }
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra dữ liệu.", Map.of(field, message));
    }

    private static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
