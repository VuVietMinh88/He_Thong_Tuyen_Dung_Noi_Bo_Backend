package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.notification.NotificationEvents;
import vn.ttcs.recruitment.notification.NotificationLink;
import vn.ttcs.recruitment.notification.NotificationType;
import vn.ttcs.recruitment.notification.NotificationWorker;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 544: committed business events become notifications for the right people, once: a new recruiter assignment,
 * a panel invitation, a candidate's offer answer, and the pending steps of offers and job postings.
 */
@TestPropertySource(properties = {"app.notifications.poll-delay-ms=3600000", "app.candidate-mail.poll-delay-ms=3600000"})
class NotificationProducersIntegrationTest extends OfferDeliveryTestSupport {
    @Autowired NotificationWorker worker;
    @Autowired NotificationEvents events;

    @BeforeEach void clean() {
        jdbc.execute("TRUNCATE notifications, notification_events");
    }

    @Test
    void aNewRecruiterIsNotifiedOnceAndAnUnchangedAssignmentNotifiesNobody() throws Exception {
        ok(call("POST", "/api/v1/requisitions/" + requisition + "/assign", json.writeValueAsString(Map.of(
                "recruiterId", outsider, "role", "SUPPORTING")), hrToken));
        // Assigning the same person to the same role again changes nothing and records no event.
        ok(call("POST", "/api/v1/requisitions/" + requisition + "/assign", json.writeValueAsString(Map.of(
                "recruiterId", outsider, "role", "SUPPORTING")), hrToken));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_events", Long.class)).isEqualTo(1);
        drain();
        assertThat(jdbc.queryForList("SELECT recipient_id, notification_type, link_type, link_id FROM notifications"))
                .containsExactly(Map.of("recipient_id", outsider, "notification_type", "RECRUITER_ASSIGNED",
                        "link_type", "REQUISITION", "link_id", requisition));
    }

