package vn.ttcs.recruitment.candidatesearch;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.security.AccessScope;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Task 392 (story S5-05): the candidate search list. It runs under the reader's live CANDIDATES read scope
 * (requireReader reads the grants again from the database), in one REPEATABLE READ snapshot together with the PROFILE
 * disclosure log of every application shown, like the board.
 * <p>
 * Order: 401/403 before any lookup; 400 for invalid criteria or paging; 404/403 for a requisition or position filter
 * that does not exist or is outside the scope (like the board); 400 for a source or tag id that is not such a catalog
 * value; then the rows.
 */
@Service
public class CandidateSearchService {
    static final int MAX_PAGE = 1_000_000;
    static final int MAX_SIZE = 100;
    private final CandidateAccess access;
    private final CandidateSearchRepository repository;
    private final TransactionTemplate reads;

    CandidateSearchService(CandidateAccess access, CandidateSearchRepository repository,
                           PlatformTransactionManager transactions) {
        this.access = access;
        this.repository = repository;
        reads = new TransactionTemplate(transactions);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(10);
    }

    public CandidateSearchView search(Jwt jwt, CandidateSearchCriteria criteria, int page, int size) {
        CandidateReader reader = access.requireReader(jwt);
        criteria.requireValid();
        if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_SIZE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Trang phải từ 0 đến 1000000; kích thước trang phải từ 1 đến 100.");
        }
        return Objects.requireNonNull(searching(reads, status -> {
            requireFilters(reader, criteria);
            long offset = (long) page * size;
            CandidateSearchRepository.Found found = repository.search(reader, criteria, offset, size);
            long total = found.total();
            List<CandidateSearchView.Item> items = found.items();
            recordDisclosed(reader, items);
            return new CandidateSearchView(items, total, page, size, offset + items.size() < total);
        }));
    }

    // The filters that name records: 404 for an unknown requisition or position, 403 outside the scope (before any
    // application is read), 400 for an id that is not a candidate source or tag.
    void requireFilters(CandidateReader reader, CandidateSearchCriteria criteria) {
        UUID requisition = criteria.requisitionId();
        if (requisition != null) {
            boolean readable = repository.readableRequisition(reader, requisition).orElseThrow(() ->
                    new ApiException(HttpStatus.NOT_FOUND, "REQUISITION_NOT_FOUND", "Không tìm thấy yêu cầu tuyển dụng."));
            if (!readable) throw new AccessDeniedException("Requisition is outside candidate read scope");
        }
        UUID position = criteria.positionId();
        if (position != null) {
            if (!repository.positionExists(position)) {
                throw new ApiException(HttpStatus.NOT_FOUND, "POSITION_NOT_FOUND", "Không tìm thấy vị trí tuyển dụng.");
            }
            if (reader.scope() != AccessScope.ALL && !repository.hasReadablePosition(reader, position)) {
                throw new AccessDeniedException("No readable opening for this position");
            }
        }
        if (repository.catalogValuesOfType(criteria.distinctSources(), "CANDIDATE_SOURCE")
                != criteria.distinctSources().size()) {
            throw invalid("sourceIds", "Có nguồn ứng viên không tồn tại.");
        }
        if (repository.catalogValuesOfType(criteria.distinctTags(), "CANDIDATE_TAG") != criteria.distinctTags().size()) {
            throw invalid("tagIds", "Có nhãn ứng viên không tồn tại.");
        }
    }

    // A shown row discloses its candidate profile: one PROFILE row per application, grouped per candidate. Counts and
    // empty pages disclose no profile.
    void recordDisclosed(CandidateReader reader, List<CandidateSearchView.Item> items) {
        items.stream().collect(Collectors.groupingBy(CandidateSearchView.Item::candidateId)).forEach((candidate, shown) ->
                access.recordGranted(reader, CandidateAccessKind.PROFILE, candidate,
                        shown.stream().map(CandidateSearchView.Item::applicationId).toList()));
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra điều kiện tìm kiếm.",
                Map.of(field, message));
    }

    // The search in its transaction. A statement or transaction timeout (a keyword scanned over many applications), a
    // lock wait or a deadlock is answered 503 CANDIDATE_SEARCH_BUSY, never a 500 with detail.
    static <T> T searching(org.springframework.transaction.support.TransactionTemplate transaction,
                           org.springframework.transaction.support.TransactionCallback<T> work) {
        try {
            return transaction.execute(work);
        } catch (RuntimeException failure) {
            if (busy(failure)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CANDIDATE_SEARCH_BUSY",
                        "Tìm kiếm đang quá tải hoặc quá lâu. Vui lòng thu hẹp điều kiện và thử lại.");
            }
            throw failure;
        }
    }

    // lock_not_available, query_canceled (statement timeout), deadlock_detected and serialization_failure, read from
    // the cause chain because Spring leaves PostgreSQL's 55P03 and 57014 uncategorized.
    static boolean busy(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.dao.TransientDataAccessException
                    || cause instanceof org.springframework.transaction.TransactionTimedOutException
                    || cause instanceof java.sql.SQLException sql
                    && java.util.Set.of("55P03", "57014", "40P01", "40001").contains(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
