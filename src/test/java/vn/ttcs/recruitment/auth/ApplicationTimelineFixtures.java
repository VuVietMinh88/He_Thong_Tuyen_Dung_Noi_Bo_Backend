package vn.ttcs.recruitment.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import vn.ttcs.recruitment.application.ApplicationStage;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Task 403 (story S5-07): the rows a timeline reads, written with SQL at times the test chooses, under the CHECKs of
 * V28, V34, V35, V40 and V41. Each method returns the id of the row it wrote (the rating's id is its application's).
 */
final class ApplicationTimelineFixtures {
    // At least 16 bytes (V28); a test looks for it in what the timeline returns.
    static final String CIPHERTEXT = "TIMELINE_CIPHERTEXT_SENTINEL";
    private final JdbcTemplate jdbc;

    ApplicationTimelineFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // A staff stage change (actor STAFF) with an optional note; interview_skipped follows V40's rule.
    UUID staffStage(UUID application, ApplicationStage before, ApplicationStage after, UUID actor, String note,
                    Instant at) {
        return stage(application, before, after, actor, note, null, null, at);
    }

    // A staff rejection with its REJECTION_REASON catalog value and the name stored with it (V40).
    UUID rejection(UUID application, ApplicationStage before, UUID actor, UUID reasonId, String reasonName, Instant at) {
        return stage(application, before, ApplicationStage.REJECTED, actor, null, reasonId, reasonName, at);
    }

    // The candidate's own withdrawal (actor CANDIDATE, no account), with the reason the candidate typed.
    UUID withdrawal(UUID application, ApplicationStage before, String reason, Instant at) {
        return stage(application, before, ApplicationStage.WITHDRAWN, null, reason, null, null, at);
    }

    private UUID stage(UUID application, ApplicationStage before, ApplicationStage after, UUID staffOrNull,
                       String reason, UUID rejectionReasonId, String rejectionReasonName, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO application_stage_events(id,application_id,stage_before,stage_after,actor_type,actor_id,
                    reason,occurred_at,rejection_reason_id,rejection_reason_name,interview_skipped)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, id, application, before.name(), after.name(), staffOrNull == null ? "CANDIDATE" : "STAFF",
                staffOrNull, reason, Timestamp.from(at), rejectionReasonId, rejectionReasonName,
                before == ApplicationStage.SCREENING && after == ApplicationStage.OFFER);
        return id;
    }

    // The receipt confirmation as the worker leaves it after SMTP accepted it: SENT, payload erased (V28).
    UUID sentConfirmation(UUID application, String messageId, Instant sentAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO application_email_outbox(id,application_id,message_id,status,attempts,created_at,updated_at,
                    sent_at)
                VALUES (?,?,?,'SENT',1,?,?,?)
                """, id, application, messageId, Timestamp.from(sentAt), Timestamp.from(sentAt), Timestamp.from(sentAt));
        return id;
    }

    // A confirmation not (yet) sent, in this status, still holding its encrypted payload; PROCESSING with a lease.
    UUID unsentConfirmation(UUID application, String status, String messageId, String keyId, Instant at) {
        UUID id = UUID.randomUUID();
        boolean leased = "PROCESSING".equals(status);
        jdbc.update("""
                INSERT INTO application_email_outbox(id,application_id,message_id,key_id,nonce,ciphertext,status,attempts,
                    lease_token,lease_until,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,1,?,?,?,?)
                """, id, application, messageId, keyId, new byte[12], CIPHERTEXT.getBytes(StandardCharsets.US_ASCII),
                status, leased ? UUID.randomUUID() : null, leased ? Timestamp.from(at.plus(Duration.ofMinutes(2))) : null,
                Timestamp.from(at), Timestamp.from(at));
        return id;
    }

    // A withdrawal notice (V35) of this kind and status; sent_at only when SENT, a lease only when PROCESSING. The
    // recipient is a staff account for RECRUITER_WITHDRAWAL and null for CANDIDATE_WITHDRAWAL.
    UUID notice(UUID application, String kind, UUID recipientOrNull, String status, String messageId, Instant at) {
        UUID id = UUID.randomUUID();
        boolean leased = "PROCESSING".equals(status);
        jdbc.update("""
                INSERT INTO application_notice_outbox(id,kind,application_id,recipient_id,message_id,status,attempts,
                    lease_token,lease_until,created_at,updated_at,sent_at)
                VALUES (?,?,?,?,?,?,1,?,?,?,?,?)
                """, id, kind, application, recipientOrNull, messageId, status, leased ? UUID.randomUUID() : null,
                leased ? Timestamp.from(at.plus(Duration.ofMinutes(2))) : null, Timestamp.from(at), Timestamp.from(at),
                "SENT".equals(status) ? Timestamp.from(at) : null);
        return id;
    }

    UUID note(UUID application, UUID author, String content, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO application_screening_notes(id,application_id,content,author_id,created_at) VALUES (?,?,?,?,?)",
                id, application, content, author, Timestamp.from(at));
        return id;
    }

    // The shared rating, overwritten like ScreeningRepository.rate: one row per application.
    UUID rate(UUID application, int rating, UUID actor, Instant at) {
        jdbc.update("""
                INSERT INTO application_screening_ratings(application_id,rating,updated_by,updated_at) VALUES (?,?,?,?)
                ON CONFLICT(application_id) DO UPDATE SET rating = EXCLUDED.rating,
                    updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at
                """, application, rating, actor, Timestamp.from(at));
        return application;
    }
}
