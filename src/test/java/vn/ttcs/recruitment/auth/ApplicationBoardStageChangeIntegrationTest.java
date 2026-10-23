package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.CvValidator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The drag/drop client uses the very same stage endpoint as every other staff action. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "app.application-confirmation.page-url=https://portal.example.test/tra-cuu",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000", "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationBoardStageChangeIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private Environment environment;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationStorageService storage;
    @Autowired private CvValidator validator;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private TokenService tokens;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private int port;
    private UUID requisition, application, recruiter, outsider, manager;

    @BeforeEach
    void fixture() {
        clock.set(ApplicationTestSupport.NOW);
        port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        var applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();
        UUID posting = applications.publishedPosting("Java developer");
        requisition = applications.requisitionOf(posting);
        recruiter = account(applications, Role.RECRUITER);
        outsider = account(applications, Role.RECRUITER);
        manager = account(applications, Role.HIRING_MANAGER);
        applications.assign(requisition, recruiter, "PRIMARY", manager);
        jdbc.update("UPDATE departments SET manager_user_id = ? WHERE id = (SELECT department_id FROM recruitment_requisitions WHERE id = ?)",
                manager, requisition);
        application = applications.stored(posting, "candidate@example.test").applicationId();
    }

    @Test
    void draggingUsesCardIdentityAndStageThenReloadsBothColumnCounts() throws Exception {
        String token = token(recruiter);
        JsonNode before = board(token);
        JsonNode card = column(before, "NEW").path("cards").get(0);
        assertThat(column(before, "NEW").path("total").asLong()).isEqualTo(1);
        HttpResponse<String> moved = change(UUID.fromString(card.path("applicationId").asText()),
                card.path("stage").asText(), "SCREENING", token);
        assertThat(moved.statusCode()).as(moved.body()).isEqualTo(200);

        JsonNode after = board(token);
        assertThat(column(after, "NEW").path("total").asLong()).isZero();
        assertThat(column(after, "NEW").path("cards").size()).isZero();
        assertThat(column(after, "SCREENING").path("total").asLong()).isEqualTo(1);
        assertThat(column(after, "SCREENING").path("cards").get(0).path("applicationId").asText()).isEqualTo(application.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events WHERE application_id = ? AND actor_type = 'STAFF' AND actor_id = ? AND stage_before = 'NEW' AND stage_after = 'SCREENING'",
                Long.class, application, recruiter)).isEqualTo(1);
    }

    @Test
    void staleDragReturnsConflictWithoutASecondEventOrAnotherMove() throws Exception {
        String token = token(recruiter);
        JsonNode original = column(board(token), "NEW").path("cards").get(0);
        String expected = original.path("stage").asText();
        assertThat(change(application, expected, "SCREENING", token).statusCode()).isEqualTo(200);
        var stale = change(application, expected, "SCREENING", token);
        assertThat(stale.statusCode()).as(stale.body()).isEqualTo(409);
        assertThat(json.readTree(stale.body()).path("code").asText()).isEqualTo("APPLICATION_STAGE_CHANGED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events WHERE application_id = ? AND actor_type = 'STAFF'", Long.class, application)).isEqualTo(1);
        assertThat(column(board(token), "SCREENING").path("total").asLong()).isEqualTo(1);
    }

    @Test
    void boardVisibilityNeverGrantsWriteAndDirectDragCannotBypassAssignment() throws Exception {
        assertThat(column(board(token(manager)), "NEW").path("total").asLong()).isEqualTo(1);
        for (UUID actor : List.of(manager, outsider)) {
            var denied = change(application, "NEW", "SCREENING", token(actor));
            assertThat(denied.statusCode()).as(denied.body()).isEqualTo(403);
        }
        assertThat(jdbc.queryForObject("SELECT stage FROM job_applications WHERE id = ?", String.class, application)).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_stage_events WHERE application_id = ? AND actor_type = 'STAFF'", Long.class, application)).isZero();
    }

    private JsonNode board(String token) throws Exception {
        var response = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                + "/api/v1/application-board?requisitionId=" + requisition)).GET(), token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> change(UUID id, String expected, String target, String token) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/applications/" + id + "/stage-changes"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                        "expectedCurrentStage", expected, "targetStage", target)))), token);
    }

    private HttpResponse<String> send(HttpRequest.Builder request, String token) throws Exception {
        var response = http.send(request.timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.headers().allValues("Cache-Control")).anyMatch(value -> value.contains("no-store"));
        return response;
    }

    private UUID account(ApplicationTestSupport applications, Role role) {
        UUID id = applications.staff("Người xử lý", UUID.randomUUID() + "@example.test");
        jdbc.update("INSERT INTO user_roles(user_id, role) VALUES (?, ?)", id, role.name());
        return id;
    }

    private String token(UUID actor) {
        UUID session = UUID.randomUUID();
        jdbc.update("INSERT INTO auth_sessions(id,user_id,refresh_token_hash,created_at,expires_at) VALUES(?,?,?,?,?)",
                session, actor, (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", ""),
                Timestamp.from(ApplicationTestSupport.NOW), Timestamp.from(ApplicationTestSupport.NOW.plus(Duration.ofDays(7))));
        return tokens.createAccessToken(actor, session);
    }

    private static JsonNode column(JsonNode body, String stage) {
        for (JsonNode column : body.path("columns")) if (stage.equals(column.path("stage").asText())) return column;
        throw new AssertionError("Missing column " + stage);
    }
}
