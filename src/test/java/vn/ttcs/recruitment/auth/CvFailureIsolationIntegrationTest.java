package vn.ttcs.recruitment.auth;

import java.util.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import vn.ttcs.recruitment.cvsuggestion.*;
import vn.ttcs.recruitment.cvextraction.CvExtractionResult;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.config.import=","app.bootstrap.enabled=false","logging.level.root=WARN","debug=false",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","app.cv-extraction.enabled=true",
        "app.cv-extraction.max-pending=2","app.application-intake.enabled=true",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.poll-delay-ms=3600000","spring.mail.host=127.0.0.1","spring.mail.port=1"})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CvFailureIsolationIntegrationTest extends CvApplicationTestSupport {
    @BeforeEach void resetQuota() { jdbc.update("DELETE FROM application_ip_quotas"); }
    @ParameterizedTest(name="submission is independent: {0}")
    @ValueSource(strings={"DISABLED","NO_WORKER","QUEUE_FULL","OCR_FAILED","OCR_TIMEOUT","NO_DATA",
            "LOST_WORKER","RETRY_LIMIT","DOCX","QUEUE_UNAVAILABLE","CONTACT_TABLE_UNAVAILABLE","QUOTA_STILL_ENFORCED"})
    void intakeRemainsAtomicAndKeepsBusinessGuards(String fault) throws Exception {
        String route="/api/v1/applications/"+app+"/cv-extractions";
        boolean queueMissing=false,contactMissing=false,disabled=false;
        byte[] incoming=pdf;String filename="cv.pdf",type="application/pdf";
        try {
            if (fault.equals("DISABLED")) {
                org.springframework.test.util.ReflectionTestUtils.setField(service,"enabled",false);disabled=true;
            } else {
                api.expect(api.request("POST",route,null,recruiterToken),202);
                switch(fault) {
                    case "QUEUE_FULL" -> api.expect(api.request("POST","/api/v1/applications/"+application("second@example.test")+"/cv-extractions",null,recruiterToken),202);
                    case "OCR_FAILED","OCR_TIMEOUT","RETRY_LIMIT" -> {
                        var worker=new CvSuggestionWorker(jobs,tx,bytes->{
                            if (fault.equals("OCR_TIMEOUT")) throw new java.util.concurrent.TimeoutException("private OCR timeout");
                            throw new java.io.IOException("private OCR failure");
                        },1);
                        assertThat(worker.processNext()).isTrue();
                        if (fault.equals("RETRY_LIMIT")) {
                            for (int i=0;i<3;i++) { api.expect(api.request("POST",route,null,recruiterToken),202);assertThat(worker.processNext()).isTrue(); }
                            api.expect(api.request("POST",route,null,recruiterToken),429);
                        }
                    }
                    case "NO_DATA" -> assertThat(new CvSuggestionWorker(jobs,tx,bytes->new CvExtractionResult(CvExtractionResult.VERSION,
                            CvExtractionResult.Outcome.NO_DATA,1,1,new CvExtractionResult.Suggestions(List.of(),List.of(),List.of()),List.of()),1).processNext()).isTrue();
                    case "LOST_WORKER" -> { jobs.claim(tx,1).orElseThrow();jdbc.update("UPDATE cv_extraction_attempts SET lease_until=clock_timestamp()-interval '1 second'");jobs.expire(tx); }
                    case "DOCX" -> {
                        try (var doc=new org.apache.poi.xwpf.usermodel.XWPFDocument();var bytes=new java.io.ByteArrayOutputStream()) {
                            doc.createParagraph().createRun().setText("Manual candidate");doc.write(bytes);incoming=bytes.toByteArray();
                        }
                        filename="cv.docx";type="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                    }
                    case "QUEUE_UNAVAILABLE" -> { jdbc.execute("ALTER TABLE cv_extraction_jobs RENAME TO cv_extraction_jobs_offline");queueMissing=true; }
                    case "CONTACT_TABLE_UNAVAILABLE" -> { jdbc.execute("ALTER TABLE application_confirmed_contacts RENAME TO application_confirmed_contacts_offline");contactMissing=true; }
                    default -> { /* Queue stays pending without a worker. */ }
                }
            }
            long existing=jdbc.queryForObject("SELECT count(*) FROM job_applications",Long.class);
            var accepted=submit("manual@example.test",incoming,filename,type);
            var body=api.expect(accepted,201);
            UUID saved=jdbc.queryForObject("SELECT id FROM job_applications WHERE email='manual@example.test'",UUID.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM job_applications",Long.class)).isEqualTo(existing+1);
            assertThat(jdbc.queryForObject("SELECT content FROM application_cvs WHERE application_id=?",byte[].class,saved)).isEqualTo(incoming);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM application_email_outbox WHERE application_id=?",Integer.class,saved)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT full_name FROM job_applications WHERE id=?",String.class,saved)).isEqualTo("Manual Candidate");
            assertThat(body.path("trackingCode").asText()).isNotBlank();
            assertThat(submit("manual@example.test",incoming,filename,type).statusCode()).isEqualTo(409);
            if (fault.equals("QUOTA_STILL_ENFORCED")) {
                for (int i=0;i<3;i++) assertThat(submit("manual@example.test",incoming,filename,type).statusCode()).isEqualTo(409);
                assertThat(submit("manual@example.test",incoming,filename,type).statusCode()).isEqualTo(429);
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM application_email_outbox WHERE application_id=?",Integer.class,saved)).isEqualTo(1);
        } finally {
            if (queueMissing) jdbc.execute("ALTER TABLE cv_extraction_jobs_offline RENAME TO cv_extraction_jobs");
            if (contactMissing) jdbc.execute("ALTER TABLE application_confirmed_contacts_offline RENAME TO application_confirmed_contacts");
            if (disabled) org.springframework.test.util.ReflectionTestUtils.setField(service,"enabled",true);
        }
    }
    private HttpResponse<String> submit(String email,byte[] content,String filename,String type) throws Exception {
        var bytes=new java.io.ByteArrayOutputStream();
        for (var field : Map.of("fullName","Manual Candidate","email",email,"phone","0912345678").entrySet())
            bytes.write(("--isolation-boundary\r\nContent-Disposition: form-data; name=\""+field.getKey()+"\"\r\n\r\n"+field.getValue()+"\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write(("--isolation-boundary\r\nContent-Disposition: form-data; name=\"cv\"; filename=\""+filename+"\"\r\nContent-Type: "+type+"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write(content);bytes.write("\r\n--isolation-boundary--\r\n".getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+environment.getRequiredProperty("local.server.port")+
                "/api/v1/public/job-postings/"+posting+"/applications")).timeout(java.time.Duration.ofSeconds(15))
                .header("Content-Type","multipart/form-data; boundary=isolation-boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build();
        return HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
    }
}
