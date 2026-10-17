package vn.ttcs.recruitment.auth;

import java.util.Map;
import java.util.UUID;
import java.time.Duration;
import java.sql.Timestamp;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
class CandidateMergeSecurityIntegrationTest extends CvApplicationTestSupport {
    @Autowired DuplicateDetectionWorker detector;

    @ParameterizedTest @ValueSource(strings={"assignment","grant","expiry","contact"})
    void changesCommittedWhileWaitingAreRechecked(String change) throws Exception {
        UUID second=pair();var request=request(second);var before=jdbc.queryForList("SELECT * FROM candidate_profiles ORDER BY id");
        try (var blocker=dataSource.getConnection();var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            try(var lock=blocker.prepareStatement("SELECT id FROM recruitment_requisitions WHERE id=? FOR UPDATE")) {
                lock.setObject(1,requisition);lock.executeQuery().close();
            }
            var pending=pool.submit(()->http("POST","/api/v1/candidate-merges",request,recruiterToken));
            awaitLock("SELECT r.id FROM recruitment_requisitions%");
            if (change.equals("assignment")) {
                try(var statement=blocker.prepareStatement("DELETE FROM requisition_recruiters WHERE requisition_id=?")) {
                    statement.setObject(1,requisition);statement.executeUpdate();
                }
            } else if (change.equals("grant")) {
                try(var statement=blocker.createStatement()) {
                    statement.executeUpdate("DELETE FROM role_permissions WHERE role_code='RECRUITER' AND permission_code='CANDIDATES_WRITE_SCOPED'");
                }
            } else if (change.equals("expiry")) {
                clock.set(NOW.plus(Duration.ofHours(1)));
            } else {
                try(var statement=blocker.prepareStatement("""
                        INSERT INTO application_confirmed_contacts(application_id,revision,full_name,email,normalized_email,phone,
                            cv_sha256,name_source,email_source,phone_source,confirmed_by,confirmed_by_name,confirmed_at)
                        SELECT a.id,1,'Changed','changed@example.test','changed@example.test',a.phone,cv.sha256,
                            'MANUAL','MANUAL','MANUAL',?,'Recruiter',clock_timestamp()
                        FROM job_applications a JOIN application_cvs cv ON cv.application_id=a.id WHERE a.id=?
                        """)) { statement.setObject(1,recruiter);statement.setObject(2,app);statement.executeUpdate(); }
            }
            blocker.commit();var response=pending.get(10,TimeUnit.SECONDS);
            assertThat(response.statusCode()).as(change+": "+response.body()).isEqualTo(change.equals("expiry")?401:change.equals("contact")?409:403);
        }
        assertThat(jdbc.queryForList("SELECT * FROM candidate_profiles ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_merge_operations",Integer.class)).isZero();
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void readAndWriteScopesAreIndependent(boolean readAll) throws Exception {
        UUID second=pair();var request=request(second);
        jdbc.update("DELETE FROM role_permissions WHERE role_code='RECRUITER' AND permission_code LIKE 'CANDIDATES_%'");
        jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('RECRUITER',?),('RECRUITER',?)",
                readAll?"CANDIDATES_READ_ALL":"CANDIDATES_READ_SCOPED",readAll?"CANDIDATES_WRITE_SCOPED":"CANDIDATES_WRITE_ALL");
        jdbc.update("DELETE FROM requisition_recruiters WHERE requisition_id=?",requisition);
        assertThat(preview(second,recruiterToken).statusCode()).isEqualTo(readAll?200:403);
        assertThat(http("POST","/api/v1/candidate-merges",request,recruiterToken).statusCode()).isEqualTo(403);
    }

    @Test void managerCanPreviewDescendantDepartmentWithoutRecruiterAssignment() throws Exception {
        UUID second=pair();jdbc.update("UPDATE user_roles SET role='HIRING_MANAGER' WHERE user_id=?",recruiter);
        UUID parent=api.department("Parent",recruiter,null,NOW);
        jdbc.update("UPDATE departments SET parent_id=? WHERE id=(SELECT department_id FROM recruitment_requisitions WHERE id=?)",parent,requisition);
        jdbc.update("DELETE FROM requisition_recruiters WHERE requisition_id=?",requisition);
        assertThat(preview(second,recruiterToken).statusCode()).isEqualTo(200);
        assertThat(preview(second,otherToken).statusCode()).isEqualTo(403);
    }

