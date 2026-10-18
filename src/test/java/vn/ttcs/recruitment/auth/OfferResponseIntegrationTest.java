package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vn.ttcs.recruitment.auth.passwordreset.ResetTokenGenerator;

import java.sql.Connection;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP and disposable PostgreSQL only: capability privacy, transactional stage changes and expiry races. */
class OfferResponseIntegrationTest extends OfferDeliveryTestSupport {
    @Test void queueUsesOnlyApprovedTermsAndLookupShowsNoInternalApprovalDataOrCredentials() throws Exception {
        var sent=queued();
        assertThat(sent.payload().email()).isEqualTo("lan@example.test");
        assertThat(sent.payload().salary()).isEqualTo(20000000);
        assertThat(sent.payload().replyUrl()).contains("#token=").doesNotContain("?token=");
        assertThat(sent.token()).matches("[A-Za-z0-9_-]{43}");
        assertThat(jdbc.queryForObject("SELECT token_hash FROM offer_response_tokens WHERE offer_id=?",String.class,sent.id()))
                .isEqualTo(new ResetTokenGenerator().hash(sent.token())).isNotEqualTo(sent.token());
        var detail=ok(lookup(sent.token()));
        assertThat(detail.propertyNames()).containsExactlyInAnyOrder("positionTitle","salary","allowance","startDate","responseDeadline","status","respondedAt");
        assertThat(detail.toString()).doesNotContain(sent.token(),"lan@example.test","bandMin","explanation","candidateId","applicationId","approver");
        assertThat(state(sent.id())).isEqualTo("SENT"); assertThat(stage()).isEqualTo("OFFER");
        assertThat(delivery(sent.id())).isEqualTo("PENDING");
        assertThat(count("application_stage_events")).isZero();
        error(send(sent.id(),0,hrToken),409,"OFFER_VERSION_CONFLICT");
        assertThat(count("offer_delivery_outbox")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"ACCEPT","DECLINE"})
    void responseMovesPipelineAtomicallyAndExactRetryDoesNotCreateAnotherEvent(String action) throws Exception {
        var sent=queued(); String expected="ACCEPT".equals(action)?"ACCEPTED":"DECLINED";
        String note="Ý kiến của ứng viên. "+"a".repeat(700);
        var result=ok(respond(sent.token(),action,note));
        assertThat(result.path("status").asText()).isEqualTo(expected);
        assertThat(ok(respond(sent.token(),action,note))).isEqualTo(result);
        assertThat(state(sent.id())).isEqualTo(expected);
        assertThat(stage()).isEqualTo("ACCEPT".equals(action)?"HIRED":"REJECTED");
        assertThat(jdbc.queryForObject("SELECT response_note FROM offers WHERE id=?",String.class,sent.id())).isEqualTo(note);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events WHERE offer_id=? AND actor_type='CANDIDATE' AND actor_id IS NULL",Long.class,sent.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM offer_events WHERE offer_id=? AND event_type=?",Long.class,sent.id(),expected)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM offers WHERE id=?",Long.class,sent.id())).isEqualTo(2);
        assertThat(ok(lookup(sent.token())).path("status").asText()).isEqualTo(expected);
        error(respond(sent.token(),action,note+" changed"),409,"OFFER_ALREADY_RESPONDED");
        error(respond(sent.token(),"ACCEPT".equals(action)?"DECLINE":"ACCEPT",note),409,"OFFER_ALREADY_RESPONDED");
    }

    @Test void invalidUnknownTokensAndInvalidJsonDoNotChangeOfferOrPipeline() throws Exception {
        var sent=queued();
        for(String token:List.of("short","!".repeat(43),new ResetTokenGenerator().create())) {
            error(lookup(token),400,"OFFER_LINK_INVALID"); error(respond(token,"ACCEPT",null),400,"OFFER_LINK_INVALID");
        }
        error(respond(sent.token(),"HIRE",null),400,"OFFER_RESPONSE_VALIDATION");
        error(respond(sent.token(),"ACCEPT","a".repeat(2001)),400,"OFFER_RESPONSE_VALIDATION");
        error(respond(sent.token(),"ACCEPT","bad\u0001note"),400,"OFFER_RESPONSE_VALIDATION");
        error(call("POST",PUBLIC+"/respond",json.writeValueAsString(Map.of("token",sent.token(),"action","ACCEPT","applicationId",other.applicationId())),null),400,"INVALID_JSON");
        assertThat(stage()).isEqualTo("OFFER"); assertThat(count("application_stage_events")).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"DRAFT","PENDING_HR","PENDING_HIGHER","CHANGES_REQUESTED","REJECTED"})
    void onlyApprovedOffersMayBeQueued(String status) throws Exception {
        UUID id=approved(); jdbc.update("UPDATE offers SET status=?,approved_at=NULL WHERE id=?",status,id);
        error(send(id,0,hrToken),409,"OFFER_NOT_APPROVED");
        assertThat(count("offer_response_tokens")).isZero(); assertThat(count("offer_delivery_outbox")).isZero();
    }

    @Test void unassignedRecruiterAndRevokedSessionCannotQueueAndCurrentGrantRevocationIsImmediate() throws Exception {
        UUID id=approved();
        assertThat(send(id,0,token(outsider)).statusCode()).isEqualTo(403);
        jdbc.update("UPDATE auth_sessions SET revoked_at=? WHERE user_id=?",Timestamp.from(NOW),hr);
        assertThat(send(id,0,hrToken).statusCode()).isEqualTo(401);
        int removed=jdbc.update("DELETE FROM role_permissions WHERE role_code='RECRUITER' AND permission_code='OFFERS_WRITE_SCOPED'");
        try { assertThat(send(id,0,recruiterToken).statusCode()).isEqualTo(403); }
        finally { if(removed!=0) jdbc.update("INSERT INTO role_permissions VALUES ('RECRUITER','OFFERS_WRITE_SCOPED')"); }
        assertThat(count("offer_delivery_outbox")).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"WITHDRAWN","REJECTED","HIRED","SCREENING"})
    void changedApplicationStageRevokesOldLinkAndCancelsQueuedMail(String stage) throws Exception {
        var sent=queued(); jdbc.update("UPDATE job_applications SET stage=? WHERE id=?",stage,first.applicationId());
        error(lookup(sent.token()),400,"OFFER_LINK_INVALID"); error(respond(sent.token(),"ACCEPT",null),400,"OFFER_LINK_INVALID");
        assertThat(service.expireInactive()).isEqualTo(1); assertThat(state(sent.id())).isEqualTo("CANCELLED");
        assertThat(delivery(sent.id())).isEqualTo("CANCELLED"); assertThat(count("application_stage_events")).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"PAUSED","FILLED","CANCELLED"})
    void closedRequisitionRevokesOldLink(String status) throws Exception {
        var sent=queued(); jdbc.update("UPDATE recruitment_requisitions SET status=? WHERE id=?",status,requisition);
        error(lookup(sent.token()),400,"OFFER_LINK_INVALID"); error(respond(sent.token(),"DECLINE",null),400,"OFFER_LINK_INVALID");
        assertThat(service.expireInactive()).isEqualTo(1); assertThat(state(sent.id())).isEqualTo("CANCELLED");
        assertThat(stage()).isEqualTo("OFFER");
    }

    @Test void changedRecipientRevokesLinkWithoutSendingToEitherMailbox() throws Exception {
        var sent=queued(); jdbc.update("UPDATE job_applications SET email='different@example.test' WHERE id=?",first.applicationId());
        error(lookup(sent.token()),400,"OFFER_LINK_INVALID");
        error(respond(sent.token(),"ACCEPT",null),400,"OFFER_LINK_INVALID");
        assertThat(service.expireInactive()).isEqualTo(1); assertThat(state(sent.id())).isEqualTo("CANCELLED");
    }

    @Test void exactDeadlineRejectsReplyAndAutomaticExpiryKeepsPipelineOffer() throws Exception {
        var sent=queued(); clock.set(DEADLINE);
        error(lookup(sent.token()),400,"OFFER_LINK_INVALID"); error(respond(sent.token(),"ACCEPT",null),400,"OFFER_LINK_INVALID");
        assertThat(service.expireInactive()).isEqualTo(1); assertThat(service.expireInactive()).isZero();
        assertThat(state(sent.id())).isEqualTo("EXPIRED"); assertThat(stage()).isEqualTo("OFFER");
        assertThat(count("application_stage_events")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM offer_events WHERE event_type='EXPIRED'",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ciphertext IS NULL FROM offer_delivery_outbox WHERE offer_id=?",Boolean.class,sent.id())).isTrue();
    }

    @Test void acceptedOfferRemainsAcceptedAndOccupiesCandidateSlotAfterItsDeadline() throws Exception {
        var sent=queued(); ok(respond(sent.token(),"ACCEPT",null)); clock.set(DEADLINE.plusSeconds(1));
        assertThat(service.expireInactive()).isZero(); assertThat(state(sent.id())).isEqualTo("ACCEPTED");
        assertThat(stage()).isEqualTo("HIRED"); error(lookup(sent.token()),400,"OFFER_LINK_INVALID");
    }

    @Test void stageEventFailureRollsBackOfferResponsePipelineAndAuditTogether() throws Exception {
        var sent=queued(); jdbc.execute("ALTER TABLE application_stage_events ADD CONSTRAINT test_reject_offer_response CHECK (offer_id IS NULL)");
        try {
            assertThat(respond(sent.token(),"ACCEPT",null).statusCode()).isEqualTo(500);
            assertThat(state(sent.id())).isEqualTo("SENT"); assertThat(stage()).isEqualTo("OFFER");
            assertThat(jdbc.queryForObject("SELECT responded_at IS NULL FROM offers WHERE id=?",Boolean.class,sent.id())).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM offer_events WHERE event_type='ACCEPTED'",Long.class)).isZero();
        } finally { jdbc.execute("ALTER TABLE application_stage_events DROP CONSTRAINT test_reject_offer_response"); }
    }

    @Test void oppositeConcurrentAnswersProduceExactlyOneStageTransition() throws Exception {
        var sent=queued(); var barrier=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var accept=executor.submit(() -> { barrier.await(); return respond(sent.token(),"ACCEPT",null); });
            var decline=executor.submit(() -> { barrier.await(); return respond(sent.token(),"DECLINE",null); });
            assertThat(List.of(accept.get(15,TimeUnit.SECONDS).statusCode(),decline.get(15,TimeUnit.SECONDS).statusCode()))
                    .containsExactlyInAnyOrder(200,409);
        }
        assertThat(count("application_stage_events")).isEqualTo(1);
        assertThat(stage()).isIn("HIRED","REJECTED");
    }

    @Test void deadlineIsRecheckedAfterWaitingForParentLock() throws Exception {
        var sent=queued();
        try(Connection lock=dataSource.getConnection(); var executor=Executors.newSingleThreadExecutor()) {
            lock.setAutoCommit(false);
            try(var statement=lock.prepareStatement("SELECT id FROM recruitment_requisitions WHERE id=? FOR NO KEY UPDATE")) {
                statement.setObject(1,requisition); statement.executeQuery().close();
            }
            var request=executor.submit(() -> respond(sent.token(),"ACCEPT",null));
            // A bounded database observation ensures the HTTP transaction reached its lock, not a blind sleep.
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            while(System.nanoTime()<end && jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Long.class)==0) Thread.onSpinWait();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'",Long.class))
                    .as("HTTP response transaction must be waiting for the held parent lock").isGreaterThan(0);
            clock.set(DEADLINE); lock.commit();
            error(request.get(10,TimeUnit.SECONDS),400,"OFFER_LINK_INVALID");
        }
        assertThat(stage()).isEqualTo("OFFER"); assertThat(count("application_stage_events")).isZero();
    }

    @Test void replyPageUsesFragmentCapabilityAndExactPublicRoutesIgnoreUnrelatedStaleBearer() throws Exception {
        var sent=queued();
        var page=call("GET",PUBLIC+"/reply",null,null); assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
        assertThat(page.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(v -> assertThat(v).contains("frame-ancestors 'none'"));
        assertThat(call("GET",PUBLIC+"/reply.js",null,null).statusCode()).isEqualTo(200);
        ok(call("POST",PUBLIC+"/lookup",json.writeValueAsString(Map.of("token",sent.token())),"invalid.old.token"));
        assertThat(call("POST",PUBLIC+"/lookup/extra","{}","invalid.old.token").statusCode()).isEqualTo(401);
        assertThat(call("GET",PUBLIC+"/lookup",null,null).statusCode()).isEqualTo(401);
    }
}
