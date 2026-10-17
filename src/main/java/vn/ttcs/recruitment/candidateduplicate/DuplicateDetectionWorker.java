package vn.ttcs.recruitment.candidateduplicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class DuplicateDetectionWorker {
    private static final Logger LOG = LoggerFactory.getLogger(DuplicateDetectionWorker.class);
    private final CandidateDuplicateRepository repository;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final int batchSize;

    public DuplicateDetectionWorker(CandidateDuplicateRepository repository, JdbcTemplate jdbc,
            PlatformTransactionManager manager, @Value("${app.candidate-duplicates.enabled:false}") boolean enabled,
            @Value("${app.candidate-duplicates.batch-size:25}") int batchSize) {
        if (batchSize < 1 || batchSize > 100) throw new IllegalArgumentException("Invalid duplicate detection batch size");
        this.repository = repository; this.jdbc = jdbc; this.enabled = enabled; this.batchSize = batchSize;
        tx = new TransactionTemplate(manager); tx.setTimeout(15);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Scheduled(initialDelayString="${app.candidate-duplicates.poll-delay-ms:5000}",
            fixedDelayString="${app.candidate-duplicates.poll-delay-ms:5000}")
    public void poll() {
        if (!enabled) return;
        try { processBatch(); }
        catch (RuntimeException exception) { LOG.warn("Candidate duplicate detection unavailable; will retry on the next run"); }
    }

    public int processBatch() {
        if (!enabled) return 0;
        try {
            return tx.execute(ignored -> {
                jdbc.execute("SET LOCAL lock_timeout='2s'"); jdbc.execute("SET LOCAL statement_timeout='3s'");
                if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class,
                        CandidateDuplicateRepository.DETECTION_LOCK))) return 0;
                // No timestamp cursor: a transaction committing late is discovered on a later scan.
                var pending = repository.pending(batchSize);
                pending.forEach(repository::index);
                return pending.size();
            });
        } catch (DataAccessException | TransactionException exception) { throw CandidateDuplicateService.unavailable(); }
    }
}
