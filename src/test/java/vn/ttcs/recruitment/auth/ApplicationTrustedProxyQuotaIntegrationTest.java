package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;
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
        "spring.config.import=","app.bootstrap.enabled=false","debug=false","logging.level.root=WARN",
        "app.auth.jwt-secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","app.application-intake.enabled=true",
        "app.application-intake.trusted-proxies=127.0.0.1,10.0.0.0/24","app.application-intake.cleanup-delay-ms=3600000"})
@Import({AuthIntegrationTest.DatabaseConfiguration.class,ApplicationIpQuotaIntegrationTest.WriterConfiguration.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ApplicationTrustedProxyQuotaIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;
    @BeforeEach void reset(){jdbc.update("DELETE FROM application_ip_quotas");}

    @Test void realHttpSocketTrustsConfiguredProxyAndCannotUseForgedLeftmostValues() throws Exception {
        for(int i=0;i<5;i++)assertThat(send("198.51.100."+(i+1)+", 192.0.2.7, 10.0.0.2").statusCode()).isEqualTo(415);
        assertThat(send("198.51.100.99, 192.0.2.7, 10.0.0.2").statusCode()).isEqualTo(429);
        assertThat(send("192.0.2.8, 10.0.0.2").statusCode()).isEqualTo(415);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_ip_attempts WHERE client_ip='192.0.2.7'",Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_ip_attempts",Integer.class)).isEqualTo(6);
    }
    @Test void missingOrInvalidProxyHeaderFailsBeforeAnyBodyOrQuotaWrite() throws Exception {
        for(String value:new String[]{null,"malicious.invalid","192.0.2.1,"}) {
            var response=send(value);assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("APPLICATION_CLIENT_IP_INVALID").doesNotContain("malicious","127.0.0.1");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_ip_attempts",Integer.class)).isZero();
    }
    @Test void mappedIpv4AndPlainIpv4CannotCreateSeparateQuotasThroughTheProxy() throws Exception {
        for(int i=0;i<5;i++)assertThat(send("::ffff:192.0.2.7").statusCode()).isEqualTo(415);
        assertThat(send("192.0.2.7").statusCode()).isEqualTo(429);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_ip_quotas",Integer.class)).isEqualTo(1);
    }
    private HttpResponse<String> send(String forwarded) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+environment.getRequiredProperty("local.server.port")+
                "/api/v1/public/job-postings/"+UUID.randomUUID()+"/applications"))
                .header("Content-Type","application/json").header("Authorization","Bearer stale")
                .POST(HttpRequest.BodyPublishers.ofString("invalid-body"));
        if(forwarded!=null)builder.header("X-Forwarded-For",forwarded);
        return HttpClient.newHttpClient().send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
}
