package vn.ttcs.recruitment.application;

import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import vn.ttcs.recruitment.auth.AuthenticationFailureException;
import vn.ttcs.recruitment.common.ApiException;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 351 (story S4-07): the referral form contract without HTTP, with the decoder built like TRAN DUC LUONG's
 * ApplicationIntakeContractTest builds it. decodeReferral accepts exactly jobPostingId, fullName, email, phone,
 * referralNote, candidateConsent and one file cv, in any order, and refuses everything else without repeating what
 * was sent; the route is the exact POST only, and only it bypasses the Servlet multipart parser besides the intake.
 * His decode() keeps its 39 cases in his own test class.
 */
class ApplicationReferralContractTest {
    private static final String BOUNDARY = "referral-test-boundary";
    private static final String POSTING = "6f1c2a4e-8d3b-4c5a-9e7f-0a1b2c3d4e5f";
    private static final String SECRET = "private-input";
    private final ApplicationMultipartDecoder decoder = new ApplicationMultipartDecoder(
            Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void onlyTheExactPostIsTheSubmissionRoute() {
        assertThat(ApplicationReferralRoutes.isSubmission(route("POST", "/api/v1/referrals"))).isTrue();
        for (String path : List.of("/api/v1/referrals/", "/api/v1/referrals/my-referrals", "/api/v1/referralsX",
                "/api/v1/referral", "/api/v1/public/referrals")) {
            assertThat(ApplicationReferralRoutes.isSubmission(route("POST", path))).as(path).isFalse();
        }
        for (String method : List.of("GET", "PUT", "PATCH", "DELETE")) {
            assertThat(ApplicationReferralRoutes.isSubmission(route(method, "/api/v1/referrals"))).as(method).isFalse();
        }
    }

    // The 5/6 MiB Servlet parser (and its temporary files) never sees the referral's CV; every other upload keeps it.
    @Test
    void onlyTheReferralAndTheIntakeBypassTheServletMultipartParser() {
        var resolver = new ApplicationMultipartConfiguration().multipartResolver(new MultipartProperties());
        assertThat(resolver.isMultipart(multipartRoute("POST", "/api/v1/referrals"))).isFalse();
        assertThat(resolver.isMultipart(multipartRoute("POST",
                "/api/v1/public/job-postings/" + UUID.randomUUID() + "/applications"))).isFalse();
        assertThat(resolver.isMultipart(multipartRoute("PUT", "/api/v1/profile/avatar"))).isTrue();
        assertThat(resolver.isMultipart(multipartRoute("POST", "/api/v1/company-profile/media"))).isTrue();
        assertThat(resolver.isMultipart(multipartRoute("PUT", "/api/v1/referrals"))).isTrue();
    }

    @Test
    void theExactFormIsAcceptedInAnyPartOrderAndNormalized() throws Exception {
        byte[] cv = CvValidatorTest.pdf(false);
        var orders = new ArrayList<List<Part>>();
        orders.add(valid(cv));
        var reversed = new ArrayList<>(valid(cv));
        Collections.reverse(reversed);
        orders.add(reversed);
        var cvFirst = new ArrayList<>(valid(cv));
        cvFirst.addFirst(cvFirst.removeLast());
        orders.add(cvFirst);
        for (var parts : orders) {
            var form = decoder.decodeReferral(request(body(parts, true)));
            assertThat(form.postingId()).isEqualTo(UUID.fromString(POSTING));
            assertThat(form.submission().fullName()).isEqualTo("Nguyễn Văn A");
            assertThat(form.submission().normalizedEmail()).isEqualTo("lan@example.test");
            assertThat(form.submission().phone()).isEqualTo("+84123456789");
            assertThat(form.submission().sourceId()).isNull();
            assertThat(form.submission().coverLetter()).isNull();
            assertThat(form.note()).isEqualTo("Bạn học cũ,\nmạnh Java");
            assertThat(form.filename()).isEqualTo("cv.pdf");
            assertThat(form.bytes()).isEqualTo(cv);
            assertThat(form.toString()).doesNotContain("Nguyễn", "lan@", "Bạn học", "cv.pdf");
        }
    }

    // The frontend's alias and client-side fields (FE referralService.ts) and the intake's own fields are extra parts.
    @ParameterizedTest
    @ValueSource(strings = {"requisitionId", "candidateName", "candidateEmail", "candidatePhone", "candidateConsentAt",
            "source", "sourceId", "referrerId", "referrerName", "referrerEmail", "coverLetter", "trackingCode", "stage",
            "cv"})
    void everyOtherTextPartIsRefused(String name) throws Exception {
        var parts = new ArrayList<>(valid(CvValidatorTest.pdf(false)));
        parts.removeIf(part -> part.name().equals("referralNote"));
        parts.add(field(name, SECRET));
        assertError(parts, "VALIDATION_ERROR", "Biểu mẫu chứa trường dư hoặc lặp.");
    }

    @Test
    void theFrontendsCvFilePartIsRefused() throws Exception {
        var parts = new ArrayList<>(valid(CvValidatorTest.pdf(false)));
        parts.set(parts.size() - 1, file("cvFile", "cv.pdf", CvValidatorTest.pdf(false)));
        assertError(parts, "VALIDATION_ERROR", "Biểu mẫu phải có đúng một tệp CV.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"jobPostingId", "fullName", "email", "phone", "referralNote", "candidateConsent"})
    void aRepeatedTextPartIsRefused(String name) throws Exception {
        // Seven parts with the repeat (the CV left out, it would only be missed later): the repeat is refused.
        var parts = new ArrayList<>(valid(CvValidatorTest.pdf(false)));
        parts.removeIf(part -> part.name().equals("cv"));
        parts.add(field(name, SECRET));
        assertError(parts, "VALIDATION_ERROR", "Biểu mẫu chứa trường dư hoặc lặp.");
    }

    @Test
    void aSecondCvIsRefused() throws Exception {
        var parts = new ArrayList<>(valid(CvValidatorTest.pdf(false)));
        parts.removeIf(part -> part.name().equals("referralNote"));
        parts.add(file("cv", "second.pdf", new byte[] {1}));
        assertError(parts, "VALIDATION_ERROR", "Biểu mẫu phải có đúng một tệp CV.");
    }

    @Test
    void anEighthPartIsRefusedBeforeItsName() throws Exception {
        var parts = new ArrayList<>(valid(CvValidatorTest.pdf(false)));
        parts.add(field("referralNote", SECRET));
        assertError(parts, "VALIDATION_ERROR", "Biểu mẫu có quá nhiều phần dữ liệu.");
    }

    @Test
    void aMissingCvIsRefused() throws Exception {
        var parts = new ArrayList<>(valid(CvValidatorTest.pdf(false)));
        parts.removeIf(part -> part.name().equals("cv"));
        var error = assertError(parts, "VALIDATION_ERROR", "Vui lòng chọn tệp CV.");
        assertThat(error.getFieldErrors()).containsOnlyKeys("cv");
    }

    @Test
    void badUtf8AndAnUnclosedBodyAreUnreadable() throws Exception {
        var parts = new ArrayList<>(valid(CvValidatorTest.pdf(false)));
        parts.set(1, new Part("fullName", null, new byte[] {(byte) 0xC3, 0x28}));
        assertError(parts, "INVALID_MULTIPART", "Không đọc được biểu mẫu. Vui lòng gửi lại.");
        assertThatThrownBy(() -> decoder.decodeReferral(request(body(valid(CvValidatorTest.pdf(false)), false))))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getCode()).isEqualTo("INVALID_MULTIPART"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"jobPostingId:129", "fullName:1025", "email:2049", "phone:257", "referralNote:8193",
            "candidateConsent:17"})
    void everyTextPartHasItsByteBudget(String budget) throws Exception {
        String name = budget.substring(0, budget.indexOf(':'));
        int size = Integer.parseInt(budget.substring(budget.indexOf(':') + 1));
        var parts = new ArrayList<>(valid(CvValidatorTest.pdf(false)));
        parts.removeIf(part -> part.name().equals(name));
        parts.add(field(name, "x".repeat(size)));
        var error = assertError(parts, "VALIDATION_ERROR", "Vui lòng kiểm tra thông tin hồ sơ.");
        assertThat(error.getFieldErrors()).containsOnlyKeys(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "6F1C2A4E8D3B4C5A9E7F0A1B2C3D4E5F", "{6f1c2a4e-8d3b-4c5a-9e7f-0a1b2c3d4e5f}"})
    void theJobPostingIdMustBeACanonicalUuid(String id) throws Exception {
        var error = assertFieldError(replace("jobPostingId", id), "jobPostingId");
        assertThat(error.getFieldErrors().get("jobPostingId")).isEqualTo("Trường không hợp lệ.");
    }

    @Test
    void theJobPostingIdIsRequired() throws Exception {
        var parts = new ArrayList<>(valid(CvValidatorTest.pdf(false)));
        parts.removeIf(part -> part.name().equals("jobPostingId"));
        var error = assertError(parts, "VALIDATION_ERROR", "Vui lòng kiểm tra thông tin giới thiệu.");
        assertThat(error.getFieldErrors()).containsEntry("jobPostingId", "Vui lòng chọn tin tuyển dụng.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "TRUE", "True", "1", "yes", "on", " true", "true ", ""})
    void theConsentMustBeExactlyTrue(String consent) throws Exception {
        var error = assertFieldError(replace("candidateConsent", consent), "candidateConsent");
        assertThat(error.getFieldErrors().get("candidateConsent"))
                .isEqualTo("Vui lòng xác nhận ứng viên đã đồng ý cho bạn gửi hồ sơ.");
    }

    @Test
    void theConsentIsRequired() throws Exception {
        var parts = new ArrayList<>(valid(CvValidatorTest.pdf(false)));
        parts.removeIf(part -> part.name().equals("candidateConsent"));
        assertThat(assertError(parts, "VALIDATION_ERROR", "Vui lòng kiểm tra thông tin giới thiệu.").getFieldErrors())
                .containsOnlyKeys("candidateConsent");
    }

    // The note follows task 350's rule (ReferralNote), under the form's field name.
    @Test
    void theNoteFollowsTheReferralNoteRule() throws Exception {
        String zeroWidth = new String(Character.toChars(0x200B));
        for (String note : List.of("", "   ", zeroWidth + zeroWidth, "x".repeat(2001), "<b>Java</b>", "a" + (char) 0)) {
            assertFieldError(replace("referralNote", note), "referralNote");
        }
        var parts = new ArrayList<>(valid(CvValidatorTest.pdf(false)));
        parts.removeIf(part -> part.name().equals("referralNote"));
        assertThat(assertError(parts, "VALIDATION_ERROR", "Vui lòng kiểm tra thông tin giới thiệu.").getFieldErrors())
                .containsEntry("referralNote", "Vui lòng nhập ghi chú giới thiệu.");
    }

    @Test
    void theCandidateFieldsFollowTheSubmissionRules() throws Exception {
        assertFieldError(replace("email", "not-an-email"), "email");
        assertFieldError(replace("phone", "12"), "phone");
        assertFieldError(replace("fullName", "<script>"), "fullName");
        assertFieldError(replace("fullName", "   "), "fullName");
    }

    // All field errors come back together, so the form can mark every wrong field at once.
    @Test
    void everyFieldErrorIsAnsweredAtOnce() throws Exception {
        var parts = new ArrayList<Part>(List.of(field("jobPostingId", "1-1-1-1-1"), field("fullName", " "),
                field("email", "x"), field("phone", "1"), field("referralNote", " "), field("candidateConsent", "no"),
                file("cv", "cv.pdf", CvValidatorTest.pdf(false))));
        var error = assertError(parts, "VALIDATION_ERROR", "Vui lòng kiểm tra thông tin giới thiệu.");
        assertThat(error.getFieldErrors()).containsOnlyKeys("jobPostingId", "fullName", "email", "phone", "referralNote",
                "candidateConsent");
    }

    @Test
    void whatTheWriterAndTheQuotaThrowMapsToOneOutcome() {
        assertThat(ReferralAttemptOutcome.of(new ApiException(HttpStatus.NOT_FOUND, "JOB_POSTING_NOT_FOUND", "x")))
                .isEqualTo(ReferralAttemptOutcome.POSTING_UNAVAILABLE);
        assertThat(ReferralAttemptOutcome.of(new DuplicateApplicationException()))
                .isEqualTo(ReferralAttemptOutcome.DUPLICATE);
        assertThat(ReferralAttemptOutcome.of(new ApiException(HttpStatus.CONFLICT, "JOB_POSTING_EXPIRED", "x")))
                .isEqualTo(ReferralAttemptOutcome.POSTING_UNAVAILABLE);
        assertThat(ReferralAttemptOutcome.of(new ApiException(HttpStatus.CONFLICT, "UNEXPECTED_CONFLICT", "x")))
                .isEqualTo(ReferralAttemptOutcome.FAILED);
        for (HttpStatus status : List.of(HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN)) {
            assertThat(ReferralAttemptOutcome.of(new ApiException(status, "X", "x"))).isEqualTo(ReferralAttemptOutcome.REFUSED);
        }
        assertThat(ReferralAttemptOutcome.of(new AccessDeniedException("x"))).isEqualTo(ReferralAttemptOutcome.REFUSED);
        assertThat(ReferralAttemptOutcome.of(AuthenticationFailureException.sessionInvalid()))
                .isEqualTo(ReferralAttemptOutcome.REFUSED);
        for (HttpStatus status : List.of(HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.CONTENT_TOO_LARGE,
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, HttpStatus.TOO_MANY_REQUESTS)) {
            assertThat(ReferralAttemptOutcome.of(new ApiException(status, "X", "x"))).isEqualTo(ReferralAttemptOutcome.FAILED);
        }
        assertThat(ReferralAttemptOutcome.of(new IllegalStateException("x"))).isEqualTo(ReferralAttemptOutcome.FAILED);
        assertThat(ReferralAttemptOutcome.values()).extracting(Enum::name)
                .containsExactly("STORED", "POSTING_UNAVAILABLE", "DUPLICATE", "REFUSED", "FAILED");
    }

    // The referred candidate's paragraph: submitted on their behalf with their declared consent, how to withdraw, and
    // whom to ask to delete the data; never the referrer.
    @Test
    void theReferralParagraphNamesTheContactAndNoReferrer() {
        String paragraph = ApplicationConfirmationMailSender.referralParagraph("support@example.test");
        assertThat(paragraph).startsWith("Hồ sơ này do một nhân viên của công ty gửi thay bạn qua kênh giới thiệu nội bộ")
                .contains("bạn đã đồng ý", "rút hồ sơ bằng liên kết rút hồ sơ bên dưới", "xóa dữ liệu",
                        "liên hệ support@example.test.")
                .endsWith("\n\n");
    }

    @Test
    void theViewHidesItsPersonalData() {
        var view = ApplicationReferralView.of(UUID.randomUUID(), "Nguyễn Văn A", "Lập trình viên",
                java.time.Instant.now(), ApplicationStage.NEW, "Bạn học cũ");
        assertThat(view.status()).isEqualTo("RECEIVED");
        assertThat(view.statusName()).isEqualTo("Đã nhận");
        assertThat(view.toString()).doesNotContain("Nguyễn", "Bạn học", "Lập trình");
    }

    private ArrayList<Part> replace(String name, String value) throws Exception {
        var parts = new ArrayList<>(valid(CvValidatorTest.pdf(false)));
        for (int index = 0; index < parts.size(); index++) {
            if (parts.get(index).name().equals(name)) parts.set(index, field(name, value));
        }
        return parts;
    }

    private ApiException assertFieldError(List<Part> parts, String field) throws Exception {
        var error = assertError(parts, "VALIDATION_ERROR", "Vui lòng kiểm tra thông tin giới thiệu.");
        assertThat(error.getFieldErrors()).containsOnlyKeys(field);
        return error;
    }

    private ApiException assertError(List<Part> parts, String code, String message) throws Exception {
        var request = request(body(parts, true));
        try {
            decoder.decodeReferral(request);
        } catch (ApiException error) {
            assertThat(error.getCode()).isEqualTo(code);
            assertThat(error.getMessage()).isEqualTo(message);
            assertThat(error.getMessage() + error.getFieldErrors()).doesNotContain(SECRET, "Bạn học", "lan@", "cv.pdf",
                    "script");
            return error;
        }
        throw new AssertionError("The form was accepted");
    }

    private static List<Part> valid(byte[] cv) {
        return List.of(field("jobPostingId", POSTING), field("fullName", " Nguyễn Văn A "),
                field("email", " Lan@Example.TEST "), field("phone", "+84 (123) 456-789"),
                field("referralNote", "Bạn học cũ,\r\nmạnh Java"), field("candidateConsent", "true"),
                file("cv", "cv.pdf", cv));
    }

    private static MockHttpServletRequest route(String method, String path) {
        var request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        return request;
    }

    private static MockHttpServletRequest multipartRoute(String method, String path) {
        var request = route(method, path);
        request.setContentType("multipart/form-data; boundary=" + BOUNDARY);
        return request;
    }

    private static MockHttpServletRequest request(byte[] bytes) {
        var request = new MockHttpServletRequest("POST", "/api/v1/referrals");
        request.setContentType("multipart/form-data; boundary=" + BOUNDARY);
        request.setContent(bytes);
        return request;
    }

    private static Part field(String name, String text) {
        return new Part(name, null, text.getBytes(StandardCharsets.UTF_8));
    }

    private static Part file(String name, String filename, byte[] bytes) { return new Part(name, filename, bytes); }

    private static byte[] body(List<Part> parts, boolean close) throws Exception {
        var output = new ByteArrayOutputStream();
        for (var part : parts) {
            String header = "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + part.name() + "\""
                    + (part.filename() == null ? "" : "; filename=\"" + part.filename() + "\"") + "\r\n\r\n";
            output.write(header.getBytes(StandardCharsets.UTF_8));
            output.write(part.bytes());
            output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        if (close) output.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return output.toByteArray();
    }

    private record Part(String name, String filename, byte[] bytes) { }
}
