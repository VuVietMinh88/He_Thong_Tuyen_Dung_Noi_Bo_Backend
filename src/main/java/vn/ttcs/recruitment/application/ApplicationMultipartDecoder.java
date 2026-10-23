package vn.ttcs.recruitment.application;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.validation.Validator;
import org.apache.commons.fileupload2.jakarta.servlet6.JakartaServletFileUpload;
import org.apache.commons.fileupload2.core.DiskFileItem;
import org.apache.commons.fileupload2.core.DiskFileItemFactory;
import org.apache.commons.fileupload2.core.FileUploadSizeException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import vn.ttcs.recruitment.common.ApiException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class ApplicationMultipartDecoder {
    public static final int MAX_REQUEST_BYTES = 11 * 1024 * 1024;
    private static final int MAX_PART_HEADER_BYTES = 2048;
    private static final Map<String, Integer> FIELD_LIMITS = Map.of(
            "fullName", 1024, "email", 2048, "phone", 256, "sourceId", 128, "coverLetter", 20000);
    // Task 351: the byte budgets of the referral form's text parts (the note: 2000 characters of up to 4 bytes).
    private static final Map<String, Integer> REFERRAL_LIMITS = Map.of(
            "jobPostingId", 128, "fullName", 1024, "email", 2048, "phone", 256, "referralNote", 8192,
            "candidateConsent", 16);
    private final Validator validator;

    public ApplicationMultipartDecoder(Validator validator) { this.validator = validator; }

    DecodedSubmission decode(HttpServletRequest request) {
        RawForm form = read(request, FIELD_LIMITS, 6);
        var fields = form.fields();
        UUID sourceId = null;
        String source = fields.get("sourceId");
        if (source != null && !source.isBlank()) sourceId = uuid(source.strip(), "sourceId");
        var submission = new ApplicationSubmission(fields.get("fullName"), fields.get("email"), fields.get("phone"),
                sourceId, fields.get("coverLetter"));
        var errors = new LinkedHashMap<String, String>();
        validator.validate(submission).forEach(error -> errors.putIfAbsent(error.getPropertyPath().toString(), "Trường không hợp lệ."));
        if (!errors.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Vui lòng kiểm tra thông tin hồ sơ.", errors);
        return new DecodedSubmission(submission, form.filename(), form.bytes());
    }

    // Task 351 (story S4-07): the staff referral form, read by the same bounded streaming loop as the public intake.
    // Exactly jobPostingId, fullName, email, phone, referralNote, candidateConsent and one file cv, in any order; any
    // other part (the frontend's requisitionId, candidate*, source, referrer*, cvFile included), a repeated part, an 8th
    // part, bad UTF-8 or a missing cv is refused like the intake's. Then every field is checked and all field errors are
    // answered together: jobPostingId a canonical UUID, the candidate's name, email and phone by ApplicationSubmission's
    // rules, the note by ReferralNote (task 350), candidateConsent exactly "true". No message repeats a submitted value.
    // The referrer is never read from the form: it is the authenticated caller.
    ReferralForm decodeReferral(HttpServletRequest request) {
        RawForm form = read(request, REFERRAL_LIMITS, 7);
        var fields = form.fields();
        var errors = new LinkedHashMap<String, String>();
        UUID postingId = null;
        String posting = fields.get("jobPostingId");
        if (posting == null || posting.isBlank()) {
            errors.put("jobPostingId", "Vui lòng chọn tin tuyển dụng.");
        } else {
            try { postingId = uuid(posting.strip(), "jobPostingId"); }
            catch (ApiException exception) { errors.put("jobPostingId", "Trường không hợp lệ."); }
        }
        var submission = new ApplicationSubmission(fields.get("fullName"), fields.get("email"), fields.get("phone"),
                null, null);
        validator.validate(submission).forEach(error -> errors.putIfAbsent(error.getPropertyPath().toString(), "Trường không hợp lệ."));
        String note = null;
        try { note = ReferralNote.validated(validator, fields.get(ReferralNote.FIELD)); }
        catch (ApiException exception) { errors.putAll(exception.getFieldErrors()); }
        if (!"true".equals(fields.get("candidateConsent"))) {
            errors.put("candidateConsent", "Vui lòng xác nhận ứng viên đã đồng ý cho bạn gửi hồ sơ.");
        }
        if (!errors.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Vui lòng kiểm tra thông tin giới thiệu.", errors);
        return new ReferralForm(postingId, submission, note, form.filename(), form.bytes());
    }

    // The streaming loop of the intake (TRAN DUC LUONG's decode, unchanged in behaviour): at most maxParts parts, the
    // text parts named in limits, each at most once and within its byte budget, strict UTF-8, exactly one file part cv
    // of at most 10 MiB, the whole body at most 11 MiB also when chunked, no temporary file.
    private static RawForm read(HttpServletRequest request, Map<String, Integer> limits, int maxParts) {
        var upload = new JakartaServletFileUpload<DiskFileItem, DiskFileItemFactory>();
        upload.setHeaderCharset(StandardCharsets.UTF_8);
        upload.setMaxSize(MAX_REQUEST_BYTES);
        upload.setMaxFileSize(CvValidator.MAX_BYTES);
        upload.setMaxPartHeaderSize(MAX_PART_HEADER_BYTES);
        // No disk factory is constructed: the streaming iterator does not create temporary files.
        var fields = new LinkedHashMap<String, String>();
        byte[] bytes = null;
        String filename = null;
        int count = 0;
        BoundedBody body = null;
        try {
            body = new BoundedBody(request.getInputStream());
            BoundedBody bounded = body;
            var boundedRequest = new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() { return bounded; }
            };
            var parts = upload.getItemIterator(boundedRequest);
            while (parts.hasNext()) {
                var part = parts.next();
                if (++count > maxParts) throw invalidForm("Biểu mẫu có quá nhiều phần dữ liệu.");
                String name = part.getFieldName();
                if (part.isFormField()) {
                    Integer limit = limits.get(name);
                    if (limit == null || fields.containsKey(name)) throw invalidForm("Biểu mẫu chứa trường dư hoặc lặp.");
                    try (var input = part.getInputStream()) {
                        byte[] value = input.readNBytes(limit + 1);
                        if (value.length > limit) throw invalidField(name);
                        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString();
                        fields.put(name, text);
                    }
                } else {
                    if (!"cv".equals(name) || bytes != null) throw invalidForm("Biểu mẫu phải có đúng một tệp CV.");
                    filename = part.getName();
                    try (var input = part.getInputStream()) {
                        bytes = input.readNBytes(CvValidator.MAX_BYTES + 1);
                        if (bytes.length > CvValidator.MAX_BYTES) throw tooLarge();
                    }
                }
            }
            // The multipart iterator stops at its closing boundary. Count any epilogue as part of the
            // HTTP body too, especially with chunked transfer where Content-Length cannot enforce it.
            byte[] discard = new byte[8192];
            while (body.read(discard) != -1) { /* bounded drain, no extra retained application data */ }
        } catch (FileUploadSizeException exception) {
            if (exception.getPermitted() == MAX_PART_HEADER_BYTES) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MULTIPART", "Không đọc được biểu mẫu. Vui lòng gửi lại.");
            }
            throw tooLarge();
        } catch (IOException | IllegalArgumentException exception) {
            if (body != null && body.exceeded) throw tooLarge();
            // Do not propagate parser exceptions, input values or uploaded filenames.
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MULTIPART", "Không đọc được biểu mẫu. Vui lòng gửi lại.");
        }
        if (bytes == null) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Vui lòng chọn tệp CV.", Map.of("cv", "Tệp CV là bắt buộc."));
        return new RawForm(fields, filename, bytes);
    }

    static UUID uuid(String value, String field) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException exception) { throw invalidField(field); }
    }

    static ApiException tooLarge() {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "APPLICATION_CV_TOO_LARGE",
                "CV tối đa 10 MiB; tổng biểu mẫu tối đa 11 MiB.");
    }
    private static ApiException invalidField(String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Vui lòng kiểm tra thông tin hồ sơ.",
                Map.of(field, "Trường không hợp lệ."));
    }
    private static ApiException invalidForm(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, Map.of("form", "Biểu mẫu không hợp lệ."));
    }

    record DecodedSubmission(ApplicationSubmission submission, String filename, byte[] bytes) {
        @Override public String toString() { return "DecodedSubmission[personal data redacted]"; }
    }

    // Task 351: a checked referral form. note is the normalized referral note; the consent was "true".
    record ReferralForm(UUID postingId, ApplicationSubmission submission, String note, String filename, byte[] bytes) {
        @Override public String toString() { return "ReferralForm[" + postingId + ", personal data redacted]"; }
    }

    private record RawForm(Map<String, String> fields, String filename, byte[] bytes) {
        @Override public String toString() { return "RawForm[personal data redacted]"; }
    }

    private static final class BoundedBody extends ServletInputStream {
        private final ServletInputStream delegate;
        private int count;
        private boolean exceeded;

        private BoundedBody(ServletInputStream delegate) { this.delegate = delegate; }
        @Override public int read() throws IOException {
            if (exceeded) throw new IOException("Application body limit exceeded");
            int value = delegate.read();
            if (value != -1) record(1);
            return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            if (exceeded) throw new IOException("Application body limit exceeded");
            if (length == 0) return 0;
            int read = delegate.read(bytes, offset, Math.min(length, MAX_REQUEST_BYTES - count + 1));
            if (read > 0) record(read);
            return read;
        }
        private void record(int read) throws IOException {
            count += read;
            if (count > MAX_REQUEST_BYTES) {
                exceeded = true;
                throw new IOException("Application body limit exceeded");
            }
        }
        @Override public boolean isFinished() { return delegate.isFinished(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
    }
}
