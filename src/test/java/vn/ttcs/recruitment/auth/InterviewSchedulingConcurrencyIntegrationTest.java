package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.CvValidator;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP/PostgreSQL races: resource exclusivity and authority must still hold when a wait ends. */
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
class InterviewSchedulingConcurrencyIntegrationTest {
    private static final Instant START = ApplicationTestSupport.NOW.plus(Duration.ofDays(1));
    private static final String WRITE_PERMISSION = "INTERVIEWS_WRITE_ALL";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private Environment environment;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationStorageService storage;
    @Autowired private CvValidator validator;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private TokenService tokens;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private ApplicationTestSupport applications;
    private UUID applicationA, applicationB, panelA, panelB;
    private String hrTokenA, hrTokenB;
    private int port;

    @BeforeEach
    void fixture() throws Exception {
        clock.set(ApplicationTestSupport.NOW);
        port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();
        applicationA = applications.stored(applications.publishedPosting("Java"), "first@example.test").applicationId();
        applicationB = applications.stored(applications.publishedPosting("Finance"), "second@example.test").applicationId();
        UUID hrA = applications.account("HR A", "hr-a@example.test", Role.HR_MANAGER);
        UUID hrB = applications.account("HR B", "hr-b@example.test", Role.HR_MANAGER);
        panelA = applications.account("Interviewer A", "panel-a@example.test", Role.INTERVIEWER);
        panelB = applications.account("Interviewer B", "panel-b@example.test", Role.INTERVIEWER);
        hrTokenA = applications.token(tokens, hrA);
        hrTokenB = applications.token(tokens, hrB);
        // Warm the HTTP/security path before observing the existing bounded 1.5-second lock-wait probe.
        var response = call("GET", "/api/v1/interviews/me", null, hrTokenA);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    @AfterEach
    void restorePermissionAndClock() {
        jdbc.update("INSERT INTO role_permissions(role_code,permission_code) VALUES ('HR_MANAGER',?) "
                + "ON CONFLICT DO NOTHING", WRITE_PERMISSION);
        clock.set(ApplicationTestSupport.NOW);
    }

    // Separate creators, sessions, applications and requisitions prevent unrelated row locks from accidentally
    // making the race pass. Only the shared room or shared panel member should make the second request fail.


    @ParameterizedTest(name = "grant removed during {0} lock wait")
    @ValueSource(strings = {"application", "resource"})
    void aRemovedSchedulingGrantDuringTheWaitCannotMutate(String held) throws Exception {
        UUID interview = held.equals("interview") ? create() : null;
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            hold(blocker, held, interview);
            Future<HttpResponse<String>> pending;
            try {
                pending = executor.submit(() -> write(interview));
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                assertThat(jdbc.update("DELETE FROM role_permissions WHERE role_code='HR_MANAGER' "
                        + "AND permission_code=?", WRITE_PERMISSION)).isEqualTo(1);
            } finally {
                blocker.rollback();
            }
            var response = pending.get(10, TimeUnit.SECONDS);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(403);
        }
        assertUnchanged(interview);
    }

    @ParameterizedTest(name = "token expires during {0} lock wait")
    @ValueSource(strings = {"application", "resource"})
    void anAccessTokenThatExpiresDuringTheWaitCannotMutate(String held) throws Exception {
        UUID interview = held.equals("interview") ? create() : null;
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            hold(blocker, held, interview);
            Future<HttpResponse<String>> pending;
            try {
                pending = executor.submit(() -> write(interview));
                applications.awaitWaiterOf(ApplicationTestSupport.backendPid(blocker));
                clock.set(ApplicationTestSupport.NOW.plusSeconds(3600));
            } finally {
                blocker.rollback();
            }
            var response = pending.get(10, TimeUnit.SECONDS);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(401);
        }
        assertUnchanged(interview);
    }

    // Represents the evaluator's transaction holding the appointment and committing its permanent submission
    // marker. The HTTP edit/cancel must read that committed marker after waiting, not the earlier unlocked copy.

    private List<HttpResponse<String>> createTogether(String first, String second) throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return call("POST", applicationPath(applicationA), first, hrTokenA);
            });
            var b = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return call("POST", applicationPath(applicationB), second, hrTokenB);
            });
            var firstResponse = a.get(20, TimeUnit.SECONDS);
            var secondResponse = b.get(20, TimeUnit.SECONDS);
            assertThat(firstResponse.statusCode()).as(firstResponse.body()).isIn(201, 409);
            assertThat(secondResponse.statusCode()).as(secondResponse.body()).isIn(201, 409);
            return List.of(firstResponse, secondResponse);
        }
    }

    private UUID create() throws Exception {
        var response = call("POST", applicationPath(applicationA), body(START, panelA, "Phong A", null), hrTokenA);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(json.readTree(response.body()).path("id").asText());
    }

    private HttpResponse<String> write(UUID interview) throws Exception {
        return interview == null
                ? call("POST", applicationPath(applicationA), body(START, panelA, "Phong A", null), hrTokenA)
                : call("PUT", "/api/v1/interviews/" + interview,
                        body(START.plusSeconds(7200), panelA, "Phong A", 0L), hrTokenA);
    }

    private void assertUnchanged(UUID interview) {
        assertThat(count("interview_sessions")).isEqualTo(interview == null ? 0 : 1);
        assertThat(count("interview_events")).isEqualTo(interview == null ? 0 : 1);
        assertThat(count("interview_participants")).isEqualTo(interview == null ? 0 : 1);
        if (interview != null) {
            assertThat(jdbc.queryForObject("SELECT version FROM interview_sessions WHERE id=?", Long.class, interview))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT starts_at FROM interview_sessions WHERE id=?", Timestamp.class,
                    interview).toInstant()).isEqualTo(START);
            assertThat(jdbc.queryForObject("SELECT status FROM interview_sessions WHERE id=?", String.class, interview))
                    .isEqualTo("SCHEDULED");
            assertThat(jdbc.queryForList("SELECT event_type FROM interview_events", String.class))
                    .containsExactly("INTERVIEW_SCHEDULED");
        }
    }

    private void hold(Connection connection, String held, UUID interview) throws Exception {
        connection.setAutoCommit(false);
        if (held.equals("resource")) {
            // Hold only the scheduling resource reservation; both the caller and candidate rows remain unlocked.
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(743882, 1)");
            }
            return;
        }
        String sql = held.equals("application") ? "SELECT id FROM job_applications WHERE id=? FOR UPDATE"
                : "SELECT id FROM interview_sessions WHERE id=? FOR UPDATE";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, held.equals("application") ? applicationA : interview);
            statement.executeQuery().close();
        }
    }

    private String body(Instant startsAt, UUID panel, String room, Long version) {
        var values = new java.util.LinkedHashMap<String, Object>(Map.of(
                "roundName", "Technical interview", "startsAt", startsAt.toString(), "durationMinutes", 60,
                "mode", "ONSITE", "room", room, "participantIds", List.of(panel.toString())));
        if (version != null) values.put("expectedVersion", version);
        return json.writeValueAsString(values);
    }

    private static String applicationPath(UUID application) { return "/api/v1/applications/" + application + "/interviews"; }

    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }

    private HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
