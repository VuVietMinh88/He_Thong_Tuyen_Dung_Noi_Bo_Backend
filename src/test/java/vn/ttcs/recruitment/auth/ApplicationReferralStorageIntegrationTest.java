package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.ApplicationSubmission;
import vn.ttcs.recruitment.application.CvValidator;
import vn.ttcs.recruitment.application.DuplicateApplicationException;
import vn.ttcs.recruitment.application.ReferralApplicationStorage;
import vn.ttcs.recruitment.application.ReferralApplicationStorage.ReferralDeclaration;
import vn.ttcs.recruitment.application.StoredApplication;
import vn.ttcs.recruitment.application.ValidatedCv;
import vn.ttcs.recruitment.common.ApiException;

import javax.sql.DataSource;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 350 (story S4-07, "Hồ sơ được gắn nhãn nguồn Giới thiệu nội bộ và tên người giới thiệu"): the storage boundary
 * of a referral, ReferralApplicationStorage.store, over real PostgreSQL without HTTP. It stores the application through
 * TRAN DUC LUONG's ApplicationStorageService.store in the caller's transaction, then the referral row of V36; it locks
 * the requisition FOR NO KEY UPDATE itself before the storage (the 321 rule). The confirmation worker never runs (poll
 * delay of an hour); no referral API exists yet (task 351), so the referrer is any staff account.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000", "app.application-notice.poll-delay-ms=3600000",
        "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationReferralStorageIntegrationTest {
    // A clock with nanoseconds: the receipt (and so the consent time) is cut to microseconds by store().
    private static final Instant LATER = ApplicationTestSupport.NOW.plus(Duration.ofHours(2)).plusNanos(123_456_789);
    private static final String NOTE = "Bạn học cũ, mạnh Java";
    private static final String NOTE_FIELD = "referralNote";
    private static final String EMAIL = "lan.nguyen@example.test";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private Environment environment;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationStorageService storage;
    @Autowired private ReferralApplicationStorage referrals;
    @Autowired private CvValidator validator;
    @Autowired private DataSource dataSource;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private final SecureRandom random = new SecureRandom();
    private ApplicationTestSupport applications;
    private UUID posting;
    private UUID requisition;
    private UUID referrer;

    @BeforeEach
    void fixture() {
        clock.set(ApplicationTestSupport.NOW);
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();
        posting = applications.publishedPosting("Lập trình viên Java");
        requisition = applications.requisitionOf(posting);
        referrer = applications.staff("Trần Văn Nam", "nam.referrer@example.test");
    }

    // One transaction writes the application (stage NEW, no catalog source, the posting-title snapshot), its CV, its
    // candidate link and the referral: the referrer, the note with Windows line breaks turned into \n and the consent
    // time, which is the application's receipt time cut to microseconds.
    @Test
    void aReferralStoresTheApplicationItsCvItsCandidateLinkAndTheReferral() {
        clock.set(LATER);
        ValidatedCv cv = applications.docx("CV Nguyễn Thị Lan.docx");
        StoredApplication stored = store(posting, submission(" Lan.Nguyen@Example.TEST "), cv,
                new ReferralDeclaration(referrer, "  Bạn học cũ,\r\nmạnh Java\rvà SQL  ", true));

        Instant received = LATER.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        assertThat(stored.receivedAt()).isEqualTo(received);
        assertThat(jdbc.queryForMap("SELECT stage, source_id, posting_title, normalized_email, received_at"
                + " FROM job_applications WHERE id = ?", stored.id()))
                .containsEntry("stage", "NEW")
                .containsEntry("source_id", null)
                .containsEntry("posting_title", "Lập trình viên Java")
                .containsEntry("normalized_email", EMAIL)
                .containsEntry("received_at", Timestamp.from(received));
        assertThat(jdbc.queryForObject("SELECT content FROM application_cvs WHERE application_id = ?", byte[].class,
                stored.id())).isEqualTo(cv.content());
        UUID candidate = applications.candidateOf(stored.id());
        assertThat(jdbc.queryForObject("SELECT normalized_email FROM candidates WHERE id = ?", String.class, candidate))
                .isEqualTo(EMAIL);
        assertThat(jdbc.queryForMap("SELECT referrer_id, note, consent_declared_at FROM application_referrals"
                + " WHERE application_id = ?", stored.id()))
                .containsEntry("referrer_id", referrer)
                .containsEntry("note", "  Bạn học cũ,\nmạnh Java\nvà SQL  ")
                .containsEntry("consent_declared_at", Timestamp.from(received));
        assertThat(count("application_referrals")).isEqualTo(1);
    }

    // A note of exactly 2000 characters is kept as it is (an emoji family joined by U+200D is visible text), and so is
    // one that reaches 2000 only once its Windows line break became \n: the length is checked after the normalization.
    @Test
    void aNoteOfExactly2000CharactersIsKept() {
        String note = "a".repeat(1990) + " <3 " + Character.toString(0x1F468) + Character.toString(0x200D)
                + Character.toString(0x1F469);
        assertThat(note.length()).isEqualTo(1999);
        String longest = note + "!";
        StoredApplication stored = store(posting, submission(EMAIL), applications.pdf(),
                new ReferralDeclaration(referrer, longest, true));

        assertThat(jdbc.queryForObject("SELECT note FROM application_referrals WHERE application_id = ?", String.class,
                stored.id())).isEqualTo(longest);

        String windows = "a".repeat(1998) + "\r\nb";
        assertThat(windows.length()).isEqualTo(2001);
        StoredApplication normalized = store(applications.publishedPosting("Kiểm thử phần mềm"), submission(EMAIL),
                applications.pdf(), new ReferralDeclaration(referrer, windows, true));
        assertThat(jdbc.queryForObject("SELECT note FROM application_referrals WHERE application_id = ?", String.class,
                normalized.id())).isEqualTo("a".repeat(1998) + "\nb");
    }

    // The shared duplicate rule of task 327 holds in both orders: a public application then a referral of the same
    // normalized email into the same requisition, and a referral then a public application. Each second one is 409
    // and leaves neither an application nor a referral.
    @Test
    void aDuplicateInEitherOrderIs409AndLeavesNoReferral() {
        var publicFirst = applications.stored(posting, EMAIL);
        assertThatThrownBy(() -> store(posting, submission(" LAN.NGUYEN@example.test "), applications.pdf(), declaration()))
                .isInstanceOf(DuplicateApplicationException.class);
        assertThat(count("application_referrals")).isZero();
        assertThat(jdbc.queryForList("SELECT id FROM job_applications", UUID.class))
                .containsExactly(publicFirst.applicationId());

        UUID otherPosting = applications.publishedPosting("Kiểm thử phần mềm");
        StoredApplication referredFirst = store(otherPosting, submission(EMAIL), applications.pdf(), declaration());
        assertThatThrownBy(() -> applications.stored(otherPosting, "Lan.Nguyen@EXAMPLE.test"))
                .isInstanceOf(DuplicateApplicationException.class);
        assertThat(jdbc.queryForList("SELECT application_id FROM application_referrals", UUID.class))
                .containsExactly(referredFirst.id());
        assertThat(count("job_applications")).isEqualTo(2);
        assertThat(count("application_cvs")).isEqualTo(2);
        assertThat(count("candidates")).isEqualTo(1);
        assertThat(count("candidate_applications")).isEqualTo(2);
    }

    // A paused requisition, a closed posting, a posting past its deadline and an unknown posting answer store()'s 404
    // and store nothing at all.
    @ParameterizedTest
    @ValueSource(strings = {"PAUSED", "CLOSED", "PAST_DEADLINE", "UNKNOWN"})
    void aPostingThatTakesNoApplicationsIs404AndStoresNothing(String state) {
        UUID target = posting;
        switch (state) {
            case "PAUSED" -> jdbc.update("UPDATE recruitment_requisitions SET status = 'PAUSED' WHERE id = ?", requisition);
            case "CLOSED" -> jdbc.update("UPDATE job_postings SET status = 'CLOSED' WHERE id = ?", posting);
            case "PAST_DEADLINE" -> jdbc.update("UPDATE job_postings SET application_deadline = ? WHERE id = ?",
                    LocalDate.of(2026, 10, 14), posting);
            case "UNKNOWN" -> target = UUID.randomUUID();
            default -> throw new IllegalArgumentException(state);
        }
        UUID unavailable = target;

        assertThatThrownBy(() -> store(unavailable, submission(EMAIL), applications.pdf(), declaration()))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus().value()).isEqualTo(404);
                    assertThat(error.getCode()).isEqualTo("JOB_POSTING_NOT_FOUND");
                    assertThat(error.getMessage()).isEqualTo("Tin tuyển dụng không còn nhận hồ sơ.");
                });
        assertNothingStored();
    }

    static Stream<Arguments> refusedNotes() {
        String nbsp = Character.toString(0x00A0), zeroWidth = Character.toString(0x200B);
        String required = "Vui lòng nhập ghi chú giới thiệu.";
        String tooLong = "Ghi chú giới thiệu tối đa 2.000 ký tự.";
        String notPlain = "Ghi chú giới thiệu chỉ được là văn bản thuần, không chứa thẻ HTML hoặc ký tự điều khiển.";
        return Stream.of(
                Arguments.of("null", null, required),
                Arguments.of("empty", "", required),
                Arguments.of("spaces and line breaks", "  \r\n\t ", required),
                Arguments.of("invisible characters only", nbsp + zeroWidth + Character.toString(0xFEFF) + "\n", required),
                Arguments.of("2001 characters", "a".repeat(2001), tooLong),
                Arguments.of("an HTML tag", "Ứng viên tốt <script>alert(1)</script>", notPlain),
                Arguments.of("NUL", "Ứng viên" + Character.toString(0) + "tốt", notPlain),
                Arguments.of("a bidi override", "Ứng viên " + Character.toString(0x202E) + "tốt", notPlain));
    }

    // The note is checked before any SQL: without a visible character, longer than 2000 characters or not plain text it
    // is 400 VALIDATION_ERROR naming referralNote, even for an unknown posting (no 404 is reached), and the error
    // never echoes the note.
    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedNotes")
    void aNoteWithoutVisibleTextTooLongOrNotPlainIs400BeforeAnyRow(String name, String note, String message) {
        for (UUID target : List.of(posting, UUID.randomUUID())) {
            assertThatThrownBy(() -> store(target, submission(EMAIL), applications.pdf(),
                    new ReferralDeclaration(referrer, note, true)))
                    .as(name)
                    .isInstanceOfSatisfying(ApiException.class, error -> {
                        assertThat(error.getStatus().value()).isEqualTo(400);
                        assertThat(error.getCode()).isEqualTo("VALIDATION_ERROR");
                        assertThat(error.getFieldErrors()).isEqualTo(Map.of(NOTE_FIELD, message));
                        if (note != null && !note.isBlank()) {
                            assertThat(error.getMessage()).doesNotContain(note);
                        }
                    });
        }
        assertNothingStored();
    }

    // Programming errors of the caller: the referral API (task 351) refuses each before it calls the storage, so they
    // are exceptions, not answers, and nothing is stored.
    @Test
    void programmingErrorsOfTheCallerAreRefusedAndStoreNothing() {
        ValidatedCv cv = applications.pdf();
        UUID linkedIn = applications.source("LinkedIn");
        assertThatThrownBy(() -> store(posting, submission(EMAIL), cv, new ReferralDeclaration(referrer, NOTE, false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store(posting, submission(EMAIL), cv, new ReferralDeclaration(null, NOTE, true)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store(posting, submission(EMAIL), cv, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store(null, submission(EMAIL), cv, declaration()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store(posting,
                new ApplicationSubmission(ApplicationTestSupport.FULL_NAME, EMAIL, ApplicationTestSupport.PHONE, linkedIn,
                        null), cv, declaration()))
                .isInstanceOf(IllegalArgumentException.class);

        // MANDATORY: never its own transaction.
        assertThatThrownBy(() -> referrals.store(posting, submission(EMAIL), cv, hash(), declaration()))
                .isInstanceOf(IllegalTransactionStateException.class);
        // READ COMMITTED and read-write only.
        for (int isolation : List.of(TransactionDefinition.ISOLATION_REPEATABLE_READ,
                TransactionDefinition.ISOLATION_SERIALIZABLE)) {
            var transaction = new TransactionTemplate(transactions);
            transaction.setIsolationLevel(isolation);
            assertThatThrownBy(() -> transaction.execute(status ->
                    referrals.store(posting, submission(EMAIL), cv, hash(), declaration())))
                    .isInstanceOf(IllegalStateException.class);
        }
        var readOnly = new TransactionTemplate(transactions);
        readOnly.setReadOnly(true);
        assertThatThrownBy(() -> readOnly.execute(status ->
                referrals.store(posting, submission(EMAIL), cv, hash(), declaration())))
                .isInstanceOf(IllegalStateException.class);
        assertNothingStored();

        // The default isolation is PostgreSQL's READ COMMITTED: accepted.
        new TransactionTemplate(transactions).execute(status ->
                referrals.store(posting, submission(EMAIL), cv, hash(), declaration()));
        assertThat(count("application_referrals")).isEqualTo(1);
    }

    // The referral row is written in the application's transaction, after it: a caller's rollback leaves nothing, and
    // a referral row that cannot be written (an unknown referrer account) takes the application, its CV, its
    // candidate and its link with it.
    @Test
    void aRollbackOrAFailedReferralRowLeavesNothing() {
        ValidatedCv cv = applications.pdf();
        readCommitted().executeWithoutResult(status -> {
            referrals.store(posting, submission(EMAIL), cv, hash(), declaration());
            assertThat(count("application_referrals")).isEqualTo(1);
            status.setRollbackOnly();
        });
        assertNothingStored();

        assertThatThrownBy(() -> store(posting, submission(EMAIL), cv,
                new ReferralDeclaration(UUID.randomUUID(), NOTE, true)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("application_referrals_referrer_id_fkey");
        assertNothingStored();
    }

    // V36's own rules, written by SQL: one row per application, a note with a non-space character and at most 2000
    // characters, a known application and referrer; the referrer's account cannot be deleted (RESTRICT); deleting the
    // application deletes its referral (CASCADE). The declaration's toString hides the note.
    @Test
    void theSchemaKeepsOneVisibleReferralPerApplicationAndItsReferrer() {
        StoredApplication referred = store(posting, submission(EMAIL), applications.pdf(), declaration());
        var plain = applications.stored(posting, "other@example.test");
        Timestamp at = Timestamp.from(ApplicationTestSupport.NOW);

        assertThatThrownBy(() -> insertReferral(referred.id(), referrer, NOTE, at))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("application_referrals_pkey");
        assertThatThrownBy(() -> insertReferral(plain.applicationId(), referrer, " \n\t ", at))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("application_referrals_visible_note");
        assertThatThrownBy(() -> insertReferral(plain.applicationId(), referrer, "a".repeat(2001), at))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReferral(plain.applicationId(), referrer, null, at))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReferral(plain.applicationId(), referrer, NOTE, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertReferral(UUID.randomUUID(), referrer, NOTE, at))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("application_referrals_application_id_fkey");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM user_accounts WHERE id = ?", referrer))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("application_referrals_referrer_id_fkey");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema()"
                + " AND indexname = 'application_referrals_referrer_idx'", Integer.class)).isEqualTo(1);

        jdbc.update("DELETE FROM job_applications WHERE id = ?", referred.id());
        assertThat(count("application_referrals")).isZero();
        jdbc.update("DELETE FROM user_accounts WHERE id = ?", referrer);

        String declaration = declaration().toString();
        assertThat(declaration).doesNotContain(NOTE).contains("redacted");
    }

    // The 321 rule, whoever calls the storage: the requisition is locked FOR NO KEY UPDATE before store(). A raw
    // connection holds the posting row, the next row store() needs after the requisition; the referral then waits
    // inside store() (pg_blocking_pids names the raw connection), holding the requisition so firmly that a FOR SHARE
    // NOWAIT from a third connection fails (55P03); store()'s own FOR SHARE alone would let it through. Released, the
    // referral completes.
    @Test
    void theReferralLocksTheRequisitionForNoKeyUpdateBeforeTheStorage() throws Exception {
        ValidatedCv cv = applications.pdf();
        byte[] hash = hash();
        try (var blocker = dataSource.getConnection(); var probe = dataSource.getConnection();
             var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("SELECT 1 FROM job_postings WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, posting);
                lock.executeQuery().close();
            }
            var referralPid = new AtomicInteger();
            var referral = executor.submit(() -> readCommitted().execute(status -> {
                referralPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                return referrals.store(posting, submission(EMAIL), cv, hash, declaration());
            }));
            try {
                assertThat(applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker)))
                        .isEqualTo(referralPid.get());

                probe.setAutoCommit(false);
                try (var share = probe.prepareStatement(
                        "SELECT 1 FROM recruitment_requisitions WHERE id = ? FOR SHARE NOWAIT")) {
                    share.setObject(1, requisition);
                    assertThatThrownBy(share::executeQuery).isInstanceOfSatisfying(SQLException.class,
                            error -> assertThat(error.getSQLState()).isEqualTo("55P03"));
                }
                probe.rollback();
            } finally {
                blocker.rollback();
            }
            StoredApplication stored = referral.get(20, TimeUnit.SECONDS);
            assertThat(jdbc.queryForList("SELECT application_id FROM application_referrals", UUID.class))
                    .containsExactly(stored.id());
        }
    }

    private StoredApplication store(UUID target, ApplicationSubmission submission, ValidatedCv cv,
                                    ReferralDeclaration referral) {
        byte[] hash = hash();
        return readCommitted().execute(status -> referrals.store(target, submission, cv, hash, referral));
    }

    private TransactionTemplate readCommitted() {
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private ReferralDeclaration declaration() {
        return new ReferralDeclaration(referrer, NOTE, true);
    }

    private static ApplicationSubmission submission(String email) {
        return new ApplicationSubmission(ApplicationTestSupport.FULL_NAME, email, ApplicationTestSupport.PHONE, null, null);
    }

    private byte[] hash() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return value;
    }

    private void insertReferral(UUID application, UUID account, String note, Timestamp at) {
        jdbc.update("INSERT INTO application_referrals (application_id, referrer_id, note, consent_declared_at)"
                + " VALUES (?, ?, ?, ?)", application, account, note, at);
    }

    private void assertNothingStored() {
        for (String table : List.of("job_applications", "application_cvs", "candidates", "candidate_applications",
                "application_referrals")) {
            assertThat(count(table)).as(table).isZero();
        }
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
