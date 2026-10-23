package vn.ttcs.recruitment.timeline;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.Actor;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.ActorType;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.Details;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.EmailSent;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.MailKind;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.RatingSnapshot;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.Recipient;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.RecipientType;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.ScreeningNote;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.StageChange;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.Submission;
import vn.ttcs.recruitment.timeline.ApplicationTimelineEvent.Type;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 403 (story S5-07): the timeline of exactly one application, gathered from the tables that already record its
 * events: the receipt (job_applications.received_at), the stage history (application_stage_events, V34/V40), the
 * confirmation and withdrawal mails the SMTP server accepted (application_email_outbox V28 and
 * application_notice_outbox V35, status SENT only), the screening notes and the current shared rating (V41). Interview
 * scheduling and evaluation have no tables yet and are not invented from the INTERVIEW stage; screening tags are
 * mutable state, not history, and are left out.
 * <p>
 * Called only after the caller authorized this application, inside its transaction (MANDATORY), the REPEATABLE READ
 * read of the timeline API. Every arm of the UNION names the application, so applications of the same candidate
 * profile never mix. One statement returns both the total and the page, so they come from one snapshot and agree even
 * under READ COMMITTED; the database orders and cuts the page (newest first, ties by event id in "C" collation byte
 * order), so at most size rows reach Java.
 * <p>
 * Read: ids, types, times, staff display names (user_accounts.full_name as it is now) and the internal text staff may
 * read (stage reasons, notes). Never read: mail ciphertext, nonce, key id or Message-ID, tracking hashes, staff or
 * candidate emails, candidate name or phone.
 */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class ApplicationTimelineRepository {
    public static final int MAX_PAGE = 1_000_000;
    public static final int MAX_SIZE = 100;

    // Seventeen columns per arm, in this order. The first arm types every column (a multi-arm UNION resolves its types
    // pair by pair, so a later NULL must find a type already set).
    private static final String TIMELINE = """
            WITH events AS (
                SELECT 'submission:' || a.id::text AS event_id, 'APPLICATION_SUBMITTED'::text AS event_type,
                    a.received_at AS occurred_at,
                    CASE WHEN referral.referrer_id IS NULL THEN 'CANDIDATE' ELSE 'STAFF' END AS actor_type,
                    referral.referrer_id AS actor_id, referrer.full_name::text AS actor_name,
                    a.posting_title::text AS posting_title,
                    NULL::text AS stage_before, NULL::text AS stage_after, NULL::text AS internal_text,
                    NULL::uuid AS rejection_reason_id, NULL::text AS rejection_reason_name,
                    NULL::boolean AS interview_skipped,
                    NULL::text AS mail_kind, NULL::uuid AS recipient_id, NULL::text AS recipient_name,
                    NULL::integer AS rating
                FROM job_applications a
                LEFT JOIN application_referrals referral ON referral.application_id = a.id
                LEFT JOIN user_accounts referrer ON referrer.id = referral.referrer_id
                WHERE a.id = ?
                UNION ALL
                SELECT 'stage:' || e.id::text, 'STAGE_CHANGED',
                    e.occurred_at, e.actor_type, e.actor_id,
                    u.full_name, NULL,
                    e.stage_before, e.stage_after, e.reason,
                    e.rejection_reason_id, e.rejection_reason_name,
                    e.interview_skipped,
                    NULL, NULL, NULL,
                    NULL
                FROM application_stage_events e LEFT JOIN user_accounts u ON u.id = e.actor_id
                WHERE e.application_id = ?
                UNION ALL
                SELECT 'email:' || o.id::text, 'EMAIL_SENT',
                    o.sent_at, 'SYSTEM', NULL,
                    NULL, NULL,
                    NULL, NULL, NULL,
                    NULL, NULL,
                    NULL,
                    'APPLICATION_CONFIRMATION', NULL, NULL,
                    NULL
                FROM application_email_outbox o
                WHERE o.application_id = ? AND o.status = 'SENT' AND o.sent_at IS NOT NULL
                UNION ALL
                SELECT 'notice:' || n.id::text, 'EMAIL_SENT',
                    n.sent_at, 'SYSTEM', NULL,
                    NULL, NULL,
                    NULL, NULL, NULL,
                    NULL, NULL,
                    NULL,
                    n.kind, n.recipient_id, r.full_name,
                    NULL
                FROM application_notice_outbox n LEFT JOIN user_accounts r ON r.id = n.recipient_id
                WHERE n.application_id = ? AND n.status = 'SENT' AND n.sent_at IS NOT NULL
                UNION ALL
                SELECT 'note:' || s.id::text, 'SCREENING_NOTE_ADDED',
                    s.created_at, 'STAFF', s.author_id,
                    u.full_name, NULL,
                    NULL, NULL, s.content,
                    NULL, NULL,
                    NULL,
                    NULL, NULL, NULL,
                    NULL
                FROM application_screening_notes s LEFT JOIN user_accounts u ON u.id = s.author_id
                WHERE s.application_id = ?
                UNION ALL
                SELECT 'rating:' || g.application_id::text, 'SCREENING_RATING_SNAPSHOT',
                    g.updated_at, 'STAFF', g.updated_by,
                    u.full_name, NULL,
                    NULL, NULL, NULL,
                    NULL, NULL,
                    NULL,
                    NULL, NULL, NULL,
                    g.rating
                FROM application_screening_ratings g LEFT JOIN user_accounts u ON u.id = g.updated_by
                WHERE g.application_id = ?
            )
            SELECT total.n AS total_elements, slice.*
            FROM (SELECT count(*) AS n FROM events) total
            LEFT JOIN (
                SELECT * FROM events ORDER BY occurred_at DESC, event_id COLLATE "C" LIMIT ? OFFSET ?
            ) slice ON true
            ORDER BY slice.occurred_at DESC, slice.event_id COLLATE "C"
            """;

    private final JdbcTemplate jdbc;

    public ApplicationTimelineRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // page 0..MAX_PAGE and size 1..MAX_SIZE, checked again here so no caller can ask for an unbounded read. An unknown
    // application has an empty timeline: the caller has already answered whether it exists.
    public ApplicationTimelinePage find(UUID applicationId, int page, int size) {
        Objects.requireNonNull(applicationId, "applicationId");
        if (page < 0 || page > MAX_PAGE) throw new IllegalArgumentException("page must be between 0 and " + MAX_PAGE);
        if (size < 1 || size > MAX_SIZE) throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
        ResultSetExtractor<ApplicationTimelinePage> extractor = rows -> toPage(rows, applicationId, page, size);
        return jdbc.query(TIMELINE, extractor, applicationId, applicationId, applicationId, applicationId,
                applicationId, applicationId, size, (long) page * size);
    }

    // Every row carries the total; a page past the end is one row with the total and no event.
    private static ApplicationTimelinePage toPage(ResultSet rows, UUID applicationId, int page, int size)
            throws SQLException {
        long total = 0;
        var items = new ArrayList<ApplicationTimelineEvent>(size);
        while (rows.next()) {
            total = rows.getLong("total_elements");
            String id = rows.getString("event_id");
            if (id != null) items.add(event(rows, id, applicationId));
        }
        return new ApplicationTimelinePage(applicationId, items, total, page, size);
    }

    // Exhaustive switches without default: a new type, actor or mail kind does not compile until someone decides
    // what the timeline shows of it; an unknown code from the database fails the read instead of being shown.
    private static ApplicationTimelineEvent event(ResultSet row, String id, UUID applicationId) throws SQLException {
        var type = Type.valueOf(row.getString("event_type"));
        Details details = switch (type) {
            case APPLICATION_SUBMITTED -> new Submission(row.getString("posting_title"));
            case STAGE_CHANGED -> new StageChange(ApplicationStage.valueOf(row.getString("stage_before")),
                    ApplicationStage.valueOf(row.getString("stage_after")), row.getString("internal_text"),
                    row.getObject("rejection_reason_id", UUID.class), row.getString("rejection_reason_name"),
                    row.getBoolean("interview_skipped"));
            case EMAIL_SENT -> emailSent(row);
            case SCREENING_NOTE_ADDED -> new ScreeningNote(row.getString("internal_text"));
            case SCREENING_RATING_SNAPSHOT -> new RatingSnapshot(row.getInt("rating"));
        };
        return new ApplicationTimelineEvent(id, type, applicationId, row.getTimestamp("occurred_at").toInstant(),
                actor(row), details);
    }

    private static Actor actor(ResultSet row) throws SQLException {
        return switch (ActorType.valueOf(row.getString("actor_type"))) {
            case CANDIDATE -> Actor.CANDIDATE;
            case SYSTEM -> Actor.SYSTEM;
            case STAFF -> Actor.staff(row.getObject("actor_id", UUID.class), row.getString("actor_name"));
        };
    }

    private static EmailSent emailSent(ResultSet row) throws SQLException {
        var mail = MailKind.valueOf(row.getString("mail_kind"));
        var recipient = switch (mail) {
            case APPLICATION_CONFIRMATION, CANDIDATE_WITHDRAWAL -> Recipient.CANDIDATE;
            case RECRUITER_WITHDRAWAL -> new Recipient(RecipientType.STAFF, row.getObject("recipient_id", UUID.class),
                    row.getString("recipient_name"));
        };
        return new EmailSent(mail, recipient);
    }
}
