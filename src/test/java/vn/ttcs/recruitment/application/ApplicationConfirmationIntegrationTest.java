package vn.ttcs.recruitment.application;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.auth.AuthIntegrationTest;
import vn.ttcs.recruitment.auth.LocalSmtpServer;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.config.import=","app.bootstrap.enabled=false","debug=false","logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","app.application-intake.enabled=true",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000","app.application-intake.cleanup-delay-ms=3600000"})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationConfirmationIntegrationTest {
    private static final LocalSmtpServer SMTP=startSmtp();
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired Environment environment;
    @Autowired ObjectMapper json;
    @Autowired Clock clock;
    @Autowired ApplicationOutboxRepository outbox;
    @Autowired ApplicationOutboxCrypto crypto;
    @Autowired ApplicationConfirmationWorker worker;
    @Autowired ApplicationConfirmationMailSender mail;
    @Autowired ApplicationConfirmationSettings settings;
    @Autowired PostgresApplicationReceiptWriter writer;
    @Autowired ApplicationStorageService storage;
    @Autowired CvValidator validator;
    @Autowired PlatformTransactionManager manager;
    @Autowired JavaMailSender sender;
    private UUID posting;
    private byte[] pdf;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @DynamicPropertySource static void smtp(DynamicPropertyRegistry values) {
        values.add("spring.mail.host",() -> "127.0.0.1");values.add("spring.mail.port",SMTP::port);
        values.add("spring.mail.properties.mail.smtp.auth",() -> false);
        values.add("spring.mail.properties.mail.smtp.starttls.enable",() -> false);
        values.add("spring.mail.properties.mail.smtp.starttls.required",() -> false);
        values.add("spring.mail.properties.mail.smtp.ssl.enable",() -> false);
        values.add("app.password-reset.mail-from",() -> "legacy-from@example.test");
    }
    @AfterAll static void closeSmtp() throws IOException {SMTP.close();}
    private static LocalSmtpServer startSmtp(){try{return new LocalSmtpServer();}catch(IOException exception){throw new IllegalStateException(exception);}}

    @BeforeEach void fixture() throws Exception {
        SMTP.reset();
        for(String table:List.of("application_ip_quotas","application_cvs","job_applications","job_posting_events",
                "job_posting_authors","job_postings","recruitment_requisitions","recruitment_catalog_items","departments","positions","user_accounts"))jdbc.update("DELETE FROM "+table);
        Instant now=clock.instant();UUID user=UUID.randomUUID(),department=UUID.randomUUID(),position=UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts(id,email,password_hash,full_name,enabled,created_at) VALUES (?,?,'test-only','HR',true,?)",user,user+"@example.test",Timestamp.from(now));
        jdbc.update("INSERT INTO departments(id,code,name,manager_user_id) VALUES (?,'IT','IT',?)",department,user);
        jdbc.update("INSERT INTO positions(id,code,name,level,salary_min,salary_max,created_at,updated_at) VALUES (?,'DEV','Developer','Junior',0,0,?,?)",position,Timestamp.from(now),Timestamp.from(now));
        UUID location=catalog("WORK_LOCATION"),employment=catalog("EMPLOYMENT_TYPE"),req=UUID.randomUUID();posting=UUID.randomUUID();
        jdbc.update("INSERT INTO recruitment_requisitions(id,position_id,department_id,headcount,reason,status,created_by,created_at,updated_at) VALUES (?,?,?,1,'NEW_HEADCOUNT','APPROVED',?,?,?)",req,position,department,user,Timestamp.from(now),Timestamp.from(now));
        jdbc.update("""
                INSERT INTO job_postings(id,requisition_id,status,revision,title,job_description,candidate_requirements,
                    work_location_id,employment_type_id,application_deadline,show_salary,created_by,created_at,updated_at,published_by,published_at)
                VALUES (?,?,'PUBLISHED',1,'Lập trình viên','Description','Requirements',?,?,'2026-12-31',false,?,?,?,?,?)
                """,posting,req,location,employment,user,Timestamp.from(now),Timestamp.from(now),user,Timestamp.from(now));
        try(var doc=new PDDocument();var bytes=new ByteArrayOutputStream()){doc.addPage(new PDPage());doc.save(bytes);pdf=bytes.toByteArray();}
    }
    private UUID catalog(String type) {
        UUID id=UUID.randomUUID();Instant now=clock.instant();
        jdbc.update("INSERT INTO recruitment_catalog_items(id,catalog_type,code,name,sort_order,active,created_at,updated_at) VALUES (?,?,?,'Visible',0,true,?,?)",id,type,id.toString(),Timestamp.from(now),Timestamp.from(now));return id;
    }
    private String status(){return jdbc.queryForObject("SELECT status FROM application_email_outbox",String.class);}
    private int attempts(){return jdbc.queryForObject("SELECT attempts FROM application_email_outbox",Integer.class);}
    private int count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class);}
    private void makeDue(){jdbc.update("UPDATE application_email_outbox SET next_attempt_at=clock_timestamp()-INTERVAL '1 second'");}
    private void expireLease(){jdbc.update("UPDATE application_email_outbox SET lease_until=clock_timestamp()-INTERVAL '1 second'");}
    private HttpResponse<String> submit(String email) throws Exception {
        var bytes=new ByteArrayOutputStream();
        for(var entry:Map.of("fullName","Nguyễn Văn A","email",email,"phone","0123456789").entrySet())bytes.write(("--ack-test\r\nContent-Disposition: form-data; name=\""+entry.getKey()+"\"\r\n\r\n"+entry.getValue()+"\r\n").getBytes(StandardCharsets.UTF_8));
        bytes.write("--ack-test\r\nContent-Disposition: form-data; name=\"cv\"; filename=\"cv.pdf\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        bytes.write(pdf);bytes.write("\r\n--ack-test--\r\n".getBytes(StandardCharsets.UTF_8));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+environment.getRequiredProperty("local.server.port")+
                "/api/v1/public/job-postings/"+posting+"/applications"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type","multipart/form-data; boundary=ack-test")
                .header("Authorization","Bearer stale").header("X-Forwarded-Host","evil.test")
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray())).build();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }
    private tools.jackson.databind.JsonNode pending() throws Exception {
        var response=submit("candidate@example.test");assertThat(response.statusCode()).as(response.body()).isEqualTo(201);return json.readTree(response.body());
    }

    @Test void commitsApplicationCvAndEncryptedOutboxBefore201WithoutSendingSmtp() throws Exception {
        var receipt=pending();String code=receipt.get("trackingCode").asString();
        assertThat(code).matches("[A-Za-z0-9_-]{43}");assertThat(receipt.properties()).hasSize(6);
        assertThat(receipt.get("trackingUrl").asString()).isEqualTo("https://portal.example.test/tra-cuu#ma="+code);
        assertThat(receipt.toString()).doesNotContain("evil.test","candidate@example.test");
        assertThat(count("job_applications")).isEqualTo(1);assertThat(count("application_cvs")).isEqualTo(1);
        assertThat(count("application_email_outbox")).isEqualTo(1);assertThat(count("application_ip_attempts")).isEqualTo(1);
        assertThat(status()).isEqualTo("PENDING");assertThat(attempts()).isZero();assertThat(SMTP.messages()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT tracking_code_hash FROM job_applications",byte[].class))
                .isEqualTo(MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(code)));
        byte[] cipher=jdbc.queryForObject("SELECT ciphertext FROM application_email_outbox",byte[].class);
        assertThat(new String(cipher,StandardCharsets.ISO_8859_1)).doesNotContain(code,"candidate@example.test","Nguyễn Văn A");
        var row=outbox.claim().orElseThrow();assertThat(crypto.decrypt(row).trackingCode()).isEqualTo(code);
    }
    @Test void sendsPlainUtf8MailFromSharedLegacySettingAndClearsSensitivePayload() throws Exception {
        var receipt=pending();String messageId=jdbc.queryForObject("SELECT message_id FROM application_email_outbox",String.class);
        assertThat(worker.processNext()).isTrue();assertThat(worker.processNext()).isFalse();
        assertThat(SMTP.messages()).hasSize(1);var message=SMTP.messages().getFirst();
        assertThat(message.getSubject()).isEqualTo("Xác nhận nhận hồ sơ ứng tuyển — Hệ thống tuyển dụng nội bộ");
        assertThat(message.getFrom()[0].toString()).isEqualTo("legacy-from@example.test");assertThat(message.getMessageID()).isEqualTo(messageId);
        assertThat(message.getContentType()).containsIgnoringCase("text/plain").containsIgnoringCase("UTF-8");
        String time=DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("Asia/Ho_Chi_Minh")).format(clock.instant());
        assertThat(message.getContent().toString()).contains("Nguyễn Văn A","Lập trình viên",time,"giờ Việt Nam",
                receipt.get("trackingCode").asString(),receipt.get("trackingUrl").asString(),"hồ sơ đã được tiếp nhận")
                .doesNotContain("evil.test","Mật khẩu","cv.pdf");
        assertThat(status()).isEqualTo("SENT");assertThat(attempts()).isEqualTo(1);
        var row=jdbc.queryForMap("SELECT key_id,nonce,ciphertext,sent_at,lease_token,lease_until FROM application_email_outbox");
        for(String key:List.of("key_id","nonce","ciphertext","lease_token","lease_until"))assertThat(row.get(key)).isNull();
        assertThat(row.get("sent_at")).isNotNull();
    }
    @Test void duplicateCreatesNoAdditionalCodeCvOrOutbox() throws Exception {
        pending();byte[] original=jdbc.queryForObject("SELECT tracking_code_hash FROM job_applications",byte[].class);
        var response=submit(" CANDIDATE@EXAMPLE.TEST ");assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("APPLICATION_ALREADY_SUBMITTED").doesNotContain("trackingCode");
        assertThat(jdbc.queryForObject("SELECT tracking_code_hash FROM job_applications",byte[].class)).isEqualTo(original);
        assertThat(count("application_email_outbox")).isEqualTo(1);assertThat(count("application_cvs")).isEqualTo(1);
        assertThat(count("application_ip_attempts")).isEqualTo(2);assertThat(SMTP.messages()).isEmpty();
    }
    @Test void outboxInsertFailureRollsBackApplicationAndCvButKeepsQuota() throws Exception {
        jdbc.execute("CREATE FUNCTION fail_ack_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'private-ack-detail'; END $$");
        jdbc.execute("CREATE TRIGGER fail_ack_test BEFORE INSERT ON application_email_outbox FOR EACH ROW EXECUTE FUNCTION fail_ack_test()");
        try {
            var response=submit("candidate@example.test");assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("APPLICATION_SUBMISSION_UNAVAILABLE").doesNotContain("private-ack-detail","candidate","trackingCode");
            assertThat(count("job_applications")).isZero();assertThat(count("application_cvs")).isZero();assertThat(count("application_email_outbox")).isZero();
            assertThat(count("application_ip_attempts")).isEqualTo(1);assertThat(SMTP.messages()).isEmpty();
        } finally {jdbc.execute("DROP TRIGGER fail_ack_test ON application_email_outbox");jdbc.execute("DROP FUNCTION fail_ack_test()");}
        assertThat(submit("candidate@example.test").statusCode()).isEqualTo(201);
    }
    @Test void cvInsertFailureCreatesNoApplicationOrOutbox() throws Exception {
        jdbc.execute("CREATE FUNCTION fail_ack_cv_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN NEW.size_bytes:=1;RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_ack_cv_test BEFORE INSERT ON application_cvs FOR EACH ROW EXECUTE FUNCTION fail_ack_cv_test()");
        try {
            assertThat(submit("candidate@example.test").statusCode()).isEqualTo(503);
            assertThat(count("job_applications")).isZero();assertThat(count("application_email_outbox")).isZero();assertThat(SMTP.messages()).isEmpty();
        } finally {jdbc.execute("DROP TRIGGER fail_ack_cv_test ON application_cvs");jdbc.execute("DROP FUNCTION fail_ack_cv_test()");}
    }
    @Test void everyApplicationReceivesItsOwnRandomCodeAndExactlyOneOutbox() throws Exception {
        var codes=new java.util.HashSet<String>();
        for(int i=0;i<5;i++){var response=submit("candidate"+i+"@example.test");assertThat(response.statusCode()).isEqualTo(201);codes.add(json.readTree(response.body()).get("trackingCode").asString());}
        assertThat(codes).hasSize(5);assertThat(count("application_email_outbox")).isEqualTo(5);
        assertThat(submit("candidate6@example.test").statusCode()).isEqualTo(429);assertThat(count("application_email_outbox")).isEqualTo(5);
    }
    @Test void noSmtpTransactionOrOutboxRowLockIsHeldDuringNetworkDelivery() throws Exception {
        pending();SMTP.pauseReceipt();var executor=Executors.newSingleThreadExecutor();
        try {
            var send=executor.submit(worker::processNext);assertThat(SMTP.awaitData()).isTrue();
            try(var connection=dataSource.getConnection();var command=connection.createStatement()) {
                connection.setAutoCommit(false);command.executeQuery("SELECT * FROM application_email_outbox FOR UPDATE NOWAIT").close();connection.rollback();
            }
            assertThat(status()).isEqualTo("PROCESSING");
            assertThat(submit("another@example.test").statusCode()).isEqualTo(201);
            SMTP.releaseReceipt();assertThat(send.get(10,TimeUnit.SECONDS)).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM application_email_outbox WHERE status='SENT'",Integer.class)).isEqualTo(1);
        } finally {SMTP.releaseReceipt();executor.shutdownNow();}
    }
    @Test void temporarySmtpFailureRetriesWithSameMessageIdAndKeepsApplication() throws Exception {
        pending();String id=jdbc.queryForObject("SELECT message_id FROM application_email_outbox",String.class);
        SMTP.rejectDelivery(true);assertThat(worker.processNext()).isTrue();
        assertThat(status()).isEqualTo("RETRY_WAIT");assertThat(attempts()).isEqualTo(1);assertThat(worker.processNext()).isFalse();
        assertThat(count("job_applications")).isEqualTo(1);assertThat(SMTP.messages()).isEmpty();
        var row=jdbc.queryForMap("SELECT next_attempt_at,updated_at,ciphertext FROM application_email_outbox");
        assertThat(Duration.between(((Timestamp)row.get("updated_at")).toInstant(),((Timestamp)row.get("next_attempt_at")).toInstant()).toMillis()).isBetween(59000L,60000L);
        assertThat(row.get("ciphertext")).isNotNull();
        SMTP.rejectDelivery(false);makeDue();worker.processNext();
        assertThat(status()).isEqualTo("SENT");assertThat(attempts()).isEqualTo(2);
        assertThat(SMTP.messages().getFirst().getMessageID()).isEqualTo(id);
    }
    @Test void initialAndFiveRetriesUseApprovedIntervalsThenStop() throws Exception {
        pending();SMTP.rejectDelivery(true);long[] intervals={60,300,900,3600,21600};
        for(int attempt=1;attempt<=6;attempt++) {
            assertThat(worker.processNext()).isTrue();assertThat(attempts()).isEqualTo(attempt);
            if(attempt<6) {
                assertThat(status()).isEqualTo("RETRY_WAIT");
                var row=jdbc.queryForMap("SELECT next_attempt_at,updated_at FROM application_email_outbox");
                long millis=Duration.between(((Timestamp)row.get("updated_at")).toInstant(),((Timestamp)row.get("next_attempt_at")).toInstant()).toMillis();
                assertThat(millis).isBetween(intervals[attempt-1]*1000-1000,intervals[attempt-1]*1000);makeDue();
            }
        }
        assertThat(status()).isEqualTo("FAILED");assertThat(SMTP.deliveryAttempts()).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT last_failure FROM application_email_outbox",String.class)).isEqualTo("RETRIES_EXHAUSTED");
        assertThat(worker.processNext()).isFalse();assertThat(count("job_applications")).isEqualTo(1);
    }
    @Test void permanentlyRejectedRecipientStopsAfterFirstAttempt() throws Exception {
        pending();SMTP.rejectRecipient("candidate@example.test");assertThat(worker.processNext()).isTrue();
        assertThat(status()).isEqualTo("FAILED");assertThat(attempts()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_failure FROM application_email_outbox",String.class)).isEqualTo("RECIPIENT_REFUSED");
        assertThat(worker.processNext()).isFalse();assertThat(count("job_applications")).isEqualTo(1);
    }
    @Test void mailUsesTheSnapshotEvenIfPostingTitleChangesBeforeDelivery() throws Exception {
        pending();jdbc.update("UPDATE job_postings SET title='Changed title' WHERE id=?",posting);
        worker.processNext();assertThat(SMTP.messages().getFirst().getContent().toString())
                .contains("Lập trình viên").doesNotContain("Changed title");
    }
    @Test void connectionFailureIsRetryableAndDoesNotAffectSharedSenderConfiguration() throws Exception {
        pending();var implementation=(JavaMailSenderImpl)sender;int port=implementation.getPort();
        try {implementation.setPort(1);assertThat(worker.processNext()).isTrue();assertThat(status()).isEqualTo("RETRY_WAIT");}
        finally {implementation.setPort(port);}
        makeDue();worker.processNext();assertThat(status()).isEqualTo("SENT");
    }
    @Test void onlyOneConcurrentWorkerClaimsTheSamePendingEmail() throws Exception {
        pending();var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(8)) {
            var claims=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int i=0;i<8;i++)claims.add(executor.submit(() -> {start.await();return outbox.claim().isPresent();}));
            start.countDown();int winners=0;for(var claim:claims)if(claim.get(10,TimeUnit.SECONDS))winners++;
            assertThat(winners).isEqualTo(1);assertThat(attempts()).isEqualTo(1);assertThat(status()).isEqualTo("PROCESSING");
        }finally{start.countDown();}
    }
    @Test void expiredLeaseCanBeReclaimedAndOldWorkerCannotOverwriteNewOwner() throws Exception {
        pending();var first=outbox.claim().orElseThrow();assertThat(outbox.renew(first)).isTrue();assertThat(outbox.claim()).isEmpty();
        expireLease();assertThat(outbox.renew(first)).isFalse();var second=outbox.claim().orElseThrow();
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());assertThat(second.attempts()).isEqualTo(2);
        assertThat(outbox.sent(first)).isFalse();assertThat(outbox.failed(first,false,"SMTP_TEMPORARY")).isFalse();
        assertThat(outbox.sent(second)).isTrue();assertThat(status()).isEqualTo("SENT");
    }
    @Test void crashedLastAttemptStopsWithoutExceedingSixClaims() throws Exception {
        pending();for(int i=1;i<=6;i++){assertThat(outbox.claim().orElseThrow().attempts()).isEqualTo(i);expireLease();}
        assertThat(outbox.claim()).isEmpty();assertThat(attempts()).isEqualTo(6);assertThat(status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_failure FROM application_email_outbox",String.class)).isEqualTo("DELIVERY_UNCERTAIN");
        assertThat(SMTP.messages()).isEmpty();
    }
    @Test void lockedExpiredFinalAttemptDoesNotBlockClaimingAnotherPendingEmail() throws Exception {
        var first=pending();UUID firstApp=UUID.fromString(first.get("applicationId").asString());
        var response=submit("second@example.test");assertThat(response.statusCode()).isEqualTo(201);
        UUID secondApp=UUID.fromString(json.readTree(response.body()).get("applicationId").asString());
        jdbc.update("UPDATE application_email_outbox SET status='PROCESSING',attempts=6,lease_token=?,lease_until=clock_timestamp()-INTERVAL '1 second' WHERE application_id=?",UUID.randomUUID(),firstApp);
        try(var blocker=dataSource.getConnection();var statement=blocker.prepareStatement("SELECT id FROM application_email_outbox WHERE application_id=? FOR UPDATE")) {
            blocker.setAutoCommit(false);statement.setObject(1,firstApp);statement.executeQuery().close();
            assertThat(outbox.claim().orElseThrow().applicationId()).isEqualTo(secondApp);blocker.rollback();
        }
        assertThat(outbox.claim()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM application_email_outbox WHERE application_id=?",String.class,firstApp)).isEqualTo("FAILED");
    }
    @Test void restartUsesExistingDurableEncryptedPayloadAndSameCode() throws Exception {
        var receipt=pending();var repository=new ApplicationOutboxRepository(jdbc,manager);
        var restarted=new ApplicationConfirmationWorker(settings,repository,new ApplicationOutboxCrypto(settings,json),mail);
        assertThat(restarted.processNext()).isTrue();assertThat(status()).isEqualTo("SENT");
        assertThat(SMTP.messages().getFirst().getContent().toString()).contains(receipt.get("trackingCode").asString());
    }
    @Test void tamperedPayloadIsNotSentAndIsRetainedForInvestigation() throws Exception {
        pending();jdbc.update("UPDATE application_email_outbox SET ciphertext=set_byte(ciphertext,0,(get_byte(ciphertext,0)+1)%256)");
        assertThat(worker.processNext()).isTrue();assertThat(status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT last_failure FROM application_email_outbox",String.class)).isEqualTo("PAYLOAD_INVALID");
        assertThat(jdbc.queryForObject("SELECT ciphertext FROM application_email_outbox",byte[].class)).isNotEmpty();
        assertThat(SMTP.messages()).isEmpty();assertThat(count("job_applications")).isEqualTo(1);
    }
    @Test void temporaryWorkerDatabaseFailureLeavesPendingAndPollCanRecover() throws Exception {
        pending();jdbc.execute("ALTER TABLE application_email_outbox RENAME TO application_email_outbox_hidden");
        try{assertThatCode(worker::poll).doesNotThrowAnyException();assertThat(SMTP.messages()).isEmpty();}
        finally{jdbc.execute("ALTER TABLE application_email_outbox_hidden RENAME TO application_email_outbox");}
        worker.poll();assertThat(status()).isEqualTo("SENT");assertThat(SMTP.messages()).hasSize(1);
    }
    @Test void smtpAcceptedButDatabaseAcknowledgementFailedCanDeliverAgainWithSameMessageId() throws Exception {
        pending();
        jdbc.execute("CREATE FUNCTION fail_sent_test() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.status='SENT' THEN RAISE EXCEPTION 'private-sent-detail'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fail_sent_test BEFORE UPDATE ON application_email_outbox FOR EACH ROW EXECUTE FUNCTION fail_sent_test()");
        try {
            worker.poll();assertThat(SMTP.messages()).hasSize(1);assertThat(status()).isEqualTo("PROCESSING");
            assertThat(attempts()).isEqualTo(1);
        }finally{jdbc.execute("DROP TRIGGER fail_sent_test ON application_email_outbox");jdbc.execute("DROP FUNCTION fail_sent_test()");}
        expireLease();worker.processNext();assertThat(status()).isEqualTo("SENT");assertThat(SMTP.messages()).hasSize(2);
        assertThat(SMTP.messages().get(0).getMessageID()).isEqualTo(SMTP.messages().get(1).getMessageID());
        assertThat(count("job_applications")).isEqualTo(1);assertThat(count("application_cvs")).isEqualTo(1);
    }
    @Test void migrationV27ToV28PreservesExistingApplicationCvAndQuotaWithoutBackfillingMail() throws Exception {
        var receipt=pending();UUID app=UUID.fromString(receipt.get("applicationId").asString());
        byte[] hash=jdbc.queryForObject("SELECT tracking_code_hash FROM job_applications",byte[].class);
        String original=jdbc.queryForObject("SELECT current_schema()",String.class);
        String schema="ack_upgrade_"+UUID.randomUUID().toString().replace("-","");jdbc.execute("CREATE SCHEMA "+schema);
        var isolated=new org.springframework.jdbc.datasource.DelegatingDataSource(dataSource){
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                var connection=super.getConnection();connection.setSchema(schema);return connection;
            }
        };
        try {
            org.flywaydb.core.Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema).target("27").load().migrate();
            var legacy=new JdbcTemplate(isolated);
            for(String table:List.of("user_accounts","departments","positions","recruitment_catalog_items","recruitment_requisitions",
                    "job_postings","job_applications","application_cvs","application_ip_quotas")) {
                legacy.execute("INSERT INTO "+schema+"."+table+" SELECT * FROM "+original+"."+table);
            }
            legacy.execute("INSERT INTO application_ip_attempts(client_ip,attempted_at) SELECT client_ip,attempted_at FROM "+original+".application_ip_attempts");
            org.flywaydb.core.Flyway.configure().dataSource(isolated).schemas(schema).defaultSchema(schema).target("28").load().migrate();
            assertThat(legacy.queryForObject("SELECT tracking_code_hash FROM job_applications WHERE id=?",byte[].class,app)).isEqualTo(hash);
            assertThat(legacy.queryForObject("SELECT content FROM application_cvs WHERE application_id=?",byte[].class,app)).isEqualTo(pdf);
            assertThat(legacy.queryForObject("SELECT count(*) FROM application_ip_attempts",Integer.class)).isEqualTo(1);
            assertThat(legacy.queryForObject("SELECT count(*) FROM application_email_outbox",Integer.class)).isZero();
            UUID id=UUID.randomUUID();legacy.update("INSERT INTO application_email_outbox(id,application_id,message_id,key_id,nonce,ciphertext) VALUES (?,?,'<migration@example.test>','v1',?,?)",id,app,new byte[12],new byte[16]);
            assertThatThrownBy(() -> legacy.update("UPDATE application_email_outbox SET attempts=7"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> legacy.update("UPDATE application_email_outbox SET status='PROCESSING'"))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            legacy.update("DELETE FROM job_applications WHERE id=?",app);
            assertThat(legacy.queryForObject("SELECT count(*) FROM application_email_outbox",Integer.class)).isZero();
        }finally{jdbc.execute("DROP SCHEMA "+schema+" CASCADE");}
        assertThat(jdbc.queryForObject("SELECT current_schema()",String.class)).isEqualTo(original);
    }
    @Test void receiptIsCommittedEvenWhenACallersUnrelatedTransactionRollsBack() {
        var outer=new TransactionTemplate(manager);
        outer.executeWithoutResult(status -> {writer.accept(posting,new ApplicationSubmission("Candidate","direct@example.test","0123456789",null,null),
                validator.validate("cv.pdf",new java.io.ByteArrayInputStream(pdf)));status.setRollbackOnly();});
        assertThat(count("job_applications")).isEqualTo(1);assertThat(count("application_cvs")).isEqualTo(1);
        assertThat(count("application_email_outbox")).isEqualTo(1);assertThat(SMTP.messages()).isEmpty();
    }
    @Test void workerSeesNothingBeforeReceiptTransactionCommits() throws Exception {
        var tx=new TransactionTemplate(manager);var stored=new CountDownLatch(1);var release=new CountDownLatch(1);
        var executor=Executors.newSingleThreadExecutor();
        try {
            var writing=executor.submit(() -> tx.execute(status -> {
                var application=storage.store(posting,new ApplicationSubmission("Candidate","pending@example.test","0123456789",null,null),validator.validate("cv.pdf",new java.io.ByteArrayInputStream(pdf)),new byte[32]);
                UUID id=UUID.randomUUID();String code="A".repeat(43);
                var payload=new ApplicationConfirmationPayload("pending@example.test","Candidate","Developer",application.receivedAt(),code,settings.trackingUrl(code),settings.from());
                outbox.enqueue(id,application.id(),settings.messageId(id),crypto.encrypt(id,application.id(),payload));stored.countDown();
                try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException();}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException();}
                return application.id();
            }));
            assertThat(stored.await(10,TimeUnit.SECONDS)).isTrue();assertThat(worker.processNext()).isFalse();assertThat(SMTP.messages()).isEmpty();
            release.countDown();writing.get(10,TimeUnit.SECONDS);assertThat(worker.processNext()).isTrue();assertThat(SMTP.messages()).hasSize(1);
        }finally{release.countDown();executor.shutdownNow();}
    }
}
