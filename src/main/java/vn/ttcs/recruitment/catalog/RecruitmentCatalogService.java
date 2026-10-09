package vn.ttcs.recruitment.catalog;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.account.Account;
import vn.ttcs.recruitment.account.AccountRepository;
import vn.ttcs.recruitment.auth.AuthService;
import vn.ttcs.recruitment.auth.AuthSessionRepository;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.security.PermissionService;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RecruitmentCatalogService {
    private final RecruitmentCatalogItemRepository items;
    private final AccountRepository accounts;
    private final AuthSessionRepository sessions;
    private final AuthService auth;
    private final PermissionService permissions;
    private final Clock clock;

    public RecruitmentCatalogService(RecruitmentCatalogItemRepository items, AccountRepository accounts,
                                     AuthSessionRepository sessions, AuthService auth,
                                     PermissionService permissions, Clock clock) {
        this.items = items;
        this.accounts = accounts;
        this.sessions = sessions;
        this.auth = auth;
        this.permissions = permissions;
        this.clock = clock;
    }

    // Catalogs are short lists used to fill drop-downs, so the whole catalog is returned without paging.
    @Transactional(readOnly = true)
    public List<RecruitmentCatalogItemView> list(Jwt jwt, String type, Boolean active) {
        requireReadAccess(jwt);
        RecruitmentCatalogType catalogType = catalogType(type);
        List<Boolean> activeValues = active == null ? List.of(true, false) : List.of(active);
        return items.findForDisplay(catalogType, activeValues).stream()
                .map(RecruitmentCatalogItemView::from).toList();
    }

    @Transactional(readOnly = true)
    public RecruitmentCatalogItemView get(Jwt jwt, String type, UUID id) {
        requireReadAccess(jwt);
        return items.findByIdAndCatalogType(id, catalogType(type)).map(RecruitmentCatalogItemView::from)
                .orElseThrow(RecruitmentCatalogService::itemNotFound);
    }

    @Transactional
    public RecruitmentCatalogItemView create(Jwt jwt, String type, RecruitmentCatalogItemRequest request) {
        requireWriteAccess(jwt);
        RecruitmentCatalogType catalogType = catalogType(type);
        if (items.existsByCatalogTypeAndCode(catalogType, request.code())) {
            throw duplicateCode();
        }
        var item = new RecruitmentCatalogItem(catalogType, request.code(), request.name(),
                nextSortOrder(catalogType), request.active(), now());
        try {
            item = items.saveAndFlush(item);
        } catch (DataIntegrityViolationException exception) {
            throw translateDuplicateCode(exception);
        }
        return RecruitmentCatalogItemView.from(item);
    }

    @Transactional
    public RecruitmentCatalogItemView update(Jwt jwt, String type, UUID id, RecruitmentCatalogItemRequest request) {
        requireWriteAccess(jwt);
        RecruitmentCatalogType catalogType = catalogType(type);
        RecruitmentCatalogItem item = items.findByIdAndCatalogTypeForUpdate(id, catalogType)
                .orElseThrow(RecruitmentCatalogService::itemNotFound);
        if (items.existsByCatalogTypeAndCodeAndIdNot(catalogType, request.code(), id)) {
            throw duplicateCode();
        }
        item.update(request.code(), request.name(), request.active(), now());
        try {
            items.flush();
        } catch (DataIntegrityViolationException exception) {
            throw translateDuplicateCode(exception);
        }
        return RecruitmentCatalogItemView.from(item);
    }

    // Only a value that no other row uses can be deleted. Each table that stores a catalog value must point to it
    // with an ordinary foreign key (NO ACTION or RESTRICT, see docs/database/README.md), so PostgreSQL itself
    // refuses the DELETE while a reference exists. That also covers a reference saved by another request at the
    // same moment, which a "count the references first" check could miss.
    @Transactional
    public void delete(Jwt jwt, String type, UUID id) {
        requireWriteAccess(jwt);
        RecruitmentCatalogItem item = items.findByIdAndCatalogTypeForUpdate(id, catalogType(type))
                .orElseThrow(RecruitmentCatalogService::itemNotFound);
        try {
            items.delete(item);
            items.flush();
        } catch (DataIntegrityViolationException exception) {
            throw translateItemInUse(exception);
        }
    }

    private void requireReadAccess(Jwt jwt) {
        requireUnexpiredToken(jwt, clock.instant());
        Account actor = auth.requireActiveAccount(jwt);
        if (!permissions.forUser(actor.getId()).contains("ORGANIZATION_READ_ALL")) {
            throw new AccessDeniedException("Recruitment catalog access requires ORGANIZATION_READ_ALL");
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
        if (!permissions.forUser(actorId).contains("ORGANIZATION_WRITE_ALL")) {
            throw new AccessDeniedException("Recruitment catalog management requires ORGANIZATION_WRITE_ALL");
        }
    }

    private void requireUnexpiredToken(Jwt jwt, Instant now) {
        if (jwt == null || jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(now)) {
            throw AuthenticationFailureException.sessionInvalid();
        }
    }

    // The URL uses the exact enum name (case-sensitive), like the role names in the account role API.
    private static RecruitmentCatalogType catalogType(String value) {
        for (RecruitmentCatalogType type : RecruitmentCatalogType.values()) {
            if (type.name().equals(value)) {
                return type;
            }
        }
        String validTypes = Arrays.stream(RecruitmentCatalogType.values()).map(Enum::name)
                .collect(Collectors.joining(", "));
        throw new ApiException(HttpStatus.NOT_FOUND, "RECRUITMENT_CATALOG_TYPE_NOT_FOUND",
                "Không có loại danh mục tuyển dụng này. Loại hợp lệ: " + validTypes + ".");
    }

    // A new value goes to the end of its catalog. Two creates at the same moment may get the same number;
    // V10 allows that and the list then orders those values by code.
    private int nextSortOrder(RecruitmentCatalogType catalogType) {
        Integer max = items.findMaxSortOrder(catalogType);
        return max == null ? 0 : max + 1;
    }

    // PostgreSQL TIMESTAMPTZ keeps microseconds, so write responses show the same time a later GET reads.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    // The existence check above misses a concurrent insert; the unique constraint still catches it here.
    private RuntimeException translateDuplicateCode(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())
                    && sql.getMessage() != null
                    && sql.getMessage().contains("recruitment_catalog_items_type_code_key")) {
                return duplicateCode();
            }
        }
        return exception;
    }

    // SQLState 23503 is PostgreSQL's foreign_key_violation: some row still references the value being deleted.
    private RuntimeException translateItemInUse(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23503".equals(sql.getSQLState())) {
                return itemInUse();
            }
        }
        return exception;
    }

    private static ApiException itemNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "RECRUITMENT_CATALOG_ITEM_NOT_FOUND",
                "Không tìm thấy giá trị danh mục.");
    }

    private static ApiException duplicateCode() {
        return new ApiException(HttpStatus.CONFLICT, "RECRUITMENT_CATALOG_CODE_EXISTS",
                "Mã giá trị đã được sử dụng trong danh mục này.");
    }

    private static ApiException itemInUse() {
        return new ApiException(HttpStatus.CONFLICT, "RECRUITMENT_CATALOG_ITEM_IN_USE",
                "Giá trị danh mục đang được dữ liệu khác sử dụng nên không thể xóa. "
                        + "Hãy chuyển giá trị sang ngừng sử dụng (active = false).");
    }
}
