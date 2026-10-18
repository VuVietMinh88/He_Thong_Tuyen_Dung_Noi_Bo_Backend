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
import tools.jackson.databind.ObjectMapper;
import vn.ttcs.recruitment.account.Role;
import vn.ttcs.recruitment.application.ApplicationStorageService;
import vn.ttcs.recruitment.application.CvValidator;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 351 (story S4-07): without the confirmation key the candidate could never receive the tracking code, so a
 * referral is refused with 503 REFERRAL_UNAVAILABLE before its form is read; a wrong Content-Type is still 415 (checked
 * before readiness) and a body above 11 MiB still 413. The anonymous intake flag is on here, and does not make
 * referrals available.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.import=", "app.bootstrap.enabled=false", "debug=false", "logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "app.application-intake.enabled=true",
        "app.application-confirmation.encryption-key=",
        "app.application-confirmation.poll-delay-ms=3600000", "app.application-intake.cleanup-delay-ms=3600000",
        "app.application-tracking.cleanup-delay-ms=3600000", "app.application-notice.poll-delay-ms=3600000",
        "spring.mail.host=127.0.0.1", "spring.mail.port=1"
})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationReferralUnavailableIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private Environment environment;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ApplicationStorageService storage;
    @Autowired private CvValidator validator;
    @Autowired private AuthIntegrationTest.MutableClock clock;
    @Autowired private TokenService tokens;

    private ApplicationTestSupport applications;
    private UUID posting;
    private String token;

    @BeforeEach
    void fixture() {
        clock.set(ApplicationTestSupport.NOW);
        int port = Integer.parseInt(environment.getRequiredProperty("local.server.port"));
        applications = new ApplicationTestSupport(jdbc, json, storage, validator, port);
        applications.reset();
        new JobPostingTestSupport(jdbc, json, transactions, port).reset();
        posting = applications.publishedPosting("Lập trình viên Java");
        token = applications.token(tokens, applications.account("Trần Văn Nam", "nam@staff.test", Role.HR_MANAGER));
    }

    @Test
    void withoutTheConfirmationKeyAValidReferralIs503() throws Exception {
        var response = applications.refer(token, posting, "lan@example.test");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(503);
        var body = json.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo("REFERRAL_UNAVAILABLE");
        assertThat(body.get("message").asString())
                .isEqualTo("Chức năng giới thiệu ứng viên chưa sẵn sàng. Vui lòng thử lại sau.");
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        assertThat(response.headers().firstValue("Retry-After")).isEmpty();
        assertNothing();
    }

    @Test
    void theContentTypeAndTheBodySizeAreStillCheckedFirst() throws Exception {
        var json415 = applications.post(ApplicationTestSupport.REFERRALS, "{}", "application/json",
                Map.of("Authorization", "Bearer " + token));
        assertThat(json415.statusCode()).as(json415.body()).isEqualTo(415);
        assertThat(json.readTree(json415.body()).get("code").asString()).isEqualTo("APPLICATION_FORM_TYPE_UNSUPPORTED");
        var tooLarge = applications.refer(token, new byte[11 * 1024 * 1024 + 1]);
        assertThat(tooLarge.statusCode()).as(tooLarge.body()).isEqualTo(413);
        assertThat(json.readTree(tooLarge.body()).get("code").asString()).isEqualTo("APPLICATION_CV_TOO_LARGE");
        assertNothing();
    }

    private void assertNothing() {
        for (String table : List.of("job_applications", "application_cvs", "application_referrals",
                "application_email_outbox", "referral_attempts", "referral_quotas")) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).as(table).isZero();
        }
    }
}
