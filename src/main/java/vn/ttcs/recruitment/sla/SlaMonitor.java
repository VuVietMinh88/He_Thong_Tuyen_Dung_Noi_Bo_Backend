package vn.ttcs.recruitment.sla;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Task 419 (story S5-10, "Tác vụ định kỳ chạy mỗi ngày một lần"): the daily job that flags applications staying too
 * long in their current stage. The time in a stage is measured from the latest stage-history row that moved the
 * application into that stage (task 380/381 history), or from its receipt while it is still NEW; final stages
 * (HIRED, ONBOARDED, REJECTED, WITHDRAWN) are never flagged, and neither is an application of a requisition that is not
 * recruiting (MONITORED_REQUISITION_STATUSES: filled, cancelled or paused). A threshold of n days counts calendar days
 * in app.business-zone (BusinessCalendar): an application that entered its stage on day D may stay there until the
 * end of day D + n, whatever the time it entered, and is late from the start of day D + n + 1. A run first deletes
 * every stale flag (the application moved or finished, its requisition stopped recruiting, or it is no longer late
 * under the current threshold), then inserts or refreshes the flags of the applications that are late now. One
 * transaction-scoped advisory lock lets only one instance run it at a time; a second concurrent run waits and then
 * finds nothing new to do.
 */
@Component
public class SlaMonitor {
    private static final Logger LOG = LoggerFactory.getLogger(SlaMonitor.class);
    // Arbitrary constant key of pg_advisory_xact_lock, unique to this job.
    static final long LOCK_KEY = 5_810_419L;
    /**
     * The requisition statuses whose applications are monitored: only a requisition still recruiting (APPROVED, like
     * RequisitionListItemView.OVERDUE_STATUSES). A paused requisition is not monitored (a question for BA/PO); resuming
     * it monitors its applications again from the next run. FILLED and CANCELLED are final.
     */
    public static final Set<RequisitionStatus> MONITORED_REQUISITION_STATUSES = EnumSet.of(RequisitionStatus.APPROVED);
    private static final String IN_STAGE = """
            SELECT a.id, a.stage, t.max_days,
                COALESCE((SELECT max(e.occurred_at) FROM application_stage_events e
                    WHERE e.application_id = a.id AND e.stage_after = a.stage), a.received_at) AS entered_at
            FROM job_applications a
            JOIN recruitment_requisitions r ON r.id = a.requisition_id AND r.status IN (:statuses)
            JOIN stage_sla_thresholds t ON t.stage = a.stage AND t.max_days IS NOT NULL
            WHERE a.stage IN ('NEW', 'SCREENING', 'INTERVIEW', 'OFFER')
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final BusinessCalendar calendar;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public SlaMonitor(JdbcTemplate jdbc, BusinessCalendar calendar, Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.calendar = calendar;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(60);
    }

    @Scheduled(cron = "${app.sla.cron:0 0 7 * * *}", zone = "${app.business-zone}")
    public void runDaily() {
        try {
            Result result = run();
            LOG.info("SLA check flagged {} application(s), cleared {}", result.flagged(), result.cleared());
        } catch (RuntimeException failure) {
            LOG.warn("SLA check failed; it runs again tomorrow or when started by hand");
        }
    }

    /** Counts of one run: flags inserted or refreshed, stale flags deleted. */
    public record Result(int flagged, int cleared) {
    }

    /** The SQL list parameter of MONITORED_REQUISITION_STATUSES. */
    public static List<String> monitoredStatuses() {
        return MONITORED_REQUISITION_STATUSES.stream().map(Enum::name).toList();
    }

    public Result run() {
        return transaction.execute(status -> {
            jdbc.execute("SET LOCAL statement_timeout = '50s'");
            jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", LOCK_KEY);
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Timestamp at = Timestamp.from(now);
            record Late(UUID id, String stage, Instant enteredAt, Instant dueAt, int maxDays) { }
            Map<UUID, Late> late = new LinkedHashMap<>();
            named.query(IN_STAGE, Map.of("statuses", monitoredStatuses()), row -> {
                Instant entered = row.getTimestamp("entered_at").toInstant();
                int maxDays = row.getInt("max_days");
                Instant due = dueAt(entered, maxDays);
                if (!now.isBefore(due)) {
                    UUID id = row.getObject("id", UUID.class);
                    late.put(id, new Late(id, row.getString("stage"), entered, due, maxDays));
                }
            });
            List<Object[]> stale = new ArrayList<>();
            jdbc.query("SELECT application_id, stage FROM application_sla_flags", row -> {
                UUID id = row.getObject("application_id", UUID.class);
                String stage = row.getString("stage");
                Late current = late.get(id);
                if (current == null || !current.stage().equals(stage)) stale.add(new Object[] {id, stage});
            });
            int cleared = count(jdbc.batchUpdate(
                    "DELETE FROM application_sla_flags WHERE application_id = ? AND stage = ?", stale));
            List<Object[]> flags = late.values().stream().map(flag -> new Object[] {flag.id(), flag.stage(),
                    Timestamp.from(flag.enteredAt()), Timestamp.from(flag.dueAt()), flag.maxDays(), at, at}).toList();
            int flagged = count(jdbc.batchUpdate("""
                    INSERT INTO application_sla_flags(application_id, stage, entered_at, due_at, max_days, flagged_at,
                        checked_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (application_id) DO UPDATE SET entered_at = EXCLUDED.entered_at,
                        due_at = EXCLUDED.due_at, max_days = EXCLUDED.max_days, checked_at = EXCLUDED.checked_at
                    """, flags));
            return new Result(flagged, cleared);
        });
    }

    /** The first moment an application that entered its stage at entered is late under a threshold of maxDays. */
    Instant dueAt(Instant entered, int maxDays) {
        return calendar.startOf(calendar.dateOf(entered).plusDays(maxDays + 1L));
    }

    private static int count(int[] updated) {
        int total = 0;
        for (int rows : updated) total += Math.max(rows, 0);
        return total;
    }
}
