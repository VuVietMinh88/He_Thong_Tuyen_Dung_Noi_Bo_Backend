package vn.ttcs.recruitment.messaging;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.ttcs.recruitment.application.ApplicationStage;

import java.util.UUID;

/**
 * Task 533 (story S8-01): the two emails that only have a template, decided by real business events in the event's
 * own transaction.
 * <ul>
 * <li>REJECTION: a staff member moved the application to REJECTED (a stage change or a hiring decision). A candidate
 * who declines an offer or withdraws is not sent a rejection letter. Both callers hold the application row lock (FOR
 * NO KEY UPDATE), like the bulk send of story S7-09, so the check for a letter already queued, being sent or sent
 * (automatic or bulk, for example before the application was reopened from REJECTED) sees every committed letter:
 * the candidate already has a rejection letter, nothing more is decided and the stage change goes on.</li>
 * <li>INTERVIEW_THANKS: the interview is completed, which the system knows from its first submitted evaluation (an
 * evaluation can only be submitted after the interview ended, and an assessed interview can never be rescheduled).
 * Later evaluations of the same interview record nothing more (one email per interview). Only an application still in
 * an active stage (NEW, SCREENING, INTERVIEW, OFFER) is thanked: after a final decision (REJECTED, HIRED, ONBOARDED)
 * or a withdrawal the candidate gets no thank-you.</li>
 * </ul>
 * With the type's automatic sending on, the email is recorded QUEUED for the candidate mail queue; with it off, it is
 * recorded SUPPRESSED (the bulk rejection of story S7-09 can still send a suppressed rejection letter).
 */
@Component
public class CandidateNoticeEmails {
    private final JdbcTemplate jdbc;
    private final EmailMessageLog messages;

    public CandidateNoticeEmails(JdbcTemplate jdbc, EmailMessageLog messages) {
        this.jdbc = jdbc;
        this.messages = messages;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void rejected(UUID applicationId) {
        String email = jdbc.queryForObject("SELECT email FROM job_applications WHERE id = ?", String.class, applicationId);
        // At most one live rejection letter per application (email_messages_one_rejection_idx): never a second one.
        if (messages.liveRejection(applicationId, null)) return;
        decide(new EmailMessageLog.Message(EmailTemplateType.REJECTION, null, applicationId, null, null,
                EmailMessageLog.Trigger.AUTOMATIC, email, "REJECTION:AUTO:" + applicationId, null));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void interviewCompleted(UUID interviewId, UUID applicationId) {
        var candidate = jdbc.queryForMap("SELECT email, stage FROM job_applications WHERE id = ?", applicationId);
        // A withdrawn candidate asked not to be contacted any more, and a decided application is not thanked.
        if (!ApplicationStage.valueOf((String) candidate.get("stage")).active()) return;
        decide(new EmailMessageLog.Message(EmailTemplateType.INTERVIEW_THANKS, null, applicationId, interviewId, null,
                EmailMessageLog.Trigger.AUTOMATIC, (String) candidate.get("email"), "INTERVIEW_THANKS:" + interviewId,
                null));
    }

    private void decide(EmailMessageLog.Message message) {
        if (messages.autoSend(message.type())) messages.queued(message, EmailMessageLog.Delivery.CANDIDATE_MAIL, null);
        else messages.suppressed(message);
    }
}