    @Test
    void panelMembersAreNotifiedOfTheirInterviewEvenWhenInvitationEmailsAreOff() throws Exception {
        UUID panel = applications.account("Panel", "panel@example.test", Role.INTERVIEWER);
        UUID later = applications.account("Later", "later@example.test", Role.INTERVIEWER);
        var body = new LinkedHashMap<String, Object>();
        body.put("roundName", "Kỹ thuật"); body.put("startsAt", NOW.plusSeconds(86400).toString());
        body.put("durationMinutes", 60); body.put("mode", "ONSITE"); body.put("room", "Room A");
        body.put("participantIds", List.of(panel));
        var created = call("POST", "/api/v1/applications/" + first.applicationId() + "/interviews",
                json.writeValueAsString(body), hrToken);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID interview = UUID.fromString(json.readTree(created.body()).path("id").asText());
        body.put("participantIds", List.of(panel, later));
        body.put("expectedVersion", 0);
        ok(call("PUT", "/api/v1/interviews/" + interview, json.writeValueAsString(body), hrToken));
        drain();
        drain();
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notifications WHERE notification_type='INTERVIEW_INVITED'"
                + " ORDER BY created_at", UUID.class)).containsExactlyInAnyOrder(panel, later);
        var link = jdbc.queryForMap("SELECT link_id, parent_id FROM notifications WHERE recipient_id=?", later);
        assertThat(link).containsEntry("link_id", interview).containsEntry("parent_id", first.applicationId());
    }

    @Test
    void theCandidatesOfferAnswerNotifiesTheOfferCreatorAndThePrimaryRecruiterOnce() throws Exception {
        Sent sent = queued();
        assertThat(respond(sent.token(), "ACCEPT", null).statusCode()).isEqualTo(200);
        // The same answer again is idempotent and records no second event.
        assertThat(respond(sent.token(), "ACCEPT", null).statusCode()).isEqualTo(200);
        drain();
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notifications WHERE notification_type='OFFER_RESPONDED'",
                UUID.class)).containsExactly(recruiter);
        assertThat(jdbc.queryForObject("SELECT body FROM notifications", String.class)).contains("chấp nhận");
    }

    @Test
    void anOfferAnswerIsNotSentToSomeoneWhoCanNoLongerReadTheOffer() throws Exception {
        Sent sent = queued();
        // The recruiter (offer creator and primary recruiter) lost the RECRUITER role, and with it every OFFERS grant.
        jdbc.update("DELETE FROM user_roles WHERE user_id=?", recruiter);
        assertThat(respond(sent.token(), "ACCEPT", null).statusCode()).isEqualTo(200);
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM notification_events", String.class)).isEqualTo("DONE");
    }

    @Test
    void aNamedOfferApproverWhoLostTheStepsRoleIsNotAskedToDecide() {
        UUID approver = applications.account("Approver", "approver3@example.test", Role.APPROVER);
        UUID offer = pendingOffer(approver);
        // HR, the named HR approver, is no longer an HR manager (it cannot decide PENDING_HR any more).
        jdbc.update("DELETE FROM user_roles WHERE user_id=? AND role='HR_MANAGER'", hr);
        publish(NotificationType.APPROVAL_PENDING, NotificationLink.Type.OFFER, offer);
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
        // The higher approver keeps its role, but the role lost its OFFERS permissions.
        jdbc.update("UPDATE offers SET status='PENDING_HIGHER' WHERE id=?", offer);
        List<String> offerGrants = jdbc.queryForList("SELECT permission_code FROM role_permissions"
                + " WHERE role_code='APPROVER' AND permission_code LIKE 'OFFERS_%'", String.class);
        assertThat(offerGrants).isNotEmpty();
        jdbc.update("DELETE FROM role_permissions WHERE role_code='APPROVER' AND permission_code LIKE 'OFFERS_%'");
        try {
            publish(NotificationType.APPROVAL_PENDING, NotificationLink.Type.OFFER, offer);
            drain();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
        } finally {
            for (String code : offerGrants) {
                jdbc.update("INSERT INTO role_permissions(role_code, permission_code) VALUES ('APPROVER', ?)", code);
            }
        }
    }

    @Test
    void offerAndPostingApprovalsNotifyTheApproverOfTheCurrentStepOnly() throws Exception {
        UUID approver = applications.account("Approver", "approver@example.test", Role.APPROVER);
        UUID offer = pendingOffer(approver);
        publish(NotificationType.APPROVAL_PENDING, NotificationLink.Type.OFFER, offer);
        publish(NotificationType.APPROVAL_PENDING, NotificationLink.Type.OFFER, offer);
        drain();
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notifications", UUID.class)).containsExactly(hr);
        jdbc.update("UPDATE offers SET status='PENDING_HIGHER' WHERE id=?", offer);
        publish(NotificationType.APPROVAL_PENDING, NotificationLink.Type.OFFER, offer);
        drain();
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notifications ORDER BY created_at", UUID.class))
                .containsExactlyInAnyOrder(hr, approver);
        // Once approved, an event about the offer plans nothing more.
        jdbc.update("UPDATE offers SET status='APPROVED', approved_at=? WHERE id=?", Timestamp.from(NOW), offer);
        publish(NotificationType.APPROVAL_PENDING, NotificationLink.Type.OFFER, offer);
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT DISTINCT status FROM notification_events", String.class)).containsExactly("DONE");

        // A job posting waiting for review: HR (JOB_POSTINGS_WRITE_ALL) unless HR wrote or sent it.
        UUID posting = jdbc.queryForObject("SELECT job_posting_id FROM job_applications WHERE id=?", UUID.class,
                first.applicationId());
        jdbc.update("UPDATE job_postings SET status='PENDING_APPROVAL', published_by=NULL, published_at=NULL WHERE id=?",
                posting);
        jdbc.update("INSERT INTO job_posting_authors(job_posting_id, account_id) VALUES (?, ?)", posting, hr);
        publish(NotificationType.APPROVAL_PENDING, NotificationLink.Type.JOB_POSTING, posting);
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE link_type='JOB_POSTING'", Long.class)).isZero();
        jdbc.update("DELETE FROM job_posting_authors WHERE job_posting_id=? AND account_id=?", posting, hr);
        publish(NotificationType.APPROVAL_PENDING, NotificationLink.Type.JOB_POSTING, posting);
        drain();
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notifications WHERE link_type='JOB_POSTING'", UUID.class))
                .contains(hr).doesNotContain(recruiter);
    }

    @Test
    void aFailingEventIsRetriedAndThenNotifiesOnce() {
        UUID offer = pendingOffer(applications.account("Approver", "approver2@example.test", Role.APPROVER));
        jdbc.execute("ALTER TABLE notifications ADD CONSTRAINT test_no_insert CHECK (false) NOT VALID");
        try {
            publish(NotificationType.APPROVAL_PENDING, NotificationLink.Type.OFFER, offer);
            drain();
            assertThat(jdbc.queryForMap("SELECT status, attempts, last_failure FROM notification_events"))
                    .containsEntry("status", "RETRY_WAIT").containsEntry("attempts", 1)
                    .containsEntry("last_failure", "PROCESSING_FAILED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE notifications DROP CONSTRAINT test_no_insert");
        }
        jdbc.update("UPDATE notification_events SET next_attempt_at = clock_timestamp() - INTERVAL '1 second'");
        drain();
        assertThat(jdbc.queryForList("SELECT recipient_id FROM notifications", UUID.class)).containsExactly(hr);
        assertThat(jdbc.queryForObject("SELECT status FROM notification_events", String.class)).isEqualTo("DONE");
    }

    // An offer waiting for HR (the HR approver of the fixture) with a named higher approver.
    private UUID pendingOffer(UUID approver) {
        jdbc.update("UPDATE job_applications SET stage='OFFER' WHERE id=?", first.applicationId());
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO offers(id,application_id,candidate_id,position_id,candidate_name,email,position_title,
                    salary,allowance,start_date,response_deadline,band_min,band_max,outside_band,status,version,
                    approval_round,created_by,submitted_by,hr_approver_id,higher_approver_id,created_at,updated_at)
                SELECT ?,a.id,ca.candidate_id,r.position_id,a.full_name,a.email,p.name,
                    20000000,2000000,?,?,10000000,30000000,false,'PENDING_HR',0,1,?,?,?,?,?,?
                FROM job_applications a JOIN candidate_applications ca ON ca.application_id=a.id
                JOIN recruitment_requisitions r ON r.id=a.requisition_id JOIN positions p ON p.id=r.position_id WHERE a.id=?
                """, id, java.time.LocalDate.ofInstant(NOW, java.time.ZoneOffset.UTC).plusDays(10), Timestamp.from(DEADLINE),
                recruiter, recruiter, hr, approver, Timestamp.from(NOW), Timestamp.from(NOW), first.applicationId());
        return id;
    }

    private void publish(NotificationType type, NotificationLink.Type entity, UUID id) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> events.publish(type, entity, id));
    }

    private void drain() {
        for (int i = 0; i < 20 && worker.processNext(); i++) { /* drain */ }
    }
}
