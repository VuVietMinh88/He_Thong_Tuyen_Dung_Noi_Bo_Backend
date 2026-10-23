package vn.ttcs.recruitment.auth;

import jakarta.mail.internet.MimeMessage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationConfirmationWorker;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.ApplicationSubmission;
import vn.ttcs.recruitment.application.CvValidator;
import vn.ttcs.recruitment.application.DuplicateApplicationException;
import vn.ttcs.recruitment.application.PostgresApplicationReceiptWriter;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 351 (story S4-07, "Nhân viên nội bộ nộp hồ sơ giúp người quen kèm ghi chú giới thiệu"): POST /api/v1/referrals
 * over real HTTP, PostgreSQL and a local SMTP server. The confirmation key is set, the confirmation worker never runs
 * on its own (poll delay of an hour; processNext() by hand), the daily limit is the default (20), and so is the
 * limit of two referrals at once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000", "app.application-notice.poll-delay-ms=3600000"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationReferralIntegrationTest {
    private static final LocalSmtpServer SMTP = startSmtp();
    private static final String FROM = "support@example.test";
    private static final String EMAIL = "lan.nguyen@example.test";
    private static final String REFERRER_NAME = "Trần Văn Nam";
    private static final String REFERRER_EMAIL = "nam.referrer@staff.test";
    private static final Set<String> ITEM_KEYS = Set.of("id", "candidateName", "jobTitle", "submittedAt", "status",
            "statusName", "referralNote");
    private static final Pattern TRACKING = Pattern.compile("#ma=([A-Za-z0-9_-]{43})");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private Environment environment;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationStorageService storage;
    @Autowired private CvValidator validator;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private TokenService tokens;
    @Autowired private ApplicationConfirmationWorker worker;
    @Autowired private PostgresApplicationReceiptWriter receipts;
    @Autowired private DataSource dataSource;

    private ApplicationTestSupport applications;
    private UUID posting;
    private UUID requisition;
    private UUID referrer;
    private String token;

    @DynamicPropertySource
    static void smtp(DynamicPropertyRegistry values) {
        values.add("spring.mail.host", () -> "127.0.0.1");
        values.add("spring.mail.port", SMTP::port);
        values.add("spring.mail.properties.mail.smtp.auth", () -> false);
        values.add("spring.mail.properties.mail.smtp.starttls.enable", () -> false);
        values.add("spring.mail.properties.mail.smtp.starttls.required", () -> false);
        values.add("spring.mail.properties.mail.smtp.ssl.enable", () -> false);
        values.add("app.password-reset.mail-from", () -> FROM);
    }

    @AfterAll
    static void closeSmtp() throws IOException { SMTP.close(); }

    private static LocalSmtpServer startSmtp() {
        try { return new LocalSmtpServer(); } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    @BeforeEach
    void fixture() {
        SMTP.reset();
        clock.set(ApplicationTestSupport.NOW);
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();
        posting = applications.publishedPosting("Lập trình viên Java");
        requisition = applications.requisitionOf(posting);
        referrer = applications.account(REFERRER_NAME, REFERRER_EMAIL, Role.INTERVIEWER);
        token = applications.token(tokens, referrer);
    }

    // One referral writes the application (stage NEW, no catalog source, the posting-title snapshot, a tracking hash),
    // its CV, its candidate link, the referral (the caller, the note, consent time = receipt), one encrypted
    // confirmation and one attempt STORED, and answers 201 with exactly the referrer's item, never the code.
    @Test
    void aReferralStoresEverythingAndAnswersTheReferrersItem() throws Exception {
        byte[] cv = applications.pdf().content();
        var response = applications.refer(token, ApplicationTestSupport.referralFields(posting, " Lan.Nguyen@Example.TEST "),
                "CV Nguyễn Văn A.pdf", cv);

        JsonNode body = created(response);
        UUID id = UUID.fromString(body.get("id").asString());
        assertThat(names(body)).isEqualTo(ITEM_KEYS);
        assertThat(body.get("candidateName").asString()).isEqualTo(ApplicationTestSupport.FULL_NAME);
        assertThat(body.get("jobTitle").asString()).isEqualTo("Lập trình viên Java");
        assertThat(body.get("submittedAt").asString()).isEqualTo(ApplicationTestSupport.NOW.toString());
        assertThat(body.get("status").asString()).isEqualTo("RECEIVED");
        assertThat(body.get("statusName").asString()).isEqualTo("Đã nhận");
        assertThat(body.get("referralNote").asString()).isEqualTo(ApplicationTestSupport.REFERRAL_NOTE);
        assertThat(response.body()).doesNotContain("trackingCode", "trackingUrl", "#ma=", "#rut=", "tra-cuu", "lan.nguyen",
                "Lan.Nguyen", ApplicationTestSupport.PHONE, "CV Nguy", "NEW", "stage", "requisition", REFERRER_EMAIL);

        assertThat(jdbc.queryForMap("SELECT stage, source_id, posting_title, normalized_email, requisition_id, received_at"
                + " FROM job_applications WHERE id = ?", id))
                .containsEntry("stage", "NEW").containsEntry("source_id", null)
                .containsEntry("posting_title", "Lập trình viên Java").containsEntry("normalized_email", EMAIL)
                .containsEntry("requisition_id", requisition)
                .containsEntry("received_at", Timestamp.from(ApplicationTestSupport.NOW));
        assertThat(jdbc.queryForObject("SELECT tracking_code_hash FROM job_applications WHERE id = ?", byte[].class, id))
                .hasSize(32);
        assertThat(jdbc.queryForObject("SELECT content FROM application_cvs WHERE application_id = ?", byte[].class, id))
                .isEqualTo(cv);
        assertThat(jdbc.queryForMap("SELECT referrer_id, note, consent_declared_at FROM application_referrals"
                + " WHERE application_id = ?", id))
                .containsEntry("referrer_id", referrer).containsEntry("note", ApplicationTestSupport.REFERRAL_NOTE)
                .containsEntry("consent_declared_at", Timestamp.from(ApplicationTestSupport.NOW));
        assertThat(applications.candidateOf(id)).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_email_outbox WHERE application_id = ?"
                + " AND status = 'PENDING'", Integer.class, id)).isEqualTo(1);
        byte[] ciphertext = jdbc.queryForObject("SELECT ciphertext FROM application_email_outbox", byte[].class);
        assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1)).doesNotContain("lan.nguyen", "Lan.Nguyen",
                "Nguyễn Văn A", "example.test");
        assertThat(attempts()).containsExactly(attempt(referrer, posting, "STORED"));
        assertThat(SMTP.messages()).isEmpty();
    }

    // The candidate's confirmation, sent by TRAN DUC LUONG's worker: the code (whose SHA-256 is the stored hash), the
    // tracking and withdrawal links, and the referral paragraph: submitted on their behalf with their declared consent,
    // the withdrawal, and MAIL_FROM as the contact for a deletion request. The referrer is not named.
    @Test
    void theReferredCandidateGetsTheConfirmationWithTheReferralParagraph() throws Exception {
        UUID id = UUID.fromString(created(applications.refer(token, posting, EMAIL)).get("id").asString());

        assertThat(worker.processNext()).isTrue();
        assertThat(worker.processNext()).isFalse();
        assertThat(SMTP.messages()).hasSize(1);
        MimeMessage message = SMTP.messages().getFirst();
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo(EMAIL);
        assertThat(message.getFrom()[0].toString()).isEqualTo(FROM);
        String text = lf(message.getContent().toString());
        var code = TRACKING.matcher(text);
        assertThat(code.find()).isTrue();
        assertThat(MessageDigest.getInstance("SHA-256").digest(Base64.getUrlDecoder().decode(code.group(1))))
                .isEqualTo(jdbc.queryForObject("SELECT tracking_code_hash FROM job_applications WHERE id = ?",
                        byte[].class, id));
        assertThat(text).startsWith("Chào " + ApplicationTestSupport.FULL_NAME + ",\n\nHồ sơ này do một nhân viên của "
                + "công ty gửi thay bạn qua kênh giới thiệu nội bộ, kèm xác nhận rằng bạn đã đồng ý.")
                .contains("rút hồ sơ bằng liên kết rút hồ sơ bên dưới", "Để yêu cầu xóa dữ liệu của bạn, vui lòng trả lời "
                        + "email này hoặc liên hệ " + FROM + ".", "Hệ thống đã nhận hồ sơ ứng tuyển của bạn.",
                        "Lập trình viên Java", "https://portal.example.test/tra-cuu#ma=" + code.group(1),
                        "https://portal.example.test/tra-cuu#rut=" + code.group(1))
                .doesNotContain(REFERRER_NAME, REFERRER_EMAIL, "Trần", ApplicationTestSupport.REFERRAL_NOTE, "Bạn học");
        assertThat(jdbc.queryForObject("SELECT status FROM application_email_outbox", String.class)).isEqualTo("SENT");
    }

    // A public application (the teammate's receipt writer) gets no referral paragraph.
    @Test
    void aPublicApplicationsConfirmationHasNoReferralParagraph() throws Exception {
        receipts.accept(posting, new ApplicationSubmission("Lê Thị Hoa", "hoa@example.test", ApplicationTestSupport.PHONE,
                null, null), applications.pdf());

        assertThat(worker.processNext()).isTrue();
        String text = lf(SMTP.messages().getFirst().getContent().toString());
        assertThat(text).startsWith("Chào Lê Thị Hoa,\n\nHệ thống đã nhận hồ sơ ứng tuyển của bạn.")
                .doesNotContain("giới thiệu", "gửi thay bạn", "xóa dữ liệu");
    }

    // Every internal role holds SELF_PROFILE_WRITE (V3, V5), with a PDF and with a DOCX CV.
    @Test
    void everyInternalRoleRefersWithAPdfOrADocx() throws Exception {
        byte[] pdf = applications.pdf().content();
        byte[] docx = applications.docx().content();
        for (Role role : Role.values()) {
            String name = role.name().toLowerCase(java.util.Locale.ROOT);
            UUID staff = applications.account("Nhân viên " + name, name + "@staff.test", role);
            String staffToken = applications.token(tokens, staff);
            for (boolean word : List.of(false, true)) {
                String email = name + (word ? ".docx" : ".pdf") + "@example.test";
                var response = applications.refer(staffToken, ApplicationTestSupport.referralFields(posting, email),
                        word ? "CV.docx" : "CV.pdf", word ? docx : pdf);
                UUID id = UUID.fromString(created(response).get("id").asString());
                assertThat(jdbc.queryForObject("SELECT referrer_id FROM application_referrals WHERE application_id = ?",
                        UUID.class, id)).isEqualTo(staff);
                assertThat(jdbc.queryForObject("SELECT content_type FROM application_cvs WHERE application_id = ?",
                        String.class, id)).isEqualTo(word
                        ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document" : "application/pdf");
            }
        }
        assertThat(count("application_referrals")).isEqualTo(2 * Role.values().length);
        assertThat(count("application_email_outbox")).isEqualTo(2 * Role.values().length);
    }

    @Test
    void withoutARoleOrATokenNothingIsRead() throws Exception {
        UUID nobody = applications.account("Không vai trò", "none@staff.test");
        var forbidden = applications.refer(applications.token(tokens, nobody), posting, EMAIL);
        assertThat(forbidden.statusCode()).as(forbidden.body()).isEqualTo(403);
        assertThat(json.readTree(forbidden.body()).get("code").asString()).isEqualTo("FORBIDDEN");

        var anonymous = applications.refer(null, posting, EMAIL);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        var stale = applications.post(ApplicationTestSupport.REFERRALS, HttpRequest.BodyPublishers.ofByteArray(
                        ApplicationTestSupport.referralBody(ApplicationTestSupport.referralFields(posting, EMAIL), "CV.pdf",
                                applications.pdf().content())),
                "multipart/form-data; boundary=referral-http-boundary", Map.of("Authorization", "Bearer not-a-jwt"));
        assertThat(stale.statusCode()).isEqualTo(401);
        assertNothingStored();
        assertThat(applications.referralAttempts()).isZero();
    }

    // The CV is read by the streaming decoder, not the 5/6 MiB Servlet parser: 10 MiB is accepted, one byte more is
    // the intake's 413 APPLICATION_CV_TOO_LARGE (not FILE_TOO_LARGE), and so is a body above 11 MiB.
    @Test
    void aTenMebibyteCvIsAcceptedAndOneByteMoreIsTooLarge() throws Exception {
        byte[] large = pdfOfSize(CvValidator.MAX_BYTES);
        created(applications.refer(token, ApplicationTestSupport.referralFields(posting, EMAIL), "CV.pdf", large));
        assertThat(jdbc.queryForObject("SELECT size_bytes FROM application_cvs", Integer.class))
                .isEqualTo(CvValidator.MAX_BYTES);

        var tooLarge = applications.refer(token, ApplicationTestSupport.referralFields(posting, "other@example.test"),
                "CV.pdf", new byte[CvValidator.MAX_BYTES + 1]);
        assertError(tooLarge, 413, "APPLICATION_CV_TOO_LARGE");
        var tooLong = applications.refer(token, new byte[11 * 1024 * 1024 + 1]);
        assertError(tooLong, 413, "APPLICATION_CV_TOO_LARGE");
        assertThat(count("job_applications")).isEqualTo(1);
        assertThat(applications.referralAttempts()).isEqualTo(1);
    }

    @Test
    void anythingButMultipartIs415() throws Exception {
        var response = applications.post(ApplicationTestSupport.REFERRALS, "{}", "application/json",
                Map.of("Authorization", "Bearer " + token));
        assertError(response, 415, "APPLICATION_FORM_TYPE_UNSUPPORTED");
        assertNothingStored();
        assertThat(applications.referralAttempts()).isZero();
    }

    // Task 362's rule holds for referrals: a DOCX with a remote template is refused before the attempt quota.
    @Test
    void aCvWithActiveContentIsRefusedBeforeTheQuota() throws Exception {
        var response = applications.refer(token, ApplicationTestSupport.referralFields(posting, EMAIL), "Mau CV.docx",
                withRemoteTemplate(applications.docx().content()));
        assertError(response, 400, "APPLICATION_CV_ACTIVE_CONTENT");
        assertThat(response.body()).doesNotContain("Mau CV", "templates.example.test");
        assertNothingStored();
        assertThat(applications.referralAttempts()).isZero();
    }

    // The shared duplicate rule of task 327, in both orders: 409, no second referral, the attempt DUPLICATE.
    @Test
    void aDuplicateIsRefusedInBothOrders() throws Exception {
        applications.stored(posting, EMAIL);
        var afterPublic = applications.refer(token, posting, " LAN.NGUYEN@example.test ");
        assertError(afterPublic, 409, "APPLICATION_ALREADY_SUBMITTED");
        assertThat(json.readTree(afterPublic.body()).get("fieldErrors").get("email").asString())
                .isEqualTo("Email này đã nộp hồ sơ cho vị trí tuyển dụng này.");
        assertThat(count("application_referrals")).isZero();

        UUID second = applications.publishedPosting("Kiểm thử viên");
        created(applications.refer(token, second, EMAIL));
        UUID other = applications.account("Lê Văn Bình", "binh@staff.test", Role.RECRUITER);
        assertError(applications.refer(applications.token(tokens, other), second, EMAIL), 409,
                "APPLICATION_ALREADY_SUBMITTED");
        assertThatThrownBy(() -> applications.stored(second, EMAIL)).isInstanceOf(DuplicateApplicationException.class);
        assertThat(count("application_referrals")).isEqualTo(1);
        assertThat(attempts()).containsExactly(attempt(referrer, posting, "DUPLICATE"),
                attempt(referrer, second, "STORED"), attempt(other, second, "DUPLICATE"));
    }

    // A requisition no longer APPROVED, a posting no longer PUBLISHED, a past deadline or an unknown posting: 404, nothing
    // stored, the attempt POSTING_UNAVAILABLE.
    @ParameterizedTest
    @ValueSource(strings = {"PAUSED", "CLOSED", "PAST_DEADLINE", "UNKNOWN"})
    void onlyAnOpenPostingTakesReferrals(String state) throws Exception {
        UUID target = posting;
        switch (state) {
            case "PAUSED" -> jdbc.update("UPDATE recruitment_requisitions SET status = 'PAUSED' WHERE id = ?", requisition);
            case "CLOSED" -> jdbc.update("UPDATE job_postings SET status = 'CLOSED' WHERE id = ?", posting);
            case "PAST_DEADLINE" -> jdbc.update("UPDATE job_postings SET application_deadline = ? WHERE id = ?",
                    LocalDate.of(2026, 10, 14), posting);
            default -> target = UUID.randomUUID();
        }
        var response = applications.refer(token, target, EMAIL);
        assertError(response, 404, "JOB_POSTING_NOT_FOUND");
        assertThat(json.readTree(response.body()).get("message").asString()).isEqualTo("Tin tuyển dụng không còn nhận hồ sơ.");
        assertNothingStored();
        assertThat(attempts()).containsExactly(attempt(referrer, target, "POSTING_UNAVAILABLE"));
    }

    // Nobody refers themselves: the candidate's email normalized like the account's. Refused after the account lock,
    // so the attempt is counted (REFUSED).
    @Test
    void nobodyRefersThemselves() throws Exception {
        var response = applications.refer(token, posting, " Nam.Referrer@STAFF.test ");
        assertError(response, 400, "REFERRAL_SELF_NOT_ALLOWED");
        assertThat(json.readTree(response.body()).get("message").asString()).isEqualTo("Không thể tự giới thiệu chính mình.");
        assertThat(json.readTree(response.body()).get("fieldErrors").get("email").asString())
                .isEqualTo("Không thể tự giới thiệu chính mình.");
        assertNothingStored();
        assertThat(attempts()).containsExactly(attempt(referrer, posting, "REFUSED"));
    }

    // Validation runs before the attempt quota: a missing consent, an invisible or a 2001-character note, a referrer
    // sent in the form, or a missing CV write nothing at all.
    @Test
    void invalidFormsAreRefusedBeforeTheQuota() throws Exception {
        String zeroWidth = new String(Character.toChars(0x200B)) + new String(Character.toChars(0xFEFF));
        List<Map<String, String>> forms = new ArrayList<>();
        var noConsent = ApplicationTestSupport.referralFields(posting, EMAIL);
        noConsent.remove("candidateConsent");
        forms.add(noConsent);
        var refused = ApplicationTestSupport.referralFields(posting, EMAIL);
        refused.put("candidateConsent", "false");
        forms.add(refused);
        var invisible = ApplicationTestSupport.referralFields(posting, EMAIL);
        invisible.put("referralNote", zeroWidth);
        forms.add(invisible);
        var tooLong = ApplicationTestSupport.referralFields(posting, EMAIL);
        tooLong.put("referralNote", "x".repeat(2001));
        forms.add(tooLong);
        for (var form : forms) {
            var response = applications.refer(token, form, "CV.pdf", applications.pdf().content());
            assertError(response, 400, "VALIDATION_ERROR");
            assertThat(json.readTree(response.body()).get("fieldErrors").properties()).hasSize(1);
        }
        // A referrer sent in the form (with the note left out, so the form keeps seven parts) is refused as an extra
        // part, before the missing note could be reported.
        var withReferrer = new LinkedHashMap<>(ApplicationTestSupport.referralFields(posting, EMAIL));
        withReferrer.remove("referralNote");
        withReferrer.put("referrerId", referrer.toString());
        var extra = applications.refer(token, withReferrer, "CV.pdf", applications.pdf().content());
        assertError(extra, 400, "VALIDATION_ERROR");
        assertThat(json.readTree(extra.body()).get("message").asString()).isEqualTo("Biểu mẫu chứa trường dư hoặc lặp.");
        assertThat(names(json.readTree(extra.body()).get("fieldErrors"))).isEqualTo(Set.of("form"));
        assertError(applications.refer(token, ApplicationTestSupport.referralFields(posting, EMAIL), null, null), 400,
                "VALIDATION_ERROR");
        assertNothingStored();
        assertThat(applications.referralAttempts()).isZero();
    }

    // The writer's own requisition lock is FOR NO KEY UPDATE, never FOR SHARE, which only two referrers at once can
    // show (so here, with the default limit of two referrals at once, not in the lock test's limit of one). A public
    // intake's store() holds the requisition FOR SHARE (here a raw connection); a referral waits behind it, then the
    // referral of a second referrer waits too. Once the intake lets go, both answer 201: the second one queued behind
    // the first. A writer that took FOR SHARE would get it next to the intake's share lock, and so would the second
    // referral; each would then wait in ReferralApplicationStorage's FOR NO KEY UPDATE for the other's share lock, a
    // deadlock that PostgreSQL ends by aborting one of them (503 REFERRAL_SUBMISSION_UNAVAILABLE). The lock test's
    // NOWAIT probe cannot tell the two apart: by then the storage has locked the requisition FOR NO KEY UPDATE itself.
    @Test
    void twoReferrersBehindAPublicIntakeBothSucceed() throws Exception {
        // Warm-up on another posting, so the waits below (inside the writers' 2 s lock_timeout) measure the locks.
        created(applications.refer(token, applications.publishedPosting("Khởi động"), "warm@example.test"));
        jdbc.update("DELETE FROM referral_attempts");
        UUID other = applications.account("Lê Văn Bình", "binh@staff.test", Role.RECRUITER);
        String otherToken = applications.token(tokens, other);
        try (Connection intake = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            intake.setAutoCommit(false);
            try (var hold = intake.prepareStatement("SELECT 1 FROM recruitment_requisitions WHERE id = ? FOR SHARE")) {
                hold.setObject(1, requisition);
                hold.executeQuery().close();
            }
            Future<HttpResponse<String>> first;
            Future<HttpResponse<String>> second;
            try {
                int holder = ApplicationTestSupport.backendPid(intake);
                first = executor.submit(() -> applications.refer(token, posting, EMAIL));
                int referring = applications.awaitWaiterOf(holder);
                second = executor.submit(() -> applications.refer(otherToken, posting, "binh.candidate@example.test"));
                awaitSecondWaiter(holder, referring);
            } finally {
                intake.rollback();
            }
            created(first.get(10, TimeUnit.SECONDS));
            created(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM application_referrals r JOIN job_applications a ON a.id = r.application_id
                WHERE a.job_posting_id = ?""", Integer.class, posting)).isEqualTo(2);
        assertThat(attempts()).containsExactly(attempt(referrer, posting, "STORED"), attempt(other, posting, "STORED"));
    }

    // The second referral waits behind the first (FOR NO KEY UPDATE) or, with a writer that took FOR SHARE, behind the
    // intake: polled every 10 ms with pg_blocking_pids, failing loudly after 1.5 s like awaitWaiterOf.
    private void awaitSecondWaiter(int holder, int referring) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1500);
        while (System.nanoTime() < deadline) {
            var waiters = jdbc.queryForList("SELECT pid FROM pg_stat_activity WHERE pid <> ?"
                    + " AND pg_blocking_pids(pid) && ARRAY[?, ?]", Integer.class, referring, holder, referring);
            if (!waiters.isEmpty()) return;
            Thread.sleep(10);
        }
        throw new AssertionError("The second referral did not wait within 1.5 s");
    }

    private JsonNode created(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        return json.readTree(response.body());
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(names(body)).isEqualTo(Set.of("code", "message", "fieldErrors"));
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(response.body()).doesNotContain("Exception", "SQL", "lan.nguyen", "LAN.NGUYEN", "Bạn học", "CV.pdf");
    }

    private void assertNothingStored() {
        for (String table : List.of("job_applications", "application_cvs", "application_referrals", "candidates",
                "application_email_outbox")) {
            assertThat(count(table)).as(table).isZero();
        }
    }

    private static Set<String> names(JsonNode node) {
        var names = new java.util.TreeSet<String>();
        node.properties().forEach(entry -> names.add(entry.getKey()));
        return names;
    }

    private List<Map<String, Object>> attempts() {
        return jdbc.queryForList("SELECT referrer_id, job_posting_id, outcome FROM referral_attempts ORDER BY id");
    }

    private static Map<String, Object> attempt(UUID referrer, UUID posting, String outcome) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("referrer_id", referrer);
        row.put("job_posting_id", posting);
        row.put("outcome", outcome);
        return row;
    }

    // SMTP carries CRLF line breaks; the mail was written with LF.
    private static String lf(String text) {
        return text.replace("\r\n", "\n");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    // A PDF of exactly this many bytes (spaces in one content stream), like the intake's 10 MiB test.
    private static byte[] pdfOfSize(int target) throws Exception {
        int payload = target - 1000;
        byte[] pdf = pdf(payload);
        for (int attempt = 0; attempt < 5 && pdf.length != target; attempt++) {
            payload += target - pdf.length;
            pdf = pdf(payload);
        }
        assertThat(pdf).hasSize(target);
        return pdf;
    }

    private static byte[] pdf(int payload) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            var contents = new PDStream(document);
            page.setContents(contents);
            try (var stream = contents.createOutputStream()) {
                byte[] spaces = new byte[8192];
                Arrays.fill(spaces, (byte) ' ');
                for (int left = payload; left > 0; left -= Math.min(left, spaces.length)) {
                    stream.write(spaces, 0, Math.min(left, spaces.length));
                }
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    // The same document with an External attachedTemplate on a web host, in word/_rels/settings.xml.rels (task 362).
    private static byte[] withRemoteTemplate(byte[] docx) throws Exception {
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId362\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/"
                + "relationships/attachedTemplate\" Target=\"https://templates.example.test/cv.dotm\""
                + " TargetMode=\"External\"/></Relationships>";
        var output = new ByteArrayOutputStream();
        try (var input = new ZipInputStream(new ByteArrayInputStream(docx)); var zip = new ZipOutputStream(output)) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null;) {
                byte[] bytes = input.readAllBytes();
                if (!entry.getName().equals("word/_rels/settings.xml.rels")) {
                    zip.putNextEntry(new ZipEntry(entry.getName()));
                    zip.write(bytes);
                    zip.closeEntry();
                }
            }
            zip.putNextEntry(new ZipEntry("word/_rels/settings.xml.rels"));
            zip.write(rels.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
