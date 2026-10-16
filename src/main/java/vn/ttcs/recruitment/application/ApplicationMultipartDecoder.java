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
    private final Validator validator;

    public ApplicationMultipartDecoder(Validator validator) { this.validator = validator; }

    DecodedSubmission decode(HttpServletRequest request) {
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
                if (++count > 6) throw invalidForm("Biểu mẫu có quá nhiều phần dữ liệu.");
                String name = part.getFieldName();
                if (part.isFormField()) {
                    Integer limit = FIELD_LIMITS.get(name);
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
        UUID sourceId = null;
        String source = fields.get("sourceId");
        if (source != null && !source.isBlank()) sourceId = uuid(source.strip(), "sourceId");
        var submission = new ApplicationSubmission(fields.get("fullName"), fields.get("email"), fields.get("phone"),
                sourceId, fields.get("coverLetter"));
        var errors = new LinkedHashMap<String, String>();
        validator.validate(submission).forEach(error -> errors.putIfAbsent(error.getPropertyPath().toString(), "Trường không hợp lệ."));
        if (!errors.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Vui lòng kiểm tra thông tin hồ sơ.", errors);
        return new DecodedSubmission(submission, filename, bytes);
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
