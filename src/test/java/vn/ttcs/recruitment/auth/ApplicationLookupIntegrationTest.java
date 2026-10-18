package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.BootstrapAdmin;
import vn.ttcs.recruitment.application.ApplicationStage;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.ApplicationSubmission;
import vn.ttcs.recruitment.application.ApplicationTrackingRequestFilter;
import vn.ttcs.recruitment.application.CvValidator;
import vn.ttcs.recruitment.application.PostgresApplicationReceiptWriter;
import vn.ttcs.recruitment.application.PublicApplicationStatus;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 339 (story S4-05, "Nhập mã tra cứu và email để xem trạng thái"): POST /api/v1/public/applications/lookup over a
 * real socket and a real PostgreSQL. A candidate without an account proves one application with its tracking code and
 * the email it was sent from, and learns only the posting title snapshot, the receipt time and the public status.
 * The tracking gate admits one request at a time here, so the busy and the slow-body cases are deterministic; every
 * other case is sequential. The confirmation worker never runs (poll delay of an hour), so no mail is attempted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.bootstrap.enabled=true", "app.bootstrap.email=admin@example.test", "app.bootstrap.password=TestingOnly123!",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000", "app.application-tracking.max-concurrent=1",
        "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationLookupIntegrationTest {
    private static final List<String> KEYS = List.of("postingTitle", "receivedAt", "status", "statusName");
    private static final String EMAIL = "Nguyen.Van.A@Example.test";
    private static final String TITLE = "Lập trình viên Java";
    private static final String NOT_FOUND = "Mã tra cứu hoặc email không đúng.";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private Environment environment;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationStorageService storage;
    @Autowired private CvValidator validator;
    @Autowired private PostgresApplicationReceiptWriter receipts;
    @Autowired private BootstrapAdmin bootstrap;
    @Autowired private AuthIntegrationTest.MutableClock clock;

    private ApplicationTestSupport applications;
    private ApprovalTestSupport api;
    private UUID posting;
    // The code a test submits; no error body may echo it (or most of it).
    private String secret;

    @BeforeEach
    void fixture() throws Exception {
        clock.set(ApplicationTestSupport.NOW);
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        var postings = new JobPostingTestSupport(jdbc, json, transactions, port);
        api = postings.api;
        applications.reset();
        postings.reset();
        posting = applications.publishedPosting(TITLE);
        secret = null;
    }

    // The code comes from the production receipt writer (task 329) and is typed back in another letter case and with
    // invisible edges: the hash and the email normalization agree end to end.
    @Test
    void aCodeIssuedAtReceptionFindsItsApplicationWithTheEmailInAnyCase() throws Exception {
        var receipt = receipts.accept(posting, new ApplicationSubmission(ApplicationTestSupport.FULL_NAME, EMAIL,
                ApplicationTestSupport.PHONE, null, "Thư giới thiệu"), applications.pdf());

        var response = applications.lookup(receipt.trackingCode(), "nguyen.van.a@EXAMPLE.TEST");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        JsonNode body = json.readTree(response.body());
        assertThat(body.propertyNames()).containsExactlyElementsOf(KEYS);
        assertThat(body.get("postingTitle").asString()).isEqualTo(TITLE);
        assertThat(body.get("receivedAt").asString()).isEqualTo(ApplicationTestSupport.NOW.toString());
        assertThat(body.get("status").asString()).isEqualTo("RECEIVED");
        assertThat(body.get("statusName").asString()).isEqualTo("Đã nhận");
        assertThat(response.body()).doesNotContain(receipt.applicationId().toString(), receipt.trackingCode(),
                ApplicationTestSupport.FULL_NAME, "Nguyen.Van.A", ApplicationTestSupport.PHONE, "Thư", "NEW", "Hồ sơ mới");

        String nbsp = Character.toString(0x00A0), zwsp = Character.toString(0x200B), bom = Character.toString(0xFEFF);
        var padded = applications.lookup(nbsp + receipt.trackingCode() + zwsp, zwsp + bom + " " + EMAIL + nbsp);
        assertThat(padded.statusCode()).as(padded.body()).isEqualTo(200);
        assertThat(padded.body()).isEqualTo(response.body());
    }

    // Task 340's mapping is what the candidate sees; the internal stage never is.
    @ParameterizedTest
    @EnumSource(ApplicationStage.class)
    void everyStageShowsItsPublicStatusOnly(ApplicationStage stage) throws Exception {
        var stored = applications.stored(posting, EMAIL);
        applications.stage(stored.applicationId(), stage);
        var response = applications.lookup(stored.trackingCode(), EMAIL);
        var body = ok(response);
        var expected = PublicApplicationStatus.of(stage);
        assertThat(body.get("status").asString()).isEqualTo(expected.name());
        assertThat(body.get("statusName").asString()).isEqualTo(expected.displayName());
        assertThat(response.body()).doesNotContain("\"" + stage.name() + "\"", stage.displayName());
    }

    // Unknown code, wrong email and revoked code are indistinguishable: same status, body and headers.
    @Test
    void anUnknownCodeAWrongEmailAndARevokedCodeGetTheSameAnswer() throws Exception {
        var stored = applications.stored(posting, EMAIL);
        secret = stored.trackingCode();
        var revoked = applications.stored(applications.publishedPosting("Kế toán"), EMAIL);
        jdbc.update("UPDATE job_applications SET tracking_revoked_at = clock_timestamp() WHERE id = ?", revoked.applicationId());

        var unknown = applications.lookup(applications.unknownCode(), EMAIL);
        var wrongEmail = applications.lookup(stored.trackingCode(), "other@example.test");
        var revokedCode = applications.lookup(revoked.trackingCode(), EMAIL);
        for (var response : List.of(unknown, wrongEmail, revokedCode)) {
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(json.readTree(response.body())).isEqualTo(json.readTree(
                    "{\"code\":\"APPLICATION_LOOKUP_NOT_FOUND\",\"message\":\"" + NOT_FOUND + "\",\"fieldErrors\":{}}"));
            assertThat(response.body()).isEqualTo(unknown.body());
            assertThat(headersWithoutDate(response)).isEqualTo(headersWithoutDate(unknown));
            assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        }
        // The right pair still works: nothing was locked out, and every miss was counted (task 339 quota).
        ok(applications.lookup(stored.trackingCode(), EMAIL));
        assertThat(applications.trackingAttempts()).isEqualTo(4);
    }

    @Test
    void oneEmailWithApplicationsToTwoRequisitionsSeesEachThroughItsOwnCode() throws Exception {
        var developer = applications.stored(posting, EMAIL);
        var accounting = applications.stored(applications.publishedPosting("Kế toán tổng hợp"),
                EMAIL.toLowerCase(java.util.Locale.ROOT));
        assertThat(ok(applications.lookup(developer.trackingCode(), EMAIL)).get("postingTitle").asString()).isEqualTo(TITLE);
        assertThat(ok(applications.lookup(accounting.trackingCode(), EMAIL)).get("postingTitle").asString())
                .isEqualTo("Kế toán tổng hợp");
    }

    // The title is the snapshot of the application; the requisition and posting statuses are never read.
    @Test
    void aRenamedPostingAPausedRequisitionOrAClosedPostingChangeNothing() throws Exception {
        var stored = applications.stored(posting, EMAIL);
        String before = applications.lookup(stored.trackingCode(), EMAIL).body();
        jdbc.update("UPDATE job_postings SET title = 'Tên mới' WHERE id = ?", posting);
        assertThat(applications.lookup(stored.trackingCode(), EMAIL).body()).isEqualTo(before).contains(TITLE);
        jdbc.update("UPDATE recruitment_requisitions SET status = 'PAUSED' WHERE id = (SELECT requisition_id FROM job_postings WHERE id = ?)", posting);
        assertThat(applications.lookup(stored.trackingCode(), EMAIL).body()).isEqualTo(before);
        jdbc.update("UPDATE job_postings SET status = 'CLOSED' WHERE id = ?", posting);
        assertThat(applications.lookup(stored.trackingCode(), EMAIL).body()).isEqualTo(before);
    }

    @Test
    void malformedRequestsAreRefusedBeforeAnyAttemptIsCounted() throws Exception {
        var stored = applications.stored(posting, EMAIL);
        secret = stored.trackingCode();
        String code = secret;
        assertField(applications.post(ApplicationTestSupport.LOOKUP, "{\"email\":\"" + EMAIL + "\"}", "application/json", Map.of()),
                "trackingCode", "Vui lòng nhập mã tra cứu.");
        assertField(applications.lookup(Character.toString(0x00A0) + Character.toString(0x200B), EMAIL),
                "trackingCode", "Vui lòng nhập mã tra cứu.");
        assertField(applications.lookup(code.substring(1), EMAIL), "trackingCode",
                "Mã tra cứu gồm đúng 43 ký tự chữ, số, dấu '-' hoặc '_'.");
        assertField(applications.lookup(code + "A", EMAIL), "trackingCode",
                "Mã tra cứu gồm đúng 43 ký tự chữ, số, dấu '-' hoặc '_'.");
        assertField(applications.lookup(nonCanonical(code), EMAIL), "trackingCode", "Mã tra cứu không hợp lệ.");
        assertField(applications.post(ApplicationTestSupport.LOOKUP, "{\"trackingCode\":\"" + code + "\"}", "application/json", Map.of()),
                "email", "Vui lòng nhập email đã dùng khi nộp hồ sơ.");
        assertField(applications.lookup(code, "not-an-email"), "email", "Email không hợp lệ.");
        // 64 + 1 + 190 = 255 characters, a well-formed address one character too long.
        String longEmail = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(57) + ".test";
        assertThat(longEmail).hasSize(255);
        assertField(applications.lookup(code, longEmail), "email", "Email tối đa 254 ký tự.");

        assertError(applications.post(ApplicationTestSupport.LOOKUP,
                "{\"trackingCode\":\"" + code + "\",\"email\":\"" + EMAIL + "\",\"applicationId\":\"x\"}", "application/json", Map.of()),
                400, "INVALID_JSON");
        var unreadable = applications.post(ApplicationTestSupport.LOOKUP, "{\"trackingCode\":", "application/json", Map.of());
        assertError(unreadable, 400, "INVALID_JSON");
        assertError(applications.post(ApplicationTestSupport.LOOKUP, "", "application/json", Map.of()), 400, "INVALID_JSON");
        var query = applications.post(ApplicationTestSupport.LOOKUP + "?trackingCode=" + code + "&email=" + EMAIL,
                applications.lookupBody(code, EMAIL), "application/json", Map.of());
        assertError(query, 400, "VALIDATION_ERROR");
        assertThat(json.readTree(query.body()).get("message").asString()).isEqualTo("Không gửi mã tra cứu hoặc email trên đường dẫn.");
        assertError(applications.post(ApplicationTestSupport.LOOKUP, applications.lookupBody(code, EMAIL), "text/plain", Map.of()),
                415, "APPLICATION_TRACKING_JSON_REQUIRED");
        assertError(applications.post(ApplicationTestSupport.LOOKUP, applications.lookupBody(code, EMAIL), null, Map.of()),
                415, "APPLICATION_TRACKING_JSON_REQUIRED");
        String padding = " ".repeat(8193 - applications.lookupBody(code, EMAIL).length());
        assertError(applications.post(ApplicationTestSupport.LOOKUP, applications.lookupBody(code, EMAIL) + padding,
                "application/json", Map.of()), 413, "APPLICATION_TRACKING_REQUEST_TOO_LARGE");
        // A chunked body (no Content-Length) is cut while the filter reads it, with the house handler's body.
        byte[] chunked = (applications.lookupBody(code, EMAIL) + padding).getBytes(StandardCharsets.UTF_8);
        var cut = applications.post(ApplicationTestSupport.LOOKUP,
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(chunked)), "application/json", Map.of());
        assertError(cut, 400, "INVALID_JSON");
        assertThat(json.readTree(cut.body())).isEqualTo(json.readTree(unreadable.body()));
        assertThat(applications.trackingAttempts()).isZero();

        // A chunked body within the limit is read normally, and a well-formed request is counted.
        byte[] small = applications.lookupBody(code, EMAIL).getBytes(StandardCharsets.UTF_8);
        var accepted = applications.post(ApplicationTestSupport.LOOKUP,
                HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(small)), "application/json", Map.of());
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(200);
        assertThat(applications.trackingAttempts()).isEqualTo(1);
    }

    // The frontend attaches any stored bearer; a stale, a missing and a valid staff token all reach the lookup.
    @Test
    void aStaleAMissingOrAValidStaffTokenDoesNotMatter() throws Exception {
        // Only this test needs a staff account. The bootstrap creates its administrator only in an empty account
        // table, so the fixture is rebuilt after it.
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, Integer.parseInt(environment.getRequiredProperty("local.server.port"))).reset();
        bootstrap.run(new DefaultApplicationArguments());
        String staff = api.token("admin@example.test");
        var stored = applications.stored(applications.publishedPosting(TITLE), EMAIL);
        for (Map<String, String> headers : List.of(Map.<String, String>of(), Map.of("Authorization", "Bearer not-a-jwt"),
                Map.of("Authorization", "Bearer " + staff))) {
            var response = applications.lookup(stored.trackingCode(), EMAIL, headers);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
        }
    }

    @Test
    void aLockedTableFailsClosedWithinTheStatementTimeout() throws Exception {
        var stored = applications.stored(posting, EMAIL);
        secret = stored.trackingCode();
        try (var blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.execute("LOCK TABLE job_applications IN ACCESS EXCLUSIVE MODE");
            }
            long started = System.nanoTime();
            try {
                var response = applications.lookup(stored.trackingCode(), EMAIL);
                assertError(response, 503, "APPLICATION_TRACKING_UNAVAILABLE");
                assertThat(response.body()).doesNotContain("job_applications", "timeout", "canceling");
            } finally {
                blocker.rollback();
            }
            assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isBetween(2500L, 8000L);
        }
        ok(applications.lookup(stored.trackingCode(), EMAIL));
    }

    // max-concurrent=1: while one lookup waits for the database, a second one is refused at once by the gate, before
    // the quota, so it writes nothing.
    @Test
    void aLookupBeyondTheGateIsBusyAndWritesNothing() throws Exception {
        var stored = applications.stored(posting, EMAIL);
        secret = stored.trackingCode();
        ok(applications.lookup(stored.trackingCode(), EMAIL));          // warm-up of the HTTP path
        try (var blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.execute("LOCK TABLE job_applications IN ACCESS EXCLUSIVE MODE");
            }
            try {
                var first = executor.submit(() -> applications.lookup(stored.trackingCode(), EMAIL));
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                int counted = applications.trackingAttempts();
                assertThat(counted).isEqualTo(2);

                var busy = applications.lookup(stored.trackingCode(), EMAIL);
                assertError(busy, 503, "APPLICATION_TRACKING_BUSY");
                assertThat(busy.headers().firstValue("Retry-After")).contains("1");
                assertThat(applications.trackingAttempts()).isEqualTo(counted);

                blocker.rollback();
                var answer = first.get(10, TimeUnit.SECONDS);
                assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
            } finally {
                blocker.rollback();
            }
        }
        ok(applications.lookup(stored.trackingCode(), EMAIL));
    }

    // max-concurrent=1: a client that sends its headers and only the start of its body holds its own connection, never
    // the gate's permit, so other candidates keep getting their answer (verifier finding on task 339: a few slow bodies
    // would otherwise lock everyone out). The slow request is answered, and counted, once its body is complete.
    @Test
    void aBodyThatArrivesSlowlyHoldsNoPermit() throws Exception {
        var stored = applications.stored(posting, EMAIL);
        secret = stored.trackingCode();
        byte[] body = applications.lookupBody(stored.trackingCode(), EMAIL).getBytes(StandardCharsets.UTF_8);
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            socket.setSoTimeout(20000);
            var out = socket.getOutputStream();
            out.write(("POST " + ApplicationTestSupport.LOOKUP + " HTTP/1.1\r\nHost: 127.0.0.1:" + port
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + body.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(body, 0, 10);                                       // {"tracking
            out.flush();
            awaitBodyReadInTrackingFilter();

            for (int lookup = 0; lookup < 3; lookup++) ok(applications.lookup(stored.trackingCode(), EMAIL));
            assertThat(applications.trackingAttempts()).isEqualTo(3);

            out.write(body, 10, body.length - 10);
            out.flush();
            String answer = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(answer).startsWith("HTTP/1.1 200").contains("\"status\":\"RECEIVED\"");
        }
        assertThat(applications.trackingAttempts()).isEqualTo(4);
    }

    private JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private void assertField(HttpResponse<String> response, String field, String message) {
        assertError(response, 400, "VALIDATION_ERROR");
        var body = json.readTree(response.body());
        assertThat(body.get("fieldErrors").propertyNames()).containsExactly(field);
        assertThat(body.get("fieldErrors").get(field).asString()).isEqualTo(message);
    }

    // Every error: the house body, no-store, and nothing of the request or the server's internals.
    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        var body = json.readTree(response.body());
        assertThat(body.propertyNames()).containsExactly("code", "message", "fieldErrors");
        assertThat(body.get("code").asString()).isEqualTo(code);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(response.body()).doesNotContain("Exception", "SQL", "Nguyen.Van.A", "nguyen.van.a", "@example.test",
                "127.0.0.1", "org.", "java.");
        if (secret != null) assertThat(response.body()).doesNotContain(secret, secret.substring(0, 42), secret.substring(1));
    }

    // A server thread blocked in a read of the request body under ApplicationTrackingRequestFilter (in the filter's own
    // read or further down the chain, wherever the body is read): polled every 10 ms for at most 5 s; fails loudly
    // instead of letting the slow-body case pass before the server even saw the request.
    private static void awaitBodyReadInTrackingFilter() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            for (StackTraceElement[] stack : Thread.getAllStackTraces().values()) {
                boolean reading = false, filtered = false;
                for (var frame : stack) {
                    reading |= frame.getClassName().equals("org.apache.catalina.connector.CoyoteInputStream");
                    filtered |= frame.getClassName().equals(ApplicationTrackingRequestFilter.class.getName());
                }
                if (reading && filtered) return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("No request body was being read under the tracking filter within 5 s");
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
