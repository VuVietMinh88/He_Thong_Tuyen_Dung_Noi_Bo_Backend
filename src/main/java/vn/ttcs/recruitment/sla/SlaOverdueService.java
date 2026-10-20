package vn.ttcs.recruitment.sla;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.messaging.MessagingTransactions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 419 (story S5-10, "Quá hạn thì hồ sơ được gắn cờ"): the flagged applications the caller may read
 * (CandidateAccess.READABLE), most overdue first, with how many calendar days (app.business-zone, like the flags)
 * each has been in its stage. A flag is shown only while the application is still in the flagged stage and its
 * requisition is still recruiting (SlaMonitor.MONITORED_REQUISITION_STATUSES), even before the next run clears it.
 * Each shown application is logged as a granted profile read, like every list of candidates.
 */
@Service
public class SlaOverdueService {
    static final int MAX_PAGE_SIZE = 100;
    private final CandidateAccess candidates;
    private final MessagingTransactions transactions;
    private final NamedParameterJdbcTemplate jdbc;
    private final BusinessCalendar calendar;

    public SlaOverdueService(CandidateAccess candidates, MessagingTransactions transactions,
                             NamedParameterJdbcTemplate jdbc, BusinessCalendar calendar) {
        this.candidates = candidates;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.calendar = calendar;
    }

    public record Item(UUID applicationId, String candidateName, String positionTitle, UUID requisitionId,
                       String stage, String stageName, Instant enteredAt, Instant dueAt, int maxDays, long daysInStage,
                       Instant flaggedAt) {
    }

    public record Page(List<Item> items, int page, int size, long totalItems, int totalPages) {
    }

    public Page list(Jwt jwt, int page, int size) {
        CandidateReader reader = candidates.requireReader(jwt);
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE || (long) page * size > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Trang hoặc số lượng không hợp lệ.",
                    Map.of("size", "Kích thước trang từ 1 đến " + MAX_PAGE_SIZE + "."));
        }
        return transactions.read(() -> {
            var parameters = candidates.parameters(reader).addValue("size", size).addValue("offset", (long) page * size)
                    .addValue("statuses", SlaMonitor.monitoredStatuses());
            String scope = """
                    FROM application_sla_flags f JOIN job_applications a ON a.id = f.application_id
                    JOIN recruitment_requisitions r ON r.id = a.requisition_id
                    JOIN candidate_applications ca ON ca.application_id = a.id
                    WHERE a.stage = f.stage AND r.status IN (:statuses) AND """ + CandidateAccess.READABLE;
            long total = jdbc.queryForObject("SELECT count(*) " + scope, parameters, Long.class);
            LocalDate today = calendar.today();
            record Row(Item item, UUID candidateId) { }
            List<Row> rows = jdbc.query("""
                    SELECT f.*, a.full_name, a.posting_title, a.requisition_id, ca.candidate_id
                    """ + scope + " ORDER BY f.due_at, f.application_id LIMIT :size OFFSET :offset", parameters,
                    (row, number) -> {
                        Instant entered = row.getTimestamp("entered_at").toInstant();
                        String stage = row.getString("stage");
                        return new Row(new Item(row.getObject("application_id", UUID.class), row.getString("full_name"),
                                row.getString("posting_title"), row.getObject("requisition_id", UUID.class), stage,
                                ApplicationStage.valueOf(stage).displayName(), entered,
                                row.getTimestamp("due_at").toInstant(), row.getInt("max_days"),
                                Math.max(0, ChronoUnit.DAYS.between(calendar.dateOf(entered), today)),
                                row.getTimestamp("flagged_at").toInstant()), row.getObject("candidate_id", UUID.class));
                    });
            Map<UUID, List<UUID>> shown = new LinkedHashMap<>();
            rows.forEach(row -> shown.computeIfAbsent(row.candidateId(), key -> new ArrayList<>()).add(row.item().applicationId()));
            shown.forEach((candidate, applications) ->
                    candidates.recordGranted(reader, CandidateAccessKind.PROFILE, candidate, applications));
            return new Page(rows.stream().map(Row::item).toList(), page, size, total, (int) ((total + size - 1) / size));
        });
    }
}
