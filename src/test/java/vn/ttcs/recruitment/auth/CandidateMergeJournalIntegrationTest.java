package vn.ttcs.recruitment.auth;

import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import vn.ttcs.recruitment.candidateduplicate.DuplicateDetectionWorker;
import vn.ttcs.recruitment.candidatemerge.*;
import vn.ttcs.recruitment.application.ApplicationSubmission;
import vn.ttcs.recruitment.application.PostgresApplicationReceiptWriter;
import static org.assertj.core.api.Assertions.*;
import static vn.ttcs.recruitment.candidatemerge.MergeAuditException.Reason.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.config.import=", "app.bootstrap.enabled=false", "logging.level.root=WARN", "debug=false",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "app.candidate-duplicates.enabled=true",
        "app.candidate-duplicates.poll-delay-ms=3600000", "app.cv-contact-confirmation.enabled=true",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.poll-delay-ms=3600000"})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
// Each test owns a fresh schema/context; immutable audit is never disabled or erased for fixture cleanup.
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CandidateMergeJournalIntegrationTest extends CvApplicationTestSupport {
    @Autowired CandidateMergeJournal journal;
    @Autowired DuplicateDetectionWorker detector;
    @Autowired PostgresApplicationReceiptWriter receipts;

    @Test void persistenceRequiresTheCallersReadWriteTransaction() {
        UUID second=pair(); var command=command(app,second,app,app);
        assertThatThrownBy(()->journal.append(command)).isInstanceOf(IllegalTransactionStateException.class);
        var readOnly=new TransactionTemplate(manager); readOnly.setReadOnly(true);
        assertThatThrownBy(()->readOnly.execute(s->journal.append(command))).isInstanceOf(MergeAuditException.class)
                .extracting(e->((MergeAuditException)e).reason()).isEqualTo(READ_ONLY_TRANSACTION);
        assertThat(groups(app,second)).hasSize(2);
    }

    @Test void repeatableReadWritersAreRejectedBeforeAnyGroupChange() {
        UUID second=pair(); var command=command(app,second,app,app); var original=profileRows();
        var repeatable=new TransactionTemplate(manager);
        repeatable.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertReason(()->repeatable.execute(s->journal.append(command)),UNSUPPORTED_ISOLATION);
        assertThat(profileRows()).isEqualTo(original);
    }

    @Test void detectorCreatesRepresentativeForNewSingletons() {
        UUID second=pair();
        assertThat(jdbc.queryForObject("SELECT representative_application_id FROM candidate_profiles WHERE id=?",UUID.class,app)).isEqualTo(app);
        assertThat(jdbc.queryForObject("SELECT representative_application_id FROM candidate_profiles WHERE id=?",UUID.class,second)).isEqualTo(second);
    }

    @Test void storesWholePartitionAndDatabaseTimeWithoutRewritingSource() throws Exception {
        UUID second=pair(); confirm(app);
        receipts.accept(posting,new ApplicationSubmission("Receipt","receipt@example.test","0912345678",null,null),
                validator.validate("cv.pdf",new java.io.ByteArrayInputStream(pdf)));
        var protectedBefore=protectedRows(); var request=command(app,second,app,second);
        var before=sourceRows(); Instant lower=dbTime();
        var operation=write(request); Instant upper=dbTime();
        assertThat(operation.before().fingerprint()).isEqualTo(request.expectedFingerprint());
        assertThat(operation.before().members()).extracting(CandidateMergeSnapshot.Member::applicationId).containsExactlyInAnyOrder(app,second);
        assertThat(operation.recordedAt()).isBetween(lower,upper);
        assertThat(Duration.between(operation.recordedAt(),operation.undoDeadline())).isEqualTo(Duration.ofHours(24));
        assertThat(operation.recordedAt()).isAfter(NOW); // Mutable application/test clock was deliberately not used.
        assertThat(groups(app,second)).containsOnly(app);
        assertThat(jdbc.queryForObject("SELECT representative_application_id FROM candidate_profiles WHERE id=?",UUID.class,app)).isEqualTo(second);
        assertThat(jdbc.queryForObject("SELECT representative_application_id FROM candidate_profiles WHERE id=?",UUID.class,second)).isNull();
        assertThat(jdbc.queryForList("SELECT revision FROM candidate_profiles WHERE id IN (?,?)",Long.class,app,second)).containsOnly(2L);
        assertThat(jdbc.queryForList("SELECT merge_head_id FROM candidate_profiles WHERE id IN (?,?)",UUID.class,app,second)).containsOnly(operation.id());
        assertThat(find(operation.id())).contains(operation);
        assertThat(sourceRows()).isEqualTo(before);
        assertThat(protectedRows()).isEqualTo(protectedBefore);
        assertThat(operation.toString()).doesNotContain("recruiter", "example.test");
    }

    @Test void sameKeyReplaysWithoutIncrementAndDifferentPayloadFails() {
        UUID second=pair(); var request=command(app,second,app,app);
        var original=write(request); assertThat(write(request)).isEqualTo(original);
        var different=new CandidateMergeCommand(recruiter,request.requestKey(),app,second,app,second,request.expectedFingerprint());
        assertReason(()->write(different),REQUEST_KEY_REUSED);
        assertThat(jdbc.queryForObject("SELECT revision FROM candidate_profiles WHERE id=?",Long.class,app)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_merge_operations WHERE actor_id=? AND request_key=?",Integer.class,recruiter,request.requestKey())).isEqualTo(1);
    }

    @Test void requestKeysAreScopedToActorAndDoNotReturnOtherActorsAudit() {
        UUID second=pair(); var request=command(app,second,app,app); write(request);
        var differentActor=new CandidateMergeCommand(other,request.requestKey(),app,second,app,app,request.expectedFingerprint());
        assertReason(()->write(differentActor),EMPTY_OR_MISSING_GROUP);
    }

    @Test void changedContactCannotUseAnOldFingerprint() throws Exception {
        UUID second=pair(); var request=command(app,second,app,app);
        confirm(app);
        assertReason(()->write(request),STALE_SNAPSHOT);
        assertThat(groups(app,second)).containsExactlyInAnyOrder(app,second);
    }

    @Test void wrongRepresentativeAndEmptyOrMissingGroupsAreRejected() {
        UUID second=pair(); var request=command(app,second,app,UUID.randomUUID());
        assertReason(()->write(request),INVALID_REQUEST);
        assertThatThrownBy(()->tx.execute(s->journal.snapshot(app,UUID.randomUUID()))).isInstanceOf(MergeAuditException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE candidate_profiles SET representative_application_id=? WHERE id=?",second,app))
                .isInstanceOf(DataAccessException.class);
        assertThat(groups(app,second)).containsExactlyInAnyOrder(app,second);
    }

    @Test void auditInsertFailureRollsBackLinksRevisionsRepresentativeAndHead() {
        UUID second=pair(); var request=command(app,second,app,app); var original=profileRows();
        jdbc.execute("CREATE FUNCTION reject_merge_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'forced audit failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_merge_insert BEFORE INSERT ON candidate_merge_operations FOR EACH ROW EXECUTE FUNCTION reject_merge_insert()");
        try { assertThatThrownBy(()->write(request)).isInstanceOf(DataAccessException.class); }
        finally { jdbc.execute("DROP TRIGGER reject_merge_insert ON candidate_merge_operations"); jdbc.execute("DROP FUNCTION reject_merge_insert()"); }
        assertThat(profileRows()).isEqualTo(original); assertThat(groups(app,second)).containsExactlyInAnyOrder(app,second);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_merge_operations WHERE actor_id=? AND request_key=?",Integer.class,recruiter,request.requestKey())).isZero();
    }

    @Test void callerRollbackAlsoRollsBackSuccessfulJournalWrite() {
        UUID second=pair(); var request=command(app,second,app,second); var original=profileRows();
        tx.executeWithoutResult(s->{journal.append(request); s.setRollbackOnly();});
        assertThat(profileRows()).isEqualTo(original); assertThat(groups(app,second)).containsExactlyInAnyOrder(app,second);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_merge_operations WHERE actor_id=? AND request_key=?",Integer.class,recruiter,request.requestKey())).isZero();
    }

    @Test void committedAuditAndSnapshotCannotBeUpdatedDeletedOrTruncated() {
        UUID second=pair(); var operation=write(command(app,second,app,app));
        for (String sql : List.of("UPDATE candidate_merge_operations SET members_before='[]'::jsonb WHERE id=?",
                "UPDATE candidate_merge_operations SET actor_name='changed' WHERE id=?","DELETE FROM candidate_merge_operations WHERE id=?"))
            assertThatThrownBy(()->jdbc.update(sql,operation.id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.execute("TRUNCATE candidate_merge_operations CASCADE")).isInstanceOf(DataAccessException.class);
        assertThat(find(operation.id())).contains(operation);
    }

    @Test void auditSurvivesApplicationAndActorDeletionWithoutBlockingExistingCascade() {
        UUID second=pair(); var original=command(app,second,app,second);
        var request=new CandidateMergeCommand(other,original.requestKey(),app,second,app,second,original.expectedFingerprint());
        var operation=write(request);
        jdbc.update("DELETE FROM job_applications WHERE id=?",second);
        jdbc.update("DELETE FROM user_accounts WHERE id=?",other);
        assertThat(find(operation.id())).contains(operation);
        assertThat(jdbc.queryForObject("SELECT representative_application_id FROM candidate_profiles WHERE id=?",UUID.class,app)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_memberships WHERE application_id=?",Integer.class,second)).isZero();
    }

    @Test void chainedMergeRetainsFirstDeadlineAndBothPriorHeads() {
        UUID second=pair(), third=application("third@example.test"), fourth=application("fourth@example.test"); drain();
        var first=write(command(app,second,app,second));
        var independent=write(command(third,fourth,third,fourth));
        var next=write(command(app,third,app,third));
        assertThat(next.before().members()).hasSize(4);
        assertThat(next.before().groups()).extracting(CandidateMergeSnapshot.Group::mergeHeadId)
                .containsExactlyInAnyOrder(first.id(),independent.id());
        assertThat(find(first.id())).contains(first);
        assertThat(first.undoDeadline()).isEqualTo(find(first.id()).orElseThrow().undoDeadline());
        assertThat(jdbc.queryForObject("SELECT revision FROM candidate_profiles WHERE id=?",Long.class,app)).isEqualTo(3);
    }

    @Test void detectorSeesGroupChangeAndNeverRewritesMembership() throws Exception {
        UUID second=pair(); write(command(app,second,app,second));
        var response=api.expect(api.request("GET","/api/v1/applications/"+app+"/duplicate-candidates",null,recruiterToken),200);
        assertThat(response.path("items")).isEmpty();
        drain(); assertThat(groups(app,second)).containsOnly(app);
        assertThat(jdbc.queryForList("SELECT candidate_id FROM candidate_contact_index WHERE application_id IN (?,?)",UUID.class,app,second)).containsOnly(app);
    }

    @Test void consumedHistoricalGroupCannotBeDeletedOrTruncatedAfterDetectorDrains() {
        UUID second=pair(); var operation=write(command(app,second,app,second)); drain();
        assertThatThrownBy(()->jdbc.update("DELETE FROM candidate_profiles WHERE id=?",second)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.execute("TRUNCATE candidate_profiles CASCADE")).isInstanceOf(DataAccessException.class);
        assertThat(find(operation.id())).contains(operation);
    }

    @Test void malformedPartitionFailsRealAuditGuardAndRollsBackTheMove() {
        UUID second=pair(); var request=command(app,second,app,app); var original=profileRows();
        jdbc.execute("CREATE FUNCTION damage_merge_snapshot() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN NEW.members_before:=NEW.members_before-0; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER a_damage_snapshot BEFORE INSERT ON candidate_merge_operations FOR EACH ROW EXECUTE FUNCTION damage_merge_snapshot()");
        try { assertThatThrownBy(()->write(request)).isInstanceOf(DataAccessException.class); }
        finally {jdbc.execute("DROP TRIGGER a_damage_snapshot ON candidate_merge_operations");jdbc.execute("DROP FUNCTION damage_merge_snapshot()");}
        assertThat(profileRows()).isEqualTo(original); assertThat(groups(app,second)).containsExactlyInAnyOrder(app,second);
    }

    @Test void unexpectedMemberInvalidatesPreviewEvenIfRawSqlDidNotAdvanceGroupRevision() {
        UUID second=pair(); var request=command(app,second,app,app);
        UUID third=application("third@example.test");drain();
        tx.executeWithoutResult(s->{
            jdbc.update("UPDATE candidate_profiles SET representative_application_id=NULL WHERE id=?",third);
            jdbc.update("UPDATE candidate_memberships SET candidate_id=? WHERE application_id=?",app,third);
        });
        assertReason(()->write(request),STALE_SNAPSHOT);
    }

    @Test void undoStoragePreservesOriginalEventAndAllowsMonotonicallyIncreasingRevisions() throws Exception {
        UUID second=pair(); var preview=command(app,second,app,app);var operation=write(preview);
        confirm(app);var protectedBefore=protectedRows();UUID undo=UUID.randomUUID();
        // Storage fixture only: there is no undo API/writer. Simulate the future caller's atomic restore.
        tx.executeWithoutResult(s->{
            for(var group:operation.before().groups())
                jdbc.update("UPDATE candidate_profiles SET revision=revision+1,representative_application_id=?,merge_head_id=? WHERE id=?",
                        group.representativeApplicationId(),group.mergeHeadId(),group.id());
            for(var member:operation.before().members())
                jdbc.update("UPDATE candidate_memberships SET candidate_id=? WHERE application_id=?",member.candidateId(),member.applicationId());
            jdbc.update("""
                    INSERT INTO candidate_merge_undo_events(id,merge_operation_id,actor_id,actor_name,request_key,payload_hash,
                      left_revision_before,right_revision_before,left_revision_after,right_revision_after)
                    VALUES (?,?,?,'Recruiter',?,?,2,2,3,3)
                    """,undo,operation.id(),recruiter,UUID.randomUUID(),java.util.HexFormat.of().parseHex(operation.payloadHash()));
        });
        assertThat(jdbc.queryForObject("SELECT recorded_at FROM candidate_merge_undo_events WHERE id=?",Timestamp.class,undo).toInstant())
                .isAfterOrEqualTo(operation.recordedAt());
        assertThat(jdbc.queryForList("SELECT revision FROM candidate_profiles WHERE id IN (?,?)",Long.class,app,second)).containsOnly(3L);
        assertThat(groups(app,second)).containsExactlyInAnyOrder(app,second);
        assertThat(protectedRows()).isEqualTo(protectedBefore);assertThat(find(operation.id())).contains(operation);
        var oldPreview=new CandidateMergeCommand(recruiter,UUID.randomUUID(),app,second,app,app,preview.expectedFingerprint());
        assertReason(()->write(oldPreview),STALE_SNAPSHOT);
        for(String sql:List.of("UPDATE candidate_merge_undo_events SET actor_name='changed' WHERE id=?","DELETE FROM candidate_merge_undo_events WHERE id=?"))
            assertThatThrownBy(()->jdbc.update(sql,undo)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.execute("TRUNCATE candidate_merge_undo_events")).isInstanceOf(DataAccessException.class);
    }

    @Test void competingSameKeyRequestsProduceOnlyOneOperation() throws Exception {
        UUID second=pair(); var request=command(app,second,app,second);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            Callable<CandidateMergeRecord> job=()->{assertThat(start.await(5,TimeUnit.SECONDS)).isTrue();return write(request);};
            var a=pool.submit(job); var b=pool.submit(job); start.countDown();
            assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT revision FROM candidate_profiles WHERE id=?",Long.class,app)).isEqualTo(2);
    }

    @Test void overlappingRequestsCannotBothApplyTheSamePreview() throws Exception {
        UUID second=pair(), third=application("third@example.test"); drain();
        var a=command(app,second,app,app); var b=command(app,third,app,app);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            Callable<Boolean> first=()->compete(start,a), secondJob=()->compete(start,b);
            var x=pool.submit(first);var y=pool.submit(secondJob);start.countDown();
            assertThat(List.of(x.get(10,TimeUnit.SECONDS),y.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("SELECT revision FROM candidate_profiles WHERE id=?",Long.class,app)).isEqualTo(2);
    }

    @Test void oppositeGroupOrderingWithDistinctKeysDoesNotDeadlock() throws Exception {
        UUID second=pair();var a=command(app,second,app,app);
        var b=new CandidateMergeCommand(recruiter,UUID.randomUUID(),second,app,app,app,a.expectedFingerprint());
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);var x=pool.submit(()->compete(start,a));var y=pool.submit(()->compete(start,b));
            start.countDown();assertThat(List.of(x.get(10,TimeUnit.SECONDS),y.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
    }

    @Test void contactCommittedDuringLockWaitMakesPreviewStale() throws Exception {
        UUID second=pair();var request=command(app,second,app,app);var profiles=profileRows();
        try(var blocker=dataSource.getConnection();var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            try(var lock=blocker.prepareStatement("SELECT id FROM recruitment_requisitions WHERE id=? FOR UPDATE")) {
                lock.setObject(1,requisition);lock.executeQuery().close();
            }
            var pending=pool.submit(()->{try{write(request);return null;}catch(MergeAuditException rejected){return rejected.reason();}});
            awaitLock("SELECT r.id FROM recruitment_requisitions%");
            try(var change=blocker.prepareStatement("""
                    INSERT INTO application_confirmed_contacts(application_id,revision,full_name,email,normalized_email,phone,
                        cv_sha256,name_source,email_source,phone_source,confirmed_by,confirmed_by_name,confirmed_at)
                    SELECT a.id,1,'Changed','changed@example.test','changed@example.test',a.phone,cv.sha256,
                        'MANUAL','MANUAL','MANUAL',?,'Recruiter',clock_timestamp()
                    FROM job_applications a JOIN application_cvs cv ON cv.application_id=a.id WHERE a.id=?
                    """)) {change.setObject(1,recruiter);change.setObject(2,app);change.executeUpdate();}
            blocker.commit();assertThat(pending.get(10,TimeUnit.SECONDS)).isEqualTo(STALE_SNAPSHOT);
        }
        assertThat(profileRows()).isEqualTo(profiles);assertThat(groups(app,second)).containsExactlyInAnyOrder(app,second);
    }

    @Test void databaseTimestampIsSampledAfterWaitingForSourceLock() throws Exception {
        UUID second=pair(); var request=command(app,second,app,app);
        try(var blocker=dataSource.getConnection();var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            try(var statement=blocker.prepareStatement("SELECT id FROM candidate_profiles WHERE id=? FOR UPDATE")) {
                statement.setObject(1,request.leftGroupId());statement.executeQuery().close();
            }
            var result=pool.submit(()->write(request));
            // Observe an actual database lock wait rather than relying on a sleep to order the writers.
            awaitLock("SELECT id FROM candidate_profiles%");
            assertThat(result.isDone()).isFalse(); Instant released=dbTime(); blocker.commit();
            assertThat(result.get(10,TimeUnit.SECONDS).recordedAt()).isAfterOrEqualTo(released);
        }
    }

    private boolean compete(CountDownLatch start,CandidateMergeCommand command) throws Exception {
        assertThat(start.await(5,TimeUnit.SECONDS)).isTrue();
        try {write(command);return true;} catch(MergeAuditException stale) {
            assertThat(stale.reason()).isIn(STALE_SNAPSHOT,EMPTY_OR_MISSING_GROUP);return false;
        }
    }
    private void awaitLock(String query) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
        while(System.nanoTime()<deadline && !(waiting=Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query LIKE ?)",Boolean.class,query))))
            Thread.sleep(10);
        assertThat(waiting).as("observed database lock wait").isTrue();
    }
    private void confirm(UUID id) throws Exception {
        String cvHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(pdf));
        api.expect(api.request("PUT","/api/v1/applications/"+id+"/confirmed-contact", """
                {"fullName":"Confirmed Name","email":"confirmed@example.test","phone":"0912345678",
                 "nameSource":"MANUAL","emailSource":"MANUAL","phoneSource":"MANUAL","expectedRevision":0,
                 "expectedCvHash":"%s"}
                """.formatted(cvHash),recruiterToken),200);
    }
    private UUID pair(){UUID second=application("second@example.test");drain();return second;}
    private void drain(){for(int i=0;i<30;i++)if(detector.processBatch()==0)return;throw new AssertionError("Detector backlog");}
    private CandidateMergeCommand command(UUID a,UUID b,UUID target,UUID rep){return new CandidateMergeCommand(recruiter,UUID.randomUUID(),a,b,target,rep,tx.execute(s->journal.snapshot(a,b)).fingerprint());}
    private CandidateMergeRecord write(CandidateMergeCommand command){return tx.execute(s->journal.append(command));}
    private java.util.Optional<CandidateMergeRecord> find(UUID id){return tx.execute(s->journal.find(id));}
    private List<UUID> groups(UUID a,UUID b){return jdbc.queryForList("SELECT candidate_id FROM candidate_memberships WHERE application_id IN (?,?)",UUID.class,a,b);}
    private Instant dbTime(){return jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();}
    private List<String> profileRows(){return jdbc.queryForList("SELECT row_to_json(p)::text FROM candidate_profiles p ORDER BY id",String.class);}
    private List<String> sourceRows(){return jdbc.queryForList("SELECT row_to_json(a)::text FROM job_applications a ORDER BY id",String.class);}
    private List<List<String>> protectedRows(){return List.of(
            jdbc.queryForList("SELECT row_to_json(c)::text FROM application_confirmed_contacts c ORDER BY application_id",String.class),
            jdbc.queryForList("SELECT row_to_json(h)::text FROM application_contact_history h ORDER BY application_id,revision",String.class),
            jdbc.queryForList("SELECT row_to_json(e)::text FROM application_email_outbox e ORDER BY application_id",String.class),
            jdbc.queryForList("SELECT encode(content,'hex') FROM application_cvs ORDER BY application_id",String.class));}
    private void assertReason(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,MergeAuditException.Reason reason){
        assertThatThrownBy(action).isInstanceOf(MergeAuditException.class).extracting(e->((MergeAuditException)e).reason()).isEqualTo(reason);
    }
}
