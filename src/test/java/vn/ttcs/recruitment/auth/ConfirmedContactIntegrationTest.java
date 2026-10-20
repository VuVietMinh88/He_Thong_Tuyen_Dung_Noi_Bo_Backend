package vn.ttcs.recruitment.auth;

import java.util.*;
import java.util.concurrent.*;
import java.sql.Timestamp;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import vn.ttcs.recruitment.cvsuggestion.*;
import vn.ttcs.recruitment.cvextraction.*;
import vn.ttcs.recruitment.application.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.config.import=","app.bootstrap.enabled=false","logging.level.root=WARN","debug=false",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","app.cv-extraction.enabled=true",
        "app.cv-extraction.max-pending=2","app.cv-contact-confirmation.enabled=true",
        "app.application-intake.enabled=true","app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.poll-delay-ms=3600000","spring.mail.host=127.0.0.1","spring.mail.port=1"})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ConfirmedContactIntegrationTest extends CvApplicationTestSupport {
    @Autowired ApplicationContactResolver resolver;
    @Autowired ConfirmedContactService confirmedService;
    @Autowired PostgresApplicationReceiptWriter receipts;
    @BeforeEach void resetQuota() { jdbc.update("DELETE FROM application_ip_quotas"); }

    @Test void confirmsAllFieldsPreservesOriginalAndAuditsChanges() throws Exception {
        String original=jdbc.queryForObject("SELECT row_to_json(a)::text FROM job_applications a WHERE id=?",String.class,app);
        var first=api.expect(api.request("GET",url(),null,recruiterToken),200);
        assertThat(first.path("revision").asLong()).isZero();assertThat(first.path("confirmed").isNull()).isTrue();
        var body=request(0,"Correct Name","new@example.test","+84987654321");
        var response=api.request("PUT",url(),json.writeValueAsString(body),recruiterToken);
        var saved=api.expect(response,200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(saved.path("revision").asLong()).isEqualTo(1);
        assertThat(saved.path("effective").path("email").asText()).isEqualTo("new@example.test");
        assertThat(saved.path("submitted").path("email").asText()).isEqualTo("one@example.test");
        assertThat(jdbc.queryForObject("SELECT row_to_json(a)::text FROM job_applications a WHERE id=?",String.class,app)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT content FROM application_cvs WHERE application_id=?",byte[].class,app)).isEqualTo(pdf);
        body.put("expectedRevision",1);api.expect(api.request("PUT",url(),json.writeValueAsString(body),recruiterToken),200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_contact_history",Integer.class)).isEqualTo(1);
        body.put("fullName","Corrected Twice");
        api.expect(api.request("PUT",url(),json.writeValueAsString(body),adminToken),200);
        var history=api.expect(api.request("GET",url()+"/history?size=1",null,recruiterToken),200);
        assertThat(history.path("totalElements").asLong()).isEqualTo(2);
        assertThat(history.path("items").get(0).path("revision").asLong()).isEqualTo(2);
        assertThat(history.path("items").get(0).path("actorId").asText()).isEqualTo(admin.toString());
        assertThat(history.path("items").get(0).path("beforeContact").path("fullName").asText()).isEqualTo("Correct Name");
        // Confirmed email is contact information, not the immutable submission duplicate identity.
        assertThat(application("new@example.test")).isNotEqualTo(app);
    }
    @Test void initialConfirmationOfSubmittedValuesIsRecordedAndNotSharedAcrossApplications() throws Exception {
        var body=request(0,"Original Name","one@example.test","0912345678");
        for (String field : List.of("nameSource","emailSource","phoneSource")) body.put(field,"SUBMITTED");
        api.expect(api.request("PUT",url(),json.writeValueAsString(body),recruiterToken),200);
        UUID second=application("second@example.test");
        assertThat(api.expect(api.request("GET",path(second),null,recruiterToken),200).path("revision").asLong()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_contact_history",Integer.class)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"fullName","email","phone","expectedRevision","expectedCvHash","nameSource"})
    void invalidRequestDoesNotPartiallySave(String field) throws Exception {
        var body=request(0,"Good Name","good@example.test","0912345678");
        body.put(field,switch(field) { case "expectedRevision" -> -1;case "expectedCvHash" -> "bad";case "nameSource" -> "FAKE";default -> ""; });
        api.expect(api.request("PUT",url(),json.writeValueAsString(body),recruiterToken),400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_confirmed_contacts",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_contact_history",Integer.class)).isZero();
    }
    @Test void preservesInternationalPhoneAndRejectsHtmlControlCharactersAndForgedSubmittedOrigin() throws Exception {
        var body=request(0,"<script>alert(1)</script>","new@example.test","+44 (20) 7946-0958");
        api.expect(api.request("PUT",url(),json.writeValueAsString(body),recruiterToken),400);
        body.put("fullName","International Candidate");
        body.put("nameSource","SUBMITTED");
        assertThat(api.code(api.request("PUT",url(),json.writeValueAsString(body),recruiterToken),400)).isEqualTo("CONTACT_SOURCE_INVALID");
        body.put("nameSource","MANUAL");
        var saved=api.expect(api.request("PUT",url(),json.writeValueAsString(body),recruiterToken),200);
        assertThat(saved.path("effective").path("phone").asText()).isEqualTo("+442079460958");
    }
    @Test void supportsVerifiedExtractionCandidatesAndRejectsForgedSource() throws Exception {
        String job=api.expect(api.request("POST","/api/v1/applications/"+app+"/cv-extractions",null,recruiterToken),202).path("id").asText();
        var worker=new CvSuggestionWorker(jobs,tx,bytes->new PdfCvExtractor(image->"").extract(bytes),1);
        assertThat(worker.processNext()).isTrue();
        var body=request(0,"Emily Carter","emily@example.test","+84912345678");
        for (String field : List.of("nameSource","emailSource","phoneSource")) body.put(field,"EXTRACTED");
        body.put("extractionId",job);body.put("fullName","Forged Name");
        assertThat(api.code(api.request("PUT",url(),json.writeValueAsString(body),recruiterToken),400)).isEqualTo("CONTACT_SOURCE_INVALID");
        body.put("fullName","Emily Carter");
        api.expect(api.request("PUT",url(),json.writeValueAsString(body),recruiterToken),200);
        body.put("expectedRevision",1);body.put("phone","0912345678");
        api.expect(api.request("PUT",url(),json.writeValueAsString(body),recruiterToken),200);
        body.put("expectedRevision",2);body.put("extractionId",UUID.randomUUID().toString());
        assertThat(api.code(api.request("PUT",url(),json.writeValueAsString(body),recruiterToken),409)).isEqualTo("CV_EXTRACTION_STALE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_contact_history",Integer.class)).isEqualTo(2);
        UUID otherApplication=application("second@example.test");
        String otherJob=api.expect(api.request("POST","/api/v1/applications/"+otherApplication+"/cv-extractions",null,recruiterToken),202).path("id").asText();
        assertThat(worker.processNext()).isTrue();body.put("extractionId",otherJob);
        assertThat(api.code(api.request("PUT",url(),json.writeValueAsString(body),recruiterToken),409)).isEqualTo("CV_EXTRACTION_STALE");
    }
    @Test void twoConcurrentWritersCannotLoseChanges() throws Exception {
        try (var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier=new CyclicBarrier(2);
            var calls=new ArrayList<Future<Integer>>();
            for (String token : List.of(adminToken,recruiterToken)) calls.add(pool.submit(()->{
                barrier.await();return api.request("PUT",url(),json.writeValueAsString(
                        request(0,"New Name","new@example.test","0912345678")),token).statusCode();
            }));
            assertThat(List.of(calls.get(0).get(15,TimeUnit.SECONDS),calls.get(1).get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_contact_history",Integer.class)).isEqualTo(1);
    }
    @Test void cvChangeMarksReviewAndRequiresCurrentHashWithoutChangingEffectiveContact() throws Exception {
        var body=request(0,"New Name","new@example.test","0912345678");
        api.expect(api.request("PUT",url(),json.writeValueAsString(body),recruiterToken),200);
        byte[] changed=pdf("Second Candidate");
        jdbc.update("UPDATE application_cvs SET content=?,size_bytes=?,sha256=sha256(?) WHERE application_id=?",changed,changed.length,changed,app);
        var view=api.expect(api.request("GET",url(),null,recruiterToken),200);
        assertThat(view.path("needsReview").asBoolean()).isTrue();
        assertThat(view.path("effective").path("email").asText()).isEqualTo("new@example.test");
        body.put("expectedRevision",1);
        assertThat(api.code(api.request("PUT",url(),json.writeValueAsString(body),recruiterToken),409)).isEqualTo("CV_CHANGED");
        body.put("expectedCvHash",view.path("currentCvHash").asText());
        assertThat(api.expect(api.request("PUT",url(),json.writeValueAsString(body),recruiterToken),200).path("needsReview").asBoolean()).isFalse();
    }
    @Test void enforcesPermissionsAndCurrentAssignmentOnAllEndpoints() throws Exception {
        assertThat(api.request("GET",url(),null,null).statusCode()).isEqualTo(401);
        for (String suffix : List.of("","/history")) api.forbidden(api.request("GET",url()+suffix,null,otherToken));
        api.forbidden(api.request("PUT",url(),json.writeValueAsString(request(0,"Name","new@example.test","0912345678")),otherToken));
        jdbc.update("UPDATE requisition_recruiters SET recruiter_id=? WHERE requisition_id=?",other,requisition);
        api.forbidden(api.request("GET",url(),null,recruiterToken));
        api.expect(api.request("PUT",url(),json.writeValueAsString(request(0,"Name","new@example.test","0912345678")),otherToken),200);
        api.forbidden(api.request("GET",url()+"/history",null,recruiterToken));
    }
    @Test void historyFailureRollsBackContactAndHistoryIsAppendOnly() throws Exception {
        jdbc.execute("CREATE FUNCTION reject_contact_history() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'private contact'; END $$");
        jdbc.execute("CREATE TRIGGER reject_contact_history BEFORE INSERT ON application_contact_history FOR EACH ROW EXECUTE FUNCTION reject_contact_history()");
        try {
            var response=api.request("PUT",url(),json.writeValueAsString(request(0,"New Name","new@example.test","0912345678")),recruiterToken);
            api.expect(response,503);assertThat(response.body()).doesNotContain("private contact","INSERT");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM application_confirmed_contacts",Integer.class)).isZero();
        } finally { jdbc.execute("DROP TRIGGER reject_contact_history ON application_contact_history");jdbc.execute("DROP FUNCTION reject_contact_history()"); }
        api.expect(api.request("PUT",url(),json.writeValueAsString(request(0,"New Name","new@example.test","0912345678")),recruiterToken),200);
        assertThatThrownBy(()->jdbc.update("UPDATE application_contact_history SET actor_name='forged' WHERE application_id=?",app)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM application_contact_history WHERE application_id=?",app)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM job_applications WHERE id=?",app);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_contact_history",Integer.class)).isZero();
    }
    @Test void mailSnapshotsUseCommitOrderAndExistingReceiptIsUnchanged() throws Exception {
        var receipt=receipts.accept(posting,new ApplicationSubmission("Receipt Name","receipt@example.test","0912345678",null,null),
                validator.validate("cv.pdf",new java.io.ByteArrayInputStream(pdf)));
        UUID target=receipt.applicationId();
        byte[] encrypted=jdbc.queryForObject("SELECT ciphertext FROM application_email_outbox WHERE application_id=?",byte[].class,target);
        var before=tx.execute(ignored->resolver.snapshotForNewRecruitmentMail(target));
        app=target;
        api.expect(api.request("PUT",url(),json.writeValueAsString(request(0,"Confirmed Name","confirmed@example.test","0912345678")),recruiterToken),200);
        var after=tx.execute(ignored->resolver.snapshotForNewRecruitmentMail(target));
        assertThat(before.contact().email()).isEqualTo("receipt@example.test");assertThat(before.revision()).isZero();
        assertThat(after.contact().email()).isEqualTo("confirmed@example.test");assertThat(after.revision()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ciphertext FROM application_email_outbox WHERE application_id=?",byte[].class,target)).isEqualTo(encrypted);
        assertThat(jdbc.queryForObject("SELECT normalized_email FROM job_applications WHERE id=?",String.class,target)).isEqualTo("receipt@example.test");
        assertThatThrownBy(()->resolver.snapshotForNewRecruitmentMail(target)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
    @Test void confirmationWaitsForMailSnapshotTransaction() throws Exception {
        try (var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var locked=new CountDownLatch(1);var release=new CountDownLatch(1);
            var snapshot=pool.submit(()->tx.execute(ignored->{
                var snap=resolver.snapshotForNewRecruitmentMail(app);locked.countDown();
                try { assertThat(release.await(4,TimeUnit.SECONDS)).isTrue(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return snap;
            }));
            assertThat(locked.await(4,TimeUnit.SECONDS)).isTrue();
            var updated=pool.submit(()->api.request("PUT",url(),json.writeValueAsString(request(0,"New Name","new@example.test","0912345678")),recruiterToken));
            Thread.sleep(150);assertThat(updated.isDone()).isFalse();release.countDown();
            api.expect(updated.get(10,TimeUnit.SECONDS),200);assertThat(snapshot.get().contact().email()).isEqualTo("one@example.test");
        }
        assertThat(tx.execute(ignored->resolver.snapshotForNewRecruitmentMail(app)).contact().email()).isEqualTo("new@example.test");
    }
    @Test void mailResolverRejectsOldRepeatableReadSnapshot() {
        var repeatable=new org.springframework.transaction.support.TransactionTemplate(manager);
        repeatable.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(()->repeatable.execute(ignored->resolver.snapshotForNewRecruitmentMail(app)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }
    @Test void featureOffAndInvalidPaginationDoNotWriteAnything() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(confirmedService,"enabled",false);
        try {
            assertThat(api.code(api.request("PUT",url(),json.writeValueAsString(request(0,"New Name","new@example.test","0912345678")),recruiterToken),503))
                    .isEqualTo("CONTACT_CONFIRMATION_UNAVAILABLE");
            api.expect(api.request("GET",url(),null,recruiterToken),200);
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(confirmedService,"enabled",true); }
        for (String query : List.of("page=-1","size=0","size=101","page=1000001&size=100"))
            api.expect(api.request("GET",url()+"/history?"+query,null,recruiterToken),400);
        api.expect(api.request("GET",path(UUID.randomUUID()),null,recruiterToken),404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_confirmed_contacts",Integer.class)).isZero();
    }
    @Test void readAndWritePermissionsAreIndependent() throws Exception {
        jdbc.update("DELETE FROM role_permissions WHERE role_code='RECRUITER' AND permission_code='CANDIDATES_READ_SCOPED'");
        try { api.forbidden(api.request("PUT",url(),json.writeValueAsString(request(0,"Name","new@example.test","0912345678")),recruiterToken)); }
        finally { jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('RECRUITER','CANDIDATES_READ_SCOPED')"); }
        jdbc.update("DELETE FROM role_permissions WHERE role_code='RECRUITER' AND permission_code='CANDIDATES_WRITE_SCOPED'");
        try {
            api.expect(api.request("GET",url(),null,recruiterToken),200);
            api.forbidden(api.request("PUT",url(),json.writeValueAsString(request(0,"Name","new@example.test","0912345678")),recruiterToken));
        } finally { jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('RECRUITER','CANDIDATES_WRITE_SCOPED')"); }
    }
    @Test void handoverWhileConfirmationWaitsForRequisitionLockIsRespected() throws Exception {
        try (var blocker=dataSource.getConnection();var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            try (var statement=blocker.prepareStatement("SELECT id FROM recruitment_requisitions WHERE id=? FOR UPDATE")) {
                statement.setObject(1,requisition);statement.executeQuery().close();
            }
            var call=pool.submit(()->api.request("PUT",url(),json.writeValueAsString(request(0,"New Name","new@example.test","0912345678")),recruiterToken));
            boolean waiting=false;
            for (int i=0;i<75;i++) {
                if (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%SELECT id FROM recruitment_requisitions%'",Integer.class)>0) { waiting=true;break; }
                Thread.sleep(20);
            }
            assertThat(waiting).isTrue();
            try (var statement=blocker.prepareStatement("UPDATE requisition_recruiters SET recruiter_id=? WHERE requisition_id=?")) {
                statement.setObject(1,other);statement.setObject(2,requisition);statement.executeUpdate();
            }
            blocker.commit();api.forbidden(call.get(10,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_confirmed_contacts",Integer.class)).isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"s5","feedback"})
    void upgradingV59PreservesSubmissionCvJobsAndReceiptWithoutBackfill(String lineage) throws Exception {
        receipts.accept(posting,new ApplicationSubmission("Receipt","receipt@example.test","0912345678",null,null),
                validator.validate("cv.pdf",new java.io.ByteArrayInputStream(pdf)));
        api.expect(api.request("POST","/api/v1/applications/"+app+"/cv-extractions",null,recruiterToken),202);
        String original=jdbc.queryForObject("SELECT current_schema()",String.class);
        String schema="contact_upgrade_"+UUID.randomUUID().toString().replace("-","");
        jdbc.execute("CREATE SCHEMA "+schema);
        var isolated=new org.springframework.jdbc.datasource.DelegatingDataSource(dataSource) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection=super.getConnection();connection.setSchema(schema);return connection;
            }
        };
        try {
            var legacy=new org.springframework.jdbc.core.JdbcTemplate(isolated);
            org.flywaydb.core.Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema).locations(lineage.equals("s5") ? "classpath:db/migration" : "classpath:db/migration-feedback", "classpath:db/migration-shared").target("59").load().migrate();
            for (String table : List.of("user_accounts","departments","positions","recruitment_catalog_items","recruitment_requisitions",
                    "job_postings","job_applications","application_cvs","application_email_outbox","cv_extraction_jobs","cv_extraction_attempts"))
                legacy.execute("INSERT INTO "+schema+"."+table+" SELECT * FROM "+original+"."+table);
            byte[] receipt=legacy.queryForObject("SELECT ciphertext FROM application_email_outbox",byte[].class);
            String submission=legacy.queryForObject("SELECT row_to_json(a)::text FROM job_applications a WHERE id=?",String.class,app);
            org.flywaydb.core.Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema).locations(lineage.equals("s5") ? "classpath:db/migration" : "classpath:db/migration-feedback", "classpath:db/migration-shared").target("60").load().migrate();
            assertThat(legacy.queryForObject("SELECT row_to_json(a)::text FROM job_applications a WHERE id=?",String.class,app)).isEqualTo(submission);
            assertThat(legacy.queryForObject("SELECT content FROM application_cvs WHERE application_id=?",byte[].class,app)).isEqualTo(pdf);
            assertThat(legacy.queryForObject("SELECT ciphertext FROM application_email_outbox",byte[].class)).isEqualTo(receipt);
            assertThat(legacy.queryForObject("SELECT count(*) FROM cv_extraction_jobs",Integer.class)).isEqualTo(1);
            assertThat(legacy.queryForObject("SELECT count(*) FROM application_confirmed_contacts",Integer.class)).isZero();
            assertThat(legacy.queryForObject("SELECT count(*) FROM application_contact_history",Integer.class)).isZero();
        } finally { jdbc.execute("DROP SCHEMA "+schema+" CASCADE"); }
        assertThat(jdbc.queryForObject("SELECT current_schema()",String.class)).isEqualTo(original);
    }
    protected String url() { return path(app); }
    protected String path(UUID id) { return "/api/v1/applications/"+id+"/confirmed-contact"; }
    protected Map<String,Object> request(long revision,String name,String email,String phone) {
        var result=new LinkedHashMap<String,Object>();
        result.put("fullName",name);result.put("email",email);result.put("phone",phone);result.put("expectedRevision",revision);
        result.put("expectedCvHash",jdbc.queryForObject("SELECT encode(sha256,'hex') FROM application_cvs WHERE application_id=?",String.class,app));
        result.put("nameSource","MANUAL");result.put("emailSource","MANUAL");result.put("phoneSource","MANUAL");return result;
    }
}
