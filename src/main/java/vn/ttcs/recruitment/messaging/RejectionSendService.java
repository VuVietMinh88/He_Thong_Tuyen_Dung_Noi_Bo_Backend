package vn.ttcs.recruitment.messaging;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.candidate.CandidateAccess;
import vn.ttcs.recruitment.candidate.CandidateAccessKind;
import vn.ttcs.recruitment.candidate.CandidateReader;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.requisition.RecruiterAssignmentAccess;
import vn.ttcs.recruitment.security.AccessScope;
import vn.ttcs.recruitment.security.PermissionModule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Task 527 (story S7-09, "Chọn nhiều ứng viên cùng trạng thái để gửi một lượt; Hệ thống chặn gửi trùng"): queues the
 * rejection letter of up to 100 rejected applications in one request, with the saved REJECTION template.
 * <p>
 * Who: a NOTIFICATIONS write permission and the CANDIDATES write scope of every selected application (ALL, or the
 * assigned recruiter of its requisition) besides reading it; one application outside that scope refuses the whole
 * batch (403) and queues nothing. Locks, in the house order: the caller's account and session, then every requisition
 * (FOR NO KEY UPDATE, by id), then every application (by requisition, then id), so concurrent batches that share an
 * application run one after the other; each re-reads the letters under those locks and the caller's permissions
 * after them. An application already notified (a rejection letter queued, being sent or sent, automatic or manual) is
 * skipped; one with a failed letter is skipped too, to be retried in the email log (task 539) so its history stays one
 * message. The others get a MANUAL message and a candidate mail queue row (task 538) in this transaction; the
 * unique email_messages_one_rejection_idx is the database's guarantee against a second live letter. A repeated
 * requestId returns the first answer and queues nothing; the same requestId with another selection is 409.
 */
