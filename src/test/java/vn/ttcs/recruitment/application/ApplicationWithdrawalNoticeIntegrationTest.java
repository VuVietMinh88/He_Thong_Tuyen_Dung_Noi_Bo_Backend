package vn.ttcs.recruitment.application;

import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
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
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.auth.AuthIntegrationTest;
import vn.ttcs.recruitment.auth.LocalSmtpServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 346 (story S4-06, "Recruiter phụ trách nhận thông báo"): the emails queued by a withdrawal (V35)
 * and their delivery, over real HTTP, a real PostgreSQL and a loopback SMTP server. The workers never run on their own
 * (poll delays of one hour); tests call processNext and age next_attempt_at with SQL instead of waiting the 15-minute
 * recruiter delay. The applicant's name is a phishing line with a URL, so any applicant text in a recruiter mail shows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000", "app.application-notice.poll-delay-ms=3600000",
        "app.application-notice.recruiter-delay-seconds=900",
        "app.candidate-profile.page-url=https://internal-hire.example/candidates",
        "app.mail.from=support@internal-hire.example"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationWithdrawalNoticeIntegrationTest {
    private static final LocalSmtpServer SMTP = startSmtp();
    private static final String JAVA = "Lập trình viên Java";
    private static final String TESTING = "Kiểm thử phần mềm";
    // What an anonymous applicant may type: a name with a phishing sentence and a URL, a phone, a cover letter, a
    // reason and a CV file name. None of it may reach a recruiter.
    private static final String APPLICANT_NAME =
            "A. Tài khoản của bạn sắp bị khóa, xác minh tại https://internal-hire.co/login";
    private static final String APPLICANT_EMAIL = "Ung.Vien.Rieng.Tu@Example.test";
    private static final String PHONE = "0987001122";
    private static final String COVER_LETTER = "THU_GIOI_THIEU_RIENG_TU";
    private static final String REASON = "LY_DO_RUT_RIENG_TU vì đã nhận việc khác";
    private static final String CV_NAME = "CV_Rieng_Tu_2026.pdf";
    private static final String PROFILE_PAGE = "https://internal-hire.example/candidates/";
    private static final String SUPPORT = "support@internal-hire.example";
    private static final Instant RECEIVED_AT = Instant.parse("2026-09-20T02:30:00Z");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Autowired private JdbcTemplate jdbc;
    @Autowired private Environment environment;
    @Autowired private ObjectMapper json;
    @Autowired private ApplicationNoticeWorker worker;
    @Autowired private ApplicationNoticeRepository outbox;
    @Autowired private ApplicationStorageService storage;
    @Autowired private CvValidator validator;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final SecureRandom random = new SecureRandom();
    private UUID hr;

    // An application as its candidate knows it (the code) and as SQL knows it (the ids).
    private record Stored(UUID id, UUID requisitionId, UUID candidateId, String code, String email) {
        @Override public String toString() { return "Stored[" + id + ", tracking code redacted]"; }
    }

    @DynamicPropertySource
    static void smtp(DynamicPropertyRegistry values) {
        values.add("spring.mail.host", () -> "127.0.0.1");
        values.add("spring.mail.port", SMTP::port);
        values.add("spring.mail.properties.mail.smtp.auth", () -> false);
        values.add("spring.mail.properties.mail.smtp.starttls.enable", () -> false);
        values.add("spring.mail.properties.mail.smtp.starttls.required", () -> false);
        values.add("spring.mail.properties.mail.smtp.ssl.enable", () -> false);
    }

    @AfterAll
    static void closeSmtp() throws IOException { SMTP.close(); }

    private static LocalSmtpServer startSmtp() {
        try {
            return new LocalSmtpServer();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // Applications and their children in key order, and the tracking quota (default 30 per hour); postings,
    // requisitions and accounts of earlier tests stay (every test makes its own with fresh ids and addresses).
    @BeforeEach
    void fixture() {
        SMTP.reset();
        jdbc.execute("TRUNCATE candidate_access_events");
        for (String table : List.of("application_notice_outbox", "application_stage_events", "candidate_applications",
                "candidates", "application_email_outbox", "application_cvs", "job_applications",
                "application_tracking_ip_quotas")) {
            jdbc.update("DELETE FROM " + table);
        }
        hr = staff("Nguyễn Thị Hoa", Role.HR_MANAGER);
    }

    // A withdrawal queues one RECRUITER_WITHDRAWAL row for the PRIMARY recruiter, due after the 900 s delay, and one
    // CANDIDATE_WITHDRAWAL row without a recipient, due at once; never one for a SUPPORTING recruiter. Before the delay
    // has passed only the candidate's mail goes out.
    @Test
    void aWithdrawalQueuesTheEligiblePrimaryAndTheCandidateButNeverASupportingRecruiter() throws Exception {
        UUID posting = posting(JAVA);
        UUID primary = staff("Trần Văn Bình", Role.RECRUITER);
        UUID supporting = staff("Lê Thị Cúc", Role.RECRUITER);
        var application = stored(posting, APPLICANT_EMAIL);
        assign(application.requisitionId(), primary, "PRIMARY");
        assign(application.requisitionId(), supporting, "SUPPORTING");

        ok(withdraw(application, REASON));

        var rows = jdbc.queryForList("""
                SELECT kind, recipient_id, status, attempts, extract(epoch FROM next_attempt_at - created_at) AS delay
                FROM application_notice_outbox WHERE application_id = ? ORDER BY kind
                """, application.id());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("kind", "CANDIDATE_WITHDRAWAL").containsEntry("status", "PENDING")
                .containsEntry("attempts", 0);
        assertThat(rows.get(0).get("recipient_id")).isNull();
        // next_attempt_at and the created_at default each read clock_timestamp(), so "at once" may come out a few
        // microseconds before created_at.
        assertThat(((Number) rows.get(0).get("delay")).doubleValue()).isBetween(-1.0, 1.0);
        assertThat(rows.get(1)).containsEntry("kind", "RECRUITER_WITHDRAWAL").containsEntry("recipient_id", primary)
                .containsEntry("status", "PENDING").containsEntry("attempts", 0);
        assertThat(((Number) rows.get(1).get("delay")).doubleValue()).isBetween(899.0, 901.0);
        assertThat(count("SELECT count(*) FROM application_notice_outbox WHERE recipient_id = ?", supporting)).isZero();

        assertThat(worker.processNext()).isTrue();
        assertThat(worker.processNext()).isFalse();
        assertThat(SMTP.messages()).hasSize(1);
        assertThat(to(SMTP.messages().getFirst())).isEqualTo(APPLICANT_EMAIL);
        assertThat(SMTP.messages().getFirst().getSubject()).isEqualTo(ApplicationNoticeMailSender.CANDIDATE_SUBJECT);
        assertThat(statusOf(application, "CANDIDATE_WITHDRAWAL")).isEqualTo("SENT");
        assertThat(statusOf(application, "RECRUITER_WITHDRAWAL")).isEqualTo("PENDING");
    }

    // The recruiter mail: to the PRIMARY's current address, the exact constant subject, From app.mail.from, the row's
    // Message-ID, plain UTF-8 text; the posting snapshot, the withdrawal time, the stage name and the profile link.
    // Nothing the applicant typed: not the phishing name (nor its domain), the email, the phone, the cover letter, the
    // reason, the code or the CV file name, in no mail and no header.
    @Test
    void theRecruiterMailNamesOnlyStaffTextTimesAndTheProfileLink() throws Exception {
        UUID posting = posting(JAVA);
        UUID primary = staff("Trần Văn Bình", Role.RECRUITER);
        String primaryEmail = emailOf(primary);
        var application = stored(posting, APPLICANT_EMAIL);
        assign(application.requisitionId(), primary, "PRIMARY");
        ok(withdraw(application, REASON));
        // The recruiter changed the address after the withdrawal: the mail goes to the current one.
        String current = "binh.moi." + UUID.randomUUID() + "@internal-hire.example";
        jdbc.update("UPDATE user_accounts SET email = ? WHERE id = ?", current, primary);
        String messageId = jdbc.queryForObject("SELECT message_id FROM application_notice_outbox WHERE kind = "
                + "'RECRUITER_WITHDRAWAL'", String.class);

        makeDue();
        drain();

        assertThat(SMTP.messages()).hasSize(2);
        var mail = message(ApplicationNoticeMailSender.RECRUITER_SUBJECT);
        assertThat(mail.getSubject()).isEqualTo("Có ứng viên rút hồ sơ — Hệ thống tuyển dụng nội bộ");
        assertThat(to(mail)).isEqualTo(current).isNotEqualTo(primaryEmail);
        assertThat(mail.getFrom()[0].toString()).isEqualTo(SUPPORT);
        assertThat(mail.getMessageID()).isEqualTo(messageId)
                .matches("<application-notice\\.[0-9a-f-]{36}@internal-hire\\.example>");
        assertThat(mail.getContentType()).containsIgnoringCase("text/plain").containsIgnoringCase("UTF-8");
        String body = mail.getContent().toString();
        assertThat(body).contains("Chào Trần Văn Bình,", "Vị trí \"" + JAVA + "\", rút lúc "
                        + TIME.format(withdrawnAt(application)) + " (giờ Việt Nam).",
                "Xem hồ sơ: " + PROFILE_PAGE + application.candidateId(), "\"Ứng viên rút\"",
                "chỉ hiển thị sau khi đăng nhập", "recruiter chính");
        for (var message : SMTP.messages()) assertNoApplicantText(message, application);
        // The candidate's address appears only where it must: as the recipient of the candidate's own mail.
        assertThat(String.join("\n", headers(mail))).doesNotContainIgnoringCase(APPLICANT_EMAIL);
        assertThat(body).doesNotContainIgnoringCase(APPLICANT_EMAIL);
        assertThat(count("SELECT count(*) FROM application_notice_outbox WHERE status = 'SENT'")).isEqualTo(2);
    }

    // The candidate mail: to the address of the application, with the posting title, the receipt and withdrawal
    // times and the support address; no name, no code, no reason and no link.
    @Test
    void theCandidateMailConfirmsTheWithdrawalWithoutNameCodeReasonOrLink() throws Exception {
        UUID posting = posting(JAVA);
        var application = stored(posting, APPLICANT_EMAIL);
        ok(withdraw(application, REASON));
        String messageId = jdbc.queryForObject("SELECT message_id FROM application_notice_outbox", String.class);

        drain();

        assertThat(SMTP.messages()).hasSize(1);
        var mail = SMTP.messages().getFirst();
        assertThat(mail.getSubject()).isEqualTo("Xác nhận rút hồ sơ ứng tuyển — Hệ thống tuyển dụng nội bộ");
        assertThat(to(mail)).isEqualTo(APPLICANT_EMAIL);
        assertThat(mail.getFrom()[0].toString()).isEqualTo(SUPPORT);
        assertThat(mail.getMessageID()).isEqualTo(messageId);
        assertThat(mail.getContentType()).containsIgnoringCase("text/plain").containsIgnoringCase("UTF-8");
        String body = mail.getContent().toString();
        assertThat(body).contains("Chào bạn,", "vị trí \"" + JAVA + "\" nộp lúc " + TIME.format(RECEIVED_AT)
                        + " đã được rút lúc " + TIME.format(withdrawnAt(application)) + " (giờ Việt Nam)",
                "liên hệ " + SUPPORT + " để được hỗ trợ");
        assertThat(TIME.format(RECEIVED_AT)).isNotEqualTo(TIME.format(withdrawnAt(application)));
        assertThat(body).doesNotContain(APPLICANT_NAME, "Nguyễn", application.code(), REASON, "LY_DO", "http", "#rut",
                "#ma=", "Xem hồ sơ", CV_NAME, PHONE);
        assertNoApplicantText(mail, application);
    }

    // Three withdrawals of one requisition within the delay go out as one recruiter mail with three lines, each row
    // SENT after one attempt; a withdrawal in another requisition of the same recruiter gets its own mail.
    @Test
    void withdrawalsOfOneRequisitionAreGroupedIntoOneRecruiterMail() throws Exception {
        UUID java = posting(JAVA), testing = posting(TESTING);
        UUID primary = staff("Trần Văn Bình", Role.RECRUITER);
        var first = stored(java, "a." + UUID.randomUUID() + "@example.test");
        var second = stored(java, "b." + UUID.randomUUID() + "@example.test");
        var third = stored(java, "c." + UUID.randomUUID() + "@example.test");
        var other = stored(testing, "d." + UUID.randomUUID() + "@example.test");
        assign(first.requisitionId(), primary, "PRIMARY");
        assign(other.requisitionId(), primary, "PRIMARY");
        for (var application : List.of(first, second, third, other)) ok(withdraw(application, null));

        makeDue();
        drain();

        var recruiterMails = SMTP.messages().stream()
                .filter(message -> subjectOf(message).equals(ApplicationNoticeMailSender.RECRUITER_SUBJECT)).toList();
        assertThat(recruiterMails).hasSize(2);
        assertThat(SMTP.messages()).hasSize(6);
        var bodies = new ArrayList<String>();
        for (var mail : recruiterMails) bodies.add(mail.getContent().toString());
        String javaMail = bodies.stream().filter(body -> body.contains(JAVA)).findFirst().orElseThrow();
        String testingMail = bodies.stream().filter(body -> body.contains(TESTING)).findFirst().orElseThrow();
        assertThat(lines(javaMail)).hasSize(3);
        assertThat(javaMail).contains(PROFILE_PAGE + first.candidateId(), PROFILE_PAGE + second.candidateId(),
                PROFILE_PAGE + third.candidateId()).doesNotContain(TESTING, PROFILE_PAGE + other.candidateId());
        assertThat(lines(testingMail)).hasSize(1);
        assertThat(testingMail).contains(PROFILE_PAGE + other.candidateId()).doesNotContain(JAVA);
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT status || '/' || attempts FROM application_notice_outbox
                WHERE kind = 'RECRUITER_WITHDRAWAL'
                """, String.class)).containsExactly("SENT/1");
    }

    // A claim of a recruiter row leases the waiting rows of the same recipient and requisition under one token, due or
    // not, and never another recruiter's or another requisition's; after the lease expires a second worker takes the
    // whole batch over, and the first worker's renew, sent, failed and abandon are refused for every row of it.
    @Test
    void aBatchIsLeasedUnderOneTokenAndALateWorkerIsFencedForEveryRow() throws Exception {
        UUID java = posting(JAVA), testing = posting(TESTING);
        UUID primary = staff("Trần Văn Bình", Role.RECRUITER), another = staff("Phạm Văn Dũng", Role.RECRUITER);
        var first = stored(java, "a." + UUID.randomUUID() + "@example.test");
        var second = stored(java, "b." + UUID.randomUUID() + "@example.test");
        var elsewhere = stored(testing, "c." + UUID.randomUUID() + "@example.test");
        assign(first.requisitionId(), primary, "PRIMARY");
        assign(elsewhere.requisitionId(), another, "PRIMARY");
        for (var application : List.of(first, second, elsewhere)) ok(withdraw(application, null));
        drain();                                                        // the three candidate mails
        assertThat(SMTP.messages()).hasSize(3);
        // Only the first row is due: the second still waits for its delay and is leased with it.
        jdbc.update("UPDATE application_notice_outbox SET next_attempt_at = clock_timestamp() - INTERVAL '1 second' "
                + "WHERE application_id = ? AND kind = 'RECRUITER_WITHDRAWAL'", first.id());

        var batch = outbox.claim().orElseThrow();
        assertThat(batch.kind()).isEqualTo(ApplicationNoticeKind.RECRUITER_WITHDRAWAL);
        assertThat(batch.applicationIds()).containsExactly(first.id(), second.id());
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT lease_token FROM application_notice_outbox WHERE status = 'PROCESSING'
                """, UUID.class)).containsExactly(batch.leaseToken());
        assertThat(statusOf(elsewhere, "RECRUITER_WITHDRAWAL")).isEqualTo("PENDING");
        assertThat(outbox.claim()).isEmpty();

        jdbc.update("UPDATE application_notice_outbox SET lease_until = clock_timestamp() - INTERVAL '1 second' "
                + "WHERE status = 'PROCESSING'");
        var takeover = outbox.claim().orElseThrow();
        assertThat(takeover.leaseToken()).isNotEqualTo(batch.leaseToken());
        assertThat(takeover.ids()).containsExactlyInAnyOrderElementsOf(batch.ids());
        assertThat(outbox.renew(batch, batch.ids())).isFalse();
        assertThat(outbox.sent(batch, batch.ids())).isFalse();
        assertThat(outbox.failed(batch, batch.ids(), false, "SMTP_TEMPORARY")).isFalse();
        assertThat(outbox.abandon(batch, batch.ids(), "OBSOLETE")).isFalse();
        assertThat(jdbc.queryForList("""
                SELECT status || '/' || attempts || '/' || lease_token FROM application_notice_outbox
                WHERE application_id IN (?, ?) AND kind = 'RECRUITER_WITHDRAWAL'
                """, String.class, first.id(), second.id()))
                .containsOnly("PROCESSING/2/" + takeover.leaseToken()).hasSize(2);
        assertThat(outbox.sent(takeover, takeover.ids())).isTrue();
        assertThat(statusOf(first, "RECRUITER_WITHDRAWAL")).isEqualTo("SENT");
        assertThat(statusOf(second, "RECRUITER_WITHDRAWAL")).isEqualTo("SENT");
        assertThat(statusOf(elsewhere, "RECRUITER_WITHDRAWAL")).isEqualTo("PENDING");
    }

    // A repeat writes nothing, and two parallel withdrawals of one application queue one row of each kind.
    @Test
    void aRepeatOrAParallelWithdrawalStillQueuesOneRowOfEachKind() throws Exception {
        UUID posting = posting(JAVA);
        UUID primary = staff("Trần Văn Bình", Role.RECRUITER);
        var repeated = stored(posting, "a." + UUID.randomUUID() + "@example.test");
        var parallel = stored(posting, "b." + UUID.randomUUID() + "@example.test");
        assign(repeated.requisitionId(), primary, "PRIMARY");

        ok(withdraw(repeated, REASON));
        ok(withdraw(repeated, "Lý do khác"));
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<HttpResponse<String>>> calls = new ArrayList<>();
            for (int call = 0; call < 2; call++) {
                calls.add(executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return withdraw(parallel, null);
                }));
            }
            for (var call : calls) ok(call.get(20, TimeUnit.SECONDS));
        }

        for (var application : List.of(repeated, parallel)) {
            assertThat(jdbc.queryForList(
                    "SELECT kind FROM application_notice_outbox WHERE application_id = ? ORDER BY kind",
                    String.class, application.id())).containsExactly("CANDIDATE_WITHDRAWAL", "RECRUITER_WITHDRAWAL");
        }
    }

    // At withdrawal time no PRIMARY (a SUPPORTING one only), a disabled or locked PRIMARY, or a PRIMARY whose roles
    // were removed (no CANDIDATES read code): no recruiter row; the candidate row is still queued; the withdrawal 200.
    @ParameterizedTest
    @ValueSource(strings = {"supporting only", "disabled", "locked", "roles removed"})
    void withoutAnEligiblePrimaryOnlyTheCandidateIsNotified(String situation) throws Exception {
        UUID posting = posting(JAVA);
        UUID recruiter = staff("Trần Văn Bình", Role.RECRUITER);
        var application = stored(posting, APPLICANT_EMAIL);
        assign(application.requisitionId(), recruiter, situation.equals("supporting only") ? "SUPPORTING" : "PRIMARY");
        change(recruiter, application.requisitionId(), situation);

        ok(withdraw(application, REASON));

        assertThat(jdbc.queryForList("SELECT kind FROM application_notice_outbox", String.class))
                .containsExactly("CANDIDATE_WITHDRAWAL");
        assertThat(stageOf(application)).isEqualTo("WITHDRAWN");
        makeDue();
        drain();
        assertThat(SMTP.messages()).hasSize(1);
        assertThat(subjectOf(SMTP.messages().getFirst())).isEqualTo(ApplicationNoticeMailSender.CANDIDATE_SUBJECT);
    }

    // A requisition without any team: the candidate row only.
    @Test
    void aRequisitionWithoutATeamNotifiesTheCandidateOnly() throws Exception {
        var application = stored(posting(JAVA), APPLICANT_EMAIL);
        ok(withdraw(application, null));
        assertThat(jdbc.queryForList("SELECT kind FROM application_notice_outbox", String.class))
                .containsExactly("CANDIDATE_WITHDRAWAL");
    }

    // After queuing, the recipient is checked again at send time: locked, disabled, removed from the team or without
    // roles → the whole batch FAILED RECIPIENT_UNAVAILABLE and no recruiter mail. Moved to SUPPORTING → still sent.
    @ParameterizedTest
    @ValueSource(strings = {"locked", "disabled", "removed from the team", "roles removed", "moved to supporting"})
    void theRecipientIsCheckedAgainWhenTheMailIsSent(String situation) throws Exception {
        UUID posting = posting(JAVA);
        UUID primary = staff("Trần Văn Bình", Role.RECRUITER);
        var first = stored(posting, "a." + UUID.randomUUID() + "@example.test");
        var second = stored(posting, "b." + UUID.randomUUID() + "@example.test");
        assign(first.requisitionId(), primary, "PRIMARY");
        ok(withdraw(first, null));
        ok(withdraw(second, null));
        assertThat(count("SELECT count(*) FROM application_notice_outbox WHERE kind = 'RECRUITER_WITHDRAWAL'"))
                .isEqualTo(2);
        change(primary, first.requisitionId(), situation);

        makeDue();
        drain();

        var recruiterMails = SMTP.messages().stream()
                .filter(message -> subjectOf(message).equals(ApplicationNoticeMailSender.RECRUITER_SUBJECT)).toList();
        var rows = jdbc.queryForList("""
                SELECT DISTINCT status || '/' || coalesce(last_failure, '-') FROM application_notice_outbox
                WHERE kind = 'RECRUITER_WITHDRAWAL'
                """, String.class);
        if (situation.equals("moved to supporting")) {
            assertThat(recruiterMails).hasSize(1);
            assertThat(lines(recruiterMails.getFirst().getContent().toString())).hasSize(2);
            assertThat(rows).containsExactly("SENT/-");
        } else {
            assertThat(recruiterMails).isEmpty();
            assertThat(rows).containsExactly("FAILED/RECIPIENT_UNAVAILABLE");
        }
        assertThat(SMTP.messages()).hasSize(2 + recruiterMails.size());
    }

    // The send-time team check is about the withdrawn application's requisition: a PRIMARY removed from it after
    // queuing who is still on another requisition's team (PRIMARY or SUPPORTING there) gets nothing; the batch is
    // RECIPIENT_UNAVAILABLE.
    @ParameterizedTest
    @ValueSource(strings = {"PRIMARY", "SUPPORTING"})
    void aRecruiterRemovedFromThisTeamButOnAnotherTeamGetsNothing(String roleElsewhere) throws Exception {
        UUID java = posting(JAVA), testing = posting(TESTING);
        UUID primary = staff("Trần Văn Bình", Role.RECRUITER);
        var application = stored(java, APPLICANT_EMAIL);
        UUID elsewhere = requisitionOf(testing);
        assign(application.requisitionId(), primary, "PRIMARY");
        assign(elsewhere, primary, roleElsewhere);
        ok(withdraw(application, null));
        assertThat(statusOf(application, "RECRUITER_WITHDRAWAL")).isEqualTo("PENDING");
        change(primary, application.requisitionId(), "removed from the team");
        assertThat(jdbc.queryForList("SELECT requisition_id FROM requisition_recruiters WHERE recruiter_id = ?",
                UUID.class, primary)).containsExactly(elsewhere);

        makeDue();
        drain();

        assertThat(SMTP.messages()).hasSize(1);
        assertThat(subjectOf(SMTP.messages().getFirst())).isEqualTo(ApplicationNoticeMailSender.CANDIDATE_SUBJECT);
        assertThat(jdbc.queryForObject("""
                SELECT status || '/' || last_failure FROM application_notice_outbox
                WHERE application_id = ? AND kind = 'RECRUITER_WITHDRAWAL'
                """, String.class, application.id())).isEqualTo("FAILED/RECIPIENT_UNAVAILABLE");
    }

    // A PRIMARY handover within the delay (RequisitionRecruiterRepository.apply deletes the old PRIMARY row and inserts
    // the new one) leaves waiting rows of two recipients on one requisition. A batch takes only its own recipient's
    // rows, whichever row is claimed first: the old PRIMARY's row is RECIPIENT_UNAVAILABLE, and the new PRIMARY gets
    // one mail that names only the withdrawal made after the handover.
    @ParameterizedTest
    @ValueSource(strings = {"old primary first", "new primary first"})
    void afterAHandoverEachPrimaryIsBatchedOnlyWithItsOwnRows(String order) throws Exception {
        UUID posting = posting(JAVA);
        UUID oldPrimary = staff("Trần Văn Bình", Role.RECRUITER);
        UUID newPrimary = staff("Phạm Văn Dũng", Role.RECRUITER);
        var before = stored(posting, "a." + UUID.randomUUID() + "@example.test");
        var after = stored(posting, "b." + UUID.randomUUID() + "@example.test");
        assign(before.requisitionId(), oldPrimary, "PRIMARY");
        ok(withdraw(before, null));
        handOver(before.requisitionId(), newPrimary);
        ok(withdraw(after, null));
        drain();                                                        // the two candidate mails
        SMTP.reset();
        assertThat(recipientOf(before)).isEqualTo(oldPrimary);
        assertThat(recipientOf(after)).isEqualTo(newPrimary);

        makeDue();
        dueFirst(jdbc.queryForObject("""
                SELECT message_id FROM application_notice_outbox WHERE kind = 'RECRUITER_WITHDRAWAL'
                    AND application_id = ?
                """, String.class, (order.equals("old primary first") ? before : after).id()));
        drain();

        assertThat(SMTP.messages()).hasSize(1);
        var mail = SMTP.messages().getFirst();
        assertThat(subjectOf(mail)).isEqualTo(ApplicationNoticeMailSender.RECRUITER_SUBJECT);
        assertThat(to(mail)).isEqualTo(emailOf(newPrimary));
        String body = mail.getContent().toString();
        assertThat(body).contains("Chào Phạm Văn Dũng,", PROFILE_PAGE + after.candidateId())
                .doesNotContain(PROFILE_PAGE + before.candidateId(), "Trần Văn Bình");
        assertThat(lines(body)).hasSize(1);
        assertThat(jdbc.queryForObject("""
                SELECT status || '/' || attempts || '/' || last_failure FROM application_notice_outbox
                WHERE application_id = ? AND kind = 'RECRUITER_WITHDRAWAL'
                """, String.class, before.id())).isEqualTo("FAILED/1/RECIPIENT_UNAVAILABLE");
        assertThat(jdbc.queryForObject("""
                SELECT status || '/' || attempts FROM application_notice_outbox
                WHERE application_id = ? AND kind = 'RECRUITER_WITHDRAWAL'
                """, String.class, after.id())).isEqualTo("SENT/1");
    }

    // An application set back to another stage before sending (SQL; no API does it in Sprint 4) is OBSOLETE: left out
    // of the recruiter batch, its candidate mail not sent; with no application left, no recruiter mail at all.
    @Test
    void anApplicationNoLongerWithdrawnIsLeftOutAndNoMailIsSentWhenNoneRemains() throws Exception {
        UUID java = posting(JAVA), testing = posting(TESTING);
        UUID primary = staff("Trần Văn Bình", Role.RECRUITER);
        var kept = stored(java, "a." + UUID.randomUUID() + "@example.test");
        var reverted = stored(java, "b." + UUID.randomUUID() + "@example.test");
        var alone = stored(testing, "c." + UUID.randomUUID() + "@example.test");
        assign(kept.requisitionId(), primary, "PRIMARY");
        assign(alone.requisitionId(), primary, "PRIMARY");
        for (var application : List.of(kept, reverted, alone)) ok(withdraw(application, null));
        jdbc.update("UPDATE job_applications SET stage = 'SCREENING' WHERE id IN (?, ?)", reverted.id(), alone.id());

        makeDue();
        drain();

        var recruiterMails = SMTP.messages().stream()
                .filter(message -> subjectOf(message).equals(ApplicationNoticeMailSender.RECRUITER_SUBJECT)).toList();
        assertThat(recruiterMails).hasSize(1);
        String body = recruiterMails.getFirst().getContent().toString();
        assertThat(lines(body)).hasSize(1);
        assertThat(body).contains(PROFILE_PAGE + kept.candidateId()).doesNotContain(
                PROFILE_PAGE + reverted.candidateId(), TESTING);
        assertThat(SMTP.messages()).hasSize(2);                          // kept's candidate mail and the recruiter's
        assertThat(statusOf(kept, "RECRUITER_WITHDRAWAL")).isEqualTo("SENT");
        for (var application : List.of(reverted, alone)) {
            for (String kind : List.of("RECRUITER_WITHDRAWAL", "CANDIDATE_WITHDRAWAL")) {
                assertThat(jdbc.queryForObject("""
                        SELECT status || '/' || last_failure FROM application_notice_outbox
                        WHERE application_id = ? AND kind = ?
                        """, String.class, application.id(), kind)).isEqualTo("FAILED/OBSOLETE");
            }
        }
    }

    // SMTP down: every row of the batch waits 60 s (RETRY_WAIT, its first attempt), then the same Message-ID is sent.
    @Test
    void aTemporarySmtpFailureRetriesTheWholeBatchAfterOneMinute() throws Exception {
        UUID posting = posting(JAVA);
        UUID primary = staff("Trần Văn Bình", Role.RECRUITER);
        var first = stored(posting, "a." + UUID.randomUUID() + "@example.test");
        var second = stored(posting, "b." + UUID.randomUUID() + "@example.test");
        assign(first.requisitionId(), primary, "PRIMARY");
        ok(withdraw(first, null));
        ok(withdraw(second, null));
        drain();
        SMTP.reset();
        // The mail takes the Message-ID of the row the worker claims: the first withdrawal's row, due first each time.
        String messageId = jdbc.queryForObject("""
                SELECT message_id FROM application_notice_outbox
                WHERE kind = 'RECRUITER_WITHDRAWAL' AND application_id = ?
                """, String.class, first.id());
        makeDue();
        dueFirst(messageId);

        SMTP.rejectDelivery(true);
        assertThat(worker.processNext()).isTrue();
        assertThat(worker.processNext()).isFalse();
        var rows = jdbc.queryForList("""
                SELECT status, attempts, last_failure, extract(epoch FROM next_attempt_at - updated_at) AS delay
                FROM application_notice_outbox WHERE kind = 'RECRUITER_WITHDRAWAL'
                """);
        assertThat(rows).hasSize(2);
        for (var row : rows) {
            assertThat(row).containsEntry("status", "RETRY_WAIT").containsEntry("attempts", 1)
                    .containsEntry("last_failure", "SMTP_TEMPORARY");
            assertThat(((Number) row.get("delay")).doubleValue()).isBetween(59.0, 60.5);
        }
        assertThat(SMTP.messages()).isEmpty();

        SMTP.rejectDelivery(false);
        makeDue();
        dueFirst(messageId);
        drain();
        assertThat(SMTP.messages()).hasSize(1);
        assertThat(SMTP.messages().getFirst().getMessageID()).isEqualTo(messageId);
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT status || '/' || attempts FROM application_notice_outbox
                WHERE kind = 'RECRUITER_WITHDRAWAL'
                """, String.class)).containsExactly("SENT/2");
    }

    // A recipient the mail server refuses is a permanent failure after the first attempt.
    @Test
    void aRefusedRecipientFailsAtOnce() throws Exception {
        UUID posting = posting(JAVA);
        UUID primary = staff("Trần Văn Bình", Role.RECRUITER);
        var application = stored(posting, APPLICANT_EMAIL);
        assign(application.requisitionId(), primary, "PRIMARY");
        ok(withdraw(application, null));
        SMTP.rejectRecipient(emailOf(primary));

        makeDue();
        drain();

        assertThat(jdbc.queryForObject("""
                SELECT status || '/' || attempts || '/' || last_failure FROM application_notice_outbox
                WHERE kind = 'RECRUITER_WITHDRAWAL'
                """, String.class)).isEqualTo("FAILED/1/RECIPIENT_REFUSED");
        assertThat(worker.processNext()).isFalse();
    }

    // A worker that crashed during its 6th attempt: the expired lease ends the batch as DELIVERY_UNCERTAIN, with no
    // seventh claim and no mail.
    @Test
    void anExpiredLeaseOnTheSixthAttemptEndsAsDeliveryUncertain() throws Exception {
        UUID posting = posting(JAVA);
        UUID primary = staff("Trần Văn Bình", Role.RECRUITER);
        var first = stored(posting, "a." + UUID.randomUUID() + "@example.test");
        var second = stored(posting, "b." + UUID.randomUUID() + "@example.test");
        assign(first.requisitionId(), primary, "PRIMARY");
        ok(withdraw(first, null));
        ok(withdraw(second, null));
        drain();
        SMTP.reset();
        makeDue();

        for (int attempt = 1; attempt <= 6; attempt++) {
            var batch = outbox.claim().orElseThrow();
            assertThat(batch.ids()).hasSize(2);
            jdbc.update("UPDATE application_notice_outbox SET lease_until = clock_timestamp() - INTERVAL '1 second' "
                    + "WHERE status = 'PROCESSING'");
        }
        assertThat(outbox.claim()).isEmpty();
        assertThat(jdbc.queryForList("""
                SELECT DISTINCT status || '/' || attempts || '/' || last_failure FROM application_notice_outbox
                WHERE kind = 'RECRUITER_WITHDRAWAL'
                """, String.class)).containsExactly("FAILED/6/DELIVERY_UNCERTAIN");
        assertThat(SMTP.messages()).isEmpty();
    }

    // The enqueue joins the withdrawal's transaction: when it fails, the withdrawal answers 503 and keeps nothing.
    @Test
    void anEnqueueFailureUndoesTheWithdrawal() throws Exception {
        UUID posting = posting(JAVA);
        UUID primary = staff("Trần Văn Bình", Role.RECRUITER);
        var application = stored(posting, APPLICANT_EMAIL);
        assign(application.requisitionId(), primary, "PRIMARY");
        jdbc.execute("CREATE FUNCTION fail_notice_enqueue() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'private-notice-detail'; END $$");
        jdbc.execute("CREATE TRIGGER fail_notice_enqueue AFTER INSERT ON application_notice_outbox "
                + "FOR EACH ROW EXECUTE FUNCTION fail_notice_enqueue()");
        try {
            var response = withdraw(application, REASON);
            assertUnavailable(response);
        } finally {
            jdbc.execute("DROP TRIGGER fail_notice_enqueue ON application_notice_outbox");
            jdbc.execute("DROP FUNCTION fail_notice_enqueue()");
        }
        assertThat(stageOf(application)).isEqualTo("NEW");
        assertThat(count("SELECT count(*) FROM application_stage_events")).isZero();
        assertThat(count("SELECT count(*) FROM application_notice_outbox")).isZero();
        ok(withdraw(application, REASON));
        assertThat(count("SELECT count(*) FROM application_notice_outbox")).isEqualTo(2);
    }

    // A failure at commit (a deferred constraint trigger on the stage event) keeps neither the stage, nor the event,
    // nor a notice of either kind: a notice queued in a transaction of its own would have committed already.
    @Test
    void aFailureAtCommitKeepsNoNotice() throws Exception {
        UUID posting = posting(JAVA);
        UUID primary = staff("Trần Văn Bình", Role.RECRUITER);
        var application = stored(posting, APPLICANT_EMAIL);
        assign(application.requisitionId(), primary, "PRIMARY");
        jdbc.execute("CREATE FUNCTION fail_notice_commit() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'private-commit-detail'; END $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER fail_notice_commit AFTER INSERT ON application_stage_events "
                + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_notice_commit()");
        try {
            assertUnavailable(withdraw(application, REASON));
        } finally {
            jdbc.execute("DROP TRIGGER fail_notice_commit ON application_stage_events");
            jdbc.execute("DROP FUNCTION fail_notice_commit()");
        }
        assertThat(stageOf(application)).isEqualTo("NEW");
        assertThat(count("SELECT count(*) FROM application_stage_events")).isZero();
        assertThat(count("SELECT count(*) FROM application_notice_outbox")).isZero();
        assertThat(worker.processNext()).isFalse();
        assertThat(SMTP.messages()).isEmpty();
    }

    // No payload is stored: the columns are ids, delivery state and times only; the kinds the CHECK accepts are exactly
    // the enum's.
    @Test
    void theOutboxStoresNoPayload() {
        assertThat(jdbc.queryForList("""
                SELECT column_name::text FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'application_notice_outbox'
                ORDER BY ordinal_position
                """, String.class)).containsExactly("id", "kind", "application_id", "recipient_id", "message_id",
                "status", "attempts", "next_attempt_at", "lease_token", "lease_until", "last_failure", "created_at",
                "updated_at", "sent_at");
        String check = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conrelid = 'application_notice_outbox'::regclass AND conname = 'application_notice_outbox_kind'
                """, String.class);
        var kinds = new ArrayList<String>();
        var matcher = java.util.regex.Pattern.compile("'([A-Z_]+)'").matcher(check);
        while (matcher.find()) kinds.add(matcher.group(1));
        assertThat(kinds).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(ApplicationNoticeKind.values()).map(Enum::name).toList());
    }

    // --- helpers ---

    private void assertNoApplicantText(MimeMessage message, Stored application) throws Exception {
        String everything = message.getContent() + "\n" + String.join("\n", headers(message));
        // "internal-hire.co" is the phishing domain; the real addresses and pages here are on internal-hire.example.
        assertThat(everything).doesNotContain(APPLICANT_NAME, "Tài khoản của bạn", "internal-hire.co", PHONE,
                COVER_LETTER, REASON, "LY_DO", application.code(), CV_NAME, "Rieng_Tu");
    }

    private static List<String> headers(MimeMessage message) throws Exception {
        return Collections.list(message.getAllHeaderLines()).stream()
                .filter(line -> !line.regionMatches(true, 0, "To:", 0, 3)).toList();
    }

    private static List<String> lines(String body) {
        return body.lines().filter(line -> line.startsWith("- Vị trí")).toList();
    }

    private MimeMessage message(String subject) {
        return SMTP.messages().stream().filter(message -> subjectOf(message).equals(subject)).findFirst().orElseThrow();
    }

    private static String subjectOf(MimeMessage message) {
        try { return message.getSubject(); } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static String to(MimeMessage message) throws Exception {
        var recipients = message.getRecipients(Message.RecipientType.TO);
        assertThat(recipients).hasSize(1);
        return recipients[0].toString();
    }

    private void assertUnavailable(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
        assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("APPLICATION_TRACKING_UNAVAILABLE");
        assertThat(response.body()).doesNotContain("private-", "application_notice_outbox", "application_stage_events");
    }

    // Every due row once, through the worker (at most 50 claims).
    private void drain() {
        for (int claims = 0; claims < 50 && worker.processNext(); claims++) { /* next */ }
        assertThat(worker.processNext()).isFalse();
    }

    // The 900 s recruiter delay (and a retry delay) has passed.
    private void makeDue() {
        jdbc.update("UPDATE application_notice_outbox SET next_attempt_at = clock_timestamp() - INTERVAL '1 second' "
                + "WHERE status IN ('PENDING', 'RETRY_WAIT')");
    }

    // This row is due before every other one, so the next claim takes it first.
    private void dueFirst(String messageId) {
        jdbc.update("UPDATE application_notice_outbox SET next_attempt_at = clock_timestamp() - INTERVAL '1 minute' "
                + "WHERE message_id = ?", messageId);
    }

    private void change(UUID account, UUID requisition, String situation) {
        switch (situation) {
            case "supporting only" -> { /* assigned as SUPPORTING above */ }
            case "disabled" -> jdbc.update("UPDATE user_accounts SET enabled = false WHERE id = ?", account);
            case "locked" -> jdbc.update("UPDATE user_accounts SET admin_locked_at = clock_timestamp(), "
                    + "admin_lock_reason = 'Nghỉ việc', admin_locked_by = ? WHERE id = ?", hr, account);
            case "roles removed" -> jdbc.update("DELETE FROM user_roles WHERE user_id = ?", account);
            case "removed from the team" -> jdbc.update(
                    "DELETE FROM requisition_recruiters WHERE requisition_id = ? AND recruiter_id = ?", requisition,
                    account);
            case "moved to supporting" -> jdbc.update("UPDATE requisition_recruiters "
                    + "SET assignment_role = 'SUPPORTING' WHERE requisition_id = ? AND recruiter_id = ?", requisition,
                    account);
            default -> throw new IllegalArgumentException(situation);
        }
    }

    // What RequisitionRecruiterRepository.apply writes for PRIMARY_HANDED_OVER: the old PRIMARY row goes (with a
    // SUPPORTING row of the new one), then the new PRIMARY row is inserted.
    private void handOver(UUID requisition, UUID newPrimary) {
        assertThat(jdbc.update("DELETE FROM requisition_recruiters WHERE requisition_id = ? "
                + "AND (assignment_role = 'PRIMARY' OR recruiter_id = ?)", requisition, newPrimary)).isEqualTo(1);
        assign(requisition, newPrimary, "PRIMARY");
    }

    private UUID requisitionOf(UUID posting) {
        return jdbc.queryForObject("SELECT requisition_id FROM job_postings WHERE id = ?", UUID.class, posting);
    }

    private UUID recipientOf(Stored application) {
        return jdbc.queryForObject("SELECT recipient_id FROM application_notice_outbox WHERE application_id = ? "
                + "AND kind = 'RECRUITER_WITHDRAWAL'", UUID.class, application.id());
    }

    private String statusOf(Stored application, String kind) {
        return jdbc.queryForObject("SELECT status FROM application_notice_outbox WHERE application_id = ? AND kind = ?",
                String.class, application.id(), kind);
    }

    private String stageOf(Stored application) {
        return jdbc.queryForObject("SELECT stage FROM job_applications WHERE id = ?", String.class, application.id());
    }

    private Instant withdrawnAt(Stored application) {
        return jdbc.queryForObject("SELECT occurred_at FROM application_stage_events WHERE application_id = ?",
                Timestamp.class, application.id()).toInstant();
    }

    private int count(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private String emailOf(UUID account) {
        return jdbc.queryForObject("SELECT email FROM user_accounts WHERE id = ?", String.class, account);
    }

    private HttpResponse<String> withdraw(Stored application, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("trackingCode", application.code());
        body.put("email", application.email());
        if (reason != null) body.put("reason", reason);
        body.put("confirm", true);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + environment.getRequiredProperty("local.server.port") + ApplicationTrackingRoutes.WITHDRAW))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    // An enabled staff account with one role and a fresh address.
    private UUID staff(String fullName, Role role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO user_accounts(id,email,password_hash,full_name,enabled,created_at) VALUES (?,?,'test-only',?,true,?)",
                id, "staff." + id + "@internal-hire.example", fullName, Timestamp.from(RECEIVED_AT));
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role.name());
        return id;
    }

    private void assign(UUID requisition, UUID recruiter, String role) {
        jdbc.update("INSERT INTO requisition_recruiters(requisition_id,recruiter_id,assignment_role,assigned_by,assigned_at) VALUES (?,?,?,?,?)",
                requisition, recruiter, role, hr, Timestamp.from(RECEIVED_AT));
    }

    // Stored through TRAN DUC LUONG's ApplicationStorageService.store with SHA-256 of 32 known random bytes, with every
    // field an applicant can type; received_at is moved back by SQL so the receipt and withdrawal times differ.
    private Stored stored(UUID posting, String email) {
        byte[] token = new byte[32];
        random.nextBytes(token);
        try {
            String code = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
            var saved = storage.store(posting, new ApplicationSubmission(APPLICANT_NAME, email, PHONE, null,
                    COVER_LETTER), pdf(), MessageDigest.getInstance("SHA-256").digest(token));
            jdbc.update("UPDATE job_applications SET received_at = ? WHERE id = ?", Timestamp.from(RECEIVED_AT),
                    saved.id());
            UUID candidate = jdbc.queryForObject("SELECT candidate_id FROM candidate_applications WHERE application_id = ?",
                    UUID.class, saved.id());
            return new Stored(saved.id(), saved.requisitionId(), candidate, code, email);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        } finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    private ValidatedCv pdf() {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return validator.validate(CV_NAME, new ByteArrayInputStream(output.toByteArray()));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    // A PUBLISHED posting (deadline 31 Dec 2026) of a new APPROVED requisition, with its own account, department,
    // position and catalog values.
    private UUID posting(String title) {
        UUID account = UUID.randomUUID(), department = UUID.randomUUID(), position = UUID.randomUUID();
        UUID requisition = UUID.randomUUID(), posting = UUID.randomUUID();
        Timestamp at = Timestamp.from(Instant.parse("2026-09-01T03:00:00Z"));
        jdbc.update("INSERT INTO user_accounts(id,email,password_hash,full_name,enabled,created_at) VALUES (?,?,'test-only','HR',true,?)",
                account, account + "@example.test", at);
        jdbc.update("INSERT INTO departments(id,code,name,manager_user_id) VALUES (?,?,'IT',?)", department,
                department.toString(), account);
        jdbc.update("INSERT INTO positions(id,code,name,level,salary_min,salary_max,created_at,updated_at) VALUES (?,?,'Developer','Junior',0,0,?,?)",
                position, position.toString(), at, at);
        UUID location = catalog("WORK_LOCATION", at), employment = catalog("EMPLOYMENT_TYPE", at);
        jdbc.update("""
                INSERT INTO recruitment_requisitions(id,position_id,department_id,headcount,reason,status,created_by,created_at,updated_at)
                VALUES (?,?,?,1,'NEW_HEADCOUNT','APPROVED',?,?,?)
                """, requisition, position, department, account, at, at);
        jdbc.update("""
                INSERT INTO job_postings(id,requisition_id,status,revision,title,job_description,candidate_requirements,
                    work_location_id,employment_type_id,application_deadline,show_salary,created_by,created_at,updated_at,
                    published_by,published_at)
                VALUES (?,?,'PUBLISHED',1,?,'Description','Requirements',?,?,?,false,?,?,?,?,?)
                """, posting, requisition, title, location, employment, LocalDate.of(2026, 12, 31), account, at, at,
                account, at);
        return posting;
    }

    private UUID catalog(String type, Timestamp at) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO recruitment_catalog_items(id,catalog_type,code,name,sort_order,active,created_at,updated_at) VALUES (?,?,?,'Visible',0,true,?,?)",
                id, type, id.toString(), at, at);
        return id;
    }
}