    @Test void distinctActorsWithOppositeOrderingCannotBothMergeSamePreview() throws Exception {
        UUID second=pair();assignOther();var first=request(second);var opposite=new java.util.HashMap<>(first);
        opposite.put("leftGroupId",second);opposite.put("rightGroupId",app);opposite.put("requestKey",UUID.randomUUID());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return http("POST","/api/v1/candidate-merges",first,recruiterToken).statusCode();});
            var b=pool.submit(()->{start.await();return http("POST","/api/v1/candidate-merges",opposite,otherToken).statusCode();});
            start.countDown();assertThat(java.util.List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_merge_operations",Integer.class)).isEqualTo(1);
    }

    @Test void revokedSessionCannotReadOrReplayOperation() throws Exception {
        UUID second=pair();var request=request(second);
        var merged=http("POST","/api/v1/candidate-merges",request,recruiterToken);assertThat(merged.statusCode()).isEqualTo(200);
        jdbc.update("UPDATE auth_sessions SET revoked_at=clock_timestamp() WHERE user_id=?",recruiter);
        assertThat(http("POST","/api/v1/candidate-merges",request,recruiterToken).statusCode()).isEqualTo(401);
        assertThat(http("GET","/api/v1/candidate-merges/"+json.readTree(merged.body()).path("id").asText(),null,recruiterToken).statusCode()).isEqualTo(401);
    }

    @Test void mergeRacingUndoHasOnlyOneWinnerAndKeepsAllApplications() throws Exception {
        UUID second=pair(),third=application("third@example.test");detector.processBatch();assignOther();
        var merged=http("POST","/api/v1/candidate-merges",request(second),recruiterToken);
        assertThat(merged.statusCode()).isEqualTo(200);String id=json.readTree(merged.body()).path("id").asText();
        var next=request(third);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            var merge=pool.submit(()->{start.await();return http("POST","/api/v1/candidate-merges",next,otherToken).statusCode();});
            var undo=pool.submit(()->{start.await();return http("POST","/api/v1/candidate-merges/"+id+"/undo",Map.of("requestKey",UUID.randomUUID()),recruiterToken).statusCode();});
            start.countDown();assertThat(java.util.List.of(merge.get(10,TimeUnit.SECONDS),undo.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_applications",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_memberships",Integer.class)).isEqualTo(3);
    }

    private void assignOther() {
        jdbc.update("INSERT INTO requisition_recruiters(requisition_id,recruiter_id,assignment_role,assigned_by,assigned_at) VALUES (?,?,'SUPPORTING',?,?)",
                requisition,other,admin,Timestamp.from(NOW));
    }
    private UUID pair() { UUID second=application("second@example.test");while(detector.processBatch()>0) { } return second; }
    private Map<String,Object> request(UUID second) throws Exception {
        var preview=preview(second,recruiterToken);assertThat(preview.statusCode()).isEqualTo(200);
        return Map.of("requestKey",UUID.randomUUID(),"leftGroupId",app,"rightGroupId",second,"resultGroupId",app,
                "representativeApplicationId",app,"expectedFingerprint",json.readTree(preview.body()).path("fingerprint").asText());
    }
    private java.net.http.HttpResponse<String> preview(UUID second,String token) throws Exception {
        return http("POST","/api/v1/candidate-merges/preview",Map.of("leftApplicationId",app,"rightApplicationId",second),token);
    }
    private java.net.http.HttpResponse<String> http(String method,String path,Object payload,String token) throws Exception {
        return api.request(method,path,payload==null?null:json.writeValueAsString(payload),token);
    }
    private void awaitLock(String pattern) throws Exception {
        long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(System.nanoTime()<limit) {
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE ?)",Boolean.class,pattern))) return;
            Thread.sleep(10);
        }
        throw new AssertionError("Expected database lock wait was not observed");
    }
}
