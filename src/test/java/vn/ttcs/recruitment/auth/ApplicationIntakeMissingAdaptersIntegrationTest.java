package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.config.import=","app.bootstrap.enabled=false","logging.level.root=WARN","debug=false",
        "app.application-intake.enabled=true","app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@Import(AuthIntegrationTest.DatabaseConfiguration.class)
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationIntakeMissingAdaptersIntegrationTest {
    @Autowired Environment environment;
    @Test void enablingOnlyTheFlagCannotOpenAnIncompleteProductionFlow() throws Exception {
        var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+
                environment.getRequiredProperty("local.server.port")+"/api/v1/public/job-postings/"+UUID.randomUUID()+"/applications"))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("invalid-form"))
                .build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(503);assertThat(response.body()).contains("APPLICATION_INTAKE_UNAVAILABLE");
    }
}
