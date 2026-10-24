package vn.ttcs.recruitment.sla;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.notification.NotificationEvents;
import vn.ttcs.recruitment.notification.NotificationLink;
import vn.ttcs.recruitment.notification.NotificationRecipients;
import vn.ttcs.recruitment.notification.NotificationType;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 420 (story S5-10, "recruiter nhận nhắc việc; không nhắc trùng trong cùng ngày"): one reminder per flagged
 * application, stage, business day and recipient. Only an eligible recruiter is reminded: assigned to the
 * application's requisition, an active account (enabled, not locked) that may still read the application (a CANDIDATES
 * read permission and the application in its scope, NotificationRecipients.readsApplication). The recipients are the
 * eligible PRIMARY recruiter, or, when the requisition has no eligible primary (none, or a disabled, locked or no
 * longer authorized one), its eligible supporting recruiters. Only applications still flagged in their stage, of a
 * requisition still recruiting (SlaMonitor.MONITORED_REQUISITION_STATUSES), are reminded. sla_reminders records the
 * day before anything is sent: its primary key makes a second run of the same day remind nobody twice, and the job's
 * advisory lock serializes concurrent runs. Each new reminder becomes an SLA_OVERDUE notification event, delivered
 * in-app and by email according to the recipient's preferences (tasks 544, 551), whose recipient is checked once more
 * when it is planned.
 */
@Component
public class SlaReminders {
    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationEvents events;
    private final NotificationRecipients recipients;
    private final BusinessCalendar calendar;

    public SlaReminders(NamedParameterJdbcTemplate jdbc, NotificationEvents events, NotificationRecipients recipients,
                        BusinessCalendar calendar) {
        this.jdbc = jdbc;
        this.events = events;
        this.recipients = recipients;
        this.calendar = calendar;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int remind(Instant now) {
        LocalDate day = calendar.dateOf(now);
        record Assigned(UUID applicationId, String stage, UUID recruiterId, boolean primary) { }
        Map<UUID, List<Assigned>> byApplication = new LinkedHashMap<>();
        jdbc.query("""
                SELECT f.application_id, f.stage, rr.recruiter_id, rr.assignment_role = 'PRIMARY' AS is_primary
                FROM application_sla_flags f
                JOIN job_applications a ON a.id = f.application_id AND a.stage = f.stage
                JOIN recruitment_requisitions r ON r.id = a.requisition_id AND r.status IN (:statuses)
                JOIN requisition_recruiters rr ON rr.requisition_id = a.requisition_id
                ORDER BY f.application_id, rr.recruiter_id
                """, Map.of("statuses", SlaMonitor.monitoredStatuses()), row -> {
            UUID application = row.getObject("application_id", UUID.class);
            byApplication.computeIfAbsent(application, key -> new ArrayList<>()).add(new Assigned(application,
                    row.getString("stage"), row.getObject("recruiter_id", UUID.class), row.getBoolean("is_primary")));
        });
        int created = 0;
        for (List<Assigned> assigned : byApplication.values()) {
            List<Assigned> eligible = assigned.stream()
                    .filter(recruiter -> recipients.readsApplication(recruiter.recruiterId(), recruiter.applicationId()))
                    .toList();
            List<Assigned> primary = eligible.stream().filter(Assigned::primary).toList();
            for (Assigned target : primary.isEmpty() ? eligible : primary) {
                int inserted = jdbc.update("""
                        INSERT INTO sla_reminders(application_id, stage, reminder_date, recipient_id, created_at)
                        VALUES (:application, :stage, :day, :recipient, :now)
                        ON CONFLICT DO NOTHING
                        """, new MapSqlParameterSource("application", target.applicationId())
                        .addValue("stage", target.stage()).addValue("day", Date.valueOf(day))
                        .addValue("recipient", target.recruiterId()).addValue("now", Timestamp.from(now)));
                if (inserted == 1) {
                    events.publish(NotificationType.SLA_OVERDUE, NotificationLink.Type.APPLICATION,
                            target.applicationId(), List.of(target.recruiterId()));
                    created++;
                }
            }
        }
        return created;
    }
}
