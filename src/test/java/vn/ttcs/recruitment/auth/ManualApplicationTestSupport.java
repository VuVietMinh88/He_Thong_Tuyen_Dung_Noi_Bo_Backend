package vn.ttcs.recruitment.auth;

import org.junit.jupiter.api.BeforeEach;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Story S5-08 (tasks 407-408): the screening fixture plus multipart helpers for the manual intake. */
abstract class ManualApplicationTestSupport extends ScreeningTestSupport {
    static final String BOUNDARY = "manual-intake-boundary";
    final HttpClient multipart = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    UUID source;

    @BeforeEach
    void manualFixture() {
        source = applications.source("Hội chợ việc làm");
    }

    UUID postingOf(UUID requisitionId) {
        return jdbc.queryForObject("SELECT id FROM job_postings WHERE requisition_id = ?", UUID.class, requisitionId);
    }

    static String manualPath(UUID requisitionId) {
        return "/api/v1/requisitions/" + requisitionId + "/manual-applications";
    }

    Map<String, String> fields(String email) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("fullName", "Hoàng Thị Mai");
        fields.put("email", email);
        fields.put("phone", "0977 888 999");
        fields.put("sourceId", source.toString());
        fields.put("coverLetter", "Gặp tại hội chợ, quan tâm vị trí Java.");
        fields.put("candidateConsent", "true");
        return fields;
    }

    static byte[] body(Map<String, String> fields, String filename, byte[] cv) {
        var output = new ByteArrayOutputStream();
        for (var field : fields.entrySet()) {
            output.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + field.getKey()
                    + "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        if (cv != null) {
            output.writeBytes(("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"cv\"; filename=\""
                    + filename + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            output.writeBytes(cv);
            output.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        output.writeBytes(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }

    HttpResponse<String> enter(String token, UUID requisitionId, Map<String, String> fields) throws Exception {
        return enter(token, requisitionId, body(fields, "CV Mai.pdf", applications.pdf().content()));
    }

    HttpResponse<String> enter(String token, UUID requisitionId, byte[] body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + manualPath(requisitionId)))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return multipart.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    void assertError(HttpResponse<String> response, int status, String code) {
        org.assertj.core.api.Assertions.assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        org.assertj.core.api.Assertions.assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo(code);
    }
}
