package vn.ttcs.recruitment.candidateduplicate;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.common.ApiException;

@Service
public class CandidateDuplicateService {
    private final CandidateDuplicateRepository repository;
    private final CandidateDuplicateAccess access;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate read;
    private final boolean enabled;

    public CandidateDuplicateService(CandidateDuplicateRepository repository, CandidateDuplicateAccess access,
            JdbcTemplate jdbc, PlatformTransactionManager manager,
            @Value("${app.candidate-duplicates.enabled:false}") boolean enabled) {
        this.repository = repository; this.access = access; this.jdbc = jdbc; this.enabled = enabled;
        read = new TransactionTemplate(manager); read.setReadOnly(true); read.setTimeout(5);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public DuplicateCandidateView get(Jwt jwt, UUID application, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > 1_000_000)
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Phân trang không hợp lệ.");
        try {
            return read.execute(ignored -> {
                jdbc.execute("SET LOCAL statement_timeout='3s'");
                var caller = access.reader(jwt);
                var app = repository.application(application);
                access.requireScope(caller, app.requisition(), app.department());
                if (!enabled) throw unavailable();
                return repository.view(app, caller, page, size);
            });
        } catch (DataAccessException | TransactionException exception) { throw unavailable(); }
    }

    static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DUPLICATE_DETECTION_UNAVAILABLE", "Chức năng cảnh báo trùng tạm chưa sẵn sàng.");
    }
}
