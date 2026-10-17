package vn.ttcs.recruitment.auth;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import vn.ttcs.recruitment.candidateduplicate.DuplicateDetectionWorker;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.config.import=", "app.bootstrap.enabled=false", "logging.level.root=WARN", "debug=false",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "app.candidate-duplicates.enabled=true",
        "app.candidate-duplicates.poll-delay-ms=3600000", "app.candidate-merges.enabled=true",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.poll-delay-ms=3600000"})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CandidateMergeUndoIntegrationTest extends CvApplicationTestSupport {
    @Autowired DuplicateDetectionWorker detector;

    @Test void undoRestoresPartitionAndRepresentativesButKeepsNewApplicationData() throws Exception {
        UUID second=application("second@example.test");detector.processBatch();UUID operation=merge(app,second);
        jdbc.update("UPDATE job_applications SET full_name='Changed after merge' WHERE id=?",app);
        var source=jdbc.queryForList("SELECT row_to_json(a)::text FROM job_applications a ORDER BY id",String.class);
        UUID key=UUID.randomUUID();var response=undo(operation,key,recruiterToken);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(undo(operation,key,recruiterToken).body()).isEqualTo(response.body());
        assertThat(jdbc.queryForList("SELECT candidate_id FROM candidate_memberships ORDER BY candidate_id",UUID.class)).containsExactlyInAnyOrder(app,second);
        assertThat(jdbc.queryForList("SELECT revision FROM candidate_profiles",Long.class)).containsOnly(3L);
        assertThat(jdbc.queryForList("SELECT representative_application_id FROM candidate_profiles",UUID.class)).containsExactlyInAnyOrder(app,second);
        assertThat(jdbc.queryForList("SELECT row_to_json(a)::text FROM job_applications a ORDER BY id",String.class)).isEqualTo(source);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_merge_undo_events",Integer.class)).isEqualTo(1);
        assertThat(undo(operation,UUID.randomUUID(),recruiterToken).statusCode()).isEqualTo(409);
    }

    @Test void chainedMergesMustUndoNewestFirstAndRevisionsNeverGoBack() throws Exception {
        UUID second=application("second@example.test"),third=application("third@example.test");detector.processBatch();
        UUID first=merge(app,second),last=merge(app,third);
        assertThat(undo(first,UUID.randomUUID(),recruiterToken).statusCode()).isEqualTo(409);
        assertThat(undo(last,UUID.randomUUID(),recruiterToken).statusCode()).isEqualTo(200);
        assertThat(undo(first,UUID.randomUUID(),recruiterToken).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForList("SELECT candidate_id FROM candidate_memberships",UUID.class)).containsExactlyInAnyOrder(app,second,third);
        assertThat(jdbc.queryForObject("SELECT revision FROM candidate_profiles WHERE id=?",Long.class,app)).isEqualTo(5L);
    }

    @Test void expirationUsesDatabaseTimeAndRollsBackWithoutAnUndoEvent() throws Exception {
        UUID second=application("second@example.test");detector.processBatch();
        // Test fixture: create a genuinely old immutable operation using its DB stamping trigger.
        String original=jdbc.queryForObject("SELECT pg_get_functiondef('stamp_candidate_merge_operation()'::regprocedure)",String.class);
        jdbc.execute(original.replace("NEW.recorded_at := clock_timestamp();","NEW.recorded_at := clock_timestamp()-INTERVAL '25 hours';"));
        UUID operation;
        try { operation=merge(app,second); } finally { jdbc.execute(original); }
        var before=jdbc.queryForList("SELECT * FROM candidate_profiles ORDER BY id");
        var response=undo(operation,UUID.randomUUID(),recruiterToken);
        assertThat(response.statusCode()).isEqualTo(409);assertThat(response.body()).contains("CANDIDATE_UNDO_EXPIRED");
        assertThat(jdbc.queryForList("SELECT * FROM candidate_profiles ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_merge_undo_events",Integer.class)).isZero();
    }

    @Test void lostScopeDeniesUndoAndReplay() throws Exception {
        UUID second=application("second@example.test");detector.processBatch();UUID operation=merge(app,second),key=UUID.randomUUID();
        assertThat(undo(operation,key,otherToken).statusCode()).isEqualTo(403);
        assertThat(undo(operation,key,recruiterToken).statusCode()).isEqualTo(200);
        jdbc.update("DELETE FROM requisition_recruiters WHERE requisition_id=?",requisition);
        assertThat(undo(operation,key,recruiterToken).statusCode()).isEqualTo(403);
    }

    @Test void deletedMemberCannotBeResurrected() throws Exception {
        UUID second=application("second@example.test");detector.processBatch();UUID operation=merge(app,second);
        jdbc.update("DELETE FROM job_applications WHERE id=?",second);
        assertThat(undo(operation,UUID.randomUUID(),recruiterToken).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_applications WHERE id=?",Integer.class,second)).isZero();
    }

    @Test void auditFailureRollsBackRestoration() throws Exception {
        UUID second=application("second@example.test");detector.processBatch();UUID operation=merge(app,second);
        var before=jdbc.queryForList("SELECT * FROM candidate_profiles ORDER BY id");
        jdbc.execute("CREATE FUNCTION reject_undo() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced undo failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_undo BEFORE INSERT ON candidate_merge_undo_events FOR EACH ROW EXECUTE FUNCTION reject_undo()");
        assertThat(undo(operation,UUID.randomUUID(),recruiterToken).statusCode()).isEqualTo(503);
        assertThat(jdbc.queryForList("SELECT * FROM candidate_profiles ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT DISTINCT candidate_id FROM candidate_memberships",UUID.class)).containsOnly(app);
    }

    @Test void concurrentRequestsWithSameKeyProduceOneUndo() throws Exception {
        UUID second=application("second@example.test");detector.processBatch();UUID operation=merge(app,second),key=UUID.randomUUID();
        try (var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            Callable<String> job=()->{start.await();var response=undo(operation,key,recruiterToken);assertThat(response.statusCode()).isEqualTo(200);return response.body();};
            var left=pool.submit(job);var right=pool.submit(job);start.countDown();
            assertThat(left.get(10,TimeUnit.SECONDS)).isEqualTo(right.get(10,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_merge_undo_events",Integer.class)).isEqualTo(1);
    }

    @Test void databaseDeadlineRuleIsStrictAtExactBoundary() {
        var deadline=java.sql.Timestamp.from(java.time.Instant.parse("2026-10-17T12:00:00Z"));
        assertThat(jdbc.queryForObject("SELECT candidate_merge_undo_open(?::timestamptz,?::timestamptz)",Boolean.class,deadline,deadline)).isFalse();
        assertThat(jdbc.queryForObject("SELECT candidate_merge_undo_open(?::timestamptz,?::timestamptz-INTERVAL '1 microsecond')",Boolean.class,deadline,deadline)).isTrue();
        assertThat(jdbc.queryForObject("SELECT candidate_merge_undo_open(?::timestamptz,?::timestamptz+INTERVAL '1 microsecond')",Boolean.class,deadline,deadline)).isFalse();
    }

    @Test void crossingDeadlineDuringRestorationRollsEverythingBack() throws Exception {
        UUID second=application("second@example.test");detector.processBatch();
        String stamp=jdbc.queryForObject("SELECT pg_get_functiondef('stamp_candidate_merge_operation()'::regprocedure)",String.class);
        jdbc.execute(stamp.replace("NEW.recorded_at := clock_timestamp();","NEW.recorded_at := clock_timestamp()-INTERVAL '24 hours'+INTERVAL '2 seconds';"));
        UUID operation;
        try { operation=merge(app,second); } finally { jdbc.execute(stamp); }
        var before=jdbc.queryForList("SELECT * FROM candidate_profiles ORDER BY id");
        var memberships=jdbc.queryForList("SELECT * FROM candidate_memberships ORDER BY application_id");
        // Sequence increments survive transaction rollback: prove that both restoration UPDATEs actually ran.
        jdbc.execute("CREATE SEQUENCE restore_attempts");
        jdbc.execute("CREATE FUNCTION delay_restore() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM nextval('restore_attempts'); PERFORM pg_sleep(1.1); RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER delay_restore BEFORE UPDATE ON candidate_profiles FOR EACH ROW EXECUTE FUNCTION delay_restore()");
        var response=undo(operation,UUID.randomUUID(),recruiterToken);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(409);
        assertThat(response.body()).contains("CANDIDATE_UNDO_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT is_called FROM restore_attempts",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT last_value FROM restore_attempts",Long.class)).isEqualTo(2L);
        assertThat(jdbc.queryForList("SELECT * FROM candidate_profiles ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM candidate_memberships ORDER BY application_id")).isEqualTo(memberships);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_merge_undo_events",Integer.class)).isZero();
    }

    @Test void undoKeyCannotTargetAnotherOperationAndOriginalRemainsReplayable() throws Exception {
        UUID second=application("second@example.test"),third=application("third@example.test"),fourth=application("fourth@example.test");
        while(detector.processBatch()>0) { }
        UUID first=merge(app,second),otherOperation=merge(third,fourth),key=UUID.randomUUID();
        var original=undo(first,key,recruiterToken);assertThat(original.statusCode()).isEqualTo(200);
        var reused=undo(otherOperation,key,recruiterToken);
        assertThat(reused.statusCode()).isEqualTo(409);assertThat(reused.body()).contains("IDEMPOTENCY_KEY_REUSED");
        assertThat(undo(first,key,recruiterToken).body()).isEqualTo(original.body());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_merge_undo_events",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT candidate_id FROM candidate_memberships WHERE application_id IN (?,?)",UUID.class,third,fourth)).containsOnly(third);
    }

    @Test void undoRequestKeysAreScopedToActor() throws Exception {
        UUID second=application("second@example.test"),third=application("third@example.test"),fourth=application("fourth@example.test");
        while(detector.processBatch()>0) { }
        jdbc.update("INSERT INTO requisition_recruiters(requisition_id,recruiter_id,assignment_role,assigned_by,assigned_at) VALUES (?,?,'SUPPORTING',?,?)",
                requisition,other,admin,java.sql.Timestamp.from(NOW));
        UUID first=merge(app,second),last=merge(third,fourth),key=UUID.randomUUID();
        var firstUndo=undo(first,key,recruiterToken);assertThat(firstUndo.statusCode()).isEqualTo(200);
        assertThat(undo(first,key,otherToken).statusCode()).isEqualTo(409);
        var secondUndo=undo(last,key,otherToken);assertThat(secondUndo.statusCode()).isEqualTo(200);
        assertThat(json.readTree(secondUndo.body()).path("mergeOperationId").asText()).isEqualTo(last.toString());
        assertThat(secondUndo.body()).isNotEqualTo(firstUndo.body());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_merge_undo_events WHERE request_key=?",Integer.class,key)).isEqualTo(2);
    }

    @Test void undoDoesNotMakeAnOldPreviewValidAgainAndMergeReplayDoesNotRemerge() throws Exception {
        UUID second=application("second@example.test");detector.processBatch();
        var preview=http("POST","/api/v1/candidate-merges/preview",Map.of("leftApplicationId",app,"rightApplicationId",second),recruiterToken);
        assertThat(preview.statusCode()).isEqualTo(200);
        var request=new java.util.HashMap<String,Object>(Map.of("requestKey",UUID.randomUUID(),"leftGroupId",app,"rightGroupId",second,
                "resultGroupId",app,"representativeApplicationId",app,"expectedFingerprint",json.readTree(preview.body()).path("fingerprint").asText()));
        var original=http("POST","/api/v1/candidate-merges",request,recruiterToken);assertThat(original.statusCode()).isEqualTo(200);
        UUID operation=UUID.fromString(json.readTree(original.body()).path("id").asText());
        assertThat(undo(operation,UUID.randomUUID(),recruiterToken).statusCode()).isEqualTo(200);
        var replay=http("POST","/api/v1/candidate-merges",request,recruiterToken);assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(json.readTree(replay.body()).path("undone").asBoolean()).isTrue();
        request.put("requestKey",UUID.randomUUID());
        var stale=http("POST","/api/v1/candidate-merges",request,recruiterToken);
        assertThat(stale.statusCode()).isEqualTo(409);assertThat(stale.body()).contains("CANDIDATE_MERGE_STALE");
        assertThat(merge(app,second)).isNotEqualTo(operation);
        assertThat(jdbc.queryForList("SELECT revision FROM candidate_profiles",Long.class)).containsOnly(4L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_merge_operations",Integer.class)).isEqualTo(2);
    }

    private UUID merge(UUID left,UUID right) throws Exception {
        var response=http("POST","/api/v1/candidate-merges/preview",Map.of("leftApplicationId",left,"rightApplicationId",right),recruiterToken);
        assertThat(response.statusCode()).isEqualTo(200);var preview=json.readTree(response.body());
        UUID leftGroup=jdbc.queryForObject("SELECT candidate_id FROM candidate_memberships WHERE application_id=?",UUID.class,left);
        UUID rightGroup=jdbc.queryForObject("SELECT candidate_id FROM candidate_memberships WHERE application_id=?",UUID.class,right);
        response=http("POST","/api/v1/candidate-merges",Map.of("requestKey",UUID.randomUUID(),"leftGroupId",leftGroup,
                "rightGroupId",rightGroup,"resultGroupId",leftGroup,"representativeApplicationId",left,"expectedFingerprint",preview.path("fingerprint").asText()),recruiterToken);
        assertThat(response.statusCode()).isEqualTo(200);return UUID.fromString(json.readTree(response.body()).path("id").asText());
    }
    private java.net.http.HttpResponse<String> undo(UUID operation,UUID key,String token) throws Exception {
        return http("POST","/api/v1/candidate-merges/"+operation+"/undo",Map.of("requestKey",key),token);
    }
    private java.net.http.HttpResponse<String> http(String method,String path,Object payload,String token) throws Exception {
        return api.request(method,path,payload==null?null:json.writeValueAsString(payload),token);
    }
}
