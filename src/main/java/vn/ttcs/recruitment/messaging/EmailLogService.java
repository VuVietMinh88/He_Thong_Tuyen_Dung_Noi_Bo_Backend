package vn.ttcs.recruitment.messaging;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.requisition.RecruiterAssignmentAccess;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Task 539 (story S8-02, "Hiển thị thời điểm gửi, mẫu đã dùng, trạng thái gửi thành công hay thất bại; Gửi lại được thư
 * thất bại"): the candidate email log and the retry of a failed email.
 * <p>
 * Reading needs a NOTIFICATIONS read permission and a CANDIDATES read permission (both read again from the database);
 * the list shows only emails of applications the caller may read (CandidateAccess.READABLE), and a detail outside the
 * scope is 403 (logged as a refused profile read). Every shown email is logged as a granted profile read.
 * <p>
 * Retrying needs a NOTIFICATIONS write permission and the CANDIDATES write scope of the application (ALL, or the
 * assigned recruiter), plus OFFERS write for an offer and INTERVIEWS write for an interview email. Only a FAILED email
 * is retried; the write locks, in the house order, the caller's account and session, the requisition, the application
 * and the message, and re-checks the caller after every wait, so two concurrent retries have exactly one winner (the
 * other sees the email QUEUED: 409 EMAIL_RETRY_NOT_FAILED). The outbox's FailedEmailResender rebuilds the delivery from
 * current data, locking its own rows (interview, offer, token, outbox), so the caller is checked once more after it
 * before anything is written; the earlier attempts stay in the immutable attempt log.
 */
@Service
public class EmailLogService {
    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> STATUSES = Set.of("QUEUED", "SENDING", "RETRY_WAIT", "SENT", "FAILED", "SUPPRESSED",
            "CANCELLED");
    private static final String SELECT = """
            SELECT m.*, a.full_name, a.posting_title, ca.candidate_id, a.requisition_id,
                (SELECT count(*) FROM email_attempts t WHERE t.message_id = m.id) AS attempt_count,
                creator.full_name AS created_by_name, retrier.full_name AS retried_by_name,
            """ + CandidateAccess.READABLE + """
             AS readable
            FROM email_messages m
            JOIN job_applications a ON a.id = m.application_id
            JOIN recruitment_requisitions r ON r.id = a.requisition_id
            JOIN candidate_applications ca ON ca.application_id = a.id
            LEFT JOIN user_accounts creator ON creator.id = m.created_by
            LEFT JOIN user_accounts retrier ON retrier.id = m.last_retried_by
            """;
    private static final String FILTER = """
             WHERE (CAST(:application AS uuid) IS NULL OR m.application_id = CAST(:application AS uuid))
                AND (CAST(:type AS varchar) IS NULL OR m.template_type = CAST(:type AS varchar))
                AND (CAST(:status AS varchar) IS NULL OR m.status = CAST(:status AS varchar))
                AND (CAST(:from AS timestamptz) IS NULL OR m.created_at >= CAST(:from AS timestamptz))
                AND (CAST(:to AS timestamptz) IS NULL OR m.created_at < CAST(:to AS timestamptz))
            """;

    private final CandidateAccess candidates;
    private final MessagingActors actors;
    private final MessagingTransactions transactions;
    private final NamedParameterJdbcTemplate jdbc;
    private final EmailAttemptRepository attempts;
    private final RecruiterAssignmentAccess assignments;
    private final Map<EmailMessageLog.Delivery, FailedEmailResender> resenders = new EnumMap<>(EmailMessageLog.Delivery.class);
    private final Clock clock;

    public EmailLogService(CandidateAccess candidates, MessagingActors actors, MessagingTransactions transactions,
                           NamedParameterJdbcTemplate jdbc, EmailAttemptRepository attempts,
                           RecruiterAssignmentAccess assignments, List<FailedEmailResender> resenders, Clock clock) {
        this.candidates = candidates;
        this.actors = actors;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.attempts = attempts;
        this.assignments = assignments;
        this.clock = clock;
        for (FailedEmailResender resender : resenders) {
            if (this.resenders.put(resender.delivery(), resender) != null) {
                throw new IllegalStateException("Two resenders for " + resender.delivery());
            }
        }
        if (this.resenders.size() != EmailMessageLog.Delivery.values().length) {
            throw new IllegalStateException("Every email outbox needs a resender");
        }
    }

    /** Filters of the list; every one is optional. */
    public record Filter(UUID applicationId, String type, String status, Instant from, Instant to, int page, int size) {
    }

    public EmailLogViews.Page list(Jwt jwt, Filter filter) {
        CandidateReader reader = reader(jwt);
        Map<String, String> problems = new LinkedHashMap<>();
        if (filter.page() < 0) problems.put("page", "Trang phải từ 0 trở lên.");
        if (filter.size() < 1 || filter.size() > MAX_PAGE_SIZE) problems.put("size", "Kích thước trang từ 1 đến " + MAX_PAGE_SIZE + ".");
        if (filter.type() != null && EmailTemplateType.parse(filter.type()).isEmpty()) problems.put("type", "Loại thư không hợp lệ.");
        if (filter.status() != null && !STATUSES.contains(filter.status())) problems.put("status", "Trạng thái không hợp lệ.");
        if (filter.from() != null && filter.to() != null && !filter.from().isBefore(filter.to())) {
            problems.put("to", "Thời điểm kết thúc phải sau thời điểm bắt đầu.");
        }
        if (!problems.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Bộ lọc nhật ký email không hợp lệ.", problems);
        }
        return transactions.read(() -> {
            MapSqlParameterSource parameters = candidates.parameters(reader)
                    .addValue("application", filter.applicationId()).addValue("type", filter.type())
                    .addValue("status", filter.status())
                    .addValue("from", filter.from() == null ? null : Timestamp.from(filter.from()))
                    .addValue("to", filter.to() == null ? null : Timestamp.from(filter.to()))
                    .addValue("size", filter.size()).addValue("offset", (long) filter.page() * filter.size());
            String scoped = FILTER + " AND " + CandidateAccess.READABLE;
            long total = jdbc.queryForObject("""
                    SELECT count(*) FROM email_messages m
                    JOIN job_applications a ON a.id = m.application_id
                    JOIN recruitment_requisitions r ON r.id = a.requisition_id
                    """ + scoped, parameters, Long.class);
            List<Row> rows = jdbc.query(SELECT + scoped + " ORDER BY m.created_at DESC, m.id DESC LIMIT :size OFFSET :offset",
                    parameters, EmailLogService::row);
            audit(reader, rows);
            return new EmailLogViews.Page(rows.stream().map(Row::view).toList(), filter.page(), filter.size(), total,
                    (int) ((total + filter.size() - 1) / filter.size()));
        });
    }

    public EmailLogViews.Detail detail(Jwt jwt, UUID id) {
        CandidateReader reader = reader(jwt);
        try {
            return transactions.read(() -> {
                Row row = find(reader, id);
                if (!row.readable()) throw new ScopeDenied(row.candidateId(), row.applicationId());
                audit(reader, List.of(row));
                return new EmailLogViews.Detail(row.view(), attempts.forMessage(id).stream()
                        .map(attempt -> new EmailLogViews.Attempt(attempt.number(), attempt.result(), attempt.errorCode(),
                                attempt.templateType(), attempt.templateVersion(), attempt.recipientEmail(),
                                attempt.attemptedAt())).toList());
            });
        } catch (ScopeDenied denied) {
            candidates.recordDenied(reader, CandidateAccessKind.PROFILE, denied.candidateId, denied.applicationId);
            throw new AccessDeniedException("The email belongs to an application outside the caller's scope");
        }
    }

    public EmailLogViews.Message retry(Jwt jwt, UUID id) {
        return transactions.write("EMAIL_RETRY_BUSY", () -> {
            var actor = actors.lockWriter(jwt);
            Set<String> granted = retrier(actor.id());
            Row before = find(new CandidateReader(actor.id(), readScope(granted)), id);
            jdbc.query("SELECT id FROM recruitment_requisitions WHERE id = :id FOR NO KEY UPDATE",
                    Map.of("id", before.requisitionId()), (result, number) -> result.getObject(1));
            jdbc.query("SELECT id FROM job_applications WHERE id = :id FOR NO KEY UPDATE",
                    Map.of("id", before.applicationId()), (result, number) -> result.getObject(1));
            requireRetryScope(jwt, actor, before);
            var locked = jdbc.queryForMap("SELECT status, delivery, delivery_id, last_failure FROM email_messages"
                    + " WHERE id = :id FOR UPDATE", Map.of("id", id));
            // The message lock may have waited for a concurrent retry: the caller is checked once more afterwards.
            requireRetryScope(jwt, actor, before);
            if (!"FAILED".equals(locked.get("status"))) {
                throw new ApiException(HttpStatus.CONFLICT, "EMAIL_RETRY_NOT_FAILED",
                        "Chỉ gửi lại được thư đang ở trạng thái gửi thất bại.");
            }
            var delivery = EmailMessageLog.Delivery.valueOf((String) locked.get("delivery"));
            UUID queued = resenders.get(delivery).resend(new FailedEmailResender.FailedEmail(id,
                    EmailTemplateType.valueOf(before.type()), before.variant(), before.applicationId(),
                    before.interviewId(), before.offerId(), (UUID) locked.get("delivery_id"), before.recipientEmail(),
                    (String) locked.get("last_failure")));
            // The resender may have waited for its interview, offer, token or outbox row locks: check again.
            requireRetryScope(jwt, actor, before);
            Timestamp now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));
            jdbc.update("""
                    UPDATE email_messages SET delivery_id = :delivery, status = 'QUEUED', last_failure = NULL,
                        sent_at = NULL, retry_count = retry_count + 1, last_retried_by = :actor,
                        last_retried_at = :now, updated_at = :now
                    WHERE id = :id
                    """, new MapSqlParameterSource("delivery", queued).addValue("actor", actor.id())
                    .addValue("now", now).addValue("id", id));
            Row after = find(new CandidateReader(actor.id(), readScope(retrier(actor.id()))), id);
            audit(new CandidateReader(actor.id(), readScope(granted)), List.of(after));
            return after.view();
        });
    }

    private CandidateReader reader(Jwt jwt) {
        CandidateReader reader = candidates.requireReader(jwt);
        Set<String> granted = actors.grants(reader.id());
        if (AccessScope.read(granted, PermissionModule.NOTIFICATIONS) == AccessScope.NONE) {
            throw new AccessDeniedException("No NOTIFICATIONS read permission");
        }
        return reader;
    }

    private Set<String> retrier(UUID actor) {
        Set<String> granted = actors.grants(actor);
        AccessScope.write(granted, PermissionModule.NOTIFICATIONS).orDeny();
        AccessScope.write(granted, PermissionModule.CANDIDATES).orDeny();
        AccessScope.read(granted, PermissionModule.CANDIDATES).orDeny();
        return granted;
    }

    private static AccessScope readScope(Set<String> granted) {
        return AccessScope.read(granted, PermissionModule.CANDIDATES).orDeny();
    }

    // The application must be readable and writable for the caller, and the email's own module writable.
    private void requireRetryScope(Jwt jwt, MessagingActors.Actor actor, Row before) {
        actors.requireFresh(jwt, actor);
        Set<String> granted = retrier(actor.id());
        boolean readable = candidates.findApplication(new CandidateReader(actor.id(), readScope(granted)),
                before.applicationId()).map(application -> application.readable()).orElse(false);
        boolean assigned = assignments.isAssigned(before.requisitionId(), actor.id());
        boolean writable = AccessScope.write(granted, PermissionModule.CANDIDATES) == AccessScope.ALL || assigned;
        PermissionModule module = switch (EmailTemplateType.valueOf(before.type())) {
            case OFFER -> PermissionModule.OFFERS;
            case INTERVIEW_INVITATION, INTERVIEW_THANKS -> PermissionModule.INTERVIEWS;
            case APPLICATION_RECEIPT, REJECTION -> PermissionModule.CANDIDATES;
        };
        AccessScope moduleScope = AccessScope.write(granted, module);
        boolean moduleWritable = moduleScope == AccessScope.ALL || (moduleScope == AccessScope.SCOPED && assigned);
        if (!readable || !writable || !moduleWritable) {
            throw new AccessDeniedException("The email is outside the caller's retry scope");
        }
    }

    private Row find(CandidateReader reader, UUID id) {
        return jdbc.query(SELECT + " WHERE m.id = :id", candidates.parameters(reader).addValue("id", id),
                EmailLogService::row).stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EMAIL_MESSAGE_NOT_FOUND",
                        "Không tìm thấy thư trong nhật ký."));
    }

    private void audit(CandidateReader reader, List<Row> rows) {
        Map<UUID, List<UUID>> byCandidate = new LinkedHashMap<>();
        for (Row row : rows) {
            List<UUID> applications = byCandidate.computeIfAbsent(row.candidateId(), key -> new ArrayList<>());
            if (!applications.contains(row.applicationId())) applications.add(row.applicationId());
        }
        byCandidate.forEach((candidate, applications) ->
                candidates.recordGranted(reader, CandidateAccessKind.PROFILE, candidate, applications));
    }

    private static Row row(ResultSet row, int number) throws SQLException {
        return new Row(row.getObject("id", UUID.class), row.getString("template_type"), row.getString("variant"),
                row.getObject("application_id", UUID.class), row.getObject("candidate_id", UUID.class),
                row.getObject("requisition_id", UUID.class), row.getString("full_name"), row.getString("posting_title"),
                row.getObject("interview_id", UUID.class), row.getObject("offer_id", UUID.class),
                row.getString("trigger_kind"), row.getString("status"), row.getString("recipient_email"),
                row.getString("last_failure"), row.getInt("attempt_count"), row.getInt("retry_count"),
                instant(row, "created_at"), instant(row, "updated_at"), instant(row, "sent_at"),
                row.getString("created_by_name"), instant(row, "last_retried_at"), row.getString("retried_by_name"),
                row.getBoolean("readable"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record Row(UUID id, String type, String variant, UUID applicationId, UUID candidateId, UUID requisitionId,
                       String candidateName, String positionTitle, UUID interviewId, UUID offerId, String trigger,
                       String status, String recipientEmail, String lastFailure, int attemptCount, int retryCount,
                       Instant createdAt, Instant updatedAt, Instant sentAt, String createdByName,
                       Instant lastRetriedAt, String lastRetriedByName, boolean readable) {
        EmailLogViews.Message view() {
            return new EmailLogViews.Message(id, type, EmailTemplateType.valueOf(type).displayName(), variant,
                    applicationId, candidateName, positionTitle, interviewId, offerId, trigger, status, recipientEmail,
                    lastFailure, attemptCount, retryCount, createdAt, updatedAt, sentAt, createdByName, lastRetriedAt,
                    lastRetriedByName, "FAILED".equals(status));
        }

        @Override public String toString() { return "EmailLogRow[" + id + ", personal data redacted]"; }
    }

    private static final class ScopeDenied extends RuntimeException {
        private final UUID candidateId;
        private final UUID applicationId;

        ScopeDenied(UUID candidateId, UUID applicationId) {
            super(null, null, false, false);
            this.candidateId = candidateId;
            this.applicationId = applicationId;
        }
    }
}
