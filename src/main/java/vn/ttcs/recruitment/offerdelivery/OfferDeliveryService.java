package vn.ttcs.recruitment.offerdelivery;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.notification.NotificationEvents;
import vn.ttcs.recruitment.notification.NotificationLink;
import vn.ttcs.recruitment.notification.NotificationType;
import vn.ttcs.recruitment.auth.passwordreset.ResetTokenGenerator;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.common.BusinessCalendar;
import vn.ttcs.recruitment.offer.OfferAccess;
import vn.ttcs.recruitment.offer.OfferDeclineReason;
import vn.ttcs.recruitment.offer.OfferMailPayload;
import vn.ttcs.recruitment.messaging.EmailMessageLog;
import vn.ttcs.recruitment.messaging.EmailTemplateType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** Frozen approved terms, one capability, and one atomic candidate answer. Never sends SMTP in a transaction. */
@Service
public class OfferDeliveryService {
    private static final Set<String> ACTIVE = Set.of("DRAFT", "CHANGES_REQUESTED", "PENDING_HR", "PENDING_HIGHER", "APPROVED", "SENT");
    private final JdbcTemplate jdbc;
    private final OfferAccess access;
    private final OfferDeliverySettings settings;
    private final OfferDeliveryCrypto crypto;
    private final OfferOutboxRepository outbox;
    private final ResetTokenGenerator tokens;
    private final Clock clock;
    private final BusinessCalendar calendar;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;
    private final EmailMessageLog messages;
    private final NotificationEvents notificationEvents;

