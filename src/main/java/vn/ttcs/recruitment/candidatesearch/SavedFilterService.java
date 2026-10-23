package vn.ttcs.recruitment.candidatesearch;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.candidate.CandidateWriteGuard;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Task 394 (story S5-05, "Lưu lại được bộ lọc hay dùng"): personal saved filters. The owner is always the JWT's
 * subject, never a request field. Another account's filter id answers 404 exactly like an unknown id, so ids cannot be
 * probed and nobody reads, changes or deletes a filter that is not theirs. Every call needs a live CANDIDATES read
 * permission (a filter is only useful to someone who may search). Writes lock the caller's account and session first,
 * which also serializes the per-owner limit and the name check of one owner's concurrent requests.
 * <p>
 * The criteria are validated like a search; the requisition, position and catalog values they name are checked, with
 * the caller's scope of that moment, each time the filter is used (CandidateSearchService), never at save time.
 */
@Service
public class SavedFilterService {
    static final int MAX_FILTERS_PER_OWNER = 50;
    private final CandidateAccess access;
    private final CandidateWriteGuard guard;
    private final SavedFilterRepository filters;
    private final Clock clock;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    SavedFilterService(CandidateAccess access, CandidateWriteGuard guard, SavedFilterRepository filters, Clock clock,
                       PlatformTransactionManager transactions) {
        this.access = access;
        this.guard = guard;
        this.filters = filters;
        this.clock = clock;
        reads = new TransactionTemplate(transactions);
        reads.setReadOnly(true);
        writes = new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(5);
    }

    public List<SavedFilterView> list(Jwt jwt) {
        CandidateReader reader = access.requireReader(jwt);
        return Objects.requireNonNull(reads.execute(status -> filters.list(reader.id())));
    }

    public SavedFilterView get(Jwt jwt, UUID id) {
        CandidateReader reader = access.requireReader(jwt);
        return Objects.requireNonNull(reads.execute(status -> filters.find(reader.id(), id)
                .orElseThrow(SavedFilterService::notFound)));
    }

    public SavedFilterView create(Jwt jwt, SavedFilterRequest request) {
        requireValidCriteria(request);
        return write(() -> {
            UUID owner = lockSearcher(jwt);
            if (filters.countOwned(owner) >= MAX_FILTERS_PER_OWNER) {
                throw new ApiException(HttpStatus.CONFLICT, "SAVED_FILTER_LIMIT",
                        "Mỗi người lưu tối đa " + MAX_FILTERS_PER_OWNER + " bộ lọc. Vui lòng xóa bớt bộ lọc cũ.");
            }
            requireFreeName(owner, request.name(), null);
            UUID id = UUID.randomUUID();
            filters.insert(id, owner, request.name(), request.criteria(), clock.instant().truncatedTo(ChronoUnit.MICROS));
            return filters.find(owner, id).orElseThrow();
        });
    }

    public SavedFilterView update(Jwt jwt, UUID id, SavedFilterRequest request) {
        if (request.expectedVersion() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra dữ liệu đã nhập.",
                    Map.of("expectedVersion", "Vui lòng gửi phiên bản bộ lọc đã đọc."));
        }
        requireValidCriteria(request);
        return write(() -> {
            UUID owner = lockSearcher(jwt);
            filters.lock(owner, id).orElseThrow(SavedFilterService::notFound);
            requireFreeName(owner, request.name(), id);
            if (!filters.update(id, owner, request.name(), request.criteria(), request.expectedVersion(),
                    clock.instant().truncatedTo(ChronoUnit.MICROS))) {
                throw new ApiException(HttpStatus.CONFLICT, "SAVED_FILTER_CHANGED",
                        "Bộ lọc vừa được thay đổi ở nơi khác. Vui lòng tải lại.");
            }
            return filters.find(owner, id).orElseThrow();
        });
    }

    public void delete(Jwt jwt, UUID id) {
        write(() -> {
            UUID owner = lockSearcher(jwt);
            if (!filters.delete(id, owner)) throw notFound();
            return Boolean.TRUE;
        });
    }

    // The account and session locked, then the CANDIDATES read permission read again (403 without it).
    private UUID lockSearcher(Jwt jwt) {
        CandidateWriteGuard.Writer writer = guard.lockWriter(jwt);
        AccessScope.read(guard.freshGrants(jwt, writer), PermissionModule.CANDIDATES).orDeny();
        return writer.id();
    }

    // The criteria's own field errors, named "criteria.<field>" so a form can highlight them.
    private static void requireValidCriteria(SavedFilterRequest request) {
        Map<String, String> problems = request.criteria().problems();
        if (!problems.isEmpty()) {
            var errors = new LinkedHashMap<String, String>();
            problems.forEach((field, message) -> errors.put("criteria." + field, message));
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra điều kiện lọc.", errors);
        }
    }

    private void requireFreeName(UUID owner, String name, UUID exceptId) {
        if (filters.nameTaken(owner, name, exceptId)) throw nameTaken();
    }

    private <T> T write(Supplier<T> work) {
        try {
            return Objects.requireNonNull(writes.execute(status -> work.get()));
        } catch (DuplicateKeyException exception) {
            // The owner's account lock serializes the name check; the unique key stays the last guard.
            throw nameTaken();
        } catch (org.springframework.dao.DataIntegrityViolationException exception) {
            // A CHECK of V65 (the criteria's size, a name's form) refused what the validation let through.
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra dữ liệu đã nhập.",
                    Map.of("criteria", "Điều kiện lọc hoặc tên bộ lọc không được lưu."));
        } catch (RuntimeException failure) {
            if (CandidateWriteGuard.busy(failure)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SAVED_FILTER_BUSY",
                        "Hệ thống đang bận. Vui lòng thử lại sau.");
            }
            throw failure;
        }
    }

    private static ApiException nameTaken() {
        return new ApiException(HttpStatus.CONFLICT, "SAVED_FILTER_NAME_TAKEN", "Bạn đã có bộ lọc trùng tên.",
                Map.of("name", "Tên bộ lọc đã được dùng."));
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "SAVED_FILTER_NOT_FOUND", "Không tìm thấy bộ lọc.");
    }
}