@Service
public class RejectionSendService {
    private final CandidateAccess candidates;
    private final MessagingActors actors;
    private final MessagingTransactions transactions;
    private final RejectionBatchService batches;
    private final EmailMessageLog messages;
    private final CandidateMailOutboxRepository outbox;
    private final RecruiterAssignmentAccess assignments;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public RejectionSendService(CandidateAccess candidates, MessagingActors actors, MessagingTransactions transactions,
                                RejectionBatchService batches, EmailMessageLog messages,
                                CandidateMailOutboxRepository outbox, RecruiterAssignmentAccess assignments,
                                NamedParameterJdbcTemplate jdbc, ObjectMapper json, Clock clock) {
        this.candidates = candidates;
        this.actors = actors;
        this.transactions = transactions;
        this.batches = batches;
        this.messages = messages;
        this.outbox = outbox;
        this.assignments = assignments;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    public record SendRequest(@NotNull UUID requestId,
                              @NotNull @Size(min = 1, max = RejectionBatchService.MAX_BATCH) List<@NotNull UUID> applicationIds) {
        @JsonAnySetter public void reject(String name, Object value) { throw new IllegalArgumentException("Unsupported field"); }
    }

    /** QUEUED, SKIPPED_ALREADY_NOTIFIED or SKIPPED_RETRY_REQUIRED, with the queued message when there is one. */
    public record SendEntry(UUID applicationId, String outcome, UUID messageId) {
    }

    public record SendResult(UUID requestId, int queued, int skipped, List<SendEntry> entries) {
    }

    public SendResult send(Jwt jwt, SendRequest request) {
        List<UUID> ids = RejectionBatchService.distinct(request.applicationIds());
        String hash = hash(ids);
        try {
            return transactions.write("REJECTION_BATCH_BUSY", () -> {
                var actor = actors.lockWriter(jwt);
                CandidateReader reader = new CandidateReader(actor.id(), sender(actor.id()));
                // The caller's account lock serializes every request of the caller, so this read sees an earlier
                // request with the same id once it has committed.
                var earlier = jdbc.queryForList("""
                        SELECT request_hash, response_json::text AS response FROM rejection_batches
                        WHERE actor_id = :actor AND request_id = :request
                        """, new MapSqlParameterSource("actor", actor.id()).addValue("request", request.requestId()));
                if (!earlier.isEmpty()) {
                    if (!hash.equals(earlier.getFirst().get("request_hash"))) {
                        throw new ApiException(HttpStatus.CONFLICT, "REJECTION_BATCH_REQUEST_REUSED",
                                "Mã yêu cầu đã được dùng cho một danh sách hồ sơ khác. Vui lòng tạo yêu cầu mới.");
                    }
                    return json.readValue((String) earlier.getFirst().get("response"), SendResult.class);
                }
                List<RejectionBatchService.Selected> before = batches.select(reader, ids);
                Set<UUID> requisitions = new TreeSet<>();
                before.forEach(application -> requisitions.add(application.requisitionId()));
                for (UUID requisition : requisitions) {
                    jdbc.query("SELECT id FROM recruitment_requisitions WHERE id = :id FOR NO KEY UPDATE",
                            java.util.Map.of("id", requisition), (row, number) -> row.getObject(1));
                }
                for (var application : before) {
                    jdbc.query("SELECT id FROM job_applications WHERE id = :id FOR NO KEY UPDATE",
                            java.util.Map.of("id", application.id()), (row, number) -> row.getObject(1));
                }
                actors.requireFresh(jwt, actor);
                reader = new CandidateReader(actor.id(), sender(actor.id()));
                List<RejectionBatchService.Selected> locked = batches.select(reader, ids);
                boolean writeAll = AccessScope.write(actors.grants(actor.id()), PermissionModule.CANDIDATES) == AccessScope.ALL;
                for (var application : locked) {
                    if (!writeAll && !assignments.isAssigned(application.requisitionId(), actor.id())) {
                        throw new AccessDeniedException("A selected application is outside the caller's write scope");
                    }
                }
                RejectionBatchService.requireRejected(locked);
                List<SendEntry> entries = new ArrayList<>();
                int queued = 0;
                for (var application : locked) {
                    if ("NOTIFIED".equals(application.letter())) {
                        entries.add(new SendEntry(application.id(), "SKIPPED_ALREADY_NOTIFIED", null));
                        continue;
                    }
                    if ("FAILED".equals(application.letter())) {
                        entries.add(new SendEntry(application.id(), "SKIPPED_RETRY_REQUIRED", null));
                        continue;
                    }
                    UUID outboxId = UUID.randomUUID();
                    UUID message = messages.queued(new EmailMessageLog.Message(EmailTemplateType.REJECTION, null,
                            application.id(), null, null, EmailMessageLog.Trigger.MANUAL, application.email(),
                            "REJECTION:MANUAL:" + application.id() + ":" + outboxId, actor.id()),
                            EmailMessageLog.Delivery.CANDIDATE_MAIL, outboxId);
                    outbox.enqueue(outboxId, message);
                    entries.add(new SendEntry(application.id(), "QUEUED", message));
                    queued++;
                    candidates.recordGranted(reader, CandidateAccessKind.PROFILE, application.candidateId(),
                            List.of(application.id()));
                }
                SendResult result = new SendResult(request.requestId(), queued, entries.size() - queued, entries);
                jdbc.update("""
                        INSERT INTO rejection_batches(actor_id, request_id, request_hash, response_json, created_at)
                        VALUES (:actor, :request, :hash, CAST(:response AS jsonb), :at)
                        """, new MapSqlParameterSource("actor", actor.id()).addValue("request", request.requestId())
                        .addValue("hash", hash).addValue("response", json.writeValueAsString(result))
                        .addValue("at", Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS))));
                return result;
            });
        } catch (RejectionBatchService.ScopeDenied denied) {
            // Logged after the write rolled back, like every refused read of an existing candidate record.
            candidates.recordDenied(candidates.requireReader(jwt), CandidateAccessKind.PROFILE, denied.candidateId,
                    denied.applicationId);
            throw new AccessDeniedException("A selected application is outside the caller's candidate scope");
        }
    }

    private AccessScope sender(UUID actor) {
        Set<String> granted = actors.grants(actor);
        AccessScope.write(granted, PermissionModule.NOTIFICATIONS).orDeny();
        AccessScope.write(granted, PermissionModule.CANDIDATES).orDeny();
        return AccessScope.read(granted, PermissionModule.CANDIDATES).orDeny();
    }

    // The selection, independent of order: the sorted ids.
    private static String hash(List<UUID> ids) {
        try {
            String canonical = String.join(",", new TreeSet<>(ids.stream().map(UUID::toString).toList()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
}
