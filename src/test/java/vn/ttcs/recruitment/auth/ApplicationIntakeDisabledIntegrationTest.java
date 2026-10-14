package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.config.import=","app.bootstrap.enabled=false","logging.level.root=WARN","debug=false",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationIntakeDisabledIntegrationTest {
    @Autowired Environment environment;
    @Autowired JdbcTemplate jdbc;
    @Test void disabledByDefaultRejectsBeforeAnyMultipartParsingEvenWithStaleBearer() throws Exception {
        assertThat(environment.getRequiredProperty("app.application-intake.enabled",Boolean.class)).isFalse();
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+
                environment.getRequiredProperty("local.server.port")+"/api/v1/public/job-postings/"+UUID.randomUUID()+"/applications"))
                .header("Content-Type","multipart/form-data").header("Authorization","Bearer stale")
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1})).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("APPLICATION_INTAKE_UNAVAILABLE").doesNotContain("INVALID_MULTIPART");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_applications",Integer.class)).isZero();
    }
}
