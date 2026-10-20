package vn.ttcs.recruitment.notification;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.requisition.ApprovalRound;
import vn.ttcs.recruitment.requisition.ApproverCandidate;
import vn.ttcs.recruitment.requisition.ApproverDirectory;
import vn.ttcs.recruitment.requisition.ChainLevelRow;
import vn.ttcs.recruitment.requisition.RecruitmentRequisitionRepository;
import vn.ttcs.recruitment.requisition.RequisitionApprovalRepository;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Task 544: turns one committed event into the notifications it means now, read from current data in the worker's
 * transaction. Each planned notification has a key that names the business state it reports (an approval level of a
 * round, a posting revision, an offer's approval step), so two events about the same state, or the same event
 * processed again, never notify one person twice. A recipient must be an active account that still holds what the
 * notification's screen requires (NotificationRecipients); an event whose state has moved on (the requisition was
 * decided meanwhile, the panel member was removed) plans nothing.
 */
@Component
public class NotificationPlanner {
    private final NamedParameterJdbcTemplate jdbc;
    private final NotificationRecipients recipients;
    private final RecruitmentRequisitionRepository requisitions;
    private final RequisitionApprovalRepository approvals;
    private final ApproverDirectory directory;
    private final BusinessCalendar calendar;

    public NotificationPlanner(NamedParameterJdbcTemplate jdbc, NotificationRecipients recipients,
                               RecruitmentRequisitionRepository requisitions, RequisitionApprovalRepository approvals,
                               ApproverDirectory directory, BusinessCalendar calendar) {
        this.jdbc = jdbc;
        this.recipients = recipients;
        this.requisitions = requisitions;
        this.approvals = approvals;
        this.directory = directory;
        this.calendar = calendar;
    }

    /** One stored event (V66_7) as the worker claimed it. */
    public record Event(UUID id, NotificationType type, NotificationLink.Type entityType, UUID entityId,
                        List<UUID> recipients) {
    }

    /** A notification to create for one recipient. */
    public record Planned(UUID recipient, NotificationType type, String title, String body, NotificationLink link,
                          String dedupeKey) {
    }

    public List<Planned> plan(Event event) {
        return switch (event.type()) {
            case APPROVAL_PENDING -> switch (event.entityType()) {
                case REQUISITION -> requisitionApproval(event);
                case JOB_POSTING -> postingReview(event);
                case OFFER -> offerApproval(event);
                case INTERVIEW, APPLICATION -> List.of();
            };
            case RECRUITER_ASSIGNED -> recruiterAssigned(event);
            case INTERVIEW_INVITED -> interviewInvited(event);
            case OFFER_RESPONDED -> offerResponded(event);
            case SLA_OVERDUE -> slaOverdue(event);
        };
    }

    private List<Planned> requisitionApproval(Event event) {
        var requisition = requisitions.findById(event.entityId()).orElse(null);
        if (requisition == null || requisition.getStatus() != RequisitionStatus.PENDING_APPROVAL) return List.of();
        var events = approvals.findEvents(requisition.getId());
        var round = ApprovalRound.latest(events).filter(ApprovalRound::open).orElse(null);
        if (round == null) return List.of();
        var levels = approvals.findChainLevels(round.flowId(), round.flowVersion(), round.levelCount()).stream()
                .map(ChainLevelRow::level).toList();
        int level = round.currentLevel();
        var context = round.context(requisition, directory.companyWideRoles(), levels);
        List<UUID> approvers = directory.admitted(levels.get(level - 1), context).stream()
                .filter(ApproverCandidate::accessAllowed).map(ApproverCandidate::id).toList();
        String title = describe("""
                SELECT p.name || ' - ' || d.name FROM recruitment_requisitions r JOIN positions p ON p.id = r.position_id
                JOIN departments d ON d.id = r.department_id WHERE r.id = :id
                """, requisition.getId());
        return plan(active(approvers), NotificationType.APPROVAL_PENDING, "Yêu cầu tuyển dụng chờ bạn duyệt",
                "Yêu cầu tuyển dụng " + title + " đang chờ bạn duyệt ở cấp " + level + ".",
                NotificationLink.requisition(requisition.getId()),
                "APPROVAL_PENDING:REQUISITION:" + requisition.getId() + ":" + round.round() + ":" + level);
    }

