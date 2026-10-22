package vn.ttcs.recruitment.auth;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.*;
import vn.ttcs.recruitment.application.*;
import vn.ttcs.recruitment.cvsuggestion.*;
import vn.ttcs.recruitment.cvextraction.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.config.import=","app.bootstrap.enabled=false","logging.level.root=WARN","debug=false",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","app.cv-extraction.enabled=true",
        "app.cv-extraction.max-pending=2","spring.mail.host=127.0.0.1","spring.mail.port=1",
        "app.application-intake.enabled=true","app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.poll-delay-ms=3600000"})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CvSuggestionIntegrationTest extends CvApplicationTestSupport {
    @Test void createsPollsAndReusesCompletedSuggestionsWithoutChangingApplication() throws Exception {
        String before=jdbc.queryForObject("SELECT row_to_json(a)::text FROM job_applications a WHERE id=?",String.class,app);
        var response=api.request("POST",url(),null,recruiterToken);
        var created=api.expect(response,202);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().firstValue("Location").orElseThrow()).endsWith(created.path("id").asText());
        assertThat(created.path("result").isNull()).isTrue();
        assertThat(worker(bytes->new PdfCvExtractor(image->{throw new AssertionError("Text PDF needs no OCR");}).extract(bytes)).processNext()).isTrue();
        var result=api.expect(api.request("GET",url()+"/"+created.path("id").asText(),null,recruiterToken),200);
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.path("result").path("outcome").asText()).isEqualTo("COMPLETE");
        assertThat(result.path("result").path("suggestions").path("fullName").get(0).path("value").asText()).isEqualTo("Emily Carter");
        assertThat(api.expect(api.request("POST",url(),null,adminToken),200).path("id").asText()).isEqualTo(created.path("id").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cv_extraction_attempts",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT row_to_json(a)::text FROM job_applications a WHERE id=?",String.class,app)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT content FROM application_cvs WHERE application_id=?",byte[].class,app)).isEqualTo(pdf);
    }
    @Test void checksAuthenticationScopeHandoverAndJobApplicationPair() throws Exception {
        assertThat(api.request("POST",url(),null,null).statusCode()).isEqualTo(401);
        api.forbidden(api.request("POST",url(),null,otherToken));
        String id=api.expect(api.request("POST",url(),null,recruiterToken),202).path("id").asText();
        api.forbidden(api.request("GET",url()+"/"+id,null,otherToken));
        jdbc.update("UPDATE requisition_recruiters SET recruiter_id=? WHERE requisition_id=?",other,requisition);
        api.forbidden(api.request("GET",url()+"/"+id,null,recruiterToken));
        api.forbidden(api.request("POST",url(),null,recruiterToken));
        api.expect(api.request("GET",url()+"/"+id,null,otherToken),200);
        UUID second=application("second@example.test");
        assertThat(api.code(api.request("GET","/api/v1/applications/"+second+"/cv-extractions/"+id,null,adminToken),404)).isEqualTo("CV_EXTRACTION_NOT_FOUND");
        assertThat(api.request("POST","/api/v1/applications/bad/cv-extractions",null,adminToken).statusCode()).isEqualTo(400);
    }
    @Test void writeNeverImpliesReadAndRequisitionPermissionsDoNotGrantCandidateWrites() throws Exception {
        jdbc.update("UPDATE user_roles SET role='APPROVER' WHERE user_id=?",other);
        api.forbidden(api.request("POST",url(),null,otherToken));
        jdbc.update("DELETE FROM role_permissions WHERE role_code='RECRUITER' AND permission_code='CANDIDATES_READ_SCOPED'");
        try { api.forbidden(api.request("POST",url(),null,recruiterToken)); }
        finally { jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('RECRUITER','CANDIDATES_READ_SCOPED')"); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cv_extraction_jobs",Integer.class)).isZero();
    }
    @Test void concurrentPostsCreateOneJobAndOneAttempt() throws Exception {
        try (var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier=new CyclicBarrier(2);
            var calls=new ArrayList<Future<String>>();
            for (String token : List.of(adminToken,recruiterToken)) calls.add(pool.submit(()->{
                barrier.await();return api.expect(api.request("POST",url(),null,token),202).path("id").asText();
            }));
            assertThat(calls.get(0).get(15,TimeUnit.SECONDS)).isEqualTo(calls.get(1).get(15,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cv_extraction_jobs",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cv_extraction_attempts",Integer.class)).isEqualTo(1);
    }
    @Test void failedJobsRequireManualRetryAndLimitThreeRetriesAcrossUsers() throws Exception {
        var worker=worker(bytes->{throw new java.io.IOException("private CV contents must never escape");});
        api.expect(api.request("POST",url(),null,adminToken),202);
        for (int attempt=1;attempt<=4;attempt++) {
            assertThat(worker.processNext()).isTrue();assertThat(worker.processNext()).isFalse();
            if (attempt<4) assertThat(api.expect(api.request("POST",url(),null,recruiterToken),202).path("attempt").asInt()).isEqualTo(attempt+1);
        }
        assertThat(api.code(api.request("POST",url(),null,adminToken),429)).isEqualTo("CV_RETRY_LIMIT");
        jdbc.update("UPDATE cv_extraction_attempts SET requested_at=clock_timestamp()-interval '61 minutes'");
        api.expect(api.request("POST",url(),null,adminToken),202);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cv_extraction_attempts",Integer.class)).isEqualTo(5);
    }
    @Test void expiredLeaseCannotFinishOrRenewAndDoesNotAutomaticallyRequeue() throws Exception {
        api.expect(api.request("POST",url(),null,adminToken),202);
        var old=jobs.claim(tx,1).orElseThrow();
        assertThat(jobs.claim(tx,1)).isEmpty();
        jdbc.update("UPDATE cv_extraction_attempts SET lease_until=clock_timestamp()-interval '1 second'");
        assertThat(jobs.renew(old)).isFalse();assertThat(jobs.finish(tx,old,null,"EXTRACTION_FAILED")).isFalse();
        jobs.expire(tx);
        assertThat(jobs.claim(tx,1)).isEmpty();
        api.expect(api.request("POST",url(),null,recruiterToken),202);
        var fresh=jobs.claim(tx,1).orElseThrow();
        assertThat(fresh.token()).isNotEqualTo(old.token());
        assertThat(jobs.finish(tx,old,null,"EXTRACTION_FAILED")).isFalse();
        assertThat(jobs.renew(fresh)).isTrue();
    }
    @Test void queueCapacityAndQueuedExpirationAreBounded() throws Exception {
        api.expect(api.request("POST",url(),null,adminToken),202);
        UUID second=application("second@example.test"),third=application("third@example.test");
        api.expect(api.request("POST",path(second),null,adminToken),202);
        assertThat(api.code(api.request("POST",path(third),null,adminToken),503)).isEqualTo("CV_QUEUE_FULL");
        jdbc.update("UPDATE cv_extraction_attempts SET requested_at=clock_timestamp()-interval '11 minutes'");
        jobs.expire(tx);
        assertThat(jobs.claim(tx,1)).isEmpty();
        api.expect(api.request("POST",path(third),null,adminToken),202);
    }
    @Test void workerCapacityAndClaimAreAtomicAcrossWorkers() throws Exception {
        api.expect(api.request("POST",url(),null,adminToken),202);
        api.expect(api.request("POST",path(application("second@example.test")),null,adminToken),202);
        try (var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier=new CyclicBarrier(2);
            Callable<Boolean> claim=()->{barrier.await();return jobs.claim(tx,1).isPresent();};
            var one=pool.submit(claim);var two=pool.submit(claim);
            assertThat(List.of(one.get(),two.get()).stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cv_extraction_jobs WHERE status='RUNNING'",Integer.class)).isEqualTo(1);
    }
    @Test void changedCvRejectsStaleReadAndOldQueuedWork() throws Exception {
        String id=api.expect(api.request("POST",url(),null,adminToken),202).path("id").asText();
        byte[] changed=pdf("Other Candidate");
        jdbc.update("UPDATE application_cvs SET content=?,size_bytes=?,sha256=sha256(?) WHERE application_id=?",changed,changed.length,changed,app);
        assertThat(api.code(api.request("GET",url()+"/"+id,null,adminToken),409)).isEqualTo("CV_CHANGED");
        assertThat(worker(bytes->{throw new AssertionError("Must not execute changed CV");}).processNext()).isTrue();
        var next=api.expect(api.request("POST",url(),null,adminToken),202);
        assertThat(next.path("id").asText()).isNotEqualTo(id);
    }
    @Test void docxReturnsUnsupportedWithoutWorkerAndReusesResult() throws Exception {
        try (var document=new org.apache.poi.xwpf.usermodel.XWPFDocument();var output=new java.io.ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Candidate");document.write(output);
            byte[] content=output.toByteArray();
            jdbc.update("UPDATE application_cvs SET content=?,size_bytes=?,sha256=sha256(?),content_type=? WHERE application_id=?",
                    content,content.length,content,"application/vnd.openxmlformats-officedocument.wordprocessingml.document",app);
        }
        var result=api.expect(api.request("POST",url(),null,recruiterToken),200);
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(result.path("result").path("outcome").asText()).isEqualTo("UNSUPPORTED");
        assertThat(worker(bytes->{throw new AssertionError();}).processNext()).isFalse();
        api.expect(api.request("POST",url(),null,recruiterToken),200);
    }
    @Test void deletionCascadesJobsAndAttemptsAndLateWorkerCannotWrite() throws Exception {
        api.expect(api.request("POST",url(),null,adminToken),202);
        var claim=jobs.claim(tx,1).orElseThrow();
        jdbc.update("DELETE FROM job_applications WHERE id=?",app);
        assertThat(jobs.finish(tx,claim,null,"EXTRACTION_FAILED")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cv_extraction_attempts",Integer.class)).isZero();
    }
    @Test void noDataAndPartialAreCompletedResultsAndNotRetried() throws Exception {
        api.expect(api.request("POST",url(),null,adminToken),202);
        var empty=new CvExtractionResult(CvExtractionResult.VERSION,CvExtractionResult.Outcome.NO_DATA,1,1,
                new CvExtractionResult.Suggestions(List.of(),List.of(),List.of()),List.of("NAME_NOT_FOUND"));
        AtomicInteger calls=new AtomicInteger();
        assertThat(worker(bytes->{calls.incrementAndGet();return empty;}).processNext()).isTrue();
        api.expect(api.request("POST",url(),null,recruiterToken),200);
        assertThat(calls).hasValue(1);
    }
    @Test void getReportsExpiredJobEvenWhenWorkerIsDown() throws Exception {
        String id=api.expect(api.request("POST",url(),null,adminToken),202).path("id").asText();
        jdbc.update("UPDATE cv_extraction_attempts SET requested_at=clock_timestamp()-interval '11 minutes'");
        var expired=api.expect(api.request("GET",url()+"/"+id,null,recruiterToken),200);
        assertThat(expired.path("status").asText()).isEqualTo("FAILED");
        assertThat(expired.path("errorCode").asText()).isEqualTo("JOB_EXPIRED");
    }
    @Test void oldVersionIsNotReusedAndCannotBeReadAsCurrentResult() throws Exception {
        String id=api.expect(api.request("POST",url(),null,adminToken),202).path("id").asText();
        jdbc.update("UPDATE cv_extraction_jobs SET extractor_version='old-version'");
        assertThat(api.code(api.request("GET",url()+"/"+id,null,adminToken),409)).isEqualTo("CV_EXTRACTION_STALE");
        assertThat(api.expect(api.request("POST",url(),null,adminToken),202).path("id").asText()).isNotEqualTo(id);
    }
    @Test void cannotSubmitExtractionWhileDisabledAndDoesNotCreateJobs() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(service,"enabled",false);
        try {
            assertThat(api.code(api.request("POST",url(),null,adminToken),503)).isEqualTo("CV_EXTRACTION_UNAVAILABLE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cv_extraction_jobs",Integer.class)).isZero();
        } finally { org.springframework.test.util.ReflectionTestUtils.setField(service,"enabled",true); }
    }
    @Test void handoverWhilePostWaitsForRequisitionLockIsRespected() throws Exception {
        try (var blocker=dataSource.getConnection();var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            blocker.setAutoCommit(false);
            try (var statement=blocker.prepareStatement("SELECT id FROM recruitment_requisitions WHERE id=? FOR UPDATE")) {
                statement.setObject(1,requisition);statement.executeQuery().close();
            }
            var call=pool.submit(()->api.request("POST",url(),null,recruiterToken));
            boolean waiting=false;
            for (int i=0;i<75;i++) {
                if (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query LIKE '%SELECT a.requisition_id%'",Integer.class)>0) {
                    waiting=true;break;
                }
                Thread.sleep(20);
            }
            assertThat(waiting).isTrue();
            try (var statement=blocker.prepareStatement("UPDATE requisition_recruiters SET recruiter_id=? WHERE requisition_id=?")) {
                statement.setObject(1,other);statement.setObject(2,requisition);statement.executeUpdate();
            }
            blocker.commit();api.forbidden(call.get(10,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cv_extraction_jobs",Integer.class)).isZero();
    }
    @Test void missingApplicationAndJobDoNotLeakDataAndRevokedSessionIsRejected() throws Exception {
        assertThat(api.code(api.request("POST",path(UUID.randomUUID()),null,adminToken),404)).isEqualTo("CV_EXTRACTION_NOT_FOUND");
        assertThat(api.code(api.request("GET",url()+"/"+UUID.randomUUID(),null,adminToken),404)).isEqualTo("CV_EXTRACTION_NOT_FOUND");
        jdbc.update("UPDATE user_accounts SET enabled=false WHERE id=?",recruiter);
        assertThat(api.request("POST",url(),null,recruiterToken).statusCode()).isEqualTo(401);
    }
    @Test void databaseFailureRollsBackJobAndAttemptWithoutLeakingSql() throws Exception {
        jdbc.execute("CREATE FUNCTION reject_cv_job() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'private CV diagnostic'; END $$");
        jdbc.execute("CREATE TRIGGER reject_cv_job BEFORE INSERT ON cv_extraction_attempts FOR EACH ROW EXECUTE FUNCTION reject_cv_job()");
        try {
            var response=api.request("POST",url(),null,adminToken);
            assertThat(api.code(response,503)).isEqualTo("CV_EXTRACTION_UNAVAILABLE");
            assertThat(response.body()).doesNotContain("private CV diagnostic","INSERT","SQLException");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM cv_extraction_jobs",Integer.class)).isZero();
        } finally { jdbc.execute("DROP TRIGGER reject_cv_job ON cv_extraction_attempts");jdbc.execute("DROP FUNCTION reject_cv_job()"); }
    }
    @Test void publicSubmissionSucceedsWhenExtractionWorkerFailsAndQueueTableIsUnavailable() throws Exception {
        api.expect(api.request("POST",url(),null,recruiterToken),202);
        assertThat(worker(bytes->{throw new java.io.IOException("OCR offline");}).processNext()).isTrue();
        jdbc.execute("ALTER TABLE cv_extraction_jobs RENAME TO cv_extraction_jobs_offline");
        try {
            var bytes=new java.io.ByteArrayOutputStream();
            for (var field : Map.of("fullName","Manual Candidate","email","manual@example.test","phone","0912345678").entrySet())
                bytes.write(("--cv-api-boundary\r\nContent-Disposition: form-data; name=\""+field.getKey()+"\"\r\n\r\n"+field.getValue()+"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bytes.write("--cv-api-boundary\r\nContent-Disposition: form-data; name=\"cv\"; filename=\"cv.pdf\"\r\nContent-Type: application/pdf\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bytes.write(pdf);bytes.write("\r\n--cv-api-boundary--\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://127.0.0.1:"+environment.getRequiredProperty("local.server.port")+
                    "/api/v1/public/job-postings/"+posting+"/applications")).timeout(java.time.Duration.ofSeconds(15))
                    .header("Content-Type","multipart/form-data; boundary=cv-api-boundary")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build();
            var response=java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
            api.expect(response,201);
            UUID saved=jdbc.queryForObject("SELECT id FROM job_applications WHERE email='manual@example.test'",UUID.class);
            assertThat(jdbc.queryForObject("SELECT content FROM application_cvs WHERE application_id=?",byte[].class,saved)).isEqualTo(pdf);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM application_email_outbox WHERE application_id=?",Integer.class,saved)).isEqualTo(1);
        } finally { jdbc.execute("ALTER TABLE cv_extraction_jobs_offline RENAME TO cv_extraction_jobs"); }
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="test.cv-sandbox.image",matches="sha256:[a-f0-9]{64}")
    void sameTwelvePdfsRunThroughHttpDatabaseAndRealSandbox() throws Exception {
        Path directory=Path.of("output/pdf/cv-layout-tests");
        var gold=json.readTree(Files.readString(directory.resolve("expected.json")));
        Path temp=Files.createTempDirectory("cv-api-sandbox-test-");
        var sandbox=new CvSandboxProcess(System.getProperty("test.cv-sandbox.python"),Path.of("devops/cv-extraction/run.py"),
                System.getProperty("test.cv-sandbox.image"),temp);
        var report=json.createArrayNode();
        try {
            int index=0;
            for (var sample : gold.path("samples")) {
                pdf=Files.readAllBytes(directory.resolve(sample.path("file").asText()));
                String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(pdf));
                assertThat(hash).isEqualTo(sample.path("sha256").asText());
                UUID candidate=application("sandbox-"+(index++)+"@example.test");
                String id=api.expect(api.request("POST",path(candidate),null,recruiterToken),202).path("id").asText();
                if (index==1 && System.getProperty("test.cv-sandbox.jar")!=null) runStandaloneWorker(temp);
                else assertThat(worker(sandbox::extract).processNext()).isTrue();
                var completed=api.expect(api.request("GET",path(candidate)+"/"+id,null,recruiterToken),200);
                assertThat(completed.path("status").asText()).isEqualTo("SUCCEEDED");
                var result=completed.path("result");var expected=sample.path("expected");
                for (String field : List.of("fullName","email","phone")) {
                    var values=new ArrayList<String>();
                    for (var item : result.path("suggestions").path(field)) if (item.path("preferred").asBoolean())
                        values.add(java.text.Normalizer.normalize(item.path(field.equals("fullName") ? "value" : "normalizedValue").asText(),java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT));
                    if (field.equals("email") && expected.has("emailPreferred") && !expected.path("emailPreferred").asBoolean()) {
                        assertThat(values).isEmpty();assertThat(result.path("warnings").toString()).contains("EMAIL_AMBIGUOUS");
                    } else assertThat(values).containsExactly(java.text.Normalizer.normalize(expected.path(field).asText(),java.text.Normalizer.Form.NFC).toLowerCase(Locale.ROOT));
                }
                assertThat(result.path("outcome").asText()).isEqualTo(expected.has("emailPreferred") ? "PARTIAL" : "COMPLETE");
                assertThat(jdbc.queryForObject("SELECT content FROM application_cvs WHERE application_id=?",byte[].class,candidate)).isEqualTo(pdf);
                report.addObject().put("file",sample.path("file").asText()).put("sha256",hash).put("passed",true).set("job",completed);
            }
            Files.writeString(Path.of(".local-notes/cv-api-twelve-sandbox-results.json"),json.writeValueAsString(report));
        } finally { Files.deleteIfExists(temp); }
    }
    private CvSuggestionWorker worker(CvSuggestionWorker.Sandbox sandbox) { return new CvSuggestionWorker(jobs,tx,sandbox,1); }
    private void runStandaloneWorker(Path temporary) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        var command=new ProcessBuilder(java,"-Xmx256m","-Dlogback.configurationFile="+Path.of("devops/cv-extraction/logback.xml").toAbsolutePath(),
                "-Dloader.main=vn.ttcs.recruitment.cvsuggestion.CvSuggestionWorkerCommand","-cp",
                System.getProperty("test.cv-sandbox.jar"),"org.springframework.boot.loader.launch.PropertiesLauncher");
        var env=command.environment();
        env.put("CV_WORKER_DB_URL",System.getProperty("test.database.url")+"?currentSchema="+jdbc.queryForObject("SELECT current_schema()",String.class));
        env.put("CV_WORKER_DB_USER","postgres");env.put("CV_WORKER_DB_PASSWORD","task325-test-only");
        env.put("CV_WORKER_PYTHON",System.getProperty("test.cv-sandbox.python"));
        env.put("CV_WORKER_RUNNER",Path.of("devops/cv-extraction/run.py").toAbsolutePath().toString());
        env.put("CV_WORKER_IMAGE",System.getProperty("test.cv-sandbox.image"));env.put("CV_WORKER_TEMP",temporary.toAbsolutePath().toString());
        env.put("CV_WORKER_ONCE","true");
        command.redirectOutput(ProcessBuilder.Redirect.DISCARD);command.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process=command.start();
        try { assertThat(process.waitFor(160,TimeUnit.SECONDS)).isTrue();assertThat(process.exitValue()).isZero(); }
        finally { process.destroyForcibly(); }
    }
    private String url() { return path(app); }
    private String path(UUID id) { return "/api/v1/applications/"+id+"/cv-extractions"; }
}
