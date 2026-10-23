package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
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

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
abstract class ScreeningTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired Environment environment;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ApplicationStorageService storage;
    @Autowired CvValidator validator;
    @Autowired AuthIntegrationTest.MutableClock clock;
    @Autowired TokenService tokens;
    @Autowired DataSource dataSource;
    ApplicationTestSupport applications;
    ApplicationTestSupport.Stored first, other;
    UUID requisition, otherRequisition, hr, recruiter, outsider, manager, tag;
    String hrToken, recruiterToken;
    int port;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void fixture() {
        clock.set(ApplicationTestSupport.NOW);
        port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();
        UUID posting = applications.publishedPosting("Java"), otherPosting = applications.publishedPosting("Finance");
        requisition = applications.requisitionOf(posting);
        otherRequisition = applications.requisitionOf(otherPosting);
        first = applications.stored(posting, "lan@example.test");
        other = applications.stored(otherPosting, "lan@example.test");
        assertThat(other.candidateId()).isEqualTo(first.candidateId());
        hr = applications.account("HR Nội Bộ", "hr@example.test", Role.HR_MANAGER);
        recruiter = applications.account("Recruiter A", "recruiter@example.test", Role.RECRUITER);
        outsider = applications.account("Recruiter B", "other@example.test", Role.RECRUITER);
        manager = applications.account("Manager", "manager@example.test", Role.HIRING_MANAGER);
        applications.assign(requisition, recruiter, "PRIMARY", hr);
        applications.assign(otherRequisition, outsider, "PRIMARY", hr);
        jdbc.update("UPDATE departments SET manager_user_id = ? WHERE id = (SELECT department_id FROM recruitment_requisitions WHERE id = ?)", manager, requisition);
        hrToken = token(hr); recruiterToken = token(recruiter);
        tag = catalog("CANDIDATE_TAG", "Nhãn nội bộ", true);
    }

    UUID catalog(String type, String name, boolean active) {
        UUID id = UUID.randomUUID();
        var at = Timestamp.from(ApplicationTestSupport.NOW);
        jdbc.update("INSERT INTO recruitment_catalog_items VALUES (?,?,?,?,0,?,?,?)", id, type, id.toString(), name, active, at, at);
        return id;
    }
    String token(UUID actor) { return applications.token(tokens, actor); }
    String path(UUID application) { return "/api/v1/applications/" + application + "/screening"; }
    HttpResponse<String> call(String method, String path, String body, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").method(method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode ok(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        return json.readTree(response.body());
    }
    HttpResponse<String> rate(UUID id, String token, int rating) throws Exception {
        return call("PUT", path(id) + "/rating", "{\"rating\":" + rating + "}", token);
    }
    long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
}
