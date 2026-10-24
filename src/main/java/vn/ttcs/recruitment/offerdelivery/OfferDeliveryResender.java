package vn.ttcs.recruitment.offerdelivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.auth.passwordreset.ResetTokenGenerator;
import vn.ttcs.recruitment.messaging.EmailMessageLog;
import vn.ttcs.recruitment.messaging.FailedEmailResender;
import vn.ttcs.recruitment.offer.OfferMailPayload;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 539: a failed offer email queued again. V62 erases the payload of a FAILED row, so the email is rebuilt from the
 * offer's frozen approved terms, and only while the offer is still exactly the one that was sent: SENT, same version,
 * not answered, before its deadline, same candidate, address and position, an approved requisition and an active
 * position. The response capability is rotated, never revived: the token row must still be unrevoked and unexpired,
 * it gets a new hash (the link of the failed email, which may have reached the candidate, stops working) and the same
 * outbox row goes back to PENDING with the new encrypted payload and a fresh cycle of attempts.
 */
@Component
class OfferDeliveryResender implements FailedEmailResender {
    private final JdbcTemplate jdbc;
    private final OfferDeliverySettings settings;
    private final OfferDeliveryCrypto crypto;
    private final ResetTokenGenerator tokens;
    private final Clock clock;

    OfferDeliveryResender(JdbcTemplate jdbc, OfferDeliverySettings settings, OfferDeliveryCrypto crypto,
                          ResetTokenGenerator tokens, Clock clock) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.crypto = crypto;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Override
    public EmailMessageLog.Delivery delivery() {
        return EmailMessageLog.Delivery.OFFER_DELIVERY;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID resend(FailedEmail email) {
        settings.requireReady();
        var offers = jdbc.queryForList("SELECT * FROM offers WHERE id = ? AND application_id = ? FOR UPDATE",
                email.offerId(), email.applicationId());
        var outboxes = jdbc.queryForList("""
                SELECT status, offer_version, token_id FROM offer_delivery_outbox
                WHERE id = ? AND offer_id = ? FOR UPDATE
                """, email.deliveryId(), email.offerId());
        if (offers.isEmpty() || outboxes.isEmpty()) throw FailedEmailResender.notPossible("Không còn offer của thư này.");
        Map<String, Object> offer = offers.getFirst();
        Map<String, Object> outbox = outboxes.getFirst();
        if (!"FAILED".equals(outbox.get("status"))) throw FailedEmailResender.obsolete("Thư không còn ở trạng thái gửi thất bại.");
        Instant now = clock.instant();
        Instant deadline = ((Timestamp) offer.get("response_deadline")).toInstant();
        if (!"SENT".equals(offer.get("status")) || ((Number) offer.get("version")).longValue()
                != ((Number) outbox.get("offer_version")).longValue() || !deadline.isAfter(now)) {
            throw FailedEmailResender.obsolete("Offer đã thay đổi, đã được phản hồi hoặc đã quá hạn phản hồi nên không gửi lại thư cũ.");
        }
        Map<String, Object> state = jdbc.queryForMap("""
                SELECT a.email, a.full_name, a.stage, ca.candidate_id, r.status AS requisition_status,
                    r.position_id, p.active AS position_active
                FROM job_applications a JOIN candidate_applications ca ON ca.application_id = a.id
                JOIN recruitment_requisitions r ON r.id = a.requisition_id JOIN positions p ON p.id = r.position_id
                WHERE a.id = ?
                """, email.applicationId());
        boolean sameRecipient = Objects.equals(state.get("email"), offer.get("email"))
                && Objects.equals(offer.get("email"), email.recipientEmail())
                && Objects.equals(state.get("full_name"), offer.get("candidate_name"))
                && Objects.equals(state.get("candidate_id"), offer.get("candidate_id"));
        boolean active = "OFFER".equals(state.get("stage")) && "APPROVED".equals(state.get("requisition_status"))
                && Boolean.TRUE.equals(state.get("position_active"))
                && Objects.equals(state.get("position_id"), offer.get("position_id"));
        if (!sameRecipient || !active) {
            throw FailedEmailResender.obsolete("Hồ sơ, người nhận hoặc vị trí của offer đã thay đổi.");
        }
        UUID tokenId = (UUID) outbox.get("token_id");
        var capability = jdbc.queryForList("""
                SELECT id FROM offer_response_tokens
                WHERE id = ? AND offer_id = ? AND revoked_at IS NULL AND expires_at > ? FOR UPDATE
                """, tokenId, email.offerId(), Timestamp.from(now));
        if (capability.isEmpty()) {
            throw FailedEmailResender.obsolete("Liên kết phản hồi của offer đã bị thu hồi hoặc hết hạn; không kích hoạt lại.");
        }
        String token = tokens.create();
        jdbc.update("UPDATE offer_response_tokens SET token_hash = ? WHERE id = ?", tokens.hash(token), tokenId);
        var payload = new OfferMailPayload((String) offer.get("email"), (String) offer.get("candidate_name"),
                (String) offer.get("position_title"), ((Number) offer.get("salary")).longValue(),
                ((Number) offer.get("allowance")).longValue(), ((java.sql.Date) offer.get("start_date")).toLocalDate(),
                deadline, settings.link(token));
        var encrypted = crypto.encrypt(email.deliveryId(), email.offerId(), payload);
        jdbc.update("""
                UPDATE offer_delivery_outbox SET status='PENDING', attempts=0, key_id=?, nonce=?, ciphertext=?,
                    next_attempt_at=clock_timestamp(), last_failure=NULL, updated_at=clock_timestamp()
                WHERE id=? AND status='FAILED'
                """, encrypted.keyId(), encrypted.nonce(), encrypted.ciphertext(), email.deliveryId());
        return email.deliveryId();
    }
}
