package vn.ttcs.recruitment.auth;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.application.*;
import vn.ttcs.recruitment.common.ApiException;
import vn.ttcs.recruitment.jobposting.JobPostingStatus;
import vn.ttcs.recruitment.requisition.RequisitionStatus;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "app.application-intake.enabled=true",
        "app.application-intake.max-concurrent=2", "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import({AuthIntegrationTest.DatabaseConfiguration.class, ApplicationIntakeIntegrationTest.Adapters.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationIntakeIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-14T03:00:00Z");
    private static final String BOUNDARY = "real-http-application-boundary";
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;
    @Autowired ObjectMapper json;
    @Autowired AuthIntegrationTest.MutableClock clock;
    @Autowired TestQuota quota;
    @Autowired TestWriter writer;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private UUID posting, requisition, source, account;
    private byte[] pdf;

    @BeforeEach void fixture() throws Exception {
        clock.set(NOW); quota.calls.set(0); quota.failure = null; writer.reset();
        for (String table : List.of("application_cvs", "job_applications", "job_posting_events", "job_posting_authors",
                "job_postings", "recruitment_requisitions", "recruitment_catalog_items", "departments", "positions", "user_accounts")) {
            jdbc.update("DELETE FROM " + table);
        }
        account = UUID.randomUUID(); UUID department = UUID.randomUUID(), position = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts(id,email,password_hash,full_name,enabled,created_at) VALUES (?,?,'test-only','HR',true,?)",
                account, "hr@example.test", Timestamp.from(NOW));
        jdbc.update("INSERT INTO departments(id,code,name,manager_user_id) VALUES (?,'IT','IT',?)", department, account);
        jdbc.update("INSERT INTO positions(id,code,name,level,salary_min,salary_max,created_at,updated_at) VALUES (?,'DEV','Developer','Junior',0,0,?,?)",
                position, Timestamp.from(NOW), Timestamp.from(NOW));
        source = catalog("CANDIDATE_SOURCE", true); UUID location = catalog("WORK_LOCATION", true), employment = catalog("EMPLOYMENT_TYPE", true);
        requisition = UUID.randomUUID(); posting = UUID.randomUUID();
        jdbc.update("INSERT INTO recruitment_requisitions(id,position_id,department_id,headcount,reason,status,created_by,created_at,updated_at) VALUES (?,?,?,1,'NEW_HEADCOUNT','APPROVED',?,?,?)",
                requisition, position, department, account, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO job_postings(id,requisition_id,status,revision,title,job_description,candidate_requirements,
                  work_location_id,employment_type_id,application_deadline,show_salary,created_by,created_at,updated_at,published_by,published_at)
                VALUES (?,?,'PUBLISHED',1,'Hiring developer','Description','Requirements',?,?,?,false,?,?,?,?,?)
                """, posting, requisition, location, employment, LocalDate.of(2026,12,31), account,
                Timestamp.from(NOW), Timestamp.from(NOW), account, Timestamp.from(NOW));
        pdf = pdf(0);
    }

    @Test void acceptsAnonymousPdfAndReturnsOnlyReceiptAfterCommit() throws Exception {
        var parts = fields(" Candidate@EXAMPLE.test ");
        parts.add(field("sourceId", source.toString())); parts.add(field("coverLetter", "Hello\nHR")); parts.add(file("cv.pdf", pdf));
        var response = submit(parts, false); assertThat(response.statusCode()).isEqualTo(201);
        var body = json.readTree(response.body()); UUID id = UUID.fromString(body.get("applicationId").asString());
        assertThat(body.get("status").asString()).isEqualTo("RECEIVED");
        assertThat(body.get("statusName").asString()).isEqualTo("Đã nhận");
        assertThat(body.get("receivedAt").asString()).isEqualTo(NOW.toString());
        assertThat(body.properties()).hasSize(6);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).doesNotContain("Candidate", "EXAMPLE", "Hello", "normalizedEmail", "content", "trackingHash");
        String code = body.get("trackingCode").asString(); assertThat(code).matches("[A-Za-z0-9_-]{43}");
        assertThat(body.get("trackingUrl").asString()).isEqualTo("https://portal.example.test/track#ma=" + code);
        assertThat(jdbc.queryForMap("SELECT normalized_email,phone,stage,source_id,cover_letter FROM job_applications WHERE id=?", id))
                .containsEntry("normalized_email", "candidate@example.test").containsEntry("phone", "+84123456789")
                .containsEntry("stage", "NEW").containsEntry("source_id", source).containsEntry("cover_letter", "Hello\nHR");
        assertThat(jdbc.queryForObject("SELECT content FROM application_cvs WHERE application_id=?", byte[].class, id)).isEqualTo(pdf);
        assertThat(jdbc.queryForObject("SELECT tracking_code_hash FROM job_applications WHERE id=?", byte[].class, id))
                .isEqualTo(MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(code)));
        assertThat(quota.calls.get()).isEqualTo(1);
    }

    @Test void acceptsDocxFileFirstWithNoOptionalFieldsAndStaleBearer() throws Exception {
        byte[] docx;
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Candidate CV"); document.write(output); docx = output.toByteArray();
        }
        var parts = fields("docx@example.test"); parts.addFirst(file("cv.docx", docx));
        var response = send("POST", path(), multipart(parts), "multipart/form-data; boundary=" + BOUNDARY,
                false, Map.of("Authorization", "Bearer expired.or.invalid", "X-Forwarded-Host", "evil.test"));
        assertThat(response.statusCode()).isEqualTo(201); assertThat(response.body()).doesNotContain("evil.test");
        assertThat(jdbc.queryForMap("SELECT source_id,cover_letter FROM job_applications")).containsEntry("source_id", null).containsEntry("cover_letter", null);
        assertThat(jdbc.queryForObject("SELECT content FROM application_cvs", byte[].class)).isEqualTo(docx);
    }

    @Test void exactTenMebibytePdfPassesTheLegacyFiveMebibyteServletLimit() throws Exception {
        int size = CvValidator.MAX_BYTES - 1000; byte[] large = pdf(size);
        for (int i=0; i<5 && large.length != CvValidator.MAX_BYTES; i++) { size += CvValidator.MAX_BYTES-large.length; large = pdf(size); }
        assertThat(large).hasSize(CvValidator.MAX_BYTES);
        var parts = fields("large@example.test"); parts.add(file("cv.pdf", large));
        assertThat(submit(parts, true).statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT size_bytes FROM application_cvs", Integer.class)).isEqualTo(CvValidator.MAX_BYTES);
    }

    @Test void overTenMebibytesIsRejectedForKnownLengthAndChunkedBodies() throws Exception {
        var parts = fields("large@example.test"); parts.add(file("cv.pdf", new byte[CvValidator.MAX_BYTES+1]));
        for (boolean chunked : List.of(false,true)) assertError(submit(parts,chunked),413,"APPLICATION_CV_TOO_LARGE");
        assertEmpty(); assertThat(quota.calls.get()).isEqualTo(2);
    }

    @Test void wholeRequestLimitRejectsBeforeReadingKnownLength() throws Exception {
        assertError(send("POST", path(),new byte[11*1024*1024+1],"multipart/form-data; boundary="+BOUNDARY,false,Map.of()),413,"APPLICATION_CV_TOO_LARGE");
        assertEmpty(); assertThat(quota.calls.get()).isEqualTo(1);
    }

    @Test void chunkedEpilogueCannotBypassTheWholeRequestLimit() throws Exception {
        byte[] oversized = Arrays.copyOf(multipart(valid()), 11*1024*1024+1);
        assertError(send("POST",path(),oversized,"multipart/form-data; boundary="+BOUNDARY,true,Map.of()),413,"APPLICATION_CV_TOO_LARGE");
        assertEmpty();
    }

    @Test void oversizedPartHeadersAndMalformedUtf8AreRejectedOverRealHttp() throws Exception {
        var parts=valid(); parts.set(3,file("x".repeat(2200)+".pdf",pdf));
        assertThat(submit(parts,false).statusCode()).isEqualTo(400); assertEmpty();
        parts=valid(); parts.set(0,new Part("fullName",null,new byte[]{(byte)0xC3,0x28}));
        assertError(submit(parts,false),400,"INVALID_MULTIPART"); assertEmpty();
    }

    @Test void duplicateMappingFromTheFutureAdapterKeepsItsPublicErrorContract() throws Exception {
        writer.failure=new ApiException(HttpStatus.CONFLICT,"APPLICATION_ALREADY_SUBMITTED","Email này đã nộp cho vị trí.");
        assertError(submit(valid(),false),409,"APPLICATION_ALREADY_SUBMITTED"); assertEmpty();
    }

    @ParameterizedTest @EnumSource(value=RequisitionStatus.class,names="APPROVED",mode=EnumSource.Mode.EXCLUDE)
    void noOtherRequisitionStatusReceivesApplications(RequisitionStatus status) throws Exception {
        jdbc.update("UPDATE recruitment_requisitions SET status=? WHERE id=?",status.name(),requisition);
        assertError(submit(valid(),false),404,"JOB_POSTING_NOT_FOUND"); assertEmpty();
    }

    @ParameterizedTest @EnumSource(value=JobPostingStatus.class,names="PUBLISHED",mode=EnumSource.Mode.EXCLUDE)
    void noOtherPostingStatusReceivesApplications(JobPostingStatus status) throws Exception {
        if (status == JobPostingStatus.CLOSED || status == JobPostingStatus.UNPUBLISHED || status == JobPostingStatus.EXPIRED) {
            jdbc.update("UPDATE job_postings SET status=? WHERE id=?", status.name(), posting);
        } else {
            jdbc.update("UPDATE job_postings SET status=?,published_by=null,published_at=null WHERE id=?", status.name(), posting);
        }
        assertError(submit(valid(),false),404,"JOB_POSTING_NOT_FOUND"); assertEmpty();
    }

    @Test void deadlineUsesTheEntireVietnameseDay() throws Exception {
        jdbc.update("UPDATE job_postings SET application_deadline=? WHERE id=?",LocalDate.of(2026,10,14),posting);
        clock.set(Instant.parse("2026-10-14T16:59:59Z")); assertThat(submit(valid(),false).statusCode()).isEqualTo(201);
        clock.set(Instant.parse("2026-10-14T17:00:00Z")); assertError(submit(valid(),false),404,"JOB_POSTING_NOT_FOUND");
        assertThat(count()).isEqualTo(1);
    }

    @Test void sourcesArePublicActiveIdAndNameOnlyAndIndependentOfIntake() throws Exception {
        catalog("CANDIDATE_SOURCE", false);
        var response=send("GET","/api/v1/public/application-sources",new byte[0],"application/json",false,Map.of("Authorization","Bearer expired"));
        assertThat(response.statusCode()).isEqualTo(200); var body=json.readTree(response.body());
        assertThat(body.size()).isEqualTo(1); assertThat(body.get(0).properties()).hasSize(2);
        assertThat(body.get(0).get("id").asString()).isEqualTo(source.toString());
        assertThat(body.get(0).get("name").asString()).isEqualTo("Visible source");
        assertThat(response.body()).doesNotContain("code","active","sortOrder","catalogType");
    }

    @ParameterizedTest @ValueSource(strings={"inactive","wrong-type","missing"})
    void sourceMustBeAnActiveCandidateSource(String kind) throws Exception {
        UUID id = kind.equals("missing")?UUID.randomUUID():source;
        if(kind.equals("inactive"))jdbc.update("UPDATE recruitment_catalog_items SET active=false WHERE id=?",source);
        if(kind.equals("wrong-type"))id=jdbc.queryForObject("SELECT id FROM recruitment_catalog_items WHERE catalog_type='WORK_LOCATION'",UUID.class);
        var parts=valid(); parts.add(field("sourceId",id.toString())); assertError(submit(parts,false),400,"VALIDATION_ERROR"); assertEmpty();
    }

    @ParameterizedTest @ValueSource(strings={"cv.exe","cv.docx","empty","corrupt"})
    void invalidCvCannotCreateAnApplication(String name) throws Exception {
        var parts=fields("private-candidate@example.test"); parts.add(file(name.contains(".")?name:"cv.pdf",
                name.equals("empty")?new byte[0]:name.equals("corrupt")?"%PDF-1.7\nbad".getBytes(StandardCharsets.US_ASCII):pdf));
        var response=submit(parts,false); assertThat(response.statusCode()).isIn(400,415);
        assertThat(response.body()).doesNotContain("private-candidate", "Exception", "cv.exe"); assertEmpty();
    }

    @Test void malformedIdMediaQueryAndBodyStillConsumeTheQuotaHook() throws Exception {
        assertError(send("POST","/api/v1/public/job-postings/1-1-1-1-1/applications",new byte[0],"application/json",false,Map.of()),400,"VALIDATION_ERROR");
        assertError(send("POST",path(),new byte[0],"application/json",false,Map.of()),415,"APPLICATION_FORM_TYPE_UNSUPPORTED");
        assertError(send("POST",path()+"?stage=HIRED",new byte[0],"multipart/form-data",false,Map.of()),400,"VALIDATION_ERROR");
        assertError(send("POST",path(),new byte[0],"multipart/form-data",false,Map.of()),400,"INVALID_MULTIPART");
        assertThat(quota.calls.get()).isEqualTo(4); assertEmpty();
    }

    @Test void quotaRefusalAndDependencyFailureAreFailClosedBeforeBodyParsing() throws Exception {
        quota.failure=new ApiException(HttpStatus.TOO_MANY_REQUESTS,"APPLICATION_RATE_LIMITED","Vui lòng thử lại sau.");
        assertError(send("POST",path(),new byte[]{1},"broken",false,Map.of()),429,"APPLICATION_RATE_LIMITED");
        quota.failure=new IllegalStateException("private SQL detail");
        assertError(submit(valid(),false),503,"APPLICATION_INTAKE_UNAVAILABLE"); assertEmpty();
    }

    @Test void receiptFailureRollsBackApplicationAndCvAndReturnsNoSqlDetails() throws Exception {
        writer.failAfterStore=true; assertError(submit(valid(),false),503,"APPLICATION_SUBMISSION_UNAVAILABLE"); assertEmpty();
        writer.failAfterStore=false;
        assertThat(submit(valid(),false).statusCode()).isEqualTo(201);
        assertThat(count()).isEqualTo(1);
    }

    @Test void duplicateSubmissionReturnsAConflictWithoutLeakingPersonalDataOrChangingTheOriginal() throws Exception {
        assertThat(submit(valid(),false).statusCode()).isEqualTo(201);
        var original = jdbc.queryForMap("SELECT id,tracking_code_hash,received_at FROM job_applications");
        var parts = valid();
        parts.set(0, field("fullName", "Different candidate name"));
        parts.set(2, field("phone", "0987654321"));
        parts.set(3, file("changed.pdf", pdf(128)));
        var duplicate = submit(parts,false);
        assertError(duplicate,409,"APPLICATION_ALREADY_SUBMITTED"); assertThat(count()).isEqualTo(1);
        assertThat(json.readTree(duplicate.body()).get("fieldErrors").get("email").asString())
                .isEqualTo("Email này đã nộp hồ sơ cho vị trí tuyển dụng này.");
        assertThat(json.readTree(duplicate.body()).properties()).hasSize(3);
        assertThat(duplicate.body()).doesNotContain("applicationId", "trackingCode", "trackingUrl", "tracking_code_hash");
        var after = jdbc.queryForMap("SELECT id,tracking_code_hash,received_at FROM job_applications");
        assertThat(after.get("id")).isEqualTo(original.get("id"));
        assertThat((byte[]) after.get("tracking_code_hash")).isEqualTo((byte[]) original.get("tracking_code_hash"));
        assertThat(after.get("received_at")).isEqualTo(original.get("received_at"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_cvs",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content FROM application_cvs", byte[].class)).isEqualTo(pdf);
        assertThat(jdbc.queryForObject("SELECT full_name FROM job_applications", String.class)).isEqualTo("Nguyễn Văn A");
        assertThat(jdbc.queryForObject("SELECT phone FROM job_applications", String.class)).isEqualTo("+84123456789");
    }

    @ParameterizedTest
    @ValueSource(strings={" A@EXAMPLE.TEST ", "\u00a0A@example.test\u00a0", "\u200bA@example.test\u200b", "\tA@example.test\r\n"})
    void normalizedEmailVariantsReturnTheSameConflict(String email) throws Exception {
        assertThat(submit(valid(),false).statusCode()).isEqualTo(201);
        assertError(submit(withEmail(email),false),409,"APPLICATION_ALREADY_SUBMITTED");
        assertThat(count()).isEqualTo(1);
        assertThat(quota.calls.get()).isEqualTo(2);
    }

    @ParameterizedTest @EnumSource(ApplicationStage.class)
    void applicationsCannotBeResubmittedInAnyStage(ApplicationStage stage) throws Exception {
        assertThat(submit(valid(),false).statusCode()).isEqualTo(201);
        jdbc.update("UPDATE job_applications SET stage=?",stage.name());
        assertError(submit(valid(),false),409,"APPLICATION_ALREADY_SUBMITTED");
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT stage FROM job_applications",String.class)).isEqualTo(stage.name());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_cvs",Integer.class)).isEqualTo(1);
    }

    @Test void lowercaseUsesRootLocaleAndDoesNotCollapseDotsOrPlusTags() throws Exception {
        Locale original=Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(submit(withEmail("i@example.test"),false).statusCode()).isEqualTo(201);
            assertError(submit(withEmail(" I@EXAMPLE.TEST "),false),409,"APPLICATION_ALREADY_SUBMITTED");
            assertThat(submit(withEmail("i+tag@example.test"),false).statusCode()).isEqualTo(201);
            assertThat(submit(withEmail("i.tag@example.test"),false).statusCode()).isEqualTo(201);
            assertThat(count()).isEqualTo(3);
        } finally { Locale.setDefault(original); }
    }

    @Test void theSameEmailCanApplyToAnotherRequisitionForTheSamePosition() throws Exception {
        assertThat(submit(valid(),false).statusCode()).isEqualTo(201);
        UUID otherRequisition=UUID.randomUUID(), otherPosting=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO recruitment_requisitions(id,position_id,department_id,headcount,reason,status,created_by,created_at,updated_at)
                SELECT ?,position_id,department_id,headcount,reason,status,created_by,created_at,updated_at
                FROM recruitment_requisitions WHERE id=?
                """,otherRequisition,requisition);
        jdbc.update("""
                INSERT INTO job_postings(id,requisition_id,status,revision,title,job_description,candidate_requirements,
                  work_location_id,employment_type_id,application_deadline,show_salary,created_by,created_at,updated_at,published_by,published_at)
                SELECT ?,?,status,revision,title,job_description,candidate_requirements,work_location_id,employment_type_id,
                  application_deadline,show_salary,created_by,created_at,updated_at,published_by,published_at FROM job_postings WHERE id=?
                """,otherPosting,otherRequisition,posting);
        var response=send("POST","/api/v1/public/job-postings/"+otherPosting+"/applications",multipart(valid()),
                "multipart/form-data; boundary="+BOUNDARY,false,Map.of());
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(count()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_cvs",Integer.class)).isEqualTo(2);
    }

    @Test void changingThePostingTitleDoesNotReleaseAnExistingEmail() throws Exception {
        assertThat(submit(valid(),false).statusCode()).isEqualTo(201);
        jdbc.update("UPDATE job_postings SET title='Renamed posting' WHERE id=?",posting);
        assertError(submit(valid(),false),409,"APPLICATION_ALREADY_SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT posting_title FROM job_applications",String.class)).isEqualTo("Hiring developer");
    }

    @Test void trackingHashCollisionForAnotherEmailRemainsAGenericStorageFailure() throws Exception {
        writer.tokenOverride=new byte[32];
        assertThat(submit(valid(),false).statusCode()).isEqualTo(201);
        assertError(submit(withEmail("b@example.test"),false),503,"APPLICATION_SUBMISSION_UNAVAILABLE");
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_cvs",Integer.class)).isEqualTo(1);
    }

    @Test void aCvDatabaseConstraintFailureIsNotAnEmailConflictAndReleasesTheReservation() throws Exception {
        jdbc.execute("CREATE FUNCTION invalidate_cv_size() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN NEW.size_bytes:=1; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER invalidate_cv_size BEFORE INSERT ON application_cvs FOR EACH ROW EXECUTE FUNCTION invalidate_cv_size()");
        try {
            assertError(submit(valid(),false),503,"APPLICATION_SUBMISSION_UNAVAILABLE");
            assertEmpty();
        } finally {
            jdbc.execute("DROP TRIGGER invalidate_cv_size ON application_cvs");
            jdbc.execute("DROP FUNCTION invalidate_cv_size()");
        }
        assertThat(submit(valid(),false).statusCode()).isEqualTo(201);
        assertThat(count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_cvs",Integer.class)).isEqualTo(1);
    }

    @Test void twoConcurrentNormalizedDuplicatesReturnOneReceiptAndOneConflict() throws Exception {
        writer.entered=new CountDownLatch(2); writer.release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            try {
                var first=executor.submit(()->submit(withEmail("a@example.test"),false));
                var second=executor.submit(()->submit(withEmail(" A@EXAMPLE.TEST "),false));
                assertThat(writer.entered.await(10,TimeUnit.SECONDS)).isTrue();
                writer.release.countDown();
                var responses=List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS));
                assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201,409);
                var conflict=responses.stream().filter(response->response.statusCode()==409).findFirst().orElseThrow();
                assertError(conflict,409,"APPLICATION_ALREADY_SUBMITTED");
                var accepted=responses.stream().filter(response->response.statusCode()==201).findFirst().orElseThrow();
                assertThat(jdbc.queryForObject("SELECT id FROM job_applications",UUID.class))
                        .isEqualTo(UUID.fromString(json.readTree(accepted.body()).get("applicationId").asString()));
                assertThat(count()).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM application_cvs",Integer.class)).isEqualTo(1);
                assertThat(quota.calls.get()).isEqualTo(2);
            } finally { writer.release.countDown(); }
        }
    }

    @Test void closureAfterPreflightIsRecheckedByLockedStorage() throws Exception {
        writer.entered=new CountDownLatch(1); writer.release=new CountDownLatch(1);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var pending=executor.submit(()->submit(valid(),false)); assertThat(writer.entered.await(10,TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE recruitment_requisitions SET status='PAUSED' WHERE id=?",requisition);
            writer.release.countDown(); assertError(pending.get(20,TimeUnit.SECONDS),404,"JOB_POSTING_NOT_FOUND"); assertEmpty();
        } finally { writer.release.countDown(); }
    }

    @Test void concurrencyBudgetRejectsTheThirdRequestAndReleasesPermits() throws Exception {
        writer.entered=new CountDownLatch(2); writer.release=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var a=executor.submit(()->submit(withEmail("a@example.test"),false));
            var b=executor.submit(()->submit(withEmail("b@example.test"),false));
            assertThat(writer.entered.await(10,TimeUnit.SECONDS)).isTrue();
            var third=submit(withEmail("c@example.test"),false); assertError(third,503,"APPLICATION_INTAKE_BUSY");
            assertThat(third.headers().firstValue("Retry-After")).contains("1");
            writer.release.countDown(); assertThat(a.get(20,TimeUnit.SECONDS).statusCode()).isEqualTo(201);
            assertThat(b.get(20,TimeUnit.SECONDS).statusCode()).isEqualTo(201);
            writer.entered=null; writer.release=null;
            assertThat(submit(withEmail("c@example.test"),false).statusCode()).isEqualTo(201);
            assertThat(count()).isEqualTo(3); assertThat(quota.calls.get()).isEqualTo(4);
        } finally { if(writer.release!=null)writer.release.countDown(); }
    }

    @ParameterizedTest @ValueSource(strings={"GET","PUT","DELETE"})
    void unrelatedMethodsStayProtected(String method) throws Exception {
        var response=send(method,path(),new byte[0],"application/json",false,Map.of());
        assertThat(response.statusCode()).isEqualTo(401); assertThat(quota.calls.get()).isZero(); assertEmpty();
    }

    @Test void corsPreflightDoesNotCountAsASubmissionAndRejectsUnknownOrigins() throws Exception {
        var allowed=send("OPTIONS",path(),new byte[0],"application/json",false,Map.of("Origin","http://localhost:5173",
                "Access-Control-Request-Method","POST","Access-Control-Request-Headers","content-type"));
        assertThat(allowed.statusCode()).isEqualTo(200);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5173");
        var denied=send("POST",path(),new byte[0],"application/json",false,Map.of("Origin","https://evil.test"));
        assertThat(denied.statusCode()).isEqualTo(403); assertThat(quota.calls.get()).isZero(); assertEmpty();
    }

    private UUID catalog(String type,boolean active) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO recruitment_catalog_items(id,catalog_type,code,name,sort_order,active,created_at,updated_at) VALUES (?,?,?,'Visible source',0,?,?,?)",
                id,type,id.toString(),active,Timestamp.from(NOW),Timestamp.from(NOW)); return id;
    }
    private int count(){return jdbc.queryForObject("SELECT count(*) FROM job_applications",Integer.class);}
    private void assertEmpty(){assertThat(count()).isZero();assertThat(jdbc.queryForObject("SELECT count(*) FROM application_cvs",Integer.class)).isZero();}
    private void assertError(HttpResponse<String> response,int status,String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo(code);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).doesNotContain("Exception","SQL","a@example.test","private");
    }
    private String path(){return "/api/v1/public/job-postings/"+posting+"/applications";}
    private java.util.ArrayList<Part> fields(String email){return new java.util.ArrayList<>(List.of(field("fullName","Nguyễn Văn A"),field("email",email),field("phone","+84 (123) 456-789")));}
    private java.util.ArrayList<Part> valid(){return withEmail("a@example.test");}
    private java.util.ArrayList<Part> withEmail(String email){var parts=fields(email);parts.add(file("cv.pdf",pdf));return parts;}
    private static Part field(String name,String value){return new Part(name,null,value.getBytes(StandardCharsets.UTF_8));}
    private static Part file(String name,byte[] bytes){return new Part("cv",name,bytes);}
    private HttpResponse<String> submit(List<Part> parts,boolean chunked) throws Exception {return send("POST",path(),multipart(parts),"multipart/form-data; boundary="+BOUNDARY,chunked,Map.of());}
    private HttpResponse<String> send(String method,String path,byte[] bytes,String contentType,boolean chunked,Map<String,String> headers) throws Exception {
        var publisher=chunked?HttpRequest.BodyPublishers.ofInputStream(()->new ByteArrayInputStream(bytes)):HttpRequest.BodyPublishers.ofByteArray(bytes);
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+environment.getRequiredProperty("local.server.port")+path))
                .timeout(Duration.ofSeconds(25)).header("Content-Type",contentType).method(method,publisher);
        headers.forEach(request::header);return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static byte[] multipart(List<Part> parts) throws Exception {
        var output=new ByteArrayOutputStream();
        for(var part:parts){
            output.write(("--"+BOUNDARY+"\r\nContent-Disposition: form-data; name=\""+part.name()+"\""+(part.filename()==null?"":"; filename=\""+part.filename()+"\"")+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(part.bytes());output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        output.write(("--"+BOUNDARY+"--\r\n").getBytes(StandardCharsets.US_ASCII));return output.toByteArray();
    }
    private static byte[] pdf(int payload) throws Exception {
        try(var document=new PDDocument();var output=new ByteArrayOutputStream()){
            var page=new PDPage();document.addPage(page);
            if(payload>0){var contents=new PDStream(document);page.setContents(contents);try(var stream=contents.createOutputStream()){
                byte[] spaces=new byte[8192];Arrays.fill(spaces,(byte)' ');for(int left=payload;left>0;left-=Math.min(left,spaces.length))stream.write(spaces,0,Math.min(left,spaces.length));}}
            document.save(output);return output.toByteArray();
        }
    }
    private record Part(String name,String filename,byte[] bytes){}

    // These adapters exercise the HTTP/storage boundary only. They implement neither IP windows nor mail
    // delivery and live exclusively in src/test: production must never accept without the real adapters.
    @TestConfiguration(proxyBeanMethods=false) static class Adapters {
        @Bean @org.springframework.context.annotation.Primary TestQuota quota(){return new TestQuota();}
        @Bean TestWriter writer(ApplicationStorageService storage, PlatformTransactionManager manager){return new TestWriter(storage, manager);}
    }
    static class TestQuota implements ApplicationAttemptQuota {
        final AtomicInteger calls=new AtomicInteger(); volatile RuntimeException failure;
        public void checkAndRecord(jakarta.servlet.http.HttpServletRequest request){calls.incrementAndGet();if(failure!=null)throw failure;}
    }
    static class TestWriter implements ApplicationReceiptWriter {
        final ApplicationStorageService storage; final TransactionTemplate transaction; volatile boolean failAfterStore;
        volatile RuntimeException failure;
        volatile byte[] tokenOverride;
        volatile CountDownLatch entered,release;
        TestWriter(ApplicationStorageService storage, PlatformTransactionManager manager){
            this.storage=storage; this.transaction=new TransactionTemplate(manager);
            this.transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        }
        void reset(){failAfterStore=false;failure=null;tokenOverride=null;entered=null;release=null;}
        @Override
        public ApplicationReceipt accept(UUID id,ApplicationSubmission submission,ValidatedCv cv) {
            return transaction.execute(status -> {
            try{
                if(failure!=null)throw failure;
                if(entered!=null)entered.countDown();if(release!=null&&!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Test gate timed out");
                byte[] token=tokenOverride==null?new byte[32]:tokenOverride.clone();
                if(tokenOverride==null)new SecureRandom().nextBytes(token);
                var saved=storage.store(id,submission,cv,MessageDigest.getInstance("SHA-256").digest(token));
                if(failAfterStore)throw new IllegalStateException("private SQL detail");
                String code=Base64.getUrlEncoder().withoutPadding().encodeToString(token);
                return new ApplicationReceipt(saved.id(),saved.receivedAt(),code,"https://portal.example.test/track#ma="+code);
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            catch(java.security.NoSuchAlgorithmException exception){throw new IllegalStateException(exception);}
            });
        }
    }
}
