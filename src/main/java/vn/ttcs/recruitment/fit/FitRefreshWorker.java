package vn.ttcs.recruitment.fit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.candidate.CandidateWriteGuard;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 413 (story S5-09): brings stored fit results up to date after a criteria save, which itself recomputes nothing.
 * A result is stale when it names an older criteria or qualifications version than the current ones, another position,
 * or is missing. Each run rewrites at most MAX_BATCHES batches of BATCH stale results, each result in its own short
 * transaction without any position lock (FitResults), so a position with many applications never blocks intakes or
 * criteria saves. Readers never wait for it: they evaluate stale rows live.
 * <p>
 * The schedule is app.fit-scoring.refresh-cron in the business zone (default every five minutes); "-" turns it off, as
 * the tests do (src/test/resources/application-default.properties), and they call refreshStale themselves.
 */
@Component
public class FitRefreshWorker {
    static final int BATCH = 200;
    static final int MAX_BATCHES = 10;
    private final FitRepository fits;
    private final FitResults results;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate reads;
    private final TransactionTemplate writes;

    FitRefreshWorker(FitRepository fits, FitResults results, JdbcTemplate jdbc, Clock clock,
                     PlatformTransactionManager transactions) {
        this.fits = fits;
        this.results = results;
        this.jdbc = jdbc;
        this.clock = clock;
        reads = new TransactionTemplate(transactions);
        reads.setReadOnly(true);
        writes = new TransactionTemplate(transactions);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(5);
    }

    @Scheduled(cron = "${app.fit-scoring.refresh-cron:0 */5 * * * *}", zone = "${app.business-zone}")
    public void refreshScheduled() {
        refreshStale();
    }

    // Returns how many results were rewritten. A result busy elsewhere (lock or statement timeout) waits for the next run.
    public int refreshStale() {
        int refreshed = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<UUID> stale = Objects.requireNonNull(reads.execute(status -> fits.staleApplications(BATCH)));
            for (UUID application : stale) {
                try {
                    writes.executeWithoutResult(status -> {
                        jdbc.execute("SET LOCAL lock_timeout = '2s'");
                        jdbc.execute("SET LOCAL statement_timeout = '3s'");
                        results.evaluate(application, clock.instant().truncatedTo(ChronoUnit.MICROS));
                    });
                    refreshed++;
                } catch (RuntimeException failure) {
                    if (!CandidateWriteGuard.busy(failure)) throw failure;
                }
            }
            if (stale.size() < BATCH) break;
        }
        return refreshed;
    }
}