    private List<Planned> postingReview(Event event) {
        var rows = jdbc.queryForList("SELECT status, revision, title FROM job_postings WHERE id = :id",
                Map.of("id", event.entityId()));
        if (rows.isEmpty() || !"PENDING_APPROVAL".equals(rows.getFirst().get("status"))) return List.of();
        long revision = ((Number) rows.getFirst().get("revision")).longValue();
        // JobPostingReviewRule: JOB_POSTINGS_WRITE_ALL holders, never an author or the latest submitter.
        List<UUID> reviewers = jdbc.queryForList("""
                SELECT DISTINCT ur.user_id FROM user_roles ur
                JOIN role_permissions rp ON rp.role_code = ur.role AND rp.permission_code = 'JOB_POSTINGS_WRITE_ALL'
                WHERE ur.user_id NOT IN (SELECT account_id FROM job_posting_authors WHERE job_posting_id = :id)
                    AND ur.user_id NOT IN (SELECT actor_id FROM job_posting_events
                        WHERE job_posting_id = :id AND event_type = 'SUBMITTED' AND revision = :revision)
                """, new MapSqlParameterSource("id", event.entityId()).addValue("revision", revision), UUID.class);
        String title = String.valueOf(rows.getFirst().get("title"));
        return plan(active(reviewers), NotificationType.APPROVAL_PENDING, "Tin tuyển dụng chờ bạn duyệt",
                "Tin tuyển dụng \"" + title + "\" đang chờ duyệt.", NotificationLink.jobPosting(event.entityId()),
                "APPROVAL_PENDING:JOB_POSTING:" + event.entityId() + ":" + revision);
    }

    private List<Planned> offerApproval(Event event) {
        var rows = jdbc.queryForList("""
                SELECT application_id, status, approval_round, hr_approver_id, higher_approver_id, candidate_name,
                    position_title FROM offers WHERE id = :id
                """, Map.of("id", event.entityId()));
        if (rows.isEmpty()) return List.of();
        var offer = rows.getFirst();
        String status = (String) offer.get("status");
        UUID approver = switch (status) {
            case "PENDING_HR" -> (UUID) offer.get("hr_approver_id");
            case "PENDING_HIGHER" -> (UUID) offer.get("higher_approver_id");
            default -> null;
        };
        // Only someone who may still decide the step is asked to (OfferService.decide: the step's role, OFFERS write).
        Role role = "PENDING_HR".equals(status) ? Role.HR_MANAGER : Role.APPROVER;
        if (approver == null || !recipients.approvesOffer(approver, role)) return List.of();
        return plan(List.of(approver), NotificationType.APPROVAL_PENDING, "Offer chờ bạn duyệt",
                "Offer vị trí " + offer.get("position_title") + " cho ứng viên " + offer.get("candidate_name")
                        + " đang chờ bạn duyệt.",
                NotificationLink.offer(event.entityId(), (UUID) offer.get("application_id")),
                "APPROVAL_PENDING:OFFER:" + event.entityId() + ":" + offer.get("approval_round") + ":" + status);
    }

    private List<Planned> recruiterAssigned(Event event) {
        List<UUID> stillAssigned = event.recipients().isEmpty() ? List.of() : jdbc.queryForList("""
                SELECT recruiter_id FROM requisition_recruiters WHERE requisition_id = :id AND recruiter_id IN (:ids)
                """, new MapSqlParameterSource("id", event.entityId()).addValue("ids", event.recipients()), UUID.class);
        String title = describe("""
                SELECT p.name || ' - ' || d.name FROM recruitment_requisitions r JOIN positions p ON p.id = r.position_id
                JOIN departments d ON d.id = r.department_id WHERE r.id = :id
                """, event.entityId());
        return plan(active(stillAssigned), NotificationType.RECRUITER_ASSIGNED, "Bạn được phân công vị trí mới",
                "Bạn được phân công tuyển dụng cho yêu cầu " + title + ".", NotificationLink.requisition(event.entityId()),
                "RECRUITER_ASSIGNED:" + event.id());
    }

    private List<Planned> interviewInvited(Event event) {
        var rows = jdbc.queryForList("""
                SELECT i.application_id, i.round_name, i.starts_at, a.posting_title FROM interview_sessions i
                JOIN job_applications a ON a.id = i.application_id WHERE i.id = :id AND i.status = 'SCHEDULED'
                """, Map.of("id", event.entityId()));
        if (rows.isEmpty() || event.recipients().isEmpty()) return List.of();
        List<UUID> panel = jdbc.queryForList("""
                SELECT user_id FROM interview_participants WHERE interview_id = :id AND active AND user_id IN (:ids)
                """, new MapSqlParameterSource("id", event.entityId()).addValue("ids", event.recipients()), UUID.class);
        var interview = rows.getFirst();
        return plan(active(panel), NotificationType.INTERVIEW_INVITED, "Bạn được mời phỏng vấn",
                "Bạn được mời tham gia " + interview.get("round_name") + " cho vị trí " + interview.get("posting_title") + ".",
                NotificationLink.interview(event.entityId(), (UUID) interview.get("application_id")),
                // One invitation notice per person and interview: a resent invitation or a later change of the
                // panel never repeats it for someone already told.
                "INTERVIEW_INVITED:" + event.entityId());
    }