    // Task 533: sending honours the OFFER automatic-send flag and records the email in email_messages.
    public OfferDeliveryService(JdbcTemplate jdbc, OfferAccess access, OfferDeliverySettings settings,
        OfferDeliveryCrypto crypto, OfferOutboxRepository outbox, ResetTokenGenerator tokens,
        Clock clock, BusinessCalendar calendar, PlatformTransactionManager manager, EmailMessageLog messages, NotificationEvents notificationEvents) {
        this.notificationEvents = notificationEvents;
        this.messages = messages;
        this.jdbc = jdbc;
        this.access = access;
        this.settings = settings;
        this.crypto = crypto;
        this.outbox = outbox;
        this.tokens = tokens;
        this.clock = clock;
        this.calendar = calendar;
        writes = new TransactionTemplate(manager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        writes.setTimeout(5);
        reads = new TransactionTemplate(manager);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setTimeout(5);
    }

    public SendResult send(Jwt jwt, UUID id, long expectedVersion) {
        settings.requireReady();
        return authorized(() -> writes.execute(tx -> {
            var actor = access.lockWriter(jwt);
            Offer before = offer(id, false);
            access.requireWriteScope(jwt, actor, before.applicationId());
            var parent = access.lockParents(before.applicationId());
            Offer current = offer(id, true);
            boolean positionAvailable = positionActive(current.positionId(), true);
            access.fresh(jwt, actor);
            access.requireWriteScope(jwt, actor, current.applicationId());
            access.requireActive(parent);
            Instant now = now();
            if (current.version() != expectedVersion) throw conflict("OFFER_VERSION_CONFLICT", "Offer đã thay đổi. Vui lòng tải lại.");
            if (!"APPROVED".equals(current.status()) || current.approvedAt() == null) {
                throw conflict("OFFER_NOT_APPROVED", "Chỉ gửi offer đã được phê duyệt đầy đủ.");
            }
            if (!current.deadline().isAfter(now) || current.startDate().isBefore(calendar.dateOf(now))) {
                throw conflict("OFFER_TERMS_EXPIRED", "Thời hạn phản hồi hoặc ngày nhận việc đã qua.");
            }
            if (!sameRecipientAndPosition(current, parent) || !positionAvailable) {
                throw conflict("OFFER_RECIPIENT_CHANGED", "Hồ sơ hoặc vị trí đã thay đổi. Vui lòng tạo offer mới.");
            }
            // Task 533 (story S8-01): the email is the only way an offer reaches the candidate (its reply link exists
            // only there), so with the OFFER type's automatic sending off the offer is not marked SENT at all.
            if (!messages.autoSend(EmailTemplateType.OFFER)) {
                throw conflict("OFFER_EMAIL_DISABLED", "Gửi thư offer tự động đang tắt trong cấu hình mẫu email."
                    + " Vui lòng bật lại trước khi gửi offer.");
            }
            String token = tokens.create();
            UUID tokenId = UUID.randomUUID();
            UUID outboxId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO offer_response_tokens(id,offer_id,application_id,token_hash,recipient_hash,expires_at,created_at)
                    VALUES (?,?,?,?,?,?,?)
                    """, tokenId, id, current.applicationId(), tokens.hash(token), tokens.hash(current.email()),
                Timestamp.from(current.deadline()), Timestamp.from(now));
            var payload = new OfferMailPayload(current.email(), current.candidateName(), current.positionTitle(), current.salary(),
                current.allowance(), current.startDate(), current.deadline(), settings.link(token));
            outbox.enqueue(outboxId, id, current.applicationId(), current.version()+1, tokenId, crypto.encrypt(outboxId, id, payload));
            messages.queued(new EmailMessageLog.Message(EmailTemplateType.OFFER, null, current.applicationId(), null, id,
                EmailMessageLog.Trigger.MANUAL, current.email(), "OFFER:" + id, actor.id()),
                EmailMessageLog.Delivery.OFFER_DELIVERY, outboxId);
            jdbc.update("UPDATE offers SET status='SENT',version=version+1,sent_at=?,updated_at=? WHERE id=?",
                Timestamp.from(now), Timestamp.from(now), id);
            event(current, "SENT", actor.id(), actor.name(), null, now);
            access.audit(actor, parent);
            return new SendResult(id, "SENT", "PENDING", current.version()+1, now);
        }));
    }

    public Details lookup(String rawToken) {
        settings.requireReady();
        String hash = hash(rawToken);
        return reads.execute(tx -> {
            Capability token = capability(hash, false);
            Offer offer = offer(token.offerId(), false);
            validate(token, offer, access.context(offer.applicationId()), now());
            return new Details(offer.positionTitle(), offer.salary(), offer.allowance(), offer.startDate(), offer.deadline(),
                offer.status(), offer.respondedAt());
        });
    }

    public Response respond(String rawToken, String action, String rawNote) {
        return respond(rawToken, action, rawNote, null);
    }

    public Response respond(String rawToken, String action, String rawNote, String rawDeclineReason) {
        settings.requireReady();
        String hash = hash(rawToken);
        String status = switch(action == null ? "" : action) {
            case "ACCEPT" -> "ACCEPTED";
            case "DECLINE" -> "DECLINED";
            default -> throw validation("Vui lòng chọn Chấp nhận hoặc Từ chối.");
        };
        String note = note(rawNote);
        OfferDeclineReason declineReason = declineReason(status, rawDeclineReason);
        return busy(() -> writes.execute(tx -> {
            timeouts();
            Capability initial = capability(hash, false);
            var parent = access.lockParents(initial.applicationId());
            Offer current = offer(initial.offerId(), true);
            Capability token = capability(hash, true);
            if (!positionActive(current.positionId(), true)) throw invalid();
            Instant now = now();
            // Authoritative after all blocking parent/offer/token locks.
            validate(token, current, parent, now);
            if (!"SENT".equals(current.status())) {
                if (status.equals(current.status()) && Objects.equals(note, current.note())
                    && Objects.equals(declineReason, current.declineReason())) {
                    return new Response(current.status(), current.respondedAt());
                }
                throw conflict("OFFER_ALREADY_RESPONDED", "Bạn đã phản hồi offer này. Vui lòng liên hệ nhà tuyển dụng nếu cần thay đổi.");
            }
            String stage = "ACCEPTED".equals(status) ? "HIRED" : "REJECTED";
            if (jdbc.update("UPDATE job_applications SET stage=? WHERE id=? AND stage='OFFER'", stage, current.applicationId()) != 1) throw invalid();
            jdbc.update("""
                    UPDATE offers SET status=?,responded_at=?,response_note=?,decline_reason=?,
                        version=version+1,updated_at=? WHERE id=?
                    """, status, Timestamp.from(now), note, declineReason == null ? null : declineReason.name(),
                Timestamp.from(now), current.id());
            jdbc.update("""
                    INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,reason,occurred_at,offer_id)
                    VALUES (?,?,'OFFER',?,'CANDIDATE',?,?,?)
                    """, UUID.randomUUID(), current.applicationId(), stage,
                "ACCEPTED".equals(status) ? "Ứng viên chấp nhận offer" : "Ứng viên từ chối offer", Timestamp.from(now), current.id());
            responseEvent(current, status, note, declineReason, now);
            outbox.cancelPending(current.id());
            // Task 544: the offer's creator and the primary recruiter hear the answer after this commits.
            notificationEvents.publish(NotificationType.OFFER_RESPONDED, NotificationLink.Type.OFFER, current.id());
            return new Response(status, now);
        }));
    }

    /** Runs even when email delivery is disabled, so expired drafts cannot retain the candidate's unique slot. */
    public int expireInactive() {
        List<UUID> ids = jdbc.query("""
                SELECT o.id FROM offers o JOIN job_applications a ON a.id=o.application_id
                JOIN recruitment_requisitions r ON r.id=a.requisition_id JOIN positions p ON p.id=r.position_id
                WHERE o.status IN ('DRAFT','CHANGES_REQUESTED','PENDING_HR','PENDING_HIGHER','APPROVED','SENT')
                    AND (o.response_deadline<=? OR a.stage<>'OFFER' OR r.status<>'APPROVED' OR a.email<>o.email
                         OR a.full_name<>o.candidate_name OR NOT EXISTS (SELECT 1 FROM candidate_applications ca
                            WHERE ca.application_id=a.id AND ca.candidate_id=o.candidate_id)
                         OR r.position_id<>o.position_id OR NOT p.active)
                ORDER BY o.response_deadline,o.id LIMIT 100
                """, (r, n) -> r.getObject(1, UUID.class), Timestamp.from(now()));
        int changed = 0;
        for (UUID id : ids) {
            Boolean updated = busy(() -> writes.execute(tx -> {
                timeouts();
                Offer before = offer(id, false);
                var parent = access.lockParents(before.applicationId());
                Offer current = offer(id, true);
                Instant at = now();
                if (!ACTIVE.contains(current.status())) return false;
                String state = !current.deadline().isAfter(at) ? "EXPIRED" :
                !"OFFER".equals(parent.stage()) || !"APPROVED".equals(parent.requisitionStatus())
                || !sameRecipientAndPosition(current, parent) || !positionActive(current.positionId(), false) ? "CANCELLED" : null;
                if (state == null) return false;
                jdbc.update("UPDATE offers SET status=?,version=version+1,updated_at=? WHERE id=?", state, Timestamp.from(at), id);
                jdbc.update("UPDATE offer_response_tokens SET revoked_at=? WHERE offer_id=? AND revoked_at IS NULL", Timestamp.from(at), id);
                outbox.cancelPending(id);
                event(current, "EXPIRED".equals(state) ? "EXPIRED" : "SYSTEM_CANCELLED", null, null, null, at);
                return true;
            }));
            if (Boolean.TRUE.equals(updated)) changed++;
        }
        return changed;
    }

    private void validate(Capability token, Offer offer, OfferAccess.Context parent, Instant now) {
        String expectedStage = switch(offer.status()) {
            case "SENT" -> "OFFER";
            case "ACCEPTED" -> "HIRED";
            case "DECLINED" -> "REJECTED";
            default -> "INVALID";
        };
        // Completing onboarding does not undo an accepted offer or invalidate an identical candidate retry.
        boolean stageMatches = expectedStage.equals(parent.stage())
                || ("ACCEPTED".equals(offer.status()) && "ONBOARDED".equals(parent.stage()));
        if (token.revoked() || !token.deadline().isAfter(now) || !offer.deadline().isAfter(now)
            || !token.applicationId().equals(offer.applicationId()) || !sameRecipientAndPosition(offer, parent)
            || !tokens.hash(parent.email()).equals(token.recipientHash()) || !"APPROVED".equals(parent.requisitionStatus())
            || !stageMatches || !positionActive(offer.positionId(), false)) throw invalid();
    }

    private boolean sameRecipientAndPosition(Offer offer, OfferAccess.Context parent) {
        return Objects.equals(offer.email(), parent.email()) && Objects.equals(offer.positionId(), parent.positionId())
        && Objects.equals(offer.candidateId(), parent.candidateId()) && Objects.equals(offer.candidateName(), parent.candidateName());
    }

    private boolean positionActive(UUID id, boolean lock) {
        return jdbc.query("SELECT active FROM positions WHERE id=?"+(lock ? " FOR SHARE" : ""), (r, n) -> r.getBoolean(1), id)
        .stream().findFirst().orElse(false);
    }

    private Offer offer(UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM offers WHERE id=?"+(lock ? " FOR UPDATE" : ""), (r, n) -> new Offer(
            r.getObject("id", UUID.class), r.getObject("application_id", UUID.class), r.getObject("candidate_id", UUID.class), r.getObject("position_id", UUID.class),
            r.getString("candidate_name"), r.getString("email"), r.getString("position_title"), r.getLong("salary"), r.getLong("allowance"),
            r.getObject("start_date", LocalDate.class), instant(r, "response_deadline"), r.getString("status"), r.getLong("version"),
            r.getInt("approval_round"), instant(r, "approved_at"), instant(r, "responded_at"), r.getString("response_note"),
            OfferDeclineReason.parseOptional(r.getString("decline_reason"))), id)
        .stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "OFFER_NOT_FOUND", "Không tìm thấy offer."));
    }

    private Capability capability(String hash, boolean lock) {
        return jdbc.query("SELECT * FROM offer_response_tokens WHERE token_hash=?"+(lock ? " FOR UPDATE" : ""), (r, n) -> new Capability(
            r.getObject("offer_id", UUID.class), r.getObject("application_id", UUID.class), r.getString("recipient_hash"),
            instant(r, "expires_at"), r.getTimestamp("revoked_at") != null), hash).stream().findFirst().orElseThrow(OfferDeliveryService::invalid);
    }

    private void event(Offer offer, String type, UUID actor, String name, String comment, Instant now) {
        jdbc.update("""
                INSERT INTO offer_events(id,offer_id,approval_round,event_type,actor_id,actor_name,occurred_at,comment)
                VALUES (?,?,?,?,?,?,?,?)
                """, UUID.randomUUID(), offer.id(), offer.round(), type, actor, name, Timestamp.from(now), comment);
    }

    private void responseEvent(Offer offer, String status, String note, OfferDeclineReason declineReason, Instant now) {
        if (!"DECLINED".equals(status)) {
            event(offer, status, null, null, note, now);
            return;
        }
        // Keep the candidate's complete note; the structured reason is separate immutable evidence.
        jdbc.update("""
                INSERT INTO offer_events(id,offer_id,approval_round,event_type,occurred_at,comment,terms_snapshot)
                VALUES (?,?,?,'DECLINED',?,?,jsonb_build_object('declineReason',CAST(? AS text)))
                """, UUID.randomUUID(), offer.id(), offer.round(), Timestamp.from(now), note,
            declineReason == null ? null : declineReason.name());
    }

    private String hash(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        return tokens.hash(token);
    }

    private static String note(String raw) {
        if (raw == null) return null;
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (normalized.length()>2000 || normalized.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
            throw validation("Ý kiến tối đa 2000 ký tự và không chứa ký tự điều khiển.");
        }
        boolean visible = normalized.codePoints().anyMatch(c -> !Character.isWhitespace(c) && !Character.isSpaceChar(c)
            && Character.getType(c) != Character.FORMAT);
        return visible ? normalized : null;
    }

    private static OfferDeclineReason declineReason(String status, String raw) {
        if (!"DECLINED".equals(status) && raw != null) {
            throw validation("Chỉ chọn lý do từ chối khi từ chối offer.");
        }
        try {
            // Missing values remain valid for clients written before structured reasons were introduced.
            return OfferDeclineReason.parseOptional(raw);
        } catch (IllegalArgumentException invalidReason) {
            throw validation("Lý do từ chối phải là SALARY, LOCATION, OTHER_JOB hoặc PERSONAL.");
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static Instant instant(ResultSet r, String column) throws SQLException {
        Timestamp t = r.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private void timeouts() {
        jdbc.execute("SET LOCAL lock_timeout='2s'");
        jdbc.execute("SET LOCAL statement_timeout='3s'");
    }

    private <T> T authorized(Supplier<T> action) {
        try {
            return busy(action);
        }
        catch (OfferAccess.ScopeDenied denied) {
            access.auditDenied(denied);
            throw denied;
        }
    }

    private static <T> T busy(Supplier<T> action) {
        try {
            return action.get();
        }
        catch(TransientDataAccessException | TransactionTimedOutException failure) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "OFFER_BUSY", "Offer đang được cập nhật. Vui lòng thử lại.");
        }
    }

    private static ApiException conflict(String code, String text) {
        return new ApiException(HttpStatus.CONFLICT, code, text);
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "OFFER_LINK_INVALID", "Liên kết offer không còn hiệu lực. Vui lòng liên hệ nhà tuyển dụng.");
    }

    private static ApiException validation(String text) {
        return new ApiException(HttpStatus.BAD_REQUEST, "OFFER_RESPONSE_VALIDATION", text);
    }

    private record Offer(UUID id, UUID applicationId, UUID candidateId, UUID positionId, String candidateName, String email, String positionTitle,
        long salary, long allowance, LocalDate startDate, Instant deadline, String status, long version, int round,
        Instant approvedAt, Instant respondedAt, String note, OfferDeclineReason declineReason) {
        @Override public String toString() {
            return "Offer[redacted]";
        }
    }

    private record Capability(UUID offerId, UUID applicationId, String recipientHash, Instant deadline, boolean revoked) {
        @Override public String toString() {
            return "OfferCapability[redacted]";
        }
    }

    public record SendResult(UUID id, String status, String deliveryStatus, long version, Instant sentAt) {
    }

    public record Details(String positionTitle, long salary, long allowance, LocalDate startDate, Instant responseDeadline,
        String status, Instant respondedAt) {
        @Override public String toString() {
            return "OfferDetails[redacted]";
        }
    }

    public record Response(String status, Instant respondedAt) {
    }
}
