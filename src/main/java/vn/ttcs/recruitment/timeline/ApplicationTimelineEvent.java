package vn.ttcs.recruitment.timeline;

import vn.ttcs.recruitment.application.ApplicationStage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 403 (story S5-07): one event of exactly one application's timeline, read by ApplicationTimelineRepository from
 * the table that already records it. id is stable and names its source ("submission:", "stage:", "email:", "notice:",
 * "note:", "rating:", "interview:" or "evaluation:" followed by the source row's UUID); occurredAt is the time that source saved. Ids and staff
 * display names only: no candidate data, no staff email, no mail payload, Message-ID or tracking secret.
 * <p>
 * Interview and evaluation events expose only their recorded metadata; a stage change to INTERVIEW is still only
 * a stage change. toString leaves internal text and names out.
 */
public record ApplicationTimelineEvent(String id, Type type, UUID applicationId, Instant occurredAt, Actor actor,
                                       Details details) {
    public ApplicationTimelineEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(details, "details");
    }

    public enum Type {
        // job_applications.received_at; details Submission.
        APPLICATION_SUBMITTED,
        // One immutable application_stage_events row (V34, V40), staff change or the candidate's withdrawal.
        STAGE_CHANGED,
        // An outbox row with status SENT: the SMTP server accepted the mail at sent_at. Not proof of delivery or reading.
        EMAIL_SENT,
        // One append-only application_screening_notes row.
        SCREENING_NOTE_ADDED,
        // The current shared rating (application_screening_ratings, one row per application, overwritten by every
        // rating): its value and who saved it when. Earlier values are not kept, so this is not a rating history.
        SCREENING_RATING_SNAPSHOT,
        // Immutable interview_events rows; details use the recorded round/time, never current room or meeting link.
        INTERVIEW_SCHEDULED,
        INTERVIEW_RESCHEDULED,
        INTERVIEW_CANCELLED,
        // Immutable evaluation_events rows, never drafts, scores, comments or conclusions.
        EVALUATION_SUBMITTED,
        EVALUATION_REOPENED
    }

    public enum ActorType { CANDIDATE, STAFF, SYSTEM }

    // STAFF: the account's id and its full name as it is now (not as it was at occurredAt). CANDIDATE and SYSTEM: no
    // id and no name; the application itself identifies the candidate.
    public record Actor(ActorType type, UUID id, String displayName) {
        static final Actor CANDIDATE = new Actor(ActorType.CANDIDATE, null, null);
        static final Actor SYSTEM = new Actor(ActorType.SYSTEM, null, null);

        static Actor staff(UUID id, String displayName) { return new Actor(ActorType.STAFF, id, displayName); }

        @Override public String toString() { return "Actor[" + type + ", " + id + "]"; }
    }

    // What the event recorded; the event's type names which record it is.
    public sealed interface Details permits Submission, StageChange, EmailSent, ScreeningNote, RatingSnapshot,
            InterviewChange, EvaluationChange { }

    // The posting title stored with the application when it was received (V26 snapshot, written by staff).
    public record Submission(String postingTitle) implements Details { }

    // reason: the staff member's note on STAFF rows, the candidate's own withdrawal reason on CANDIDATE rows (V34's
    // reason column), null without one. rejectionReasonId/Name only on a staff rejection, with the catalog name stored
    // at that time (V40), not its current name. interviewSkipped only on the HR exception SCREENING → OFFER.
    public record StageChange(ApplicationStage previousStage, ApplicationStage stage, String reason,
                              UUID rejectionReasonId, String rejectionReasonName, boolean interviewSkipped)
            implements Details {
        @Override public String toString() {
            return "StageChange[" + previousStage + " -> " + stage + ", reason redacted]";
        }
    }

    public enum MailKind {
        // application_email_outbox (V28): the receipt confirmation to the application's address.
        APPLICATION_CONFIRMATION,
        // application_notice_outbox (V35), CANDIDATE_WITHDRAWAL: to the application's address.
        CANDIDATE_WITHDRAWAL,
        // application_notice_outbox (V35), RECRUITER_WITHDRAWAL: to the requisition's PRIMARY recruiter.
        RECRUITER_WITHDRAWAL
    }

    public enum RecipientType { CANDIDATE, STAFF }

    // STAFF: the recipient account's id and current full name, never its email. CANDIDATE: no id and no name.
    public record Recipient(RecipientType type, UUID id, String displayName) {
        static final Recipient CANDIDATE = new Recipient(RecipientType.CANDIDATE, null, null);

        @Override public String toString() { return "Recipient[" + type + ", " + id + "]"; }
    }

    // Metadata only: which mail and to whom. Accepted by the SMTP server, not delivered or read.
    public record EmailSent(MailKind mail, Recipient recipient) implements Details { }

    // The note as stored (append-only, V41); its author is the event's actor.
    public record ScreeningNote(String content) implements Details {
        @Override public String toString() { return "ScreeningNote[content redacted]"; }
    }

    // The current shared rating 1..5; who saved it is the event's actor and when is occurredAt.
    public record RatingSnapshot(int value) implements Details {
        @Override public String toString() { return "RatingSnapshot[value redacted]"; }
    }

    // The round name and scheduled times as recorded by this event, even after later rescheduling or cancellation.
    public record InterviewChange(UUID interviewId, String roundName, Instant startsAt, Instant endsAt)
            implements Details {
        @Override public String toString() { return "InterviewChange[" + interviewId + ", details redacted]"; }
    }

    // A submission or reopening occurred; the caller must use the evaluation API's separate rules to read a form.
    public record EvaluationChange(UUID interviewId, UUID formId) implements Details { }

    @Override
    public String toString() {
        return "ApplicationTimelineEvent[" + id + ", " + type + ", " + occurredAt + ", details redacted]";
    }
}
