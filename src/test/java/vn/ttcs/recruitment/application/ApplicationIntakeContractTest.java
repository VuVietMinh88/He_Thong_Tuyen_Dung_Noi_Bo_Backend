package vn.ttcs.recruitment.application;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import vn.ttcs.recruitment.common.ApiException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class ApplicationIntakeContractTest {
    private static final String BOUNDARY = "intake-test-boundary";
    private final ApplicationMultipartDecoder decoder = new ApplicationMultipartDecoder(
            Validation.buildDefaultValidatorFactory().getValidator());

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/public/job-postings/applications", "/api/v1/public/job-postings//applications",
            "/api/v1/public/job-postings/a/b/applications", "/api/v1/public/job-postings/a/applications/",
            "/api/v1/public/job-postings/a", "/api/v1/public/job-postings/a/applications-extra"})
    void routeMatchingNeverThrowsOrCatchesSiblingRoutes(String path) {
        var request = request(new byte[0]); request.setServletPath(path);
        assertThat(ApplicationIntakeRoutes.isSubmission(request)).isFalse();
    }

    @Test void onlyTheExactPostBypassesTheServletMultipartResolver() {
        var request = request(new byte[0]);
        request.setServletPath("/api/v1/public/job-postings/" + UUID.randomUUID() + "/applications");
        assertThat(ApplicationIntakeRoutes.isSubmission(request)).isTrue();
        request.setMethod("PUT"); assertThat(ApplicationIntakeRoutes.isSubmission(request)).isFalse();
    }

    @Test void validFieldsAreNormalizedAndFileBytesPreservedInEitherPartOrder() throws Exception {
        byte[] cv = CvValidatorTest.pdf(false);
        for (boolean first : List.of(false, true)) {
            var fields = List.of(field("fullName", " Nguyễn Văn A "), field("email", " Candidate@EXAMPLE.test "),
                    field("phone", "+84 (123) 456-789"), field("coverLetter", "Hello\nHR"));
            var parts = new java.util.ArrayList<>(fields);
            parts.add(first ? 0 : parts.size(), file("cv", "cv.pdf", cv));
            var decoded = decoder.decode(request(body(parts, true)));
            assertThat(decoded.submission().fullName()).isEqualTo("Nguyễn Văn A");
            assertThat(decoded.submission().normalizedEmail()).isEqualTo("candidate@example.test");
            assertThat(decoded.submission().phone()).isEqualTo("+84123456789");
            assertThat(decoded.submission().sourceId()).isNull();
            assertThat(decoded.bytes()).isEqualTo(cv);
            assertThat(decoded.toString()).doesNotContain("Candidate", "cv.pdf", "Hello");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"stage", "trackingHash", "requisitionId", "unexpected", "cv"})
    void rejectsUnknownOrIncorrectlyTypedTextParts(String field) throws Exception {
        var parts = valid(); parts.add(field(field, "private-input")); assertError(parts, "VALIDATION_ERROR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"fullName", "email", "phone", "sourceId", "coverLetter"})
    void rejectsDuplicateTextParts(String field) throws Exception {
        var parts = valid();
        if (!List.of("fullName", "email", "phone").contains(field)) parts.add(field(field, ""));
        parts.add(field(field, "private-input")); assertError(parts, "VALIDATION_ERROR");
    }

    @Test void rejectsDuplicateCvAndUnknownFilePart() throws Exception {
        var parts = valid(); parts.add(file("cv", "cv.pdf", new byte[]{1})); assertError(parts, "VALIDATION_ERROR");
        parts = valid(); parts.set(3, file("attachment", "cv.pdf", new byte[]{1})); assertError(parts, "VALIDATION_ERROR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"fullName", "email", "phone", "cv"})
    void missingRequiredInputsAreRejected(String name) throws Exception {
        var parts = valid(); parts.removeIf(part -> part.name().equals(name)); assertError(parts, "VALIDATION_ERROR");
    }

    @Test void rejectsInvalidUnicodeMalformedBodiesAndMissingBoundaryWithoutEcho() throws Exception {
        var parts = valid(); parts.set(0, new Part("fullName", null, new byte[]{(byte)0xC3, 0x28}));
        assertError(parts, "INVALID_MULTIPART");
        assertThatThrownBy(() -> decoder.decode(request(body(valid(), false)))).isInstanceOf(ApiException.class);
        var request = request(body(valid(), true)); request.setContentType("multipart/form-data");
        assertThatThrownBy(() -> decoder.decode(request)).isInstanceOf(ApiException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fullName", "email", "phone", "sourceId", "coverLetter"})
    void fieldByteBudgetsAreEnforcedBeforeValidation(String name) throws Exception {
        var parts = valid(); parts.removeIf(part -> part.name().equals(name));
        parts.add(field(name, "x".repeat(20001))); assertError(parts, "VALIDATION_ERROR");
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-uuid", "1-1-1-1-1"})
    void nonCanonicalSourceIdsCannotBeCoerced(String id) throws Exception {
        var parts = valid(); parts.add(field("sourceId", id)); assertError(parts, "VALIDATION_ERROR");
    }

    @Test void tenMebibyteFileLimitIsEnforcedEvenWithoutContentLength() throws Exception {
        var parts = valid(); parts.set(3, file("cv", "cv.pdf", new byte[CvValidator.MAX_BYTES + 1]));
        var request = request(body(parts, true)); request.addHeader("Transfer-Encoding", "chunked");
        assertThatThrownBy(() -> decoder.decode(request)).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException)error).getCode()).isEqualTo("APPLICATION_CV_TOO_LARGE"));
    }

    @Test void activationRequiresBothAdaptersAndTheSwitch() {
        for (int mask = 0; mask < 8; mask++) {
            var beans = new DefaultListableBeanFactory();
            if ((mask & 2) != 0) beans.registerSingleton("quota", (ApplicationAttemptQuota) request -> { });
            if ((mask & 4) != 0) beans.registerSingleton("writer", (ApplicationReceiptWriter) (id, submission, cv) -> null);
            var availability = new ApplicationIntakeAvailability((mask & 1) != 0,
                    beans.getBeanProvider(ApplicationAttemptQuota.class), beans.getBeanProvider(ApplicationReceiptWriter.class));
            if (mask == 7) assertThatCode(availability::requireReady).doesNotThrowAnyException();
            else assertThatThrownBy(availability::requireReady).isInstanceOf(ApiException.class)
                    .satisfies(error -> assertThat(((ApiException)error).getCode()).isEqualTo("APPLICATION_INTAKE_UNAVAILABLE"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://external.test/app#ma=", "https://user:pass@portal.test/app#ma=",
            "https://portal.test/app?token=private#ma=", "javascript:alert(1)#ma=", "/relative#ma="})
    void receiptRejectsUnsafeTrackingLinks(String prefix) {
        String code = "A".repeat(43);
        assertThatThrownBy(() -> new ApplicationReceipt(UUID.randomUUID(), Instant.now(), code, prefix + code))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void receiptAndResponseNeverIncludeTrackingSecretsInToString() {
        String code = "A".repeat(43); String url = "https://portal.test/track#ma=" + code;
        var receipt = new ApplicationReceipt(UUID.randomUUID(), Instant.now(), code, url);
        assertThat(receipt.toString()).doesNotContain(code, url);
        assertThat(new ApplicationIntakeController.AcceptedApplication(receipt.applicationId(), "RECEIVED", "Đã nhận",
                receipt.receivedAt(), code, url).toString()).doesNotContain(code, url);
    }

    private void assertError(List<Part> parts, String code) throws Exception {
        assertThatThrownBy(() -> decoder.decode(request(body(parts, true)))).isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    assertThat(((ApiException)error).getCode()).isEqualTo(code);
                    assertThat(error.getMessage()).doesNotContain("private-input");
                });
    }

    private static MockHttpServletRequest request(byte[] bytes) {
        var request = new MockHttpServletRequest("POST", "/unused");
        request.setContentType("multipart/form-data; boundary=" + BOUNDARY); request.setContent(bytes); return request;
    }
    private static java.util.ArrayList<Part> valid() throws Exception {
        return new java.util.ArrayList<>(List.of(field("fullName", "Candidate"), field("email", "a@example.test"),
                field("phone", "0123456789"), file("cv", "cv.pdf", CvValidatorTest.pdf(false))));
    }
    private static Part field(String name, String text) { return new Part(name, null, text.getBytes(StandardCharsets.UTF_8)); }
    private static Part file(String name, String filename, byte[] bytes) { return new Part(name, filename, bytes); }
    private static byte[] body(List<Part> parts, boolean close) throws Exception {
        var output = new ByteArrayOutputStream();
        for (var part : parts) {
            String header = "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + part.name() + "\""
                    + (part.filename() == null ? "" : "; filename=\"" + part.filename() + "\"") + "\r\n\r\n";
            output.write(header.getBytes(StandardCharsets.UTF_8)); output.write(part.bytes()); output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        if (close) output.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }
    private record Part(String name, String filename, byte[] bytes) { }
}