    private List<Planned> offerResponded(Event event) {
        var rows = jdbc.queryForList("""
                SELECT o.application_id, o.status, o.created_by, o.candidate_name, o.position_title, a.requisition_id
                FROM offers o JOIN job_applications a ON a.id = o.application_id WHERE o.id = :id
                """, Map.of("id", event.entityId()));
        if (rows.isEmpty()) return List.of();
        var offer = rows.getFirst();
        String status = (String) offer.get("status");
        if (!"ACCEPTED".equals(status) && !"DECLINED".equals(status)) return List.of();
        Set<UUID> told = new LinkedHashSet<>();
        told.add((UUID) offer.get("created_by"));
        told.addAll(jdbc.queryForList("""
                SELECT recruiter_id FROM requisition_recruiters WHERE requisition_id = :id AND assignment_role = 'PRIMARY'
                """, Map.of("id", offer.get("requisition_id")), UUID.class));
        // Each of them must still be able to open the offer (its read permission and the application's scope).
        UUID application = (UUID) offer.get("application_id");
        List<UUID> readers = told.stream().filter(user -> recipients.readsOffer(user, application)).toList();
        String answer = "ACCEPTED".equals(status) ? "chấp nhận" : "từ chối";
        return plan(readers, NotificationType.OFFER_RESPONDED, "Ứng viên phản hồi offer",
                "Ứng viên " + offer.get("candidate_name") + " đã " + answer + " offer vị trí " + offer.get("position_title") + ".",
                NotificationLink.offer(event.entityId(), (UUID) offer.get("application_id")),
                "OFFER_RESPONDED:" + event.entityId());
    }

    // Task 420: one reminder per (application, stage, day, recipient) was recorded by SlaReminders before this event;
    // the notification is sent only while the application is still flagged in that stage, to a recipient still
    // assigned to its requisition and still allowed to read it. Days are calendar days, like the flag (SlaMonitor).
    private List<Planned> slaOverdue(Event event) {
        var rows = jdbc.queryForList("""
                SELECT f.stage, f.max_days, f.entered_at, a.full_name, a.posting_title, a.requisition_id
                FROM application_sla_flags f JOIN job_applications a ON a.id = f.application_id
                WHERE f.application_id = :id AND a.stage = f.stage
                """, Map.of("id", event.entityId()));
        if (rows.isEmpty() || event.recipients().isEmpty()) return List.of();
        var flag = rows.getFirst();
        List<UUID> readers = jdbc.queryForList("""
                SELECT recruiter_id FROM requisition_recruiters WHERE requisition_id = :id AND recruiter_id IN (:ids)
                """, new MapSqlParameterSource("id", flag.get("requisition_id")).addValue("ids", event.recipients()),
                UUID.class).stream().filter(user -> recipients.readsApplication(user, event.entityId())).toList();
        LocalDate entered = calendar.dateOf(((Timestamp) flag.get("entered_at")).toInstant());
        long days = Math.max(0, ChronoUnit.DAYS.between(entered, calendar.today()));
        String stage = ApplicationStage.valueOf((String) flag.get("stage")).displayName();
        return plan(readers, NotificationType.SLA_OVERDUE, "Hồ sơ quá hạn xử lý",
                "Hồ sơ của " + flag.get("full_name") + " (vị trí " + flag.get("posting_title") + ") đã ở giai đoạn "
                        + stage + " " + days + " ngày, vượt ngưỡng " + flag.get("max_days") + " ngày.",
                NotificationLink.application(event.entityId()), "SLA_OVERDUE:" + event.id());
    }

    private List<UUID> active(List<UUID> ids) {
        return recipients.active(ids);
    }

    private String describe(String sql, UUID id) {
        return jdbc.queryForList(sql, Map.of("id", id), String.class).stream().findFirst().orElse("");
    }

    private static List<Planned> plan(List<UUID> recipients, NotificationType type, String title, String body,
                                      NotificationLink link, String key) {
        List<Planned> planned = new ArrayList<>();
        for (UUID recipient : new LinkedHashSet<>(recipients)) {
            planned.add(new Planned(recipient, type, title, body, link, key));
        }
        return planned;
    }
}
