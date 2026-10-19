package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.ApplicationSubmission;
import vn.ttcs.recruitment.application.CvValidator;
import vn.ttcs.recruitment.application.DuplicateApplicationException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static vn.ttcs.recruitment.application.PublicResponseContract.assertJsonKeys;

/**
 * Task 345 (story S4-06, "Rút hồ sơ qua liên kết trong email xác nhận, có xác nhận lại trước khi thực hiện"): POST
 * /api/v1/public/applications/withdraw over a real socket and a real PostgreSQL. A candidate without an account proves
 * one application with its tracking code and email, confirms, and the application leaves the active pipeline with one
 * CANDIDATE stage event (V34). Default quota (30 per hour, reset by every test); the confirmation worker never runs.
 * Locking and concurrency are in ApplicationWithdrawalLockIntegrationTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000",
        "app.application-notice.poll-delay-ms=3600000",
        "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationWithdrawalIntegrationTest {
    private static final String EMAIL = "Nguyen.Van.A@Example.test";
    private static final String TITLE = "Lập trình viên Java";
    private static final String REASON = "Đã tìm được công việc khác";
    // The clock has nanoseconds; the stored and returned time is truncated to microseconds, as PostgreSQL keeps it.
    private static final Instant WITHDRAWAL_TIME = Instant.parse("2026-10-16T02:00:00.123456789Z");
    private static final Instant WITHDRAWN_AT = Instant.parse("2026-10-16T02:00:00.123456Z");
    private static final String[] KEYS = {"postingTitle", "receivedAt", "status", "statusName", "withdrawnAt"};
    private static final String CONFIRM = "Vui lòng xác nhận rút hồ sơ.";
    private static final String PLAIN_TEXT = "Chỉ được nhập văn bản thuần có ký tự nhìn thấy được, không chứa thẻ HTML "
            + "hoặc ký tự điều khiển.";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private Environment environment;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationStorageService storage;
    @Autowired private CvValidator validator;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private TokenService tokens;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private ApplicationTestSupport applications;
    private int port;
    private UUID posting;
    private UUID requisition;

    @BeforeEach
    void fixture() {
        clock.set(ApplicationTestSupport.NOW);
        port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();
        posting = applications.publishedPosting(TITLE);
        requisition = applications.requisitionOf(posting);
    }

    // Each stage still in the pipeline: 200 with exactly the five public keys, the stage becomes WITHDRAWN and one
    // CANDIDATE event records the stage before, the reason and the clock's time in microseconds. The email may be
    // typed in another letter case.
    @ParameterizedTest
    @EnumSource(value = ApplicationStage.class, names = {"NEW", "SCREENING", "INTERVIEW", "OFFER"})
    void everyActiveStageIsWithdrawnWithOneCandidateEvent(ApplicationStage stage) throws Exception {
        var stored = applications.stored(posting, EMAIL);
        applications.stage(stored.applicationId(), stage);
        clock.set(WITHDRAWAL_TIME);

        var response = applications.withdraw(stored.trackingCode(), "nguyen.van.a@EXAMPLE.TEST", REASON);
        JsonNode body = ok(response);
        assertJsonKeys(body, KEYS);
        assertThat(body.get("postingTitle").asString()).isEqualTo(TITLE);
        assertThat(body.get("receivedAt").asString()).isEqualTo(ApplicationTestSupport.NOW.toString());
        assertThat(body.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(body.get("statusName").asString()).isEqualTo("Kết thúc");
        assertThat(body.get("withdrawnAt").asString()).isEqualTo(WITHDRAWN_AT.toString());
        assertThat(response.body()).doesNotContain(REASON, stored.applicationId().toString(), requisition.toString(),
                posting.toString(), stored.trackingCode(), "WITHDRAWN", "Ứng viên rút", "\"" + stage.name() + "\"",
                ApplicationTestSupport.FULL_NAME, "Nguyen.Van.A");

        assertThat(stageOf(stored.applicationId())).isEqualTo("WITHDRAWN");
        var events = events();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).containsEntry("application_id", stored.applicationId())
                .containsEntry("stage_before", stage.name()).containsEntry("stage_after", "WITHDRAWN")
                .containsEntry("actor_type", "CANDIDATE").containsEntry("reason", REASON)
                .containsEntry("occurred_at", Timestamp.from(WITHDRAWN_AT));
        assertThat(events.getFirst().get("actor_id")).isNull();
        assertThat(applications.trackingAttempts()).isEqualTo(1);
    }

    // The application leaves the active pipeline at once: the count of task 291 drops by one per withdrawal, and after
    // the last active application is withdrawn, HR closes the requisition as filled without confirmActiveCandidates.
    @Test
    void aWithdrawalLeavesTheActivePipelineAndTheClosureNeedsNoConfirmation() throws Exception {
        String hr = token(account("Nguyễn Thị Hoa", "hr@example.test", Role.HR_MANAGER));
        var first = applications.stored(posting, EMAIL);
        var second = applications.stored(posting, "tran.thi.b@example.test");
        applications.stage(second.applicationId(), ApplicationStage.SCREENING);
        assertThat(activeCount(hr)).isEqualTo(2);

        ok(applications.withdraw(first.trackingCode(), EMAIL, null));
        assertThat(activeCount(hr)).isEqualTo(1);
        var refused = close(hr);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(json.readTree(refused.body()).get("code").asString()).isEqualTo("REQUISITION_HAS_ACTIVE_CANDIDATES");
        assertThat(json.readTree(refused.body()).get("activePipeline").get("count").asInt()).isEqualTo(1);

        ok(applications.withdraw(second.trackingCode(), "tran.thi.b@example.test", null));
        assertThat(activeCount(hr)).isZero();
        var closed = close(hr);
        assertThat(closed.statusCode()).as(closed.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT status FROM recruitment_requisitions WHERE id = ?", String.class,
                requisition)).isEqualTo("FILLED");
    }

    // The lookup offers the withdrawal (actions.canWithdraw) exactly while the stage is in the pipeline; after a
    // withdrawal it shows COMPLETED and no longer offers it.
    @ParameterizedTest
    @EnumSource(ApplicationStage.class)
    void theLookupOffersTheWithdrawalOnlyWhileTheApplicationIsActive(ApplicationStage stage) throws Exception {
        var stored = applications.stored(posting, EMAIL);
        applications.stage(stored.applicationId(), stage);

        JsonNode before = ok(applications.lookup(stored.trackingCode(), EMAIL));
        assertJsonKeys(before.get("actions"), "canWithdraw");
        assertThat(before.get("actions").get("canWithdraw").asBoolean()).isEqualTo(stage.active());
        if (!stage.active()) return;

        ok(applications.withdraw(stored.trackingCode(), EMAIL, REASON));
        JsonNode after = ok(applications.lookup(stored.trackingCode(), EMAIL));
        assertThat(after.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(after.get("statusName").asString()).isEqualTo("Kết thúc");
        assertThat(after.get("actions").get("canWithdraw").asBoolean()).isFalse();
        assertThat(after.toString()).doesNotContain(REASON);
    }

    // A repeat answers 200 with the first withdrawal's time and writes nothing, whatever the clock and the reason say
    // now; an application moved to WITHDRAWN without an event (raw SQL) answers 200 with withdrawnAt null.
    @Test
    void aRepeatAnswersTheSameAndWritesNothing() throws Exception {
        var stored = applications.stored(posting, EMAIL);
        clock.set(WITHDRAWAL_TIME);
        String first = ok(applications.withdraw(stored.trackingCode(), EMAIL, REASON)).toString();
        var event = events();

        clock.set(WITHDRAWAL_TIME.plusSeconds(3600));
        String repeat = ok(applications.withdraw(stored.trackingCode(), EMAIL, "Lý do khác")).toString();
        assertThat(repeat).isEqualTo(first).contains(WITHDRAWN_AT.toString());
        assertThat(events()).isEqualTo(event);

        var raw = applications.stored(applications.publishedPosting("Kế toán"), EMAIL);
        applications.stage(raw.applicationId(), ApplicationStage.WITHDRAWN);
        JsonNode withoutEvent = ok(applications.withdraw(raw.trackingCode(), EMAIL, REASON));
        assertJsonKeys(withoutEvent, KEYS);
        assertThat(withoutEvent.get("withdrawnAt").isNull()).isTrue();
        assertThat(withoutEvent.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(events()).isEqualTo(event);
    }

    // Terminal hiring/rejection outcomes return 409 without changing existing history.
    @ParameterizedTest
    @EnumSource(value = ApplicationStage.class, names = {"HIRED", "ONBOARDED", "REJECTED"})
    void anApplicationWithAnOutcomeCannotBeWithdrawn(ApplicationStage stage) throws Exception {
        var stored = applications.stored(posting, EMAIL);
        applications.stage(stored.applicationId(), stage);
        var previousEvents = events();
        var response = applications.withdraw(stored.trackingCode(), EMAIL, REASON);
        assertError(response, 409, "APPLICATION_NOT_WITHDRAWABLE");
        assertThat(json.readTree(response.body()).get("message").asString())
                .isEqualTo("Hồ sơ đã có kết quả nên không thể rút nữa.");
        assertThat(stageOf(stored.applicationId())).isEqualTo(stage.name());
        assertThat(events()).isEqualTo(previousEvents);
    }

    // A wrong email, an unknown code and a revoked code get exactly the lookup's 404: same status, body and headers.
    @Test
    void aWrongPairAnUnknownOrARevokedCodeGetTheLookupsNotFound() throws Exception {
        var stored = applications.stored(posting, EMAIL);
        var revoked = applications.stored(applications.publishedPosting("Kế toán"), EMAIL);
        jdbc.update("UPDATE job_applications SET tracking_revoked_at = clock_timestamp() WHERE id = ?",
                revoked.applicationId());
        String unknown = applications.unknownCode();

        for (String[] pair : List.of(new String[] {stored.trackingCode(), "other@example.test"},
                new String[] {unknown, EMAIL}, new String[] {revoked.trackingCode(), EMAIL})) {
            var withdrawal = applications.withdraw(pair[0], pair[1], REASON);
            var lookup = applications.lookup(pair[0], pair[1]);
            assertError(withdrawal, 404, "APPLICATION_LOOKUP_NOT_FOUND");
            assertThat(withdrawal.body()).isEqualTo(lookup.body());
            assertThat(headersWithoutDate(withdrawal)).isEqualTo(headersWithoutDate(lookup));
        }
        assertThat(jdbc.queryForList("SELECT DISTINCT stage FROM job_applications", String.class)).containsExactly("NEW");
        assertThat(events()).isEmpty();
    }

    // Every malformed request is refused before the database: no quota attempt, no change. Without confirm: true, a
    // well-formed pair withdraws nothing.
    @Test
    void malformedOrUnconfirmedRequestsAreRefusedBeforeAnythingIsCountedOrChanged() throws Exception {
        var stored = applications.stored(posting, EMAIL);
        String code = stored.trackingCode();
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("trackingCode", code);
        missing.put("email", EMAIL);
        assertField(post(applications.withdrawBody(missing)), "confirm", CONFIRM);
        assertField(post(applications.withdrawBody(code, EMAIL, REASON, null)), "confirm", CONFIRM);
        assertField(post(applications.withdrawBody(code, EMAIL, REASON, false)), "confirm", CONFIRM);
        assertField(post(applications.withdrawBody(code, EMAIL, "a".repeat(501), true)), "reason",
                "Lý do tối đa 500 ký tự.");
        assertField(post(applications.withdrawBody(code, EMAIL, "Lý do" + Character.toString(0) + "ẩn", true)),
                "reason", PLAIN_TEXT);
        assertField(post(applications.withdrawBody(code, EMAIL, "<script>alert(1)</script>", true)), "reason",
                PLAIN_TEXT);
        assertField(post(applications.withdrawBody(nonCanonical(code), EMAIL, REASON, true)), "trackingCode",
                "Mã tra cứu không hợp lệ.");
        assertField(post(applications.withdrawBody(code.substring(1), EMAIL, REASON, true)), "trackingCode",
                "Mã tra cứu gồm đúng 43 ký tự chữ, số, dấu '-' hoặc '_'.");
        assertField(post(applications.withdrawBody(code, "not-an-email", REASON, true)), "email", "Email không hợp lệ.");

        Map<String, Object> extra = new LinkedHashMap<>(missing);
        extra.put("confirm", true);
        extra.put("stage", "WITHDRAWN");
        assertError(post(applications.withdrawBody(extra)), 400, "INVALID_JSON");
        // Only the JSON literal true confirms: Jackson's coercion of a text or a number to a Boolean is refused.
        for (Object notABoolean : List.of("true", "TRUE", 1, "false", 0, List.of(true), Map.of("value", true))) {
            Map<String, Object> coerced = new LinkedHashMap<>(missing);
            coerced.put("confirm", notABoolean);
            assertError(post(applications.withdrawBody(coerced)), 400, "INVALID_JSON");
        }
        String valid = applications.withdrawBody(code, EMAIL, REASON, true);
        var query = applications.post(ApplicationTestSupport.WITHDRAW + "?trackingCode=" + code, valid,
                "application/json", Map.of());
        assertError(query, 400, "VALIDATION_ERROR");
        assertThat(json.readTree(query.body()).get("message").asString())
                .isEqualTo("Không gửi mã tra cứu hoặc email trên đường dẫn.");
        assertError(applications.post(ApplicationTestSupport.WITHDRAW, valid, "text/plain", Map.of()), 415,
                "APPLICATION_TRACKING_JSON_REQUIRED");
        assertError(applications.post(ApplicationTestSupport.WITHDRAW, valid + " ".repeat(8193 - valid.length()),
                "application/json", Map.of()), 413, "APPLICATION_TRACKING_REQUEST_TOO_LARGE");

        assertThat(applications.trackingAttempts()).isZero();
        assertThat(stageOf(stored.applicationId())).isEqualTo("NEW");
        assertThat(events()).isEmpty();
    }

    // The reason is kept as typed with \n line breaks; 500 characters is the limit; a reason without any visible
    // character, like no reason, is stored as NULL.
    @Test
    void theReasonIsStoredWithUnixLineBreaksAndAnInvisibleReasonAsNone() throws Exception {
        var windows = applications.stored(posting, "a@example.test");
        var longest = applications.stored(posting, "b@example.test");
        var invisible = applications.stored(posting, "c@example.test");
        var none = applications.stored(posting, "d@example.test");

        ok(applications.withdraw(windows.trackingCode(), "a@example.test", "Dòng 1\r\nDòng 2\rDòng 3"));
        ok(applications.withdraw(longest.trackingCode(), "b@example.test", "ý".repeat(500)));
        String blank = Character.toString(0x00A0) + Character.toString(0x200B) + "\r\n" + Character.toString(0x2060)
                + Character.toString(0x3000);
        ok(applications.withdraw(invisible.trackingCode(), "c@example.test", blank));
        ok(applications.withdraw(none.trackingCode(), "d@example.test", null));

        assertThat(reasonOf(windows.applicationId())).isEqualTo("Dòng 1\nDòng 2\nDòng 3");
        assertThat(reasonOf(longest.applicationId())).isEqualTo("ý".repeat(500));
        assertThat(reasonOf(invisible.applicationId())).isNull();
        assertThat(reasonOf(none.applicationId())).isNull();
    }

    // No requisition or posting status is checked: a candidate withdraws from a paused, filled or cancelled
    // requisition, a closed posting and a posting past its deadline alike.
    @Test
    void theRequisitionAndPostingStatusesDoNotMatter() throws Exception {
        for (String status : List.of("PAUSED", "FILLED", "CANCELLED")) {
            UUID other = applications.publishedPosting("Vị trí " + status);
            var stored = applications.stored(other, EMAIL);
            jdbc.update("UPDATE recruitment_requisitions SET status = ? WHERE id = ?", status,
                    applications.requisitionOf(other));
            ok(applications.withdraw(stored.trackingCode(), EMAIL, null));
            assertThat(stageOf(stored.applicationId())).as(status).isEqualTo("WITHDRAWN");
        }
        UUID closed = applications.publishedPosting("Tin đã đóng");
        var inClosed = applications.stored(closed, EMAIL);
        jdbc.update("UPDATE job_postings SET status = 'CLOSED' WHERE id = ?", closed);
        ok(applications.withdraw(inClosed.trackingCode(), EMAIL, null));

        var beforeDeadline = applications.stored(posting, EMAIL);
        clock.set(Instant.parse("2027-01-02T03:00:00Z"));                // the deadline was 31 Dec 2026
        ok(applications.withdraw(beforeDeadline.trackingCode(), EMAIL, null));
        assertThat(stageOf(inClosed.applicationId())).isEqualTo("WITHDRAWN");
        assertThat(stageOf(beforeDeadline.applicationId())).isEqualTo("WITHDRAWN");
    }

    // A withdrawn application keeps the email's place in the requisition (task 327): applying again is a duplicate.
    @Test
    void applyingAgainAfterAWithdrawalIsStillADuplicate() throws Exception {
        var stored = applications.stored(posting, EMAIL);
        ok(applications.withdraw(stored.trackingCode(), EMAIL, REASON));

        assertThatThrownBy(() -> applications.stored(posting, EMAIL.toLowerCase(java.util.Locale.ROOT)))
                .isInstanceOfSatisfying(DuplicateApplicationException.class,
                        duplicate -> assertThat(duplicate.getStatus().value()).isEqualTo(409));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_applications", Integer.class)).isEqualTo(1);
    }

    // The frontend attaches any stored bearer: none and a stale one both reach the withdrawal.
    @Test
    void aMissingOrAStaleBearerDoesNotMatter() throws Exception {
        var first = applications.stored(posting, EMAIL);
        var second = applications.stored(posting, "tran.thi.b@example.test");
        for (var pair : List.of(Map.entry(first, EMAIL), Map.entry(second, "tran.thi.b@example.test"))) {
            Map<String, String> headers = pair.getKey() == first ? Map.of()
                    : Map.of("Authorization", "Bearer not-a-jwt");
            var response = applications.withdraw(pair.getKey().trackingCode(), pair.getValue(), null, headers);
            ok(response);
            assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
        }
        assertThat(jdbc.queryForList("SELECT DISTINCT stage FROM job_applications", String.class))
                .containsExactly("WITHDRAWN");
    }

    // The unified profile (task 361) shows withdrawnAt and withdrawalReason to a reader in scope, and counts the
    // withdrawal in updatedAt; a reader whose scope holds only the candidate's other application gets nothing of the
    // withdrawn one.
    @Test
    void theProfileShowsTheWithdrawalToAReaderInScopeOnly() throws Exception {
        UUID otherPosting = applications.publishedPosting("Kiểm thử phần mềm");
        UUID hr = account("Nguyễn Thị Hoa", "hr@example.test", Role.HR_MANAGER);
        UUID javaRecruiter = account("Trần Văn Bình", "binh@example.test", Role.RECRUITER);
        UUID testingRecruiter = account("Lê Thị Cúc", "cuc@example.test", Role.RECRUITER);
        applications.assign(requisition, javaRecruiter, "PRIMARY", hr);
        applications.assign(applications.requisitionOf(otherPosting), testingRecruiter, "PRIMARY", hr);
        var java = applications.stored(posting, EMAIL);
        var testing = applications.stored(otherPosting, EMAIL);
        clock.set(WITHDRAWAL_TIME);
        ok(applications.withdraw(java.trackingCode(), EMAIL, "Dòng 1\nDòng 2"));

        JsonNode inScope = profile(java.candidateId(), javaRecruiter);
        assertThat(inScope.get("applications").size()).isEqualTo(1);
        JsonNode withdrawn = inScope.get("applications").get(0);
        assertThat(withdrawn.get("id").asString()).isEqualTo(java.applicationId().toString());
        assertThat(withdrawn.get("stage").asString()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.get("stageName").asString()).isEqualTo("Ứng viên rút");
        assertThat(withdrawn.get("withdrawnAt").asString()).isEqualTo(WITHDRAWN_AT.toString());
        assertThat(withdrawn.get("withdrawalReason").asString()).isEqualTo("Dòng 1\nDòng 2");
        assertThat(inScope.get("updatedAt").asString()).isEqualTo(WITHDRAWN_AT.toString());

        var outOfScope = get(profilePath(java.candidateId()), token(testingRecruiter));
        assertThat(outOfScope.statusCode()).as(outOfScope.body()).isEqualTo(200);
        JsonNode other = json.readTree(outOfScope.body());
        assertThat(other.get("applications").size()).isEqualTo(1);
        assertThat(other.get("applications").get(0).get("id").asString()).isEqualTo(testing.applicationId().toString());
        assertThat(other.get("applications").get(0).has("withdrawnAt")).isFalse();
        assertThat(other.get("applications").get(0).has("withdrawalReason")).isFalse();
        assertThat(other.get("updatedAt").asString()).isEqualTo(ApplicationTestSupport.NOW.toString());
        assertThat(outOfScope.body()).doesNotContain("Dòng 1", "Dòng", WITHDRAWN_AT.toString(),
                java.applicationId().toString(), "WITHDRAWN");

        JsonNode all = profile(java.candidateId(), hr);
        assertThat(all.get("applications").size()).isEqualTo(2);
        assertThat(all.get("updatedAt").asString()).isEqualTo(WITHDRAWN_AT.toString());
    }

    // The stage and its event commit together: a failure at commit (a deferred constraint trigger raising on the
    // stage change) answers 503 and keeps neither. An event written in a transaction of its own would have committed
    // before this failure.
    @Test
    void aFailureAtCommitKeepsNeitherTheStageNorTheEvent() throws Exception {
        var stored = applications.stored(posting, EMAIL);
        jdbc.execute("CREATE FUNCTION fail_withdrawal_commit() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'private-commit-detail'; END $$");
        jdbc.execute("CREATE CONSTRAINT TRIGGER fail_withdrawal_commit AFTER UPDATE ON job_applications "
                + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION fail_withdrawal_commit()");
        try {
            var response = applications.withdraw(stored.trackingCode(), EMAIL, REASON);
            assertError(response, 503, "APPLICATION_TRACKING_UNAVAILABLE");
            assertThat(response.body()).doesNotContain("private-commit-detail", "job_applications");
        } finally {
            jdbc.execute("DROP TRIGGER fail_withdrawal_commit ON job_applications");
            jdbc.execute("DROP FUNCTION fail_withdrawal_commit()");
        }
        assertThat(stageOf(stored.applicationId())).isEqualTo("NEW");
        assertThat(events()).isEmpty();
        ok(applications.withdraw(stored.trackingCode(), EMAIL, REASON));
        assertThat(events()).hasSize(1);
    }

    private HttpResponse<String> post(String body) throws Exception {
        return applications.post(ApplicationTestSupport.WITHDRAW, body, "application/json", Map.of());
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control"))
                .hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        return json.readTree(response.body());
    }

    private void assertField(HttpResponse<String> response, String field, String message) {
        assertError(response, 400, "VALIDATION_ERROR");
        var body = json.readTree(response.body());
        assertThat(body.get("fieldErrors").propertyNames()).containsExactly(field);
        assertThat(body.get("fieldErrors").get(field).asString()).isEqualTo(message);
    }

    // Every error: the house body, no-store, and nothing of the request.
    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        var body = json.readTree(response.body());
        assertJsonKeys(body, "code", "message", "fieldErrors");
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(response.headers().firstValue("Cache-Control"))
                .hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(response.body()).doesNotContain("Exception", "SQL", "Nguyen.Van.A", "nguyen.van.a", "@example.test",
                REASON, "127.0.0.1", "org.", "java.");
    }

    private String stageOf(UUID application) {
        return jdbc.queryForObject("SELECT stage FROM job_applications WHERE id = ?", String.class, application);
    }

    private String reasonOf(UUID application) {
        return jdbc.queryForObject("SELECT reason FROM application_stage_events WHERE application_id = ?", String.class,
                application);
    }

    private List<Map<String, Object>> events() {
        return jdbc.queryForList("SELECT * FROM application_stage_events ORDER BY occurred_at, id");
    }

    private int activeCount(String token) throws Exception {
        var response = get("/api/v1/requisitions/" + requisition + "/active-pipeline", token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).get("count").asInt();
    }

    // Closing as filled without confirmActiveCandidates; the SQL-made requisition has no history yet (revision 0).
    private HttpResponse<String> close(String token) throws Exception {
        return applications.post("/api/v1/requisitions/" + requisition + "/status-changes",
                "{\"status\":\"FILLED\",\"reason\":\"Đã tuyển đủ\",\"expectedRevision\":0}", "application/json",
                Map.of("Authorization", "Bearer " + token));
    }

    private JsonNode profile(UUID candidate, UUID reader) throws Exception {
        var response = get(profilePath(candidate), token(reader));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private static String profilePath(UUID candidate) {
        return "/api/v1/candidates/" + candidate + "/unified-profile";
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // A session row of this account (SQL) and a signed access token for it, valid for 15 minutes from the clock.
    private String token(UUID account) {
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO auth_sessions (id, user_id, refresh_token_hash, created_at, expires_at)"
                + " VALUES (?, ?, ?, ?, ?)",
                session, account, (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""),
                Timestamp.from(ApplicationTestSupport.NOW.minusSeconds(60)),
                Timestamp.from(ApplicationTestSupport.NOW.plus(Duration.ofDays(30))));
        return tokens.createAccessToken(account, session);
    }

    private UUID account(String fullName, String email, Role... roles) {
        UUID id = applications.staff(fullName, email);
        for (Role role : roles) {
            jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role.name());
        }
        return id;
    }

    private static Map<String, List<String>> headersWithoutDate(HttpResponse<String> response) {
        var headers = new TreeMap<>(response.headers().map());
        headers.remove("date");
        return headers;
    }

    // The same 32 bytes spelled with one of the unused low bits of the last character set.
    private static String nonCanonical(String code) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        return code.substring(0, 42) + alphabet.charAt(alphabet.indexOf(code.charAt(42)) ^ 1);
    }
}
